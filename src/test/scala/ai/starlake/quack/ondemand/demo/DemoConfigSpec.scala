package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.edge.config.AclConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.Paths

class DemoConfigSpec extends AnyFlatSpec with Matchers:

  // Load the real defaults exactly as normalManagerRun does, to prove the overlay
  // transforms a genuine base config (not a hand-built stub).
  import ai.starlake.quack.Main.given
  private val baseManager =
    ConfigSource.default.at("quack-on-demand").loadOrThrow[ai.starlake.quack.ManagerConfig]
  private val baseFlight =
    ConfigSource.default.at("quack-flightsql").loadOrThrow[ai.starlake.quack.FlightConfig]
  private val baseAcl =
    ConfigSource.default.at("quack-flightsql.acl").loadOrThrow[AclConfig]

  private val home =
    DemoHome(Paths.get("/x"), Paths.get("/x/pg"), Paths.get("/x/dl"), Paths.get("/x/native"))
  private val pg = PgCoords("localhost", 54321, "postgres", "")

  "DemoConfig.overlay" should "force the approved demo posture" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseAcl, pg, home)
    out.manager.defaultMetastore.pgHost shouldBe "localhost"
    out.manager.defaultMetastore.pgPort shouldBe "54321"
    out.manager.defaultMetastore.pgUser shouldBe "postgres"
    out.manager.defaultMetastore.pgPassword shouldBe ""
    out.manager.defaultMetastore.dbName shouldBe "qod"
    out.manager.defaultMetastore.dataPath shouldBe "/x/dl"
    out.manager.runtimeType shouldBe "local"
    out.manager.apiKey shouldBe None        // REST open
    out.manager.nativeClient shouldBe false // portable HTTP client fallback, not the JNI native lib
    out.flight.tlsEnabled shouldBe true     // self-signed TLS, auto-generated at boot
    // Cert material lives under the ephemeral demo home (wiped on exit), not the
    // cwd-relative default `certs/` -- `qod demo` must not litter the invoking directory.
    out.flight.tlsCertChain shouldBe home.root.resolve("certs/server-cert.pem").toString
    out.flight.tlsPrivateKey shouldBe home.root.resolve("certs/server-key.pem").toString
    out.flight.port shouldBe 31338
    out.acl.enabled shouldBe true // ACL kept on
  }

  it should "leave the base configs untouched (Guardrail 1: no in-place mutation)" in {
    val beforeTls = baseFlight.tlsEnabled
    val beforeKey = baseManager.apiKey
    DemoConfig.overlay(baseManager, baseFlight, baseAcl, pg, home)
    baseFlight.tlsEnabled shouldBe beforeTls
    baseManager.apiKey shouldBe beforeKey
  }
