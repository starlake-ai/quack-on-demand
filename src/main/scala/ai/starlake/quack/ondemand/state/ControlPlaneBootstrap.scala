package ai.starlake.quack.ondemand.state

import com.typesafe.scalalogging.LazyLogging

import java.sql.{DriverManager, SQLException}
import java.util.Properties

/** Ensures the control-plane database exists before anything (Liquibase, Hikari pools) touches
  * it, so a fresh install needs no `psql` preflight in the launcher.
  *
  * Classification is deliberately narrow: only a connect failure with SQLState `3D000`
  * (invalid_catalog_name, "database does not exist") triggers a `CREATE DATABASE` through
  * [[PostgresDbAdmin]] on the admin database. Every other failure (server unreachable, bad
  * credentials, TLS trouble) is returned as `Left` untouched, so boot keeps refusing to start
  * instead of masking a real problem.
  */
object ControlPlaneBootstrap extends LazyLogging:

  /** Postgres SQLState for "database does not exist" on connect. */
  private val MissingDatabase = "3D000"

  Class.forName("org.postgresql.Driver")

  /** Ensure `meta`'s control-plane database (`dbName`, default `qod`) exists.
    *
    *   - `Right(false)`: the database already existed.
    *   - `Right(true)`: the server was reachable, the database was missing, and it was created.
    *   - `Left(reason)`: the server is unreachable, credentials are wrong, or the create failed.
    *
    * `adminDb` is the maintenance database used to issue `CREATE DATABASE` (override with the
    * `PG_ADMIN_DB` env var for managed Postgres whose maintenance DB is not named `postgres`).
    */
  def ensureDatabase(
      meta: Map[String, String],
      adminDb: String = sys.env.getOrElse("PG_ADMIN_DB", "postgres"),
      timeoutSec: Int = 5
  ): Either[String, Boolean] =
    val host = meta.getOrElse("pgHost", "localhost")
    val port = meta.getOrElse("pgPort", "5432")
    val user = meta.getOrElse("pgUser", "postgres")
    val db   = meta.getOrElse("dbName", "qod")

    def probe(): Unit =
      val props = new Properties()
      props.setProperty("user", user)
      props.setProperty("password", meta.getOrElse("pgPassword", ""))
      // Per-connection timeouts: DriverManager.setLoginTimeout is JVM-global and racy.
      props.setProperty("connectTimeout", timeoutSec.toString)
      props.setProperty("loginTimeout", timeoutSec.toString)
      DriverManager.getConnection(s"jdbc:postgresql://$host:$port/$db", props).close()

    def firstLine(t: Throwable): String =
      Option(t.getMessage).getOrElse(t.getClass.getSimpleName).linesIterator.next()

    try
      probe()
      Right(false)
    catch
      case t: SQLException if t.getSQLState == MissingDatabase =>
        logger.info(s"control-plane database '$db' does not exist; creating it via '$adminDb'")
        val admin =
          new PostgresDbAdmin(host, port, user, meta.getOrElse("pgPassword", ""), adminDb)
        admin.createDatabase(db) match
          case Left(err) =>
            Left(s"control-plane database '$db' is missing and CREATE DATABASE failed: $err")
          case Right(()) =>
            try
              probe()
              Right(true)
            catch
              case t2: Throwable =>
                Left(s"created control-plane database '$db' but cannot connect to it: ${firstLine(t2)}")
      case t: Throwable =>
        Left(firstLine(t))
