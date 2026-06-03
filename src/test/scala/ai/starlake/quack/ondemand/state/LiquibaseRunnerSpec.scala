package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.sys.process._
import scala.util.Try

/** Integration test for [[LiquibaseRunner]]. Requires a local Postgres
  * reachable with the SL_TEST_PG_* env vars (defaults: localhost:5432,
  * user `postgres`, password `azizam`). The test creates a throwaway
  * database, runs the changelog against it, verifies the four control-
  * plane tables exist with the expected columns, then drops the DB. */
class LiquibaseRunnerSpec extends AnyFlatSpec with Matchers:

  // One-shot sweep: drops any `qodlb_test_%` database left behind by a
  // previously-interrupted suite.
  TestPostgres.dropStrayTestDatabases("qodlb")


  private val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST",     "localhost")
  private val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT",     "5432").toInt
  private val pgUser = sys.env.getOrElse("SL_TEST_PG_USER",     "postgres")
  private val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  Class.forName("org.postgresql.Driver")

  private def adminUrl: String          = s"jdbc:postgresql://$pgHost:$pgPort/postgres"
  private def dbUrl(db: String): String = s"jdbc:postgresql://$pgHost:$pgPort/$db"

  private def pgReachable: Boolean =
    Try {
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try c.isValid(2) finally c.close()
    }.getOrElse(false)

  private def psql(targetDb: String, sql: String): Unit =
    val rc = Process(
      Seq("psql", "-h", pgHost, "-p", pgPort.toString, "-U", pgUser, "-d", targetDb, "-tAc", sql),
      None,
      "PGPASSWORD" -> pgPass
    ).!
    assert(rc == 0, s"psql ($sql) exit=$rc")

  private def withFreshDb(test: String => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qodlb_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try test(dbName)
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  "LiquibaseRunner" should "apply the changelog and create the qodstate_* tables" in withFreshDb { db =>
    new LiquibaseRunner(dbUrl(db), pgUser, pgPass).run()

    val c = DriverManager.getConnection(dbUrl(db), pgUser, pgPass)
    try
      val rs = c.createStatement().executeQuery(
        """SELECT table_name FROM information_schema.tables
          |WHERE table_schema = 'public' AND table_name LIKE 'qodstate_%'
          |ORDER BY table_name""".stripMargin
      )
      val tables = scala.collection.mutable.ListBuffer.empty[String]
      while rs.next() do tables += rs.getString(1)
      rs.close()
      tables.toList shouldBe List(
        "qodstate_node", "qodstate_pool", "qodstate_tenant",
        "qodstate_tenant_db", "qodstate_tenant_identity", "qodstate_user"
      )
    finally c.close()
  }

  it should "be idempotent on a second run" in withFreshDb { db =>
    new LiquibaseRunner(dbUrl(db), pgUser, pgPass).run()
    // Second invocation must not throw and must not duplicate tables.
    new LiquibaseRunner(dbUrl(db), pgUser, pgPass).run()
    val c = DriverManager.getConnection(dbUrl(db), pgUser, pgPass)
    try
      val rs = c.createStatement().executeQuery(
        "SELECT count(*) FROM information_schema.tables " +
          "WHERE table_schema = 'public' AND table_name LIKE 'qodstate_%'"
      )
      rs.next()
      rs.getInt(1) shouldBe 6
    finally c.close()
  }
