package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Thread-safe map of `peerIdentity → Entry`, populated by the auth handler
  * during Handshake and read by the producer during `getStream`.
  *
  * `groups` and `role` come from `AuthenticatedProfile` when a real auth
  * provider claimed the credentials; for unauthenticated (trust-the-client)
  * sessions they're `Set.empty` / `""` respectively. The ACL validator
  * expands them into `group:<g>` and `role:<r>` principals so grants
  * targeted at groups / roles match — not just `user:<name>`.
  *
  * Sessions carry an `expiresAt`. Once past, every accessor pretends the
  * entry doesn't exist and the auth handler falls through to a full
  * handshake — which re-validates the original Basic/Bearer credentials
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
      expiresAt:    Instant
  ):
    def expired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

  private val byPeer = TrieMap.empty[String, Entry]

  /** Default 1 hour. Overridable via `EdgeConfig.sessionTtlSec` at the
    * binding site — kept here as a fallback for tests that build entries
    * directly. */
  private val DefaultTtlSec: Long = 3600

  def bind(
      peer: String,
      key: PoolKey,
      connectionId: String,
      user: String,
      groups: Set[String] = Set.empty,
      role:   String       = "",
      ttlSec: Long         = DefaultTtlSec
  ): Unit =
    val exp = Instant.now().plusSeconds(ttlSec)
    byPeer.put(peer, Entry(key, connectionId, user, groups, role, exp)); ()

  /** Return the entry if present AND not expired. Expired entries are
    * evicted lazily on access so a long-idle peerId doesn't accumulate. */
  def entry(peer: String): Option[Entry] =
    byPeer.get(peer).filter { e =>
      if e.expired() then { byPeer.remove(peer); false }
      else true
    }

  // Back-compat accessors for the existing call sites — all go through
  // `entry` so they share the TTL eviction.
  def poolFor(peer: String):         Option[PoolKey] = entry(peer).map(_.poolKey)
  def connectionIdFor(peer: String): Option[String]  = entry(peer).map(_.connectionId)
  def userFor(peer: String):         Option[String]  = entry(peer).map(_.user)
  def groupsFor(peer: String):       Set[String]     = entry(peer).map(_.groups).getOrElse(Set.empty)
  def roleFor(peer: String):         String          = entry(peer).map(_.role).getOrElse("")

  def unbind(peer: String): Unit =
    byPeer.remove(peer); ()
