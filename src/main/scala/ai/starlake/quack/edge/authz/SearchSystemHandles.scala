package ai.starlake.quack.edge.authz

import ai.starlake.quack.observability.metrics.CacheMetrics
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import io.micrometer.core.instrument.MeterRegistry

import java.util.concurrent.TimeUnit

/** The nonces of system-principal handles this replica minted for the statements of a call site
  * that asks for index records (the interim bundle build, D7; any later system search), so that the
  * record route can serve the system principal (OWNER RULING 2026-11-16, D4 of the plan; review
  * I1). Filled only by such a call site's explicit flag (`QuackHttpAdapter.send(searchSystemCall =
  * true)`), never by reading a statement's text, so no other system statement (one embedding a
  * tenant-chosen name included) can crowd it (security review M3). Bounded apart from
  * [[IssuedHandles]]: 4096 entries, kept [[NodeHandles.LifetimeMs]] +
  * [[NodeHandles.DecideGraceMs]], so the probes' handles, which are never recorded, never share its
  * bound. The node and the pool are recorded as minted, with the expiry: the route takes its scope
  * from the recorded pool, never from what a node signed. In memory, per replica: a node calls the
  * replica its handle names.
  *
  * Past the bound Caffeine evicts by W-TinyLFU, as for [[IssuedHandles]]; an evicted nonce's fetch
  * is a retryable 503, the build's statement then failing retryably
  * (`cache_evictions_total{cache="qod_search_system_handles"}` shows it).
  */
final class SearchSystemHandles(
    maxEntries: Long = 4096L,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val cache: Cache[String, SearchSystemHandles.Recorded] =
    Caffeine
      .newBuilder()
      .maximumSize(maxEntries)
      .expireAfterWrite(NodeHandles.LifetimeMs + NodeHandles.DecideGraceMs, TimeUnit.MILLISECONDS)
      .executor(_.run())
      .recordStats()
      .build[String, SearchSystemHandles.Recorded]()

  def put(nonce: String, node: String, pool: String, expiresAt: Long): Unit =
    cache.put(nonce, SearchSystemHandles.Recorded(node, pool, expiresAt))

  /** What the handle `nonce` was minted for, while it may still authenticate a fetch. */
  def get(nonce: String): Option[SearchSystemHandles.Recorded] =
    Option(cache.getIfPresent(nonce)).filter(r =>
      clock() <= r.expiresAt + NodeHandles.DecideGraceMs
    )

  /** Its size, hits, misses, evictions and capacity under `cache=qod_search_system_handles`. */
  def bindMetrics(registry: MeterRegistry): Unit =
    CacheMetrics.bind(registry, SearchSystemHandles.MetricName, cache, maxEntries)

  /** How many nonces are held, after pending evictions ran. */
  def size: Long =
    cache.cleanUp()
    cache.estimatedSize()

object SearchSystemHandles:

  /** As minted: `pool` is `NodeHandles.pool` of the node's pool, `expiresAt` the handle's `exp`. */
  final case class Recorded(node: String, pool: String, expiresAt: Long)

  /** The `cache` tag of its meters. */
  val MetricName: String = "qod_search_system_handles"
