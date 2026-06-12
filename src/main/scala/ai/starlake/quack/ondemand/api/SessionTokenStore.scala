package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** In-memory session token registry for UI logins. Keys are opaque UUIDs returned to the browser;
  * values carry the authenticated profile AND the resolved [[SessionScope]] so the admin-only
  * middleware can admit a request without re-validating credentials on every call.
  *
  * **Expiry.** Sessions are tracked with an idle TTL (`idleTtl`, default 8 hours): each successful
  * lookup slides the per-session `lastAccessedAt` forward; a session idle longer than `idleTtl` is
  * dropped on the next access and the reader sees `None`. There is no scheduled sweep -- expired
  * entries linger in memory until the next access touches them, which is fine for a control plane
  * with order-thousands of concurrent sessions. Manager restart still invalidates everything (the
  * map is heap-only).
  *
  * `clock` is injectable so tests can fast-forward without sleeping.
  */
final class SessionTokenStore(
    val idleTtl: FiniteDuration = 8.hours,
    clock: () => Instant = () => Instant.now()
):

  /** A live row in the store. `lastAccessedAt` slides on every successful lookup; reading the
    * map's `Session.lastAccessedAt` therefore reflects the most recent admit, not the mint time.
    */
  final case class Session(
      profile: AuthenticatedProfile,
      scope: SessionScope,
      createdAt: Instant,
      private val lastAccessedAtRef: AtomicReference[Instant]
  ):
    def lastAccessedAt: Instant = lastAccessedAtRef.get()
    private[SessionTokenStore] def touch(now: Instant): Unit = lastAccessedAtRef.set(now)

  private val sessions = TrieMap.empty[String, Session]

  /** Mint a token carrying an explicit scope. Used by both the OIDC path (multi-tenant scope) and
    * the DB path (single-tenant or superuser scope collapsed into the same model).
    */
  def mintWithScope(profile: AuthenticatedProfile, scope: SessionScope): String =
    val token = UUID.randomUUID().toString
    val now   = clock()
    sessions.put(token, Session(profile, scope, now, new AtomicReference(now)))
    token

  /** Resolve a token, sliding its idle-TTL window on success. Returns `None` if the token is
    * unknown OR its idle TTL has elapsed (in which case the entry is also evicted).
    */
  def get(token: String): Option[Session] = lookup(token)

  def revoke(token: String): Unit = { sessions.remove(token); () }

  /** True when the token resolves to a session whose `role = admin`. The UI is admin-only, so this
    * remains the apiKeyGuard admission check. Expired sessions return false (same as unknown).
    */
  def isAdmin(token: String): Boolean =
    lookup(token).exists(_.profile.role.equalsIgnoreCase("admin"))

  /** True when the session is a superuser (cross-tenant). */
  def isSuperuser(token: String): Boolean =
    lookup(token).exists(_.scope.superuser)

  /** True when the session can manage the given tenant: superuser or the tenant is in
    * `manageableTenants`. Drives the per-request `tenant_forbidden` guard.
    */
  def canManage(token: String, tenant: String): Boolean =
    lookup(token).exists { s =>
      s.scope.superuser || s.scope.manageableTenants.contains(tenant)
    }

  def scopeOf(token: String): Option[SessionScope] = lookup(token).map(_.scope)

  /** Test hook: how many live sessions are currently held (after expiring any idle ones the
    * caller has touched). Pure observability -- not a security boundary.
    */
  def liveSessionCount: Int = sessions.size

  /** Central lookup with expiry + sliding-window update. */
  private def lookup(token: String): Option[Session] =
    sessions.get(token).flatMap { s =>
      val now = clock()
      val idleMs = java.time.Duration.between(s.lastAccessedAt, now).toMillis
      if idleMs > idleTtl.toMillis then
        // Conditional remove so we don't race a concurrent mint that
        // reused the token (UUIDs make this practically impossible, but
        // the cost is one CAS).
        sessions.remove(token, s)
        None
      else
        s.touch(now)
        Some(s)
    }