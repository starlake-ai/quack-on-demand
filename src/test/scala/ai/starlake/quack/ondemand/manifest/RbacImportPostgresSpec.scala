package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  LiquibaseRunner,
  PostgresControlPlaneStore,
  RbacGroup,
  RbacRole
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** RBAC-only import on real Postgres: the foreign keys remove a removed role's bindings, and the
  * whole apply is one transaction.
  */
class RbacImportPostgresSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodrbac")

  private def withStore(test: (PostgresControlPlaneStore, String) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodrbac_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, url)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private val Acme = "acme"

  private def seed(store: ControlPlaneStore): String =
    store.upsertTenant(Tenant(id = Acme))
    store.ensureBuiltins(Acme)
    store.upsertRole(RbacRole("r-old", Acme, "old"))
    store.upsertGroup(RbacGroup("g-old", Acme, "old_group"))
    store.addGroupRole("g-old", "r-old")
    val user = store.upsertUserWithHash(
      tenant = Some(Acme),
      username = "bob",
      passwordHash = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234",
      kind = "user"
    )
    store.addUserRole(user, "r-old")
    store.addUserGroup(user, "g-old")
    user

  private val analyst = ManifestRole(tenant = Acme, name = "analyst")

  "An RBAC import" should "remove a role and its bindings through the foreign keys" in withStore {
    (store, _) =>
      val user = seed(store)
      ManifestImporter
        .applyRbac(RbacManifest(roles = List(analyst)), Acme, store) shouldBe a[Right[?, ?]]
      store.listRoles(Acme).filterNot(_.builtin).map(_.name) shouldBe List("analyst")
      store.listDirectRolesForUser(user) shouldBe empty
      store.listGroupsForUser(user) shouldBe empty
  }

  it should "roll everything back when a removal fails" in withStore { (store, _) =>
    seed(store)
    val failing = new ControlPlaneStore:
      val inner = store
      export inner.{deleteGroup as _, *}
      override def deleteGroup(id: String): Unit =
        throw new RuntimeException("simulated failure during an RBAC import")
    a[RuntimeException] should be thrownBy
      ManifestImporter.applyRbac(RbacManifest(roles = List(analyst)), Acme, failing)
    store.listRoles(Acme).filterNot(_.builtin).map(_.name) shouldBe List("old")
  }
