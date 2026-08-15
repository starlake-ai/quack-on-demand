package ai.starlake.quack.edge.auth

/** Auth realm the caller is targeting.
  *
  *   - [[AuthScope.System]] -- manager UI login with empty tenant field, OR FlightSQL handshake
  *     with `?superuser=true`. Credentials are validated against the global realm (HOCON
  *     `quack-flightsql.auth.*` + `quack-on-demand.auth.management`). The matching `qodstate_user`
  *     row must have `tenant IS NULL`.
  *   - [[AuthScope.Tenant]] -- manager UI login with a tenant filled in, OR FlightSQL handshake
  *     without `superuser=true`. Credentials are validated against the tenant's own realm
  *     (`qodstate_tenant.authConfig`). The matching `qodstate_user` row must have `tenant = ?`.
  *
  * There is no fallback between the two scopes: a system credential cannot authenticate a
  * tenant-scoped login and vice versa. The caller picks the realm at the wire and the manager
  * enforces it.
  */
sealed trait AuthScope:
  def tenantId: Option[String]

object AuthScope:
  case object System extends AuthScope:
    val tenantId: Option[String] = None

  final case class Tenant(id: String) extends AuthScope:
    val tenantId: Option[String] = Some(id)

/** Typed failure of the basic (password) auth chain.
  *
  *   - [[InvalidCredentials]]: unknown user, wrong password, disabled account, provider error. The
  *     detail string is what the legacy `Either[String, _]` carried.
  *   - [[PasswordChangeRequired]]: the password VERIFIED but is flagged `must_change_password` --
  *     the caller must be told to rotate it, not that the credentials were wrong. Only reachable
  *     with a correct password on an enabled account, so surfacing it leaks nothing.
  */
enum AuthFailure:
  case InvalidCredentials(detail: String)
  case PasswordChangeRequired
  case AccountLocked

  def message: String = this match
    case InvalidCredentials(d)  => d
    case PasswordChangeRequired =>
      "password change required; use POST /api/auth/change-password"
    case AccountLocked =>
      "account locked - use forgot password to reset your credentials"

/** Authenticates username/password credentials (database, ROPC).
  *
  * `scope` picks the realm. For the database backend, [[AuthScope.System]] looks up the row with
  * `tenant IS NULL` and [[AuthScope.Tenant]] looks up the row with `tenant = ?`. OIDC ROPC
  * providers ignore the scope -- the OIDC server is authoritative -- and the registry layer picks
  * the right provider instance per scope before this is called.
  */
trait BasicAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(
      scope: AuthScope,
      username: String,
      password: String
  ): Either[AuthFailure, AuthenticatedProfile]
  def close(): Unit = ()

/** Authenticates Bearer tokens (JWT, OIDC). */
trait BearerAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(token: String): Either[String, AuthenticatedProfile]
  def close(): Unit = ()
