package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.{LiquibaseRunner, TenantIdentityStore}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.sys.process._
import scala.util.Try

/** Integration test: requires a local Postgres. Verifies that
  * [[BootstrapAccessSeeder]] inserts the tenant-identity row on first
  * run and that a second run is a no-op. Post-Phase-C the seeder no
  * longer touches a grant table -- superuser admins (tenant=NULL)
  * bypass the per-statement ACL gate outright. */
class BootstrapAccessSeederSpec extends AnyFlatSpec with Matchers:

  ai.starlake.quack.ondemand.state.testkit.TestPostgres.dropStrayTestDatabases("qodbs")

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

  private def withStores(test: (TenantIdentityStore, String) => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qodbs_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      new LiquibaseRunner(dbUrl(dbName), pgUser, pgPass).run()
      val tid = "t-bootstrap"
      psql(dbName, s"INSERT INTO qodstate_tenant (id, display_name, disabled) VALUES ('$tid', 'tpch', false)")
      val identityStore = new TenantIdentityStore(dbUrl(dbName), pgUser, pgPass)
      test(identityStore, tid)
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  "BootstrapAccessSeeder.seed" should
    "insert one identity per admin on first run" in withStores { (ids, tid) =>
    val inserted = BootstrapAccessSeeder.seed(
      tenantId      = tid,
      tenantLabel   = "tpch",
      adminNames    = List("admin@localhost.local", "admin"),
      identityStore = ids
    )
    inserted shouldBe 2
    ids.list(Some(tid)).map(i => (i.issuer, i.externalId)).toSet shouldBe Set(
      ("db", "admin@localhost.local"),
      ("db", "admin"),
    )
  }

  it should "be a no-op on a second run" in withStores { (ids, tid) =>
    val first  = BootstrapAccessSeeder.seed(tid, "tpch", List("admin"), ids)
    val second = BootstrapAccessSeeder.seed(tid, "tpch", List("admin"), ids)
    first  shouldBe 1
    second shouldBe 0
    ids.list(Some(tid)).size shouldBe 1
  }

  it should "return 0 when adminNames is empty" in withStores { (ids, tid) =>
    BootstrapAccessSeeder.seed(tid, "tpch", Nil, ids) shouldBe 0
    ids.list(Some(tid)) shouldBe empty
  }

  it should "only seed rows missing for the admin (mixed pre-existing state)" in withStores { (ids, tid) =>
    ids.create(tid, "db", "alice")
    val inserted = BootstrapAccessSeeder.seed(tid, "tpch", List("alice", "bob"), ids)
    inserted shouldBe 1
    ids.list(Some(tid)).map(_.externalId).toSet shouldBe Set("alice", "bob")
  }
