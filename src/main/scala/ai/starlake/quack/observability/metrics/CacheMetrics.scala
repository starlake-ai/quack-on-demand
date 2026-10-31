package ai.starlake.quack.observability.metrics

import com.github.benmanes.caffeine.cache.Cache
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import io.micrometer.core.instrument.{Gauge, MeterRegistry}

/** The meters of a bounded manager cache (Task 11 addendum 6): Micrometer's Caffeine binder under
  * `cache=<name>` (`cache_size`, `cache_gets_total{result=hit|miss}`, `cache_evictions_total`, ...;
  * the cache must be built with `recordStats()`, or hits, misses and evictions stay at zero), and
  * its configured bound as `qod_cache_capacity{cache=<name>}`, so a dashboard can show how full it
  * is. Every replica registers its own: these caches are per replica, so a sum across replicas is
  * the fleet's.
  */
object CacheMetrics:

  /** The gauge of a cache's configured bound. */
  val CapacityMetric: String = "qod_cache_capacity"

  def bind[K, V](registry: MeterRegistry, name: String, cache: Cache[K, V], capacity: Long): Unit =
    CaffeineCacheMetrics.monitor(registry, cache, name)
    Gauge
      .builder(CapacityMetric, () => java.lang.Long.valueOf(capacity): Number)
      .tag("cache", name)
      .description("The most entries the cache keeps")
      .register(registry)
