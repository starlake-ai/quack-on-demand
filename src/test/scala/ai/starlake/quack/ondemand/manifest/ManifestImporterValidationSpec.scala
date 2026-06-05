
// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestImporterValidationSpec.scala
package ai.starlake.quack.ondemand.manifest

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