package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import io.circe.syntax.*
import io.circe.yaml.v12.Printer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestExporterSpec extends AnyFlatSpec with Matchers:

  private def exportOk(
      r: Either[ManifestExporter.ReservedNameRows, ConfigManifest]
  ): ConfigManifest =
    r.fold(e => fail(e.message), identity)

  private def populated: InMemoryControlPlaneStore =
    val s = new InMemoryControlPlaneStore()
    s.upsertTenant(Tenant(id = "tpch", displayName = "tpch"))
    s.upsertTenantDb(
      TenantDb(
        id = "td-1",
        tenantId = "tpch",
        name = "tpch_tpch1",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = "/tmp/data",
        objectStore = Map.empty
      )
    )
    s.upsertPool(
      Pool(
        id = "p-1",
        tenantId = "tpch",
        tenantDbId = "td-1",
        name = "sales",
        size = 3,
        distribution = RoleDistribution(1, 1, 1),
        maxConcurrentPerNode = 0,
        disabled = false
      )
    )
    s

  "ManifestExporter" should "emit a v1 manifest with the live tenants/pools" in {
    val store = populated
    val m     = exportOk(
      ManifestExporter.build(
        store,
        exportedAt = Instant.EPOCH,
        managerVersion = "0.2.0",
        hostname = "test"
      )
    )
    m.apiVersion shouldBe ConfigManifest.ApiVersion
    m.kind shouldBe ConfigManifest.Kind
    m.tenants.map(_.name) should contain("tpch")
    m.tenants.head.tenantDbs.map(_.name) should contain("tpch_tpch1")
    m.tenants.head.pools.map(_.name) should contain("sales")
  }

  it should "never emit a password field on users" in {
    val store = populated
    store.upsertUserIdentity(
      RbacUser(
        id = "u-1",
        tenant = None,
        username = "admin",
        kind = "admin"
      )
    )
    val m = exportOk(ManifestExporter.build(store, Instant.EPOCH, "0.2.0", "test"))
    m.users.find(_.username == "admin").get.password shouldBe None
  }

  it should "emit encrypted but redact encryptionKey out of the metastore map" in {
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "tpch", displayName = "tpch"))
    store.upsertTenantDb(
      TenantDb(
        id = "td-2",
        tenantId = "tpch",
        name = "tpch_secure",
        kind = TenantDbKind.DuckDbFile,
        metastore = Map(
          "dbName"        -> "tpch_secure",
          "schemaName"    -> "main",
          "encryptionKey" -> "super-secret-key"
        ),
        dataPath = "/tmp/data",
        encrypted = true
      )
    )
    val m   = exportOk(ManifestExporter.build(store, Instant.EPOCH, "0.2.0", "test"))
    val mtd = m.tenants.head.tenantDbs.find(_.name == "tpch_secure").get
    mtd.encrypted shouldBe true
    mtd.metastore.keySet should not contain "encryptionKey"
    mtd.metastore.get("dbName") shouldBe Some("tpch_secure")
  }

  it should "export tenant acl with the OPA token redacted" in {
    import ai.starlake.quack.model.TenantAcl
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(
      Tenant(
        id = "acme",
        displayName = "acme",
        acl = TenantAcl(Some("opa"), Some("http://opa"), None, Some("tok"))
      )
    )
    val m   = exportOk(ManifestExporter.build(store, Instant.EPOCH, "0.2.0", "test"))
    val acl = m.tenants.find(_.name == "acme").flatMap(_.acl).get
    acl.mode shouldBe Some("opa")
    acl.opaUrl shouldBe Some("http://opa")
    acl.opaToken shouldBe Some(ai.starlake.quack.model.FederatedSecret.RedactedMarker)
  }

  it should "omit acl entirely for a tenant with no ACL override" in {
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    val m = exportOk(ManifestExporter.build(store, Instant.EPOCH, "0.2.0", "test"))
    m.tenants.find(_.name == "acme").flatMap(_.acl) shouldBe None
  }

  // The printer ManifestHandlers and ManifestCli use: null keys are dropped.
  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  it should "export a stored tenant client secret as the redaction marker, never the value" in {
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(
      Tenant(
        id = "acme",
        displayName = "acme",
        authProvider = "keycloak",
        authConfig =
          Map("issuerUrl" -> "https://idp.example/realms/acme", "clientId" -> "qod-admin"),
        authClientSecret = Some("cs-s3cr3t")
      )
    )
    val m = exportOk(ManifestExporter.build(store, Instant.EPOCH, "0.2.0", "test"))
    m.tenants.find(_.name == "acme").get.clientSecret shouldBe
      Some(ai.starlake.quack.model.FederatedSecret.RedactedMarker)
    Yaml.pretty(m.asJson) should not include "cs-s3cr3t"
  }

  it should "export no clientSecret for a tenant without a stored secret" in {
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    val m = exportOk(ManifestExporter.build(store, Instant.EPOCH, "0.2.0", "test"))
    m.tenants.find(_.name == "acme").get.clientSecret shouldBe None
    Yaml.pretty(m.asJson) should not include "clientSecret"
  }
