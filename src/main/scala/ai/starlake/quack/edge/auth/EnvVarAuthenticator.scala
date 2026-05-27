package ai.starlake.quack.edge.auth

import com.typesafe.scalalogging.LazyLogging

/** Authenticates against the legacy GIZMOSQL_USERNAME/GIZMOSQL_PASSWORD env vars.
  * Always added as the last basic auth provider so the configured backend credentials
  * continue to work even when external auth providers are enabled.
  */
class EnvVarAuthenticator(expectedUsername: String, expectedPassword: String)
    extends BasicAuthProvider,
      LazyLogging:

  val name = "env-var"

  override def authenticate(
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile] =
    if expectedUsername.nonEmpty && username == expectedUsername && password == expectedPassword then
      Right(
        AuthenticatedProfile(
          username = username,
          role = "admin",
          groups = Set("admin"),
          claims = Map("sub" -> username, "role" -> "admin", "auth_method" -> "env-var"),
          authMethod = "env-var"
        )
      )
    else Left("Credentials do not match configured GIZMOSQL_USERNAME/PASSWORD")