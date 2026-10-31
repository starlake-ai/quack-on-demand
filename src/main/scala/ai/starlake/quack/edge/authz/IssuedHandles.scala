package ai.starlake.quack.edge.authz

import ai.starlake.quack.observability.metrics.CacheMetrics
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import io.micrometer.core.instrument.MeterRegistry

import java.util.concurrent.TimeUnit

/** The nonce of every handle this replica minted for a principal other than the system one, with
  * the node and the principal it names (decision 23). The internal credentials endpoint serves a
  * user's keys only for a handle recorded here: every node holds its node key, and a node user who
  * could read it would otherwise mint a handle for any principal and fetch that principal's keys.
  *
  * Kept until [[NodeHandles.DecideGraceMs]] past the handle's expiry (a connection keeps its last
  * handle while it fetches), bounded by `maxEntries` (`QOD_AUTHZ_HANDLE_CONTEXTS_MAX`, the bound of
  * [[MintedHandles]] too; required). Past the bound Caffeine evicts by W-TinyLFU, not oldest first:
  * a nonce still in its small admission window (about 1% of the bound) can go before older ones.
  * The fetch follows the mint within milliseconds, so under a flood that is rare, and an evicted
  * nonce's fetch is a retryable 503. In memory only, per replica: a node calls the replica its
  * handle names.
  */
final class IssuedHandles(
    maxEntries: Long,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val cache: Cache[String, (IssuedHandles.Issued, Long)] =
    Caffeine
      .newBuilder()
      .maximumSize(maxEntries)
      .expireAfterWrite(NodeHandles.LifetimeMs + NodeHandles.DecideGraceMs, TimeUnit.MILLISECONDS)
      .executor(_.run())
      .recordStats()
      .build[String, (IssuedHandles.Issued, Long)]()

  def put(nonce: String, node: String, principalId: String, expiresAt: Long): Unit =
    cache.put(nonce, (IssuedHandles.Issued(node, principalId), expiresAt))

  /** Who the handle `nonce` names was minted for, while it may still authenticate a fetch. */
  def get(nonce: String): Option[IssuedHandles.Issued] =
    Option(cache.getIfPresent(nonce)).collect {
      case (issued, expiresAt) if clock() <= expiresAt + NodeHandles.DecideGraceMs => issued
    }

  /** Its size, hits, misses, evictions and capacity under `cache=qod_issued_handles`. */
  def bindMetrics(registry: MeterRegistry): Unit =
    CacheMetrics.bind(registry, IssuedHandles.MetricName, cache, maxEntries)

  /** How many nonces are held, after pending evictions ran. */
  def size: Long =
    cache.cleanUp()
    cache.estimatedSize()

object IssuedHandles:

  final case class Issued(node: String, principalId: String)

  /** The `cache` tag of its meters. */
  val MetricName: String = "qod_issued_handles"
