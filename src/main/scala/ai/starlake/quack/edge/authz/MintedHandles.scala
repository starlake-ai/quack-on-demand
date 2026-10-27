package ai.starlake.quack.edge.authz

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import java.util.concurrent.TimeUnit

/** The authorization context of every handle this replica minted for a user statement, by nonce
  * (decision 22): a node's decide call names its handle, and the decision is taken with exactly the
  * effective set, pool and token restriction the handle was minted for, which the handle only
  * digests (`ctx`). That is why a node calls the replica its handle names.
  *
  * A context is kept until [[NodeHandles.DecideGraceMs]] past its handle's expiry. Bounded by
  * `maxEntries`: under a flood of mints the oldest contexts go first, and their decide calls get a
  * retryable 503. In memory only.
  */
final class MintedHandles(
    maxEntries: Long = MintedHandles.MaxEntries,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val cache: Cache[String, (DecideContext, Long)] =
    Caffeine
      .newBuilder()
      .maximumSize(maxEntries)
      .expireAfterWrite(NodeHandles.LifetimeMs + NodeHandles.DecideGraceMs, TimeUnit.MILLISECONDS)
      .executor(_.run())
      .build[String, (DecideContext, Long)]()

  def put(nonce: String, context: DecideContext, expiresAt: Long): Unit =
    cache.put(nonce, (context, expiresAt))

  /** The context of the handle `nonce` names, while that handle may still authenticate a decide
    * call.
    */
  def get(nonce: String): Option[DecideContext] =
    Option(cache.getIfPresent(nonce)).collect {
      case (context, expiresAt) if clock() <= expiresAt + NodeHandles.DecideGraceMs => context
    }

  /** How many contexts are held, after pending evictions ran. */
  def size: Long =
    cache.cleanUp()
    cache.estimatedSize()

object MintedHandles:

  /** Contexts kept at most. Decide calls follow their prelude within milliseconds; this many covers
    * 550 statements a second for a handle's lifetime plus its grace.
    */
  val MaxEntries: Long = 200_000L
