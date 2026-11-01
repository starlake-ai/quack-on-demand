
// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestImporterValidationSpec.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestImporterValidationSpec extends AnyFlatSpec with Matchers:

  private def emptyStore = new InMemoryControlPlaneStore()

  private def baseManifest = ConfigManifest(
    apiVersion = ConfigManifest.ApiVersion,
    kind       = ConfigManifest.Kind,
    exportedAt = Instant.EPOCH,
    exportedFrom = ExportedFrom("0.2.0", "test")
  )

  "ManifestImporter.validate" should "reject an unknown apiVersion" in {
    val m = baseManifest.copy(apiVersion = "quack-on-demand/v0")
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.head should include ("apiVersion")
  }

  it should "reject a user whose tenant is neither in the YAML nor the DB" in {
    val m = baseManifest.copy(users = List(
      ManifestUser(tenant = Some("ghost"), username = "alice")))
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(_.contains("ghost")) shouldBe true
  }

  it should "accept a DB tenant referenced by its id when the display name differs" in {
    // The exporter writes tenant ids; a partial manifest (users only) must still validate
    // against a DB tenant whose display name is not its id.
    val store = emptyStore
    store.upsertTenant(Tenant(id = "acme", displayName = "Acme Corporation"))
    val m = baseManifest.copy(users = List(
      ManifestUser(tenant = Some("acme"), username = "alice")))
    ManifestImporter.validate(m, store) shouldBe Right(())
  }

  it should "reject duplicate (tenant, username) pairs" in {
    val m = baseManifest.copy(users = List(
      ManifestUser(tenant = None, username = "admin"),
      ManifestUser(tenant = None, username = "admin")))
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(_.contains("duplicate")) shouldBe true
  }

  it should "reject a user-role reference that doesn't resolve" in {
    val m = baseManifest.copy(
      tenants = List(ManifestTenant(name = "tpch")),
      users   = List(ManifestUser(tenant = Some("tpch"), username = "alice",
                                   roles = List("ghost"))))
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(_.contains("ghost")) shouldBe true
  }

  it should "accept a valid empty manifest" in {
    ManifestImporter.validate(baseManifest, emptyStore) shouldBe Right(())
  }
  // ------------------------------------------------------------------
  // Pool grants name a pool that exists: a name that resolves to nothing must never be stored
  // as `poolId = None`, which reads as "every pool in the tenant".
  // ------------------------------------------------------------------

  private def tenantWithPool = ManifestTenant(
    name = "acme",
    tenantDbs = List(ManifestTenantDb(name = "acme_db", kind = "memory")),
    pools = List(
      ManifestPool(
        name = "sales",
        tenantDb = "acme_db",
        roleDistribution = ManifestRoleDistribution(writeonly = 0, readonly = 0, dual = 1)
      )
    )
  )

  it should "reject a user pool grant naming a pool the tenant does not have" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool),
      users = List(
        ManifestUser(
          tenant = Some("acme"),
          username = "alice",
          poolGrants = List(ManifestPoolGrant(pool = Some("saels")))
        )
      )
    )
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(e => e.contains("alice") && e.contains("saels")) shouldBe true
  }

  it should "reject a user pool grant whose pool exists in the DB but the manifest drops it" in {
    // The tenant is in the manifest, so apply() replaces its pools with the manifest's: a pool
    // only the DB still has is gone by the time the grant is written.
    val store = emptyStore
    store.upsertTenant(Tenant(id = "acme"))
    val m = baseManifest.copy(
      tenants = List(tenantWithPool),
      users = List(
        ManifestUser(
          tenant = Some("acme"),
          username = "alice",
          poolGrants = List(ManifestPoolGrant(pool = Some("legacy")))
        )
      )
    )
    val err = ManifestImporter.validate(m, store).left.toOption.get
    err.exists(_.contains("legacy")) shouldBe true
  }

  it should "accept a user pool grant naming a pool of the manifest, and a tenant-wide one" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool),
      users = List(
        ManifestUser(
          tenant = Some("acme"),
          username = "alice",
          poolGrants = List(ManifestPoolGrant(pool = Some("sales")), ManifestPoolGrant(None))
        )
      )
    )
    ManifestImporter.validate(m, emptyStore) shouldBe Right(())
  }

  it should "reject a pool grant whose tenant qualifier names an unknown tenant" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool),
      users = List(
        ManifestUser(
          tenant = None,
          username = "root",
          kind = "admin",
          poolGrants = List(ManifestPoolGrant(pool = Some("sales"), tenant = Some("ghost")))
        )
      )
    )
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(e => e.contains("root") && e.contains("ghost")) shouldBe true
  }

  it should "reject a tenant user's pool grant into another tenant" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool, ManifestTenant(name = "globex")),
      users = List(
        ManifestUser(
          tenant = Some("globex"),
          username = "bob",
          poolGrants = List(ManifestPoolGrant(pool = Some("sales"), tenant = Some("acme")))
        )
      )
    )
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(e => e.contains("bob") && e.contains("acme")) shouldBe true
  }

  it should "reject a superuser pool grant with no tenant qualifier" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool),
      users = List(
        ManifestUser(
          tenant = None,
          username = "root",
          kind = "admin",
          poolGrants = List(ManifestPoolGrant(pool = Some("sales")))
        )
      )
    )
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(_.contains("root")) shouldBe true
  }

  it should "reject a group pool grant naming a pool the tenant does not have" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool),
      groups = List(
        ManifestGroup(
          tenant = "acme",
          name = "analysts",
          poolGrants = List(ManifestPoolGrant(pool = Some("saels")))
        )
      )
    )
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(e => e.contains("analysts") && e.contains("saels")) shouldBe true
  }

  it should "reject a group pool grant into another tenant" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool, ManifestTenant(name = "globex")),
      groups = List(
        ManifestGroup(
          tenant = "globex",
          name = "analysts",
          poolGrants = List(ManifestPoolGrant(pool = Some("sales"), tenant = Some("acme")))
        )
      )
    )
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(e => e.contains("analysts") && e.contains("acme")) shouldBe true
  }

  it should "reject a built-in group that carries pool grants" in {
    val m = baseManifest.copy(
      tenants = List(tenantWithPool),
      groups = List(
        ManifestGroup(
          tenant = "acme",
          name = "qod_no_pools",
          poolGrants = List(ManifestPoolGrant(pool = Some("sales")))
        )
      )
    )
    val err = ManifestImporter.validate(m, emptyStore).left.toOption.get
    err.exists(_.contains("qod_no_pools")) shouldBe true
  }
