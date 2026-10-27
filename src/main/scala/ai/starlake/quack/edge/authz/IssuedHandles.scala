package ai.starlake.quack.edge.authz

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import java.util.concurrent.TimeUnit

/** The nonce of every handle this replica minted for a principal other than the system one, with
  * the node and the principal it names (decision 23). The internal credentials endpoint serves a
  * user's keys only for a handle recorded here: every node holds its node key, and a node user who
  * could read it would otherwise mint a handle for any principal and fetch that principal's keys.
  *
  * Kept until [[NodeHandles.DecideGraceMs]] past the handle's expiry (a connection keeps its last
  * handle while it fetches), bounded by `maxEntries`: under a flood of mints the oldest go first,
  * and their fetch is a retryable 503. In memory only, per replica: a node calls the replica its
  * handle names.
  */
final class IssuedHandles(
    maxEntries: Long = IssuedHandles.MaxEntries,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val cache: Cache[String, (IssuedHandles.Issued, Long)] =
    Caffeine
      .newBuilder()
      .maximumSize(maxEntries)
      .expireAfterWrite(NodeHandles.LifetimeMs + NodeHandles.DecideGraceMs, TimeUnit.MILLISECONDS)
      .executor(_.run())
      .build[String, (IssuedHandles.Issued, Long)]()

  def put(nonce: String, node: String, principalId: String, expiresAt: Long): Unit =
    cache.put(nonce, (IssuedHandles.Issued(node, principalId), expiresAt))

  /** Who the handle `nonce` names was minted for, while it may still authenticate a fetch. */
  def get(nonce: String): Option[IssuedHandles.Issued] =
    Option(cache.getIfPresent(nonce)).collect {
      case (issued, expiresAt) if clock() <= expiresAt + NodeHandles.DecideGraceMs => issued
    }

  /** How many nonces are held, after pending evictions ran. */
  def size: Long =
    cache.cleanUp()
    cache.estimatedSize()

object IssuedHandles:

  final case class Issued(node: String, principalId: String)

  /** Nonces kept at most: one per statement of a non-system principal, for a handle's lifetime plus
    * its grace, covers 550 statements a second.
    */
  val MaxEntries: Long = 200_000L
