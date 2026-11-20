package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.rbac.UserMemberships
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** #265: deleting a tenant deletes its users (and, through the store's ON DELETE CASCADE, their
  * memberships, personal access tokens and S3 credentials), and nothing else's. Real Postgres:
  * `qodstate_user.tenant` has no foreign key, so the in-memory store cannot prove the rows go.
  */
class TenantDeleteUsersSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodtdu")

  private def withSup(test: (PoolSupervisor, PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodtdu_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val sup       = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
      sup.restore()
      sup.createTenant(Tenant(id = "acme")).unsafeRunSync()
      sup.createTenant(Tenant(id = "globex")).unsafeRunSync()
      try test(sup, store, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def user(sup: PoolSupervisor, users: UserStore, tenant: Option[String], name: String) =
    sup
      .createUser(
        tenant,
        name,
        "pw-123456",
        if tenant.isEmpty then "admin" else "user",
        users,
        UserMemberships.Requested(None, None)
      )
      .unsafeRunSync()
      .toOption
      .get

  "deleteTenant" should "delete the tenant's users and keep every other user" in withSup {
    (sup, store, users) =>
      user(sup, users, Some("acme"), "alice")
      user(sup, users, Some("acme"), "bob@acme.com")
      user(sup, users, Some("globex"), "carol")
      user(sup, users, None, "root")

      sup.deleteTenant("acme").unsafeRunSync() shouldBe Right(())

      store.listUsers(Some("acme")) shouldBe empty
      store.listUsers(Some("globex")).map(_.username) shouldBe List("carol")
      store.listUsers(None).filter(_.tenant.isEmpty).map(_.username) should contain("root")
  }

  it should "leave the users in place when the delete is refused" in withSup {
    (sup, store, users) =>
      user(sup, users, Some("acme"), "alice")
      sup
        .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "", writerTrusted = true)
        .unsafeRunSync() shouldBe a[Right[?, ?]]
      sup.createPool(PoolKey("acme", "acme_default", "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()

      sup.deleteTenant("acme").unsafeRunSync().isLeft shouldBe true

      store.listUsers(Some("acme")).map(_.username) shouldBe List("alice")
  }
