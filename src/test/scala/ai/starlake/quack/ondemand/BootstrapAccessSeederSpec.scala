package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.{AclGrantStore, LiquibaseRunner, TenantIdentityStore}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.sys.process._
import scala.util.Try

/** Integration test: requires a local Postgres. Verifies that
  * [[BootstrapAccessSeeder]] inserts both the tenant-identity row and
  * the ALL grant on first run, and that a second run is a no-op. */
class BootstrapAccessSeederSpec extends AnyFlatSpec with Matchers:

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

  private def withStores(test: (TenantIdentityStore, AclGrantStore, String) => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qodbs_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      new LiquibaseRunner(dbUrl(dbName), pgUser, pgPass).run()
      // Bootstrap admin owns one tenant row; seed it.
      val tid = "t-bootstrap"
      psql(dbName, s"INSERT INTO qodstate_tenant (id, display_name, disabled) VALUES ('$tid', 'tpch', false)")
      val identityStore = new TenantIdentityStore(dbUrl(dbName), pgUser, pgPass)
      val grantStore    = new AclGrantStore(dbUrl(dbName), pgUser, pgPass)
      grantStore.ensureTable()
      test(identityStore, grantStore, tid)
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName""""))

  "BootstrapAccessSeeder.seed" should
    "insert one identity + one ALL grant per admin on first run" in withStores { (ids, grants, tid) =>
    val inserted = BootstrapAccessSeeder.seed(
      tenantId      = tid,
      tenantLabel   = "tpch",
      adminNames    = List("admin@localhost.local", "admin"),
      identityStore = ids,
      grantStore    = grants
    )
    // 2 admins x (1 identity + 1 grant) = 4 inserts on a clean DB.
    inserted shouldBe 4

    ids.list(Some(tid)).map(i => (i.issuer, i.externalId)).toSet shouldBe Set(
      ("db", "admin@localhost.local"),
      ("db", "admin"),
    )
    grants.list(Some(tid)).map(_.principal).toSet shouldBe Set(
      "user:admin@localhost.local",
      "user:admin"
    )
    grants.list(Some(tid)).foreach { g =>
      g.permission  shouldBe "ALL"
      g.catalogName shouldBe None
      g.schemaName  shouldBe None
      g.tableName   shouldBe None
    }
  }

  it should "be a no-op on a second run" in withStores { (ids, grants, tid) =>
    val first  = BootstrapAccessSeeder.seed(tid, "tpch", List("admin"), ids, grants)
    val second = BootstrapAccessSeeder.seed(tid, "tpch", List("admin"), ids, grants)
    first  shouldBe 2
    second shouldBe 0
    ids.list(Some(tid)).size    shouldBe 1
    grants.list(Some(tid)).size shouldBe 1
  }

  it should "return 0 when adminNames is empty" in withStores { (ids, grants, tid) =>
    BootstrapAccessSeeder.seed(tid, "tpch", Nil, ids, grants) shouldBe 0
    ids.list(Some(tid))    shouldBe empty
    grants.list(Some(tid)) shouldBe empty
  }

  it should "only seed rows missing for the admin (mixed pre-existing state)" in withStores { (ids, grants, tid) =>
    // Pre-seed the identity but NOT the grant for one of two admins;
    // the seeder should fill in just the holes.
    ids.create(tid, "db", "alice")
    val inserted = BootstrapAccessSeeder.seed(tid, "tpch", List("alice", "bob"), ids, grants)
    // alice: identity already there (-> 1 grant insert), bob: both (+2). Total 3.
    inserted shouldBe 3
    ids.list(Some(tid)).map(_.externalId).toSet  shouldBe Set("alice", "bob")
    grants.list(Some(tid)).map(_.principal).toSet shouldBe Set("user:alice", "user:bob")
  }
