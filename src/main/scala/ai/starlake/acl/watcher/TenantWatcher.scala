package ai.starlake.acl.watcher

import ai.starlake.acl.model.TenantId
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** File system watcher for automatic tenant cache invalidation.
  *
  * Monitors basePath for changes to tenant ACL files. Uses Java WatchService
  * with a dedicated daemon thread. Debounces rapid changes and recovers from
  * errors with exponential backoff.
  *
  * @param basePath
  *   Base directory containing tenant folders
  * @param listener
  *   Callback for watcher events
  * @param config
  *   Watcher configuration
  */
final class TenantWatcher(
    basePath: Path,
    listener: TenantListener,
    config: WatcherConfig = WatcherConfig.default
) extends AutoCloseable
    with LazyLogging:

  private val statusRef = new AtomicReference[WatcherStatus](WatcherStatus.Healthy)
  private val running = new AtomicReference[Boolean](true)

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "TenantWatcher-scheduler")
      t.setDaemon(true)
      t
    }

  private val pendingInvalidations = new ConcurrentHashMap[TenantId, ScheduledFuture[?]]()
  private val registeredKeys = new ConcurrentHashMap[WatchKey, Path]()

  @volatile private var watcher: WatchService = uninitialized
  @volatile private var watchThread: Thread = uninitialized

  // Initialize watcher
  initializeWatcher()
  startWatchThread()

  /** Get current watcher health status. */
  def status: WatcherStatus = statusRef.get()

  /** Stop the watcher and release resources. */
  override def close(): Unit =
    running.set(false)
    Option(watchThread).foreach(_.interrupt())
    scheduler.shutdown()
    val _ = Try(scheduler.awaitTermination(1, TimeUnit.SECONDS))
    Option(watcher).foreach(w => { val _ = Try(w.close()) })

  /** True if the watcher was initialized successfully (basePath exists). */
  @volatile var initialized: Boolean = false

  private def initializeWatcher(): Unit =
    // Wait for basePath with a bounded retry (3 attempts, 3s apart)
    val maxWaitAttempts = 10
    var waitAttempt = 0
    while running.get() && !Files.exists(basePath) do
      waitAttempt += 1
      if waitAttempt > maxWaitAttempts then
        logger.warn(
          s"ACL base path '$basePath' does not exist after $waitAttempt/$maxWaitAttempts attempts. " +
            "ACL enforcement is disabled — all queries will be allowed."
        )
        return
      logger.info(s"Waiting for base path to exist: $basePath (attempt $waitAttempt/$maxWaitAttempts)")
      Thread.sleep(3000)

    if running.get() then
      watcher = FileSystems.getDefault.newWatchService()
      registerRecursively(basePath)
      initialized = true
      logger.info(s"TenantWatcher started monitoring: $basePath")

  private def startWatchThread(): Unit =
    watchThread = new Thread(() => watchLoop(), "TenantWatcher-main")
    watchThread.setDaemon(true)
    watchThread.start()

  private def watchLoop(): Unit =
    var attempt = 0
    while running.get() do
      try
        if watcher == null then initializeWatcher()
        if watcher == null then
          // basePath never appeared — stop the watch thread silently
          logger.info("TenantWatcher stopping: base path not available")
          return

        val key = watcher.take()
        attempt = 0
        statusRef.set(WatcherStatus.Healthy)

        try processEvents(key)
        finally
          val valid = key.reset()
          if !valid then { val _ = registeredKeys.remove(key) }
      catch
        case _: InterruptedException =>
          logger.debug("TenantWatcher interrupted")
          return
        case _: ClosedWatchServiceException =>
          if running.get() then
            logger.warn("WatchService closed unexpectedly, reinitializing")
            reinitializeWatcher()
            attempt += 1
        case e: Exception =>
          if running.get() then
            attempt += 1
            val delay = calculateBackoff(attempt, config.maxBackoffMs)
            val nextRetry = Instant.now().plusMillis(delay)
            statusRef.set(WatcherStatus.Retrying(attempt, nextRetry))
            logger.error(s"Watcher error (attempt $attempt), retrying in ${delay}ms", e)
            Thread.sleep(delay)
            reinitializeWatcher()

  @scala.annotation.nowarn("msg=unused explicit parameter")
  private def reinitializeWatcher(): Unit =
    val _ = Try(Option(watcher).foreach(_.close()))
    registeredKeys.clear()
    watcher = null
    // initializeWatcher will be called on next loop iteration

  private def processEvents(key: WatchKey): Unit =
    val watchedDir = registeredKeys.get(key)
    if watchedDir == null then return

    for event <- key.pollEvents().asScala do
      event.kind() match
        case OVERFLOW =>
          logger.warn("Overflow event, invalidating all known tenants")
          invalidateAllKnownTenants()
        case kind =>
          val path = event.context().asInstanceOf[Path]
          val fullPath = watchedDir.resolve(path)
          handleEvent(kind, fullPath, watchedDir)

  private def handleEvent(
      kind: WatchEvent.Kind[?],
      fullPath: Path,
      watchedDir: Path
  ): Unit =
    kind match
      case ENTRY_CREATE =>
        if Files.isDirectory(fullPath) then
          // New directory - could be new tenant or subfolder
          registerRecursively(fullPath)
          detectTenantFromPath(fullPath).foreach { tenant =>
            logger.info(s"New tenant folder detected: ${tenant.canonical}")
            safeCallback(() => listener.onNewTenant(tenant))
          }
        else if matchesPattern(fullPath.getFileName.toString) then
          detectTenantFromPath(watchedDir).foreach(debounceInvalidation)

      case ENTRY_MODIFY =>
        if matchesPattern(fullPath.getFileName.toString) then
          detectTenantFromPath(watchedDir).foreach(debounceInvalidation)

      case ENTRY_DELETE =>
        val filename = fullPath.getFileName.toString
        if matchesPattern(filename) then
          detectTenantFromPath(watchedDir).foreach(debounceInvalidation)
        else
          // Could be deleted folder
          detectTenantFromPath(fullPath).foreach { tenant =>
            logger.info(s"Tenant folder deleted: ${tenant.canonical}")
            safeCallback(() => listener.onTenantDeleted(tenant))
          }

      case _ => ()

  private def debounceInvalidation(tenant: TenantId): Unit =
    val _ = pendingInvalidations.compute(
      tenant,
      (_, existing) => {
        Option(existing).foreach(f => { val _ = f.cancel(false) })
        scheduler.schedule(
          (() => {
            val _ = pendingInvalidations.remove(tenant)
            logger.debug(s"Invalidating tenant: ${tenant.canonical}")
            safeCallback(() => listener.onInvalidate(tenant))
          }): Runnable,
          config.debounceMs,
          TimeUnit.MILLISECONDS
        )
      }
    )

  private def safeCallback(f: () => Unit): Unit =
    try f()
    catch case e: Exception => logger.error("Listener callback failed", e)

  private def registerRecursively(dir: Path): Unit =
    if !Files.exists(dir) || !Files.isDirectory(dir) then return

    val _ = Files.walkFileTree(
      dir,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            d: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult =
          try
            val key = d.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            val _ = registeredKeys.put(key, d)
            FileVisitResult.CONTINUE
          catch
            case e: Exception =>
              logger.warn(s"Failed to register directory: $d", e)
              FileVisitResult.CONTINUE

        override def visitFileFailed(
            file: Path,
            exc: java.io.IOException
        ): FileVisitResult =
          logger.warn(s"Failed to visit: $file", exc)
          FileVisitResult.CONTINUE
      }
    )

  private def detectTenantFromPath(path: Path): Option[TenantId] =
    if path == basePath then None
    else
      // Find the direct child of basePath
      var current = path
      while current.getParent != null && current.getParent != basePath do
        current = current.getParent

      if current.getParent == basePath then
        val tenantName = current.getFileName.toString
        TenantId.parse(tenantName).toOption
      else None

  private def matchesPattern(filename: String): Boolean =
    val lower = filename.toLowerCase
    config.filePatterns.exists { pattern =>
      val glob = pattern.toLowerCase
      if glob.startsWith("*") then lower.endsWith(glob.drop(1))
      else lower == glob
    }

  private def invalidateAllKnownTenants(): Unit =
    // Get all direct children of basePath that are valid tenant IDs
    if Files.exists(basePath) then
      val _ = Try {
        Files.list(basePath).iterator().asScala.foreach { child =>
          if Files.isDirectory(child) then
            TenantId.parse(child.getFileName.toString).foreach { tenant =>
              safeCallback(() => listener.onInvalidate(tenant))
            }
        }
      }

  private def calculateBackoff(attempt: Int, maxMs: Long): Long =
    val base = 1000L
    val delay = math.min(base * math.pow(2, attempt - 1).toLong, maxMs)
    // Add jitter (10%)
    delay + (math.random() * 0.1 * delay).toLong
