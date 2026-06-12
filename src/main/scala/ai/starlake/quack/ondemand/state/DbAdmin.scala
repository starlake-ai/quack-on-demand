package ai.starlake.quack.ondemand.state

import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager}

/** Provisions / decommissions one Postgres database per tenant-db row.
  *
  * `CREATE DATABASE` cannot run inside a transaction, so all calls open a fresh auto-commit JDBC
  * connection to an admin database (usually `postgres`) and execute a single statement. Both
  * methods are idempotent: they pre-check `pg_database` and skip if the target state is already in
  * place.
  */
trait DbAdmin:
  /** `Right(())` once the database exists; `Left(msg)` on a Postgres error we cannot classify as
    * "already in the target state".
    */
  def createDatabase(name: String): Either[String, Unit]

  /** Drop the database if it exists. Swallowed-by-caller failures (e.g. still in use by another
    * connection) are surfaced as `Left`.
    */
  def dropDatabase(name: String): Either[String, Unit]

/** Test fixture that never touches a real server. */
object NoopDbAdmin extends DbAdmin:
  def createDatabase(name: String): Either[String, Unit] = Right(())
  def dropDatabase(name: String): Either[String, Unit]   = Right(())

/** Production [[DbAdmin]] backed by an admin JDBC connection.
  *
  * `adminDbName` is the database used to issue `CREATE / DROP DATABASE` statements (it just needs
  * to be a DB on the same server that we can connect to). Defaults to `postgres`, which always
  * exists on a stock Postgres install.
  */
final class PostgresDbAdmin(
    host: String,
    port: String,
    user: String,
    password: String,
    adminDbName: String = "postgres"
) extends DbAdmin
    with LazyLogging:

  Class.forName("org.postgresql.Driver")

  private def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$adminDbName"

  def createDatabase(name: String): Either[String, Unit] =
    withAdminConnection { conn =>
      if exists(conn, name) then logger.info(s"createDatabase: '$name' already exists; skipping")
      else
        val st = conn.createStatement()
        try
          st.execute(s"""CREATE DATABASE "${escapeIdent(name)}"""")
          logger.info(s"createDatabase: created '$name'")
        finally st.close()
    }

  def dropDatabase(name: String): Either[String, Unit] =
    withAdminConnection { conn =>
      if !exists(conn, name) then logger.info(s"dropDatabase: '$name' already gone; skipping")
      else
        val st = conn.createStatement()
        try
          // WITH (FORCE) (PG 13+) terminates any lingering idle backend
          // connection. The project floor is PG16, so this is always
          // supported -- no fallback needed.
          st.execute(s"""DROP DATABASE "${escapeIdent(name)}" WITH (FORCE)""")
          logger.info(s"dropDatabase: dropped '$name'")
        finally st.close()
    }

  private def exists(conn: Connection, name: String): Boolean =
    val ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
    try
      ps.setString(1, name)
      val rs = ps.executeQuery()
      try rs.next()
      finally rs.close()
    finally ps.close()

  private def withAdminConnection(f: Connection => Unit): Either[String, Unit] =
    try
      val conn = DriverManager.getConnection(jdbcUrl, user, password)
      try
        conn.setAutoCommit(true) // CREATE/DROP DATABASE cannot run in a transaction
        f(conn)
        Right(())
      finally conn.close()
    catch case t: Throwable => Left(t.getMessage)

  // Double any quote that may appear in the identifier so the resulting
  // quoted literal stays well-formed even on the unusual cases.
  private def escapeIdent(s: String): String = s.replace("\"", "\"\"")

object PostgresDbAdmin:

  /** Build a [[PostgresDbAdmin]] from the global `defaultMetastore` map. `adminDbName` defaults to
    * `"postgres"`.
    */
  def fromDefaultMetastore(
      meta: Map[String, String],
      adminDbName: String = "postgres"
  ): PostgresDbAdmin =
    def required(k: String): String =
      meta
        .get(k)
        .filter(_.nonEmpty)
        .getOrElse(
          sys.error(s"defaultMetastore.$k must be set for PostgresDbAdmin")
        )
    new PostgresDbAdmin(
      host = required("pgHost"),
      port = required("pgPort"),
      user = required("pgUser"),
      password = required("pgPassword"),
      adminDbName = adminDbName
    )
