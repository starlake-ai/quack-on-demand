package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.EffectiveSet

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

/** Thread-safe map of `peerIdentity → Entry`, populated by the auth handler during Handshake and
  * read by the producer during `getStream`.
  *
  * Every authenticated entry carries the session's [[SessionGrants]]: the
  * [[ai.starlake.quack.ondemand.rbac.EffectiveSet]] the handshake resolved, resolved again on the
  * first statement after the tenant's policy version moved, so a statement reads no join against
  * qodstate_role_permission / qodstate_user_role until a grant changed. Superusers carry an empty
  * effective set, never resolved again; the validator bypasses them outright based on
  * `effectiveSet.user.tenant.isEmpty`.
  *
  * Sessions carry an `expiresAt`. Once past, every accessor pretends the entry doesn't exist and
  * the auth handler falls through to a full handshake -- which re-validates the original
  * Basic/Bearer credentials against the configured providers. This bounds the "revoked JWT still
  * works" window to `sessionTtlSec`.
  */
object ConnectionContext:

  /** Snapshot of a bound session. Immutable so the router can pass it around freely without TrieMap
    * re-lookups.
    */
  final case class Entry(
      poolKey: PoolKey,
      connectionId: String,
      user: String,
      grants: Option[SessionGrants],
      expiresAt: Instant
  ):
    def expired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

    /** The effective set the session's next statement runs under ([[SessionGrants.current]]); None
      * when no handshake state is attached. Left: the session's grants now refuse it, or could not
      * be resolved again. May block on the control plane.
      */
    def effectiveSet(): Either[RouterFailure, Option[EffectiveSet]] =
      grants match
        case None    => Right(None)
        case Some(g) => g.current().map(Some(_))

  private val byPeer = TrieMap.empty[String, Entry]

  /** Called with the connection id of every entry this table drops, however it is dropped (lazy
    * expiry on access, [[evictExpired]], [[unbind]]). The Flight edge installs the router's session
    * close here, so the router's per-connection state does not outlive the handshake.
    */
  private val evictHook = new AtomicReference[String => Unit](_ => ())

  def onEvict(f: String => Unit): Unit = evictHook.set(f)

  private def drop(peer: String): Unit =
    byPeer.remove(peer).foreach(e => evictHook.get()(e.connectionId))

  /** Default 1 hour. Overridable via `EdgeConfig.sessionTtlSec` at the binding site -- kept here as
    * a fallback for tests that build entries directly.
    */
  private val DefaultTtlSec: Long = 3600

  /** `grants` is required: the FlightSQL handshake binds the [[SessionGrants]] it resolved, which
    * follow the tenant's policy version; None binds a session without handshake state, whose
    * statements the validator denies.
    */
  def bind(
      peer: String,
      key: PoolKey,
      connectionId: String,
      user: String,
      grants: Option[SessionGrants],
      ttlSec: Long = DefaultTtlSec
  ): Unit =
    val exp = Instant.now().plusSeconds(ttlSec)
    byPeer.put(peer, Entry(key, connectionId, user, grants, exp)); ()

  /** Return the entry if present AND not expired. Expired entries are evicted lazily on access so a
    * long-idle peerId doesn't accumulate.
    */
  def entry(peer: String): Option[Entry] =
    byPeer.get(peer).filter { e =>
      if e.expired() then { drop(peer); false }
      else true
    }

  /** Drop every expired entry now, whether or not its peer ever comes back. Returns how many were
    * dropped. Run periodically by the Flight edge, since a client that simply went away never
    * triggers the lazy eviction in [[entry]].
    */
  def evictExpired(now: Instant = Instant.now()): Int =
    val gone = byPeer.iterator.collect { case (peer, e) if e.expired(now) => peer }.toList
    gone.foreach(drop)
    gone.size

  def poolFor(peer: String): Option[PoolKey]        = entry(peer).map(_.poolKey)
  def connectionIdFor(peer: String): Option[String] = entry(peer).map(_.connectionId)
  def userFor(peer: String): Option[String]         = entry(peer).map(_.user)

  def unbind(peer: String): Unit = drop(peer)

  /** Test-only reset to drop every entry. Production never calls this -- session lifecycle is
    * governed by the per-entry TTL.
    */
  def clear(): Unit = byPeer.clear()
