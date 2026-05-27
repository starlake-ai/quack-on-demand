package ai.starlake.acl.store

import ai.starlake.acl.watcher.{TenantListener, WatcherStatus}

/** Abstraction for detecting changes to ACL files.
  *
  * Local filesystem uses Java WatchService (via LocalChangeDetector).
  * Cloud storage uses periodic polling (via PollingChangeDetector).
  */
trait AclChangeDetector extends AutoCloseable:

  /** Start detecting changes and notifying the listener. */
  def start(listener: TenantListener): Unit

  /** Current health status of the change detector. */
  def status: WatcherStatus
