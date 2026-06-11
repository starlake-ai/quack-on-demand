package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.DatabaseAuthConfig
import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

/** Authenticates `(scope, username, password)` against the `qodstate_user` table on the
  * control-plane Postgres.
  *
  * Two queries from `DatabaseAuthConfig`, picked by `AuthScope`:
  *   - [[AuthScope.System]] uses `systemQuery` (one placeholder: username). Matches the
  *     `tenant IS NULL` row. The caller asked for system auth (UI login with empty tenant, or
  *     FlightSQL `?superuser=true`).
  *   - [[AuthScope.Tenant]] uses `tenantQuery` (two placeholders: tenant, username). Matches the
  *     `tenant = ?` row.
  *
  * No fallback between scopes: a tenant credential cannot authenticate a system login and vice
  * versa.
  */
class DatabaseAuthenticator(config: DatabaseAuthConfig, roleClaim: String)
    extends BasicAuthProvider,
      LazyLogging:

  val name = "database"

  private val dataSource: HikariDataSource =
    val hc = new HikariConfig()
    hc.setJdbcUrl(config.jdbcUrl)
    hc.setUsername(config.username)
    hc.setPassword(config.password)
    hc.setMaximumPoolSize(5)
    hc.setMinimumIdle(1)
    hc.setConnectionTimeout(5000)
    new HikariDataSource(hc)

  override def authenticate(
      scope: AuthScope,
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile] =
    try
      val conn = dataSource.getConnection
      try
        val (query, tenantArg) = scope match
          case AuthScope.System    => (config.systemQuery, None)
          case AuthScope.Tenant(t) => (config.tenantQuery, Some(t))
        val ps = conn.prepareStatement(query)
        try
          tenantArg match
            case Some(t) =>
              ps.setString(1, t)
              ps.setString(2, username)
            case None =>
              ps.setString(1, username)
          val rs = ps.executeQuery()
          if rs.next() then
            val storedHash = rs.getString(1)
            val role       = Option(rs.getString(2)).getOrElse("user")
            if BCrypt.verifyer().verify(password.toCharArray, storedHash).verified then
              Right(
                AuthenticatedProfile(
                  username = username,
                  role = role,
                  groups = Set(role),
                  claims = Map("sub" -> username, "role" -> role, "auth_method" -> "database"),
                  authMethod = "database",
                  tenant = scope.tenantId
                )
              )
            else Left("Invalid password")
          else Left("User not found")
        finally ps.close()
      finally conn.close()
    catch
      case e: Exception =>
        logger.error(s"Database authentication error for '$username': ${e.getMessage}", e)
        Left(s"Database error: ${e.getMessage}")

  override def close(): Unit =
    dataSource.close()
