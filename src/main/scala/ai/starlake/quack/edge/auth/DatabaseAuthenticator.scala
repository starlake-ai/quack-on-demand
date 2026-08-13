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
  *
  * Both queries MUST return exactly four columns:
  * `(password_hash, role, enabled, must_change_password)`. The third and fourth columns are
  * mandatory, not optional -- a result set with fewer than four columns fails the login outright
  * (config error, not a tolerant default-to-enabled / default-to-unflagged).
  *
  * Check ordering is bcrypt -> enabled -> must_change_password. The flag is only ever surfaced
  * after the password verified on an enabled account, so the distinct
  * [[AuthFailure.PasswordChangeRequired]] cannot be used as an account-probing oracle by a caller
  * who does not already hold the credential.
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
  ): Either[AuthFailure, AuthenticatedProfile] =
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
            // The enabled and must_change_password columns are mandatory:
            // systemQuery/tenantQuery MUST project exactly
            // (password_hash, role, enabled, must_change_password). A short
            // result set used to be tolerated with enabled defaulted to
            // true, which silently dropped enforcement for any operator
            // still running a legacy custom query. That tolerant branch is
            // gone -- a short projection now fails the login instead of
            // admitting it.
            if rs.getMetaData.getColumnCount < 4 then
              logger.error(
                s"login rejected for '$username': the configured auth query returns " +
                  s"${rs.getMetaData.getColumnCount} column(s); it must project " +
                  "(password_hash, role, enabled, must_change_password) -- update " +
                  "systemQuery/tenantQuery (QOD_AUTH_DB_SYSTEM_QUERY / QOD_AUTH_DB_TENANT_QUERY)"
              )
              Left(AuthFailure.InvalidCredentials("Invalid password"))
            else
              val storedHash = rs.getString(1)
              val role       = Option(rs.getString(2)).getOrElse("user")
              val enabled    = rs.getBoolean(3)
              val mustChange = rs.getBoolean(4)
              if !BCrypt.verifyer().verify(password.toCharArray, storedHash).verified then
                Left(AuthFailure.InvalidCredentials("Invalid password"))
              else if !enabled then
                // Same failure shape as a wrong password so the response does
                // not reveal that the account exists but is disabled. The
                // distinct reason is only visible in the manager log. bcrypt
                // verification ran above regardless, so timing does not
                // distinguish the two either.
                logger.info(s"login rejected for '$username': user is disabled")
                Left(AuthFailure.InvalidCredentials("Invalid password"))
              else if mustChange then
                // Distinct, deliberately revealed failure: the password verified
                // and the account is enabled, so the caller already holds the
                // credential. Telling them to rotate it is the whole point of
                // the flag.
                logger.info(s"login rejected for '$username': password change required")
                Left(AuthFailure.PasswordChangeRequired)
              else
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
          else Left(AuthFailure.InvalidCredentials("User not found"))
        finally ps.close()
      finally conn.close()
    catch
      case e: Exception =>
        logger.error(s"Database authentication error for '$username': ${e.getMessage}", e)
        Left(AuthFailure.InvalidCredentials(s"Database error: ${e.getMessage}"))

  override def close(): Unit =
    dataSource.close()
