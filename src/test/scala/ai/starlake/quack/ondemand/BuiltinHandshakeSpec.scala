package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.rbac.{AuthzRequest, HandshakeDenial, UserMemberships}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  BuiltinRbac,
  LiquibaseRunner,
  PostgresControlPlaneStore,
  UserStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Gate 4 end to end: the default memberships open every pool and every table; the qod_no_*
  * built-ins open nothing.
  */
class BuiltinHandshakeSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodbhs")

  private def withSup(test: (PoolSupervisor, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodbhs_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val sup       = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
      sup.restore()
      sup.createTenant(Tenant(id = "acme")).unsafeRunSync()
      store.upsertTenantDb(
        TenantDb(
          id = "td-acme",
          tenantId = "acme",
          name = "acme_db",
          kind = TenantDbKind.InMemory,
          metastore = Map.empty,
          dataPath = ""
        )
      )
      store.upsertPool(
        Pool(
          id = "p-acme",
          tenantId = "acme",
          tenantDbId = "td-acme",
          name = "bi",
          size = 1,
          distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
          maxConcurrentPerNode = 0,
          disabled = false
        )
      )
      sup.restore()
      try test(sup, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def req(user: String): AuthzRequest =
    AuthzRequest("acme", "bi", user, superuserAdmissible = true, edge = "flightsql")

  "a default-created user" should "pass the handshake and hold *.*.* ALL" in withSup {
    (sup, users) =>
      sup
        .createUser(Some("acme"), "bob", "pw", "user", users, UserMemberships.Requested(None, None))
        .unsafeRunSync()
        .isRight shouldBe true
      val out = sup.authorizeHandshakeDetailed(req("bob"))
      out.isRight shouldBe true
      out.toOption.get.effectiveSet.permissions.map(p =>
        (p.catalogName, p.schemaName, p.tableName, p.verb)
      ) should contain(("*", "*", "*", "ALL"))
  }

  "a qod_no_tables + qod_no_pools user" should "be refused at the handshake" in withSup {
    (sup, users) =>
      sup
        .createUser(
          Some("acme"),
          "eve",
          "pw",
          "user",
          users,
          UserMemberships.Requested(
            Some(List(BuiltinRbac.NoTables)),
            Some(List(BuiltinRbac.NoPools))
          )
        )
        .unsafeRunSync()
        .isRight shouldBe true
      sup.authorizeHandshakeDetailed(req("eve")) match
        case Left(HandshakeDenial.Denied(m)) => m should include("has no access to pool 'acme/bi'")
        case other                           => fail(s"expected a gate-4 denial, got $other")
  }
