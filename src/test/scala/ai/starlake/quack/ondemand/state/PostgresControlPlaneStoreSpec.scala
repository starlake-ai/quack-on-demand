package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, PoolKey, Role, RoleDistribution, RunningNode, Tenant, TenantDb}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import java.time.Instant
import scala.sys.process._
import scala.util.Try

class PostgresControlPlaneStoreSpec extends AnyFlatSpec with Matchers:

  ai.starlake.quack.ondemand.state.testkit.TestPostgres.dropStrayTestDatabases("qodcp")

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

  /** Fresh DB + migrated schema, with the store wired against it. */
  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qodcp_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      new LiquibaseRunner(dbUrl(dbName), pgUser, pgPass).run()
      test(new PostgresControlPlaneStore(dbUrl(dbName), pgUser, pgPass))
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private val tenant = Tenant(
    id          = "tenant-1",
    name        = "acme",
    displayName = "acme",
    disabled    = false
  )
  private val tenantDb = TenantDb(
    id        = "tdb-1",
    tenantId  = "tenant-1",
    name      = "acme_default",
    metastore = Map("pgHost" -> "h", "schemaName" -> "main"),
    dataPath  = "/data/acme"
  )
  private val pool = Pool(
    id           = "pool-1",
    tenantDbId   = "tdb-1",
    name         = "sales",
    size         = 1,
    distribution = RoleDistribution(0, 0, 1)
  )
  private val node = RunningNode(
    nodeId    = "node-1",
    poolKey   = PoolKey("acme", "sales"),
    role      = Role.Dual,
    host      = "127.0.0.1",
    port      = 21900,
    token     = "tok",
    pid       = Some(12345L),
    podName   = None,
    startedAt = Instant.parse("2026-01-01T00:00:00Z")
  )

  "PostgresControlPlaneStore" should "round-trip a tenant" in withStore { store =>
    store.upsertTenant(tenant)
    store.listTenants() shouldBe List(tenant)
  }

  it should "be idempotent on repeated tenant upsert" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenant(tenant.copy(disabled = true))
    store.listTenants().head.disabled shouldBe true
  }

  it should "round-trip a tenant-db with JSONB params" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.listTenantDbs("tenant-1") shouldBe List(tenantDb)
  }

  it should "round-trip a pool" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.listPools("tdb-1") shouldBe List(pool)
  }

  it should "preserve idleTimeoutSec as a populated Option" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    val withIdle = pool.copy(idleTimeoutSec = Some(60))
    store.upsertPool(withIdle)
    store.listPools("tdb-1") shouldBe List(withIdle)
  }

  it should "round-trip a node with pid set + lastSeen NULL" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    store.listNodes("pool-1") shouldBe List(node)
  }

  it should "round-trip a node with lastSeen + podName populated" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    val withFields = node.copy(
      pid      = None,
      podName  = Some("quack-pod-xyz"),
      lastSeen = Some(Instant.parse("2026-02-01T12:00:00Z"))
    )
    store.upsertNode(withFields, "pool-1")
    store.listNodes("pool-1") shouldBe List(withFields)
  }

  it should "reject delete of a tenant that still has tenant-dbs (FK RESTRICT)" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    intercept[java.sql.SQLException](store.deleteTenant("tenant-1"))
    store.listTenants() shouldBe List(tenant)
  }

  it should "reject delete of a pool that still has nodes (FK RESTRICT)" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    intercept[java.sql.SQLException](store.deletePool("pool-1"))
    store.listPools("tdb-1") shouldBe List(pool)
  }

  it should "allow ordered teardown: nodes, then pool, then tenant-db, then tenant" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    store.deleteNode("node-1")
    store.deletePool("pool-1")
    store.deleteTenantDb("tdb-1")
    store.deleteTenant("tenant-1")
    store.snapshot() shouldBe ControlPlaneSnapshot()
  }

  it should "load the full graph via snapshot()" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    val s = store.snapshot()
    s.tenants   shouldBe List(tenant)
    s.tenantDbs shouldBe List(tenantDb)
    s.pools     shouldBe List(pool)
    s.nodes     shouldBe List(node)
  }
