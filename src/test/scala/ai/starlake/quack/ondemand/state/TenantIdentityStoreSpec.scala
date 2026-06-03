package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.sys.process._
import scala.util.Try

/** Integration test for [[TenantIdentityStore]]. Requires a local
  * Postgres reachable via SL_TEST_PG_* env vars (defaults:
  * localhost:5432, user `postgres`, password `azizam`). */
class TenantIdentityStoreSpec extends AnyFlatSpec with Matchers:

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

  private def withStore(test: TenantIdentityStore => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qodti_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      new LiquibaseRunner(dbUrl(dbName), pgUser, pgPass).run()
      // The identity FK points at qodstate_tenant, so a parent row has to
      // exist before we can insert.
      psql(dbName, "INSERT INTO qodstate_tenant (id, display_name, disabled) VALUES ('t-1', 'acme', false)")
      psql(dbName, "INSERT INTO qodstate_tenant (id, display_name, disabled) VALUES ('t-2', 'beta', false)")
      test(new TenantIdentityStore(dbUrl(dbName), pgUser, pgPass))
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName""""))

  "TenantIdentityStore" should "round-trip a database-user identity" in withStore { store =>
    val created = store.create("t-1", "db", "alice")
    created.tenantId   shouldBe "t-1"
    created.issuer     shouldBe "db"
    created.externalId shouldBe "alice"
    created.id            should not be empty
    store.list().map(i => (i.issuer, i.externalId)) shouldBe List(("db", "alice"))
  }

  it should "resolve a verified pair to its tenant id via lookup" in withStore { store =>
    store.create("t-1", "db", "alice")
    store.lookup("db", "alice") shouldBe Some("t-1")
    store.lookup("db", "ghost") shouldBe None
  }

  it should "filter list by tenant id when provided" in withStore { store =>
    store.create("t-1", "db", "alice")
    store.create("t-2", "db", "bob")
    store.list(Some("t-1")).map(_.externalId) shouldBe List("alice")
    store.list(Some("t-2")).map(_.externalId) shouldBe List("bob")
    store.list().map(_.externalId).toSet      shouldBe Set("alice", "bob")
  }

  it should "reject duplicate (issuer, externalId) pairs across tenants" in withStore { store =>
    store.create("t-1", "https://accounts.google.com", "acme.com")
    intercept[java.sql.SQLException] {
      store.create("t-2", "https://accounts.google.com", "acme.com")
    }
  }

  it should "delete by id and report success only when a row was removed" in withStore { store =>
    val r = store.create("t-1", "db", "alice")
    store.delete(r.id)            shouldBe true
    store.delete(r.id)            shouldBe false
    store.lookup("db", "alice")   shouldBe None
  }
