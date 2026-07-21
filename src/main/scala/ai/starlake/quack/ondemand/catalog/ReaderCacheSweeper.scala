package ai.starlake.quack.ondemand.catalog

import com.typesafe.scalalogging.LazyLogging

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap

/** Bounds an otherwise-unbounded per-tenant-db reader cache (e.g. Main's `catalogReaderCache`) by
  * evicting entries whose last access is older than `idleEvict`. Each cached reader owns its own
  * HikariCP pool, so leaving thousands of self-serve tenant-dbs cached forever would exhaust
  * Postgres connections; this sweeper is the process-local backstop.
  *
  * Deliberately process-local, not HA-coordinated: every manager replica caches (and must evict)
  * its own readers, so there is no singleton election here -- unlike the HA leader-gated recurring
  * tasks.
  */
final class ReaderCacheSweeper[K, R](
    cache: ConcurrentHashMap[K, ReaderCacheSweeper.Entry[R]],
    closeReader: R => Unit,
    idleEvict: Duration,
    now: () => Instant = () => Instant.now()
) extends LazyLogging:

  /** Evicts every entry whose lastAccess is older than `idleEvict` as of `now()`, closing its
    * reader. Uses the two-arg `remove(key, value)` so a concurrent rebuild under the same key (e.g.
    * `computeIfAbsent` racing a sweep) is never evicted: only the exact stale Entry object
    * identified during iteration is removed, never whatever happens to be mapped to the key at
    * removal time.
    *
    * @return
    *   the number of entries evicted
    */
  def sweep(): Int =
    val cutoff  = now().minus(idleEvict)
    var evicted = 0
    val it      = cache.entrySet().iterator()
    while it.hasNext do
      val e = it.next()
      if e.getValue.lastAccess.isBefore(cutoff) then
        if cache.remove(e.getKey, e.getValue) then
          try closeReader(e.getValue.reader)
          catch
            case t: Throwable => logger.warn(s"reader-cache sweep: close failed: ${t.getMessage}")
          evicted += 1
    if evicted > 0 then logger.info(s"reader-cache sweep: evicted $evicted idle catalog reader(s)")
    evicted

object ReaderCacheSweeper:

  /** A cache slot pairing the cached reader with its last-access stamp. `lastAccess` is `@volatile`
    * because it is written by request-handling threads on every cache hit and read by the sweeper
    * thread.
    */
  final class Entry[R](val reader: R):
    @volatile var lastAccess: Instant = Instant.now()
