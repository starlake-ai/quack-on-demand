package ai.starlake.quack.cli

import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}

class ManifestCliSpec extends AnyFlatSpec with Matchers:

  "ManifestCli.exportTo" should "write a YAML manifest to the given stream" in {
    val store = new InMemoryControlPlaneStore()
    val out   = new ByteArrayOutputStream()
    ManifestCli.exportTo(store, new PrintStream(out)) shouldBe 0
    out.toString should include ("apiVersion: quack-on-demand/v1")
  }

  "ManifestCli.importFrom" should "apply a manifest from an input stream" in {
    // Defaulted fields (tenants / roles / groups / users) may be omitted -
    // the hand-rolled decoders fall back to `Nil` per the case-class default.
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: test, hostname: test }
        |""".stripMargin
    val store = new InMemoryControlPlaneStore()
    val rc = ManifestCli.importFrom(store, new ByteArrayInputStream(yaml.getBytes))
    rc shouldBe 0
  }