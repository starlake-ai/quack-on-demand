package ai.starlake.quack.edge.auth

/** Authenticates username/password credentials (database, ROPC). */
trait BasicAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(username: String, password: String): Either[String, AuthenticatedProfile]
  def close(): Unit = ()

/** Authenticates Bearer tokens (JWT, OIDC). */
trait BearerAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(token: String): Either[String, AuthenticatedProfile]
  def close(): Unit = ()