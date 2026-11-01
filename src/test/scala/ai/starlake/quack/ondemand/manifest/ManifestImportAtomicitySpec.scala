package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  LiquibaseRunner,
  PostgresControlPlaneStore,
  RbacRole
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

/** A manifest import is one transaction on the control plane: a refused import (an error found
  * while applying, or a store failure) leaves every row as it was before the import began.
  */
class ManifestImportAtomicitySpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodmfx")

  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodmfx_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private val base = ConfigManifest(
    apiVersion = ConfigManifest.ApiVersion,
    kind = ConfigManifest.Kind,
    exportedAt = Instant.EPOCH,
    exportedFrom = ExportedFrom("0.2.0", "test")
  )

  /** Tenant acme with database `old_db` serving pool `old_pool`. */
  private def seed(store: ControlPlaneStore): Unit =
    store.upsertTenant(Tenant(id = "acme"))
    store.ensureBuiltins("acme")
    store.upsertTenantDb(
      TenantDb(
        id = "td-old",
        tenantId = "acme",
        name = "old_db",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = "",
        objectStore = Map.empty
      )
    )
    store.upsertPool(
      Pool(
        id = "p-old",
        tenantId = "acme",
        tenantDbId = "td-old",
        name = "old_pool",
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1)
      )
    )

  /** Replaces old_db / old_pool by new_db / new_pool. */
  private def replacing(kind: String = "memory") = base.copy(
    tenants = List(
      ManifestTenant(
        name = "acme",
        tenantDbs = List(ManifestTenantDb(name = "new_db", kind = kind)),
        pools = List(
          ManifestPool(
            name = "new_pool",
            tenantDb = "new_db",
            roleDistribution = ManifestRoleDistribution(writeonly = 0, readonly = 0, dual = 1)
          )
        )
      )
    ),
    roles = List(ManifestRole(tenant = "acme", name = "reader"))
  )

  private def assertUntouched(store: ControlPlaneStore): Unit =
    store.listTenantDbs("acme").map(_.name) shouldBe List("old_db")
    store.listPools("td-old").map(_.name) shouldBe List("old_pool")
    store.listRoles("acme").map(_.name).filterNot(_.startsWith("qod_")) shouldBe empty

  "ManifestImporter.apply" should "leave the store untouched when a write fails mid-import" in
    withStore { pg =>
      seed(pg)
      // Fails on the role pass, after the tenant-db and pool passes have deleted old_db.
      val failing = new ControlPlaneStore:
        val inner = pg
        export inner.{upsertRole as _, *}
        override def upsertRole(r: RbacRole): Unit = throw new RuntimeException("pg down")
      a[RuntimeException] should be thrownBy
        ManifestImporter.apply(replacing(), failing, requireEncryption = false)
      assertUntouched(pg)
    }

  it should "leave the store untouched when apply refuses an entry" in withStore { pg =>
    seed(pg)
    // A new user with no password is found only while applying, after the deletes.
    val m = replacing().copy(users = List(ManifestUser(tenant = Some("acme"), username = "newbie")))
    val err = ManifestImporter.apply(m, pg, requireEncryption = false).left.toOption.get
    err.exists(_.contains("newbie")) shouldBe true
    assertUntouched(pg)
  }

  it should "refuse an invalid database kind without deleting the database it replaces" in
    withStore { pg =>
      seed(pg)
      val err =
        ManifestImporter.apply(replacing(kind = "bogus"), pg, requireEncryption = false)
      err.isLeft shouldBe true
      assertUntouched(pg)
    }

  it should "commit a valid import" in withStore { pg =>
    seed(pg)
    ManifestImporter.apply(replacing(), pg, requireEncryption = false) shouldBe Right(())
    pg.listTenantDbs("acme").map(_.name) shouldBe List("new_db")
    pg.listRoles("acme").map(_.name) should contain("reader")
  }
