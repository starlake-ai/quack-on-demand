package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{
  Pool,
  PoolKey,
  Role,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

class NodeServerNameRoundTripSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodsn")

  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodsn_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def seedPool(store: PostgresControlPlaneStore): Unit =
    val tenant = Tenant(id = "t1", displayName = "acme")
    store.upsertTenant(tenant)
    val td = TenantDb(
      id = "td1",
      tenantId = "t1",
      name = "db",
      kind = TenantDbKind.InMemory,
      metastore = Map.empty,
      dataPath = ""
    )
    store.upsertTenantDb(td)
    val pool = Pool(
      id = "p1",
      tenantId = "t1",
      tenantDbId = "td1",
      name = "bi",
      size = 1,
      distribution = RoleDistribution(0, 0, 1),
      maxConcurrentPerNode = 0
    )
    store.upsertPool(pool)

  "upsertNode" should "round-trip serverName" in withStore { store =>
    seedPool(store)
    val n = RunningNode(
      "quack-acme-db-bi-1",
      PoolKey("acme", "db", "bi"),
      Role.Dual,
      "10.0.0.7",
      21900,
      "tok",
      None,
      None,
      Instant.EPOCH,
      serverName = Some("srv-07")
    )
    store.upsertNode(n, "p1")
    store.listNodes("p1").map(_.serverName) shouldBe List(Some("srv-07"))
  }

  // PoolSupervisor keys pools by tenant id (restore() builds PoolKey(t.id, ...)). A node read back
  // keyed by the display name matched no pool after a manager restart, so every running node was
  // dropped from the in-memory topology while its row stayed.
  "listNodes and snapshot" should "key nodes by tenant id, not display name" in withStore { store =>
    seedPool(store)
    val n = RunningNode(
      "quack-t1-db-bi-1",
      PoolKey("t1", "db", "bi"),
      Role.Dual,
      "10.0.0.7",
      21900,
      "tok",
      None,
      None,
      Instant.EPOCH
    )
    store.upsertNode(n, "p1")
    store.listNodes("p1").map(_.poolKey) shouldBe List(PoolKey("t1", "db", "bi"))
    store.snapshot().nodes.map(_.poolKey) shouldBe List(PoolKey("t1", "db", "bi"))
  }
