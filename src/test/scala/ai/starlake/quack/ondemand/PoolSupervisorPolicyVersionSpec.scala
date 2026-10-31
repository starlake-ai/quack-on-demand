package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantAclPatch, TenantDbKind}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** The supervisor's policy versions follow the store: after its own RBAC and ACL mutations without
  * a restore, and after a restore for writes it did not make (a peer replica, a manifest import).
  */
class PoolSupervisorPolicyVersionSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodsupv")

  private def withSup(test: (PoolSupervisor, PostgresControlPlaneStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodsupv_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        val sup = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
        sup.restore()
        sup.createTenant(Tenant(id = "acme")).unsafeRunSync()
        sup.createTenant(Tenant(id = "globex")).unsafeRunSync()
        test(sup, store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def stored(store: PostgresControlPlaneStore, t: String): Long = store.policyVersions()(t)

  "the supervisor" should "know each tenant's version once it created it" in withSup {
    (sup, store) =>
      // The built-in roles, groups and grants the tenant was created with bumped it already.
      stored(store, "acme") should be > 0L
      sup.policyVersions.current("acme") shouldBe stored(store, "acme")
  }

  it should "follow its own RBAC mutation without a restore, and leave other tenants alone" in
    withSup { (sup, store) =>
      val acme   = sup.policyVersions.current("acme")
      val globex = sup.policyVersions.current("globex")
      sup.createRole("acme", "analyst").unsafeRunSync().isRight shouldBe true
      sup.policyVersions.current("acme") shouldBe stored(store, "acme")
      sup.policyVersions.current("acme") should be > acme
      sup.policyVersions.current("globex") shouldBe globex
    }

  it should "follow an ACL change" in withSup { (sup, store) =>
    val before = sup.policyVersions.current("acme")
    sup
      .setTenantAcl("acme", TenantAclPatch(mode = Some("qod")), "", "qod")
      .unsafeRunSync()
      .isRight shouldBe true
    sup.policyVersions.current("acme") should be > before
    sup.policyVersions.current("acme") shouldBe stored(store, "acme")
  }

  it should "pick up a write it did not make at the next restore" in withSup { (sup, store) =>
    val before = sup.policyVersions.current("globex")
    val c      = store.jdbcDataSource.getConnection
    try
      val st = c.createStatement()
      try
        st.execute(
          "INSERT INTO qodstate_role (id, tenant_id, name) VALUES ('r-peer', 'globex', 'p')"
        )
      finally st.close()
    finally c.close()
    sup.policyVersions.current("globex") shouldBe before
    sup.restore()
    sup.policyVersions.current("globex") should be > before
  }

  // Liquibase 0054 (CONTRACT Addendum 4 item 23): a tenant-db write bumps every tenant. The writing
  // replica follows at once; a peer follows through the topology notice the write publishes (its
  // handler is restore(), HaCoordinator's qod_topology), with nothing sent on qod_rbac.
  "a tenant-db create" should "move every tenant's version, here and on a peer through qod_topology" in
    withSup { (peer, store) =>
      var rbac   = 0
      val notify = new ai.starlake.quack.ondemand.ha.StateChangePublisher:
        def topologyChanged(): Unit = peer.restore()
        def rbacChanged(): Unit     = rbac += 1
      val writer =
        new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store, publish = notify)
      writer.restore()
      val acme   = peer.policyVersions.current("acme")
      val globex = peer.policyVersions.current("globex")
      writer
        .createTenantDb("acme", "lake", TenantDbKind.InMemory, Map.empty, dataPath = "")
        .unsafeRunSync()
        .isRight shouldBe true
      writer.policyVersions.current("acme") shouldBe stored(store, "acme")
      writer.policyVersions.current("globex") shouldBe stored(store, "globex")
      peer.policyVersions.current("acme") should be > acme
      peer.policyVersions.current("globex") should be > globex
      peer.policyVersions.current("acme") shouldBe stored(store, "acme")
      rbac shouldBe 0
    }
