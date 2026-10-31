package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{Tenant, TenantLocation}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestTenantLocationSpec extends AnyFlatSpec with Matchers:

  private val Root = Some("/srv/qod")
  private val In   =
    TenantLocation("0123abcd" + "0" * 24, "acme", "local", "/srv/qod/acme/in/", 1L, Some("alice"))

  private def seeded(): InMemoryControlPlaneStore =
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant("acme"))
    store.upsertTenant(Tenant("globex"))
    store.insertTenantLocation(In)
    store

  private def exported(store: InMemoryControlPlaneStore): ConfigManifest =
    ManifestExporter.build(store, Instant.EPOCH, "test", "host").toOption.get

  private def withLocations(m: ConfigManifest, ls: Option[List[ManifestTenantLocation]]) =
    m.copy(tenants = m.tenants.map(t => if t.name == "acme" then t.copy(locations = ls) else t))

  private def run(
      m: ConfigManifest,
      store: InMemoryControlPlaneStore,
      root: Option[String] = Root
  ) =
    ManifestImporter.apply(m, store, requireEncryption = false, tenantFilesRoot = root)

  "export" should "carry a tenant's folders, and none for a tenant without" in {
    val m = exported(seeded())
    m.tenants.find(_.name == "acme").get.locations shouldBe
      Some(List(ManifestTenantLocation("/srv/qod/acme/in/")))
    m.tenants.find(_.name == "globex").get.locations shouldBe None
  }

  "import" should "keep an unchanged location and its id" in {
    val store = seeded()
    run(exported(store), store) shouldBe Right(())
    store.listTenantLocations("acme") shouldBe List(In)
  }

  it should "make the stored locations equal a listed set, and leave them alone when absent" in {
    val store = seeded()
    val base  = exported(store)
    run(
      withLocations(base, Some(List(ManifestTenantLocation("/srv/qod/acme/two")))),
      store
    ) shouldBe
      Right(())
    store.listTenantLocations("acme").map(_.location) shouldBe List("/srv/qod/acme/two/")
    run(withLocations(base, None), store) shouldBe Right(())
    store.listTenantLocations("acme").map(_.location) shouldBe List("/srv/qod/acme/two/")
    run(withLocations(base, Some(Nil)), store) shouldBe Right(())
    store.listTenantLocations("acme") shouldBe Nil
  }

  it should "apply the add rules: the operator's root, and no s3 location" in {
    val store = seeded()
    val local =
      withLocations(exported(store), Some(List(ManifestTenantLocation("/srv/qod/acme/x"))))
    run(local, store, root = None).left.toOption.get.mkString should include(
      "tenant_files_root_unset"
    )
    val s3 = withLocations(exported(store), Some(List(ManifestTenantLocation("s3://acme-etl/"))))
    run(s3, store).left.toOption.get.mkString should include("invalid_location")
    store.listTenantLocations("acme") shouldBe List(In)
  }

  it should "refuse two spellings of one location as a duplicate" in {
    val store = seeded()
    val twice = withLocations(
      exported(store),
      Some(
        List(
          ManifestTenantLocation("/srv/qod/acme/two"),
          ManifestTenantLocation("/srv/qod/acme/two/")
        )
      )
    )
    run(twice, store).left.toOption.get.mkString should
      include("duplicate location within acme: /srv/qod/acme/two/")
    store.listTenantLocations("acme") shouldBe List(In)
  }

  it should "decode a row an earlier export wrote with an objectStore" in {
    import ConfigManifest.given
    val json =
      """{"location":"s3://acme-etl/","objectStore":{"s3_access_key_id":"A","s3_secret_access_key":"x"}}"""
    io.circe.parser.decode[ManifestTenantLocation](json) shouldBe
      Right(ManifestTenantLocation("s3://acme-etl/"))
  }
