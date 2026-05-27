package ai.starlake.acl.watcher

/** Configuration for TenantWatcher.
  *
  * @param debounceMs
  *   Delay before triggering callback after file change (milliseconds)
  * @param maxBackoffMs
  *   Maximum retry delay on watcher failure (milliseconds)
  * @param filePatterns
  *   File patterns to watch (case-insensitive globs)
  */
final case class WatcherConfig(
    debounceMs: Long = 500,
    maxBackoffMs: Long = 60000,
    filePatterns: Set[String] = Set("*.yaml", "*.yml")
)

object WatcherConfig:
  val default: WatcherConfig = WatcherConfig()
