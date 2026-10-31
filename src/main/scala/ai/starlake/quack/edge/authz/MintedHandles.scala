package ai.starlake.quack.edge.authz

import ai.starlake.quack.observability.metrics.CacheMetrics
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import io.micrometer.core.instrument.MeterRegistry

import java.util.concurrent.TimeUnit

/** The authorization context of every handle this replica minted for a user statement, by nonce
  * (decision 22): a node's decide call names its handle, and the decision is taken with exactly the
  * effective set, pool and token restriction the handle was minted for, which the handle only
  * digests (`ctx`). That is why a node calls the replica its handle names.
  *
  * A context is kept, with the `ctx` digest its handle carries (computed once, at mint), until
  * [[NodeHandles.DecideGraceMs]] past its handle's expiry. Bounded by `maxEntries`
  * (`QOD_AUTHZ_HANDLE_CONTEXTS_MAX`, required: no construction gets a bound by omission): past the
  * bound Caffeine evicts by W-TinyLFU, not oldest first, so some contexts go early (a just-minted
  * one among them), and their decide calls get a retryable 503 (`authorization context expired;
  * retry`). A context is kept a handle's lifetime plus its grace (360 s) whatever the statement's
  * duration, so the bound caps a replica's sustained tenant statements at about `maxEntries / 360`
  * per second (about 555 at the default 200000);
  * `cache_evictions_total{cache="qod_minted_handles"}` shows it being reached. Superusers and the
  * system principal need no context and are never registered (NodeKeys). In memory only.
  */
final class MintedHandles(
    maxEntries: Long,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val cache: Cache[String, (MintedHandles.Minted, Long)] =
    Caffeine
      .newBuilder()
      .maximumSize(maxEntries)
      .expireAfterWrite(NodeHandles.LifetimeMs + NodeHandles.DecideGraceMs, TimeUnit.MILLISECONDS)
      .executor(_.run())
      .recordStats()
      .build[String, (MintedHandles.Minted, Long)]()

  /** Registers the context the handle `nonce` was minted with, and that handle's `ctx`. */
  def put(nonce: String, context: DecideContext, ctx: String, expiresAt: Long): Unit =
    cache.put(nonce, (MintedHandles.Minted(context, ctx), expiresAt))

  /** The context of the handle `nonce` names, and its `ctx`, while that handle may still
    * authenticate a decide call.
    */
  def get(nonce: String): Option[MintedHandles.Minted] =
    Option(cache.getIfPresent(nonce)).collect {
      case (minted, expiresAt) if clock() <= expiresAt + NodeHandles.DecideGraceMs => minted
    }

  /** Its size, hits, misses, evictions and capacity under `cache=qod_minted_handles`. */
  def bindMetrics(registry: MeterRegistry): Unit =
    CacheMetrics.bind(registry, MintedHandles.MetricName, cache, maxEntries)

  /** How many contexts are held, after pending evictions ran. */
  def size: Long =
    cache.cleanUp()
    cache.estimatedSize()

object MintedHandles:

  /** A registered context and the `ctx` digest of it the handle carries. */
  final case class Minted(context: DecideContext, ctx: String)

  /** The `cache` tag of its meters. */
  val MetricName: String = "qod_minted_handles"
