package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.EffectiveSet

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Thread-safe map of `peerIdentity → Entry`, populated by the auth handler
  * during Handshake and read by the producer during `getStream`.
  *
  * Phase C: every authenticated entry carries an [[EffectiveSet]] computed
  * once at handshake time. The per-statement ACL gate reads this cached
  * snapshot via `effectiveSetFor(peer)` -- no per-RPC join against
  * qodstate_role_permission / qodstate_user_role. Superusers carry an
  * empty effective set; the validator bypasses them outright based on
  * `effectiveSet.user.tenant.isEmpty`.
  *
  * `groups` and `role` are retained as legacy free-text fields populated
  * from the [[ai.starlake.quack.edge.auth.AuthenticatedProfile]] JWT
  * claims. RBAC role / group resolution happens through `effectiveSet`,
  * not these.
  *
  * Sessions carry an `expiresAt`. Once past, every accessor pretends the
  * entry doesn't exist and the auth handler falls through to a full
  * handshake - which re-validates the original Basic/Bearer credentials
  * against the configured providers. This bounds the "revoked JWT still
  * works" window to `sessionTtlSec`. */
object ConnectionContext:

  /** Snapshot of a bound session. Immutable so the router can pass it
    * around freely without TrieMap re-lookups. */
  final case class Entry(
      poolKey:      PoolKey,
      connectionId: String,
      user:         String,
      groups:       Set[String],
      role:         String,
      effectiveSet: Option[EffectiveSet],
      expiresAt:    Instant
  ):
    def expired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

  private val byPeer = TrieMap.empty[String, Entry]

  /** Default 1 hour. Overridable via `EdgeConfig.sessionTtlSec` at the
    * binding site - kept here as a fallback for tests that build entries
    * directly. */
  private val DefaultTtlSec: Long = 3600

  def bind(
      peer: String,
      key: PoolKey,
      connectionId: String,
      user: String,
      groups:       Set[String]          = Set.empty,
      role:         String               = "",
      effectiveSet: Option[EffectiveSet] = None,
      ttlSec:       Long                 = DefaultTtlSec
  ): Unit =
    val exp = Instant.now().plusSeconds(ttlSec)
    byPeer.put(peer, Entry(key, connectionId, user, groups, role, effectiveSet, exp)); ()

  /** Return the entry if present AND not expired. Expired entries are
    * evicted lazily on access so a long-idle peerId doesn't accumulate. */
  def entry(peer: String): Option[Entry] =
    byPeer.get(peer).filter { e =>
      if e.expired() then { byPeer.remove(peer); false }
      else true
    }

  // Back-compat accessors for the existing call sites - all go through
  // `entry` so they share the TTL eviction.
  def poolFor(peer: String):         Option[PoolKey]      = entry(peer).map(_.poolKey)
  def connectionIdFor(peer: String): Option[String]       = entry(peer).map(_.connectionId)
  def userFor(peer: String):         Option[String]       = entry(peer).map(_.user)
  def groupsFor(peer: String):       Set[String]          = entry(peer).map(_.groups).getOrElse(Set.empty)
  def roleFor(peer: String):         String               = entry(peer).map(_.role).getOrElse("")
  def effectiveSetFor(peer: String): Option[EffectiveSet] = entry(peer).flatMap(_.effectiveSet)

  def unbind(peer: String): Unit =
    byPeer.remove(peer); ()

  /** Test-only reset to drop every entry. Production never calls this --
    * session lifecycle is governed by the per-entry TTL. */
  def clear(): Unit = byPeer.clear()