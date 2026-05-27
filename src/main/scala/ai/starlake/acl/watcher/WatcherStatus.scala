package ai.starlake.acl.watcher

import java.time.Instant

/** Health status of the TenantWatcher.
  *
  * Healthy: Watcher is running normally. Retrying: Watcher encountered error,
  * will retry.
  */
enum WatcherStatus:
  case Healthy
  case Retrying(attempt: Int, nextRetryAt: Instant)
