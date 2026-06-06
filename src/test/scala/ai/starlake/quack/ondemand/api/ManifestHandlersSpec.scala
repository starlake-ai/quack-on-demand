package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.manifest.ConfigManifest
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ManifestHandlersSpec extends AnyFlatSpec with Matchers:

  "ManifestHandlers.export" should "return a v1 YAML manifest" in {
    val store    = new InMemoryControlPlaneStore()
    val handlers = new ManifestHandlers(store, managerVersion = "test", hostname = "host")
    val yaml     = handlers.exportYaml.unsafeRunSync().toOption.get
    yaml should include ("apiVersion: quack-on-demand/v1")
    yaml should include ("kind: ConfigManifest")
  }

  "ManifestHandlers.import" should "reject invalid apiVersion with 400" in {
    val store    = new InMemoryControlPlaneStore()
    val handlers = new ManifestHandlers(store, "test", "host")
    val bad =
      """apiVersion: quack-on-demand/v99
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: test, hostname: host }
        |""".stripMargin
    val res = handlers.importYaml(bad).unsafeRunSync()
    res.isLeft shouldBe true
    res.left.toOption.get._1.code shouldBe 400
  }