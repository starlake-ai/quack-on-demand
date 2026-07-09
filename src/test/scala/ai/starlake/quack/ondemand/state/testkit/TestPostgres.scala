package ai.starlake.quack.ondemand.state.testkit

import java.sql.DriverManager
import scala.sys.process._
import scala.util.Try

/** Shared Postgres test helpers. Two responsibilities:
  *
  *   - `dropDatabase(name)` issues a Postgres 13+ `DROP DATABASE IF
  *     EXISTS "name" WITH (FORCE)` so the cleanup succeeds even if a
  *     test left an idle backend connection open.
  *   - `dropStrayTestDatabases(prefix)` runs at suite-start so any
  *     leftover `${prefix}_test_*` databases from a SIGKILL'd previous
  *     run get reaped before the new run starts allocating names. */
object TestPostgres:

  val pgHost: String = sys.env.getOrElse("SL_TEST_PG_HOST",     "localhost")
  val pgPort: Int    = sys.env.getOrElse("SL_TEST_PG_PORT",     "5432").toInt
  val pgUser: String = sys.env.getOrElse("SL_TEST_PG_USER",     "postgres")
  val pgPass: String = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  Class.forName("org.postgresql.Driver")

  def adminUrl: String          = s"jdbc:postgresql://$pgHost:$pgPort/postgres"
  def dbUrl(db: String): String = s"jdbc:postgresql://$pgHost:$pgPort/$db"

  def reachable: Boolean =
    Try {
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try c.isValid(2) finally c.close()
    }.getOrElse(false)

  def reachableOrCancel(message: String => String = identity): Nothing =
    org.scalatest.Assertions.cancel(message(s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"))

  /** Drop a database via psql, forcing connection termination so a
    * lingering idle backend can't block the test cleanup. */
  def dropDatabase(name: String): Unit =
    psql("postgres", s"""DROP DATABASE IF EXISTS "$name" WITH (FORCE)""")

  /** Reap any `${prefix}_test_%` database left behind by a previous,
    * interrupted suite run. Each spec calls this once at suite start
    * so a clean test run is the steady state regardless of how the
    * prior JVM died. */
  def dropStrayTestDatabases(prefix: String): Unit =
    if !reachable then ()
    else
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try
        val ps = c.prepareStatement(
          "SELECT datname FROM pg_database WHERE datname LIKE ?"
        )
        val strays = scala.collection.mutable.ListBuffer.empty[String]
        try
          ps.setString(1, s"${prefix}_test_%")
          val rs = ps.executeQuery()
          try while rs.next() do strays += rs.getString(1)
          finally rs.close()
        finally ps.close()
        strays.foreach(n => Try(dropDatabase(n)))
      finally c.close()

  def psql(targetDb: String, sql: String): Unit =
    val rc = Process(
      Seq("psql", "-h", pgHost, "-p", pgPort.toString, "-U", pgUser, "-d", targetDb, "-tAc", sql),
      None,
      "PGPASSWORD" -> pgPass
    ).!
    assert(rc == 0, s"psql ($sql) exit=$rc")
