package ai.starlake.acl.store

import ai.starlake.acl.model.TenantId
import ai.starlake.acl.watcher.{TenantListener, WatcherStatus}
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** AclChangeDetector that polls an AclStore for changes at a configurable interval.
  *
  * Designed for cloud storage backends (S3, GCS, Azure) where push-based
  * file watching is not available. Detects:
  * - New tenant folders → `onNewTenant`
  * - Deleted tenant folders → `onTenantDeleted`
  * - Changed files within a tenant (name or lastModified) → `onInvalidate`
  *
  * @param store
  *   The AclStore to poll for changes
  * @param pollIntervalMs
  *   Polling interval in milliseconds (default 30000)
  */
class PollingChangeDetector(
    store: AclStore,
    pollIntervalMs: Long = 30000
) extends AclChangeDetector
    with LazyLogging:

  private val statusRef = new AtomicReference[WatcherStatus](WatcherStatus.Healthy)
  private val running = new AtomicReference[Boolean](false)
  private var scheduler: Option[ScheduledExecutorService] = None

  // Previous state for change detection.
  // Thread-safety: these vars are only accessed from the single-thread
  // ScheduledExecutorService (poll) and from start() which runs before
  // the scheduler is created. The single-thread executor provides
  // happens-before guarantees between successive tasks.
  private var knownTenants: Set[String] = Set.empty
  private var tenantFingerprints: Map[String, List[FileEntry]] = Map.empty

  override def start(listener: TenantListener): Unit =
    synchronized {
      if running.get() then return

      running.set(true)

      // Initial snapshot
      refreshState()

      val exec = Executors.newSingleThreadScheduledExecutor { r =>
        val t = new Thread(r, "PollingChangeDetector")
        t.setDaemon(true)
        t
      }
      scheduler = Some(exec)

      val _ = exec.scheduleAtFixedRate(
        () => poll(listener),
        pollIntervalMs,
        pollIntervalMs,
        TimeUnit.MILLISECONDS
      )
      logger.info(s"PollingChangeDetector started (interval=${pollIntervalMs}ms)")
    }

  override def status: WatcherStatus = statusRef.get()

  override def close(): Unit =
    running.set(false)
    scheduler.foreach { exec =>
      exec.shutdown()
      try exec.awaitTermination(2, TimeUnit.SECONDS)
      catch case _: InterruptedException => ()
    }
    scheduler = None

  private def poll(listener: TenantListener): Unit =
    try
      val currentTenants = store.listTenants() match
        case Right(tenants) => tenants.map(_.canonical).toSet
        case Left(_)        => return // skip this poll cycle on error

      // Detect new tenants
      val newTenants = currentTenants -- knownTenants
      newTenants.foreach { name =>
        TenantId.parse(name).foreach { tid =>
          logger.debug(s"New tenant detected: $name")
          safeCallback(() => listener.onNewTenant(tid))
        }
      }

      // Detect deleted tenants
      val deletedTenants = knownTenants -- currentTenants
      deletedTenants.foreach { name =>
        TenantId.parse(name).foreach { tid =>
          logger.debug(s"Tenant deleted: $name")
          safeCallback(() => listener.onTenantDeleted(tid))
        }
        tenantFingerprints = tenantFingerprints.removed(name)
      }

      // Check for file changes in existing tenants
      currentTenants.foreach { name =>
        TenantId.parse(name).foreach { tid =>
          store.listYamlFilesWithMetadata(tid) match
            case Right(currentEntries) =>
              val previousEntries = tenantFingerprints.getOrElse(name, List.empty)
              if currentEntries != previousEntries then
                logger.debug(s"Files changed for tenant: $name")
                safeCallback(() => listener.onInvalidate(tid))
                tenantFingerprints = tenantFingerprints.updated(name, currentEntries)
            case Left(_) => () // skip tenant on error
        }
      }

      knownTenants = currentTenants
      statusRef.set(WatcherStatus.Healthy)
    catch
      case e: Exception =>
        logger.error("Polling error", e)
        statusRef.set(WatcherStatus.Retrying(1, Instant.now().plusMillis(pollIntervalMs)))

  private def refreshState(): Unit =
    store.listTenants() match
      case Right(tenants) =>
        knownTenants = tenants.map(_.canonical).toSet
        knownTenants.foreach { name =>
          TenantId.parse(name).foreach { tid =>
            store.listYamlFilesWithMetadata(tid).foreach { entries =>
              tenantFingerprints = tenantFingerprints.updated(name, entries)
            }
          }
        }
      case Left(_) => ()

  private def safeCallback(f: () => Unit): Unit =
    try f()
    catch case e: Exception => logger.error("Listener callback failed", e)
