package ai.starlake.quack.edge.auth

/** Authenticates username/password credentials (database, ROPC).
  *
  * `tenant` and `pool` scope the lookup: a tenant-scoped principal is
  * identified by `(tenant, pool, username)`; the system admin (manager
  * UI / REST) is `(None, None, username)`. Providers that don't care
  * about tenant scoping (Keycloak/Azure ROPC) may ignore the extra
  * params -- the OIDC server is the source of truth there. */
trait BasicAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(
      tenant:   Option[String],
      pool:     Option[String],
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile]
  def close(): Unit = ()

/** Authenticates Bearer tokens (JWT, OIDC). */
trait BearerAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(token: String): Either[String, AuthenticatedProfile]
  def close(): Unit = ()