package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.DatabaseAuthConfig
import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.Types

/** Authenticates `(tenant, username, password)` against the
  * `qodstate_user` table on the control-plane Postgres. The `pool`
  * parameter is kept on the [[BasicAuthProvider]] signature for
  * back-compat with the auth chain but is no longer used here -- pool
  * access is enforced at the FlightSQL handshake via
  * [[ai.starlake.quack.ondemand.state.PoolPermission]], not in the
  * password lookup.
  *
  * The query MUST return two columns `(password_hash, role)` and accept
  * two placeholders in order: `tenant`, `username`. `tenant` is bound
  * as SQL NULL for the manager UI / REST login.
  *
  * Default query in `application.conf` treats a row with `tenant IS
  * NULL` as a wildcard matching any caller, so the bootstrap superuser
  * works on both the manager UI and any FlightSQL tenant. A
  * tenant-scoped row `(tenant, username)` wins over the wildcard NULL
  * row when both exist:
  * {{{
  *   SELECT password_hash, role FROM qodstate_user
  *   WHERE (tenant IS NULL OR tenant = ?)
  *     AND username = ?
  *   ORDER BY (tenant IS NOT NULL) DESC
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
    // `pool` is ignored -- the directory is keyed by (tenant, username)
    // only. Pool access is checked separately at handshake time.
    val _ = pool
    try
      val conn = dataSource.getConnection
      try
        val ps = conn.prepareStatement(query)
        try
          tenant match
            case Some(t) => ps.setString(1, t)
            case None    => ps.setNull(1, Types.VARCHAR)
          ps.setString(2, username)
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