package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.DatabaseAuthConfig
import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.Types

/** Authenticates `(tenant, pool, username, password)` against the
  * `qodstate_user` table on the control-plane Postgres.
  *
  * The query MUST return two columns `(password_hash, role)` and accept
  * three placeholders in order: `tenant`, `pool`, `username`. `tenant`
  * and `pool` are bound as SQL NULL for the manager UI / REST login.
  *
  * Default query in `application.conf` treats a row with NULL
  * tenant/pool as a wildcard matching any caller (tenant, pool), so the
  * bootstrap admin works on both the manager UI and any FlightSQL
  * (tenant, pool). A scoped row `(tenant, pool, username)` wins over
  * the wildcard NULL row when both exist:
  * {{{
  *   SELECT password_hash, role FROM qodstate_user
  *   WHERE (tenant IS NULL OR tenant = ?)
  *     AND (pool   IS NULL OR pool   = ?)
  *     AND username = ?
  *   ORDER BY (tenant IS NOT NULL) DESC,
  *            (pool   IS NOT NULL) DESC
  *   LIMIT 1
  * }}}
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

  private val query: String = config.query

  override def authenticate(
      tenant:   Option[String],
      pool:     Option[String],
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile] =
    try
      val conn = dataSource.getConnection
      try
        val ps = conn.prepareStatement(query)
        try
          tenant match
            case Some(t) => ps.setString(1, t)
            case None    => ps.setNull(1, Types.VARCHAR)
          pool match
            case Some(p) => ps.setString(2, p)
            case None    => ps.setNull(2, Types.VARCHAR)
          ps.setString(3, username)
          val rs = ps.executeQuery()
          if rs.next() then
            val storedHash = rs.getString(1)
            val role = Option(rs.getString(2)).getOrElse("user")
            if BCrypt.verifyer().verify(password.toCharArray, storedHash).verified then
              Right(
                AuthenticatedProfile(
                  username = username,
                  role = role,
                  groups = Set(role),
                  claims = Map("sub" -> username, "role" -> role, "auth_method" -> "database"),
                  authMethod = "database"
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