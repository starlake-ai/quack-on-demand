package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{FederatedSecret, Tenant, TenantLocation}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestTenantLocationSpec extends AnyFlatSpec with Matchers:

  private val Secret = "manifest-sekr3t"
  private val Keys   = Map("s3_access_key_id" -> "AKIAEXAMPLE", "s3_secret_access_key" -> Secret)
  private val Etl    =
    TenantLocation("0123abcd" + "0" * 24, "acme", "s3", "s3://acme-etl/", Keys, 1L, Some("alice"))

  private def seeded(): InMemoryControlPlaneStore =
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant("acme"))
    store.upsertTenant(Tenant("globex"))
    store.insertTenantLocation(Etl)
    store

  private def exported(store: InMemoryControlPlaneStore): ConfigManifest =
    ManifestExporter.build(store, Instant.EPOCH, "test", "host").toOption.get

  private def withLocations(m: ConfigManifest, ls: Option[List[ManifestTenantLocation]]) =
    m.copy(tenants = m.tenants.map(t => if t.name == "acme" then t.copy(locations = ls) else t))

  "export" should "carry a tenant's locations with the secret redacted, and none for a tenant without" in {
    val m    = exported(seeded())
    val acme = m.tenants.find(_.name == "acme").get
    acme.locations shouldBe Some(
      List(
        ManifestTenantLocation(
          "s3://acme-etl/",
          Map(
            "s3_access_key_id"     -> "AKIAEXAMPLE",
            "s3_secret_access_key" -> FederatedSecret.RedactedMarker
          )
        )
      )
    )
    m.tenants.find(_.name == "globex").get.locations shouldBe None
    m.asJson.noSpaces should not include Secret
    acme.toString should not include Secret
  }

  "import" should "keep an unchanged location, its id and its stored secret" in {
    val store = seeded()
    ManifestImporter.apply(exported(store), store, requireEncryption = false) shouldBe Right(())
    store.listTenantLocations("acme") shouldBe List(Etl)
  }

  it should "refuse a redacted secret whose endpoint or access key changed, before any write" in {
    val store = seeded()
    val m     = exported(store)
    val moved = withLocations(
      m,
      m.tenants
        .find(_.name == "acme")
        .get
        .locations
        .map(
          _.map(l =>
            l.copy(objectStore = l.objectStore + ("s3_endpoint" -> "https://evil.example"))
          )
        )
    )
    val res = ManifestImporter.apply(moved, store, requireEncryption = false)
    res.left.toOption.get.mkString should include("s3_secret_access_key is redacted")
    store.listTenantLocations("acme") shouldBe List(Etl)
  }

  it should "refuse a redacted secret for a location the tenant does not hold" in {
    val store = seeded()
    val m     = withLocations(
      exported(store),
      Some(
        List(
          ManifestTenantLocation(
            "s3://acme-new/",
            Map("s3_access_key_id" -> "A", "s3_secret_access_key" -> FederatedSecret.RedactedMarker)
          )
        )
      )
    )
    ManifestImporter.apply(m, store, requireEncryption = false).isLeft shouldBe true
    store.listTenantLocations("acme") shouldBe List(Etl)
  }

  it should "make the stored locations equal a listed set, and leave them alone when absent" in {
    val store = seeded()
    val base  = exported(store)
    val next  = Some(
      List(
        ManifestTenantLocation(
          "s3://acme-two/",
          Map("s3_access_key_id" -> "B", "s3_secret_access_key" -> "two")
        )
      )
    )
    ManifestImporter.apply(
      withLocations(base, next),
      store,
      requireEncryption = false
    ) shouldBe Right(())
    store
      .listTenantLocations("acme")
      .map(l => (l.location, l.objectStore("s3_secret_access_key"))) shouldBe
      List(("s3://acme-two/", "two"))
    ManifestImporter.apply(
      withLocations(base, None),
      store,
      requireEncryption = false
    ) shouldBe Right(())
    store.listTenantLocations("acme").map(_.location) shouldBe List("s3://acme-two/")
    ManifestImporter.apply(
      withLocations(base, Some(Nil)),
      store,
      requireEncryption = false
    ) shouldBe Right(())
    store.listTenantLocations("acme") shouldBe Nil
  }

  it should "apply the add rules: a local folder needs the operator's root, a key path is refused" in {
    val store = seeded()
    val local =
      withLocations(exported(store), Some(List(ManifestTenantLocation("/srv/qod/acme/in"))))
    ManifestImporter
      .apply(local, store, requireEncryption = false)
      .left
      .toOption
      .get
      .mkString should
      include("tenant_files_root_unset")
    ManifestImporter.apply(
      local,
      store,
      requireEncryption = false,
      tenantFilesRoot = Some("/srv/qod")
    ) shouldBe
      Right(())
    store.listTenantLocations("acme").map(_.location) shouldBe List("/srv/qod/acme/in/")
    val keyPath = withLocations(
      exported(store),
      Some(List(ManifestTenantLocation("s3://acme-etl/in/", Keys)))
    )
    ManifestImporter
      .apply(keyPath, store, requireEncryption = false)
      .left
      .toOption
      .get
      .mkString should
      include("invalid_location")
  }

  it should "refuse two spellings of one location as a duplicate" in {
    val store = seeded()
    val twice = withLocations(
      exported(store),
      Some(
        List(
          ManifestTenantLocation("s3://acme-two", Keys),
          ManifestTenantLocation("s3://acme-two/", Keys)
        )
      )
    )
    ManifestImporter
      .apply(twice, store, requireEncryption = false)
      .left
      .toOption
      .get
      .mkString should
      include("duplicate location within acme: s3://acme-two/")
    store.listTenantLocations("acme") shouldBe List(Etl)
  }

  it should "store the credentials trimmed" in {
    val store  = seeded()
    val padded = withLocations(
      exported(store),
      Some(
        List(
          ManifestTenantLocation(
            "s3://acme-two/",
            Map("s3_access_key_id" -> " B ", "s3_secret_access_key" -> "two ")
          )
        )
      )
    )
    ManifestImporter.apply(padded, store, requireEncryption = false) shouldBe Right(())
    store.listTenantLocations("acme").map(_.objectStore) shouldBe
      List(Map("s3_access_key_id" -> "B", "s3_secret_access_key" -> "two"))
  }
