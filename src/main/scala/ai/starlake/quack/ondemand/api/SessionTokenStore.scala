package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope

import java.time.Instant
import java.util.UUID
import scala.collection.concurrent.TrieMap

/** In-memory session token registry for UI logins. Keys are opaque UUIDs returned to the browser;
  * values carry the authenticated profile AND the resolved [[SessionScope]] so the admin-only
  * middleware can admit a request without re-validating credentials on every call.
  *
  * Tokens live in memory; manager restart invalidates all sessions. v1 doesn't expire tokens; the
  * UI logout drops the row and the user re-authenticates after a restart.
  */
final class SessionTokenStore:

  final case class Session(profile: AuthenticatedProfile, scope: SessionScope, createdAt: Instant)

  private val sessions = TrieMap.empty[String, Session]

  /** Mint a token carrying an explicit scope. Used by both the OIDC path (multi-tenant scope) and
    * the DB path (single-tenant or superuser scope collapsed into the same model).
    */
  def mintWithScope(profile: AuthenticatedProfile, scope: SessionScope): String =
    val token = UUID.randomUUID().toString
    sessions.put(token, Session(profile, scope, Instant.now()))
    token

  def get(token: String): Option[Session] = sessions.get(token)

  def revoke(token: String): Unit = { sessions.remove(token); () }

  /** True when the token resolves to a session whose `role = admin`. The UI is admin-only, so this
    * remains the apiKeyGuard admission check.
    */
  def isAdmin(token: String): Boolean =
    sessions.get(token).exists(_.profile.role.equalsIgnoreCase("admin"))

  /** True when the session is a superuser (cross-tenant). */
  def isSuperuser(token: String): Boolean =
    sessions.get(token).exists(_.scope.superuser)

  /** True when the session can manage the given tenant: superuser or the tenant is in
    * `manageableTenants`. Drives the per-request `tenant_forbidden` guard.
    */
  def canManage(token: String, tenant: String): Boolean =
    sessions.get(token).exists { s =>
      s.scope.superuser || s.scope.manageableTenants.contains(tenant)
    }

  def scopeOf(token: String): Option[SessionScope] = sessions.get(token).map(_.scope)