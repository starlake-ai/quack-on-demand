package ai.starlake.acl.store

import ai.starlake.acl.watcher.{TenantListener, TenantWatcher, WatcherConfig, WatcherStatus}

import java.nio.file.Path

/** AclChangeDetector backed by Java WatchService (for local filesystem).
  *
  * Wraps the existing TenantWatcher to implement the AclChangeDetector trait.
  * The TenantWatcher is created lazily on `start()` to avoid starting the
  * daemon thread before the listener is registered.
  *
  * @param basePath
  *   Base directory containing tenant folders
  * @param config
  *   Watcher configuration (debounce, backoff)
  */
class LocalChangeDetector(
    basePath: Path,
    config: WatcherConfig = WatcherConfig.default
) extends AclChangeDetector:

  @volatile private var watcher: Option[TenantWatcher] = None

  override def start(listener: TenantListener): Unit =
    synchronized {
      if watcher.isEmpty then watcher = Some(new TenantWatcher(basePath, listener, config))
    }

  override def status: WatcherStatus =
    watcher.map(_.status).getOrElse(WatcherStatus.Healthy)

  override def close(): Unit =
    watcher.foreach(_.close())
    watcher = None
