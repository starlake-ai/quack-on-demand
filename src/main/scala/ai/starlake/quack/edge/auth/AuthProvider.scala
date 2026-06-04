package ai.starlake.quack.edge.auth

/** Authenticates username/password credentials (database, ROPC).
  *
  * `tenant` scopes the lookup for the database backend: a tenant-scoped
  * row identifies one principal in that tenant; the wildcard NULL row
  * matches across every tenant (superuser). OIDC ROPC providers ignore
  * it -- the OIDC server is authoritative. */
trait BasicAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(
      tenant:   Option[String],
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile]
  def close(): Unit = ()

/** Authenticates Bearer tokens (JWT, OIDC). */
trait BearerAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(token: String): Either[String, AuthenticatedProfile]
  def close(): Unit = ()
