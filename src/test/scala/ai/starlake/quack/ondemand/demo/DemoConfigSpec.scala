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

  "DemoConfig.overlay" should "give the demo admin the advertised password unless one is exported" in {
    def overlayWith(pw: String) =
      DemoConfig
        .overlay(
          baseManager.copy(admin = baseManager.admin.copy(password = pw)),
          baseFlight,
          baseAcl,
          pg,
          home
        )
        .manager
        .admin
        .password
    overlayWith("") shouldBe "admin"
    overlayWith("from-shell") shouldBe "from-shell"
  }

  it should "label the admin password for the banner without ever exposing a chosen one" in {
    def labelWith(pw: String) =
      DemoConfig
        .overlay(
          baseManager.copy(admin = baseManager.admin.copy(password = pw)),
          baseFlight,
          baseAcl,
          pg,
          home
        )
        .adminPasswordLabel
    labelWith("") shouldBe "admin"
    labelWith("from-shell") shouldBe "<QOD_ADMIN_PASSWORD>"
    (labelWith("from-shell") should not).include("from-shell")
  }

  it should "force the approved demo posture" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseAcl, pg, home)
    out.manager.defaultMetastore.pgHost shouldBe "localhost"
    out.manager.defaultMetastore.pgPort shouldBe "54321"
    out.manager.defaultMetastore.pgUser shouldBe "postgres"
    out.manager.defaultMetastore.pgPassword shouldBe ""
    out.manager.defaultMetastore.dbName shouldBe "qod"
    out.manager.defaultMetastore.dataPath shouldBe "/x/dl"
    out.manager.runtimeType shouldBe "local"
    out.flight.tlsEnabled shouldBe true // self-signed TLS, auto-generated at boot
    // Cert material lives under the ephemeral demo home (wiped on exit), not the
    // cwd-relative default `certs/` -- `qod demo` must not litter the invoking directory.
    out.flight.tlsCertChain shouldBe home.root.resolve("certs/server-cert.pem").toString
    out.flight.tlsPrivateKey shouldBe home.root.resolve("certs/server-key.pem").toString
    out.flight.port shouldBe 31338
    out.acl.enabled shouldBe true // ACL kept on
  }

  it should "not pick a manager-to-node client: the native client is the only one" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseAcl, pg, home)
    (out.manager.productElementNames.toList should contain).noneOf("nativeClient", "nodeDisableSsl")
  }

  it should "carry no API key setting" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseAcl, pg, home)
    (out.manager.productElementNames.toList should not).contain("apiKey")
  }

  it should "leave the base configs untouched (Guardrail 1: no in-place mutation)" in {
    val beforeTls       = baseFlight.tlsEnabled
    val beforeMetastore = baseManager.defaultMetastore
    DemoConfig.overlay(baseManager, baseFlight, baseAcl, pg, home)
    baseFlight.tlsEnabled shouldBe beforeTls
    // A boolean: a failure must not print the metastore (its password included).
    (baseManager.defaultMetastore == beforeMetastore) shouldBe true
  }
