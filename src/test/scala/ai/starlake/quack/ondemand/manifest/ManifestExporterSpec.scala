package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestExporterSpec extends AnyFlatSpec with Matchers:

  private def populated: InMemoryControlPlaneStore =
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "tpch", displayName = "tpch"))
    s.upsertTenantDb(TenantDb(
      id        = "td-1",
      tenantId  = "tpch",
      name      = "tpch_tpch1",
      kind      = TenantDbKind.DuckLake,
      metastore = Map.empty,
      dataPath  = "/tmp/data",
      objectStore = Map.empty
    ))
    s.upsertPool(Pool(
      id                   = "p-1",
      tenantId             = "tpch",
      tenantDbId           = "td-1",
      name                 = "sales",
      size                 = 3,
      distribution         = RoleDistribution(1, 1, 1),
      maxConcurrentPerNode = 0,
      disabled             = false
    ))
    s

  "ManifestExporter" should "emit a v1 manifest with the live tenants/pools" in {
    val store = populated
    val m = ManifestExporter.build(store, exportedAt = Instant.EPOCH,
                                    managerVersion = "0.2.0", hostname = "test")
    m.apiVersion                              shouldBe ConfigManifest.ApiVersion
    m.kind                                    shouldBe ConfigManifest.Kind
    m.tenants.map(_.name)                     should contain("tpch")
    m.tenants.head.tenantDbs.map(_.name)      should contain("tpch_tpch1")
    m.tenants.head.pools.map(_.name)          should contain("sales")
  }

  it should "never emit a password field on users" in {
    val store = populated
    store.upsertUserIdentity(RbacUser(
      id       = "u-1",
      tenant   = None,
      username = "admin",
      role     = "admin"
    ))
    val m = ManifestExporter.build(store, Instant.EPOCH, "0.2.0", "test")
    m.users.find(_.username == "admin").get.password shouldBe None
  }