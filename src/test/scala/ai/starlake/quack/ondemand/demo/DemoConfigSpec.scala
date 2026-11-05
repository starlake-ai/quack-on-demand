package ai.starlake.quack.ondemand.demo

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
  private val baseQuack =
    ConfigSource.default.at("quack-native").loadOrThrow[ai.starlake.quack.QuackNativeConfig]

  private val home =
    DemoHome(Paths.get("/x"), Paths.get("/x/pg"), Paths.get("/x/dl"), Paths.get("/x/native"))
  private val pg = PgCoords("localhost", 54321, "postgres", "")

  "DemoConfig.overlay" should "give the demo admin the advertised password unless one is exported" in {
    def overlayWith(pw: String) =
      DemoConfig
        .overlay(
          baseManager.copy(admin = baseManager.admin.copy(password = pw)),
          baseFlight,
          baseQuack,
          pg,
          home,
          None
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
          baseQuack,
          pg,
          home,
          None
        )
        .adminPasswordLabel
    labelWith("") shouldBe "admin"
    labelWith("from-shell") shouldBe "<QOD_ADMIN_PASSWORD>"
    (labelWith("from-shell") should not).include("from-shell")
  }

  it should "force the approved demo posture" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, None)
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
    // Nothing to turn on: every node enforces the grants, so the demo carries no ACL config.
    (out.productElementNames.toList should not).contain("acl")
  }

  it should "not pick a manager-to-node client: the native client is the only one" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, None)
    (out.manager.productElementNames.toList should contain).noneOf("nativeClient", "nodeDisableSsl")
  }

  it should "carry no API key setting" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, None)
    (out.manager.productElementNames.toList should not).contain("apiKey")
  }

  it should "leave the base configs untouched (Guardrail 1: no in-place mutation)" in {
    val beforeTls       = baseFlight.tlsEnabled
    val beforeMetastore = baseManager.defaultMetastore
    DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, None)
    baseFlight.tlsEnabled shouldBe beforeTls
    // A boolean: a failure must not print the metastore (its password included).
    (baseManager.defaultMetastore == beforeMetastore) shouldBe true
  }

  private val s3Run                      = "s3://b12/qod-demo-1a2b3c4d"
  private def s3(local: Boolean): DemoS3 =
    DemoS3
      .fromEnv(
        Map(
          "QOD_DEMO_S3_PATH"         -> s3Run,
          "QOD_S3_ACCESS_KEY_ID"     -> "k",
          "QOD_S3_SECRET_ACCESS_KEY" -> "s"
        ).updatedWith("QOD_DEMO_S3_LOCAL")(_ => Option.when(local)("1")).get
      )
      .toOption
      .flatten
      .get

  "DemoConfig.overlay with no S3" should "keep the folder and the configured hosts" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, None)
    out.manager.defaultMetastore.dataPath shouldBe "/x/dl"
    out.manager.host shouldBe baseManager.host
    out.flight.host shouldBe baseFlight.host
    out.quack.host shouldBe baseQuack.host
  }

  "DemoConfig.overlay on a local store" should "use the S3 path and keep the configured hosts" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, Some(s3(true)))
    out.manager.defaultMetastore.dataPath shouldBe s"$s3Run/data"
    out.manager.host shouldBe baseManager.host
    out.flight.host shouldBe baseFlight.host
    out.quack.host shouldBe baseQuack.host
  }

  "DemoConfig.overlay on the user's bucket" should "bind every client listener to loopback" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, Some(s3(false)))
    out.manager.defaultMetastore.dataPath shouldBe s"$s3Run/data"
    out.manager.host shouldBe "127.0.0.1"
    out.flight.host shouldBe "127.0.0.1"
    out.quack.host shouldBe "127.0.0.1"
  }

  it should "derive the demo's database inside the run folder" in {
    val out = DemoConfig.overlay(baseManager, baseFlight, baseQuack, pg, home, Some(s3(false)))
    ai.starlake.quack.ondemand.PoolSupervisor.replaceLastSegment(
      out.manager.defaultMetastore.dataPath,
      "acme_tpch"
    ) shouldBe s"$s3Run/acme_tpch"
  }
