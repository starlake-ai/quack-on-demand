package ai.starlake.quack.boot

import ai.starlake.quack.{EmbeddedPostgresConfig, Main, ManagerConfig}
import ai.starlake.quack.edge.config.AuthenticationConfig
import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.Files

class EmbeddedControlPlaneWiringSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  /** Proves a port is free by binding to the SAME address `EmbeddedControlPlane` binds to
    * ("localhost"), not the wildcard address a bare `new ServerSocket(port)` would use. On some
    * platforms (observed on macOS) a wildcard bind does not conflict with a still-live process
    * bound specifically to "localhost", so it would not catch a server that failed to stop.
    */
  private def assertPortFree(port: Int): Unit =
    val probe = new java.net.ServerSocket(port, 50, java.net.InetAddress.getByName("localhost"))
    probe.close()

  private def base: ManagerConfig =
    ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]

  private def baseAuth: AuthenticationConfig =
    ConfigSource.default.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]

  "withEmbeddedControlPlane" should "pass both configs through untouched when disabled" in:
    val cfg                                    = base
    val authCfg                                = baseAuth
    var seenMgr: Option[ManagerConfig]         = None
    var seenAuth: Option[AuthenticationConfig] = None
    Main
      .withEmbeddedControlPlane(cfg, authCfg) { (appliedMgr, appliedAuth) =>
        seenMgr = Some(appliedMgr)
        seenAuth = Some(appliedAuth)
        cats.effect.IO.pure(ExitCode.Success)
      }
      .unsafeRunSync() shouldBe ExitCode.Success
    seenMgr shouldBe Some(cfg)
    seenAuth shouldBe Some(authCfg)

  it should "inject live coordinates into both configs and stop the server when enabled" in:
    val dir  = Files.createTempDirectory("qod-embedded-wiring")
    val port = freePort()
    val cfg  = base.copy(embeddedPostgres =
      EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)
    )
    val authCfg                                = baseAuth
    var seenMgr: Option[ManagerConfig]         = None
    var seenAuth: Option[AuthenticationConfig] = None
    Main
      .withEmbeddedControlPlane(cfg, authCfg) { (appliedMgr, appliedAuth) =>
        seenMgr = Some(appliedMgr)
        seenAuth = Some(appliedAuth)
        cats.effect.IO.pure(ExitCode.Success)
      }
      .unsafeRunSync()
    seenMgr.get.defaultMetastore.pgPort shouldBe port.toString
    seenMgr.get.defaultMetastore.pgHost shouldBe "localhost"
    // Posture guardrail: nothing outside the five pg coordinates moved.
    seenMgr.get.runtimeType shouldBe cfg.runtimeType
    seenMgr.get.defaultMetastore.dataPath shouldBe cfg.defaultMetastore.dataPath
    // The auth.database block is re-anchored to the same live server, keyed by the SAME
    // dbName the control plane itself was ensured against.
    seenAuth.get.database.jdbcUrl shouldBe
      s"jdbc:postgresql://localhost:$port/${cfg.defaultMetastore.dbName}"
    seenAuth.get.database.username shouldBe "postgres"
    seenAuth.get.database.password shouldBe "postgres"
    // Negative space: every other auth field is untouched.
    seenAuth.get.database.enabled shouldBe authCfg.database.enabled
    seenAuth.get.database.systemQuery shouldBe authCfg.database.systemQuery
    seenAuth.get.database.tenantQuery shouldBe authCfg.database.tenantQuery
    seenAuth.get.roleClaim shouldBe authCfg.roleClaim
    seenAuth.get.keycloak shouldBe authCfg.keycloak
    seenAuth.get.google shouldBe authCfg.google
    seenAuth.get.azure shouldBe authCfg.azure
    seenAuth.get.aws shouldBe authCfg.aws
    seenAuth.get.jwt shouldBe authCfg.jwt
    seenAuth.get.oauthScopes shouldBe authCfg.oauthScopes
    // The port is free again, so stop() ran.
    assertPortFree(port)

  it should "let an explicit QOD_AUTH_DB_JDBC_URL win while username/password still patch" in:
    val dir  = Files.createTempDirectory("qod-embedded-wiring-authenv")
    val port = freePort()
    val cfg  = base.copy(embeddedPostgres =
      EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)
    )
    val overrideEnv: String => Option[String] =
      Map("QOD_AUTH_DB_JDBC_URL" -> "jdbc:postgresql://elsewhere:9/x").get
    var seenAuth: Option[AuthenticationConfig] = None
    Main
      .withEmbeddedControlPlane(cfg, baseAuth, overrideEnv) { (_, appliedAuth) =>
        seenAuth = Some(appliedAuth)
        cats.effect.IO.pure(ExitCode.Success)
      }
      .unsafeRunSync()
    // The explicit env var is a deliberate decision to authenticate elsewhere: it wins.
    seenAuth.get.database.jdbcUrl shouldBe "jdbc:postgresql://elsewhere:9/x"
    // But username/password were NOT overridden by their own env vars, so they still patch.
    seenAuth.get.database.username shouldBe "postgres"
    seenAuth.get.database.password shouldBe "postgres"
    assertPortFree(port)

  it should "stop the server even when the boot body fails" in:
    val dir  = Files.createTempDirectory("qod-embedded-wiring-fail")
    val port = freePort()
    val cfg  = base.copy(embeddedPostgres =
      EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)
    )
    an[RuntimeException] should be thrownBy
      Main
        .withEmbeddedControlPlane(cfg, baseAuth)((_, _) =>
          cats.effect.IO.raiseError(new RuntimeException("boom"))
        )
        .unsafeRunSync()
    assertPortFree(port)

  it should "stop the server when ensureDatabase itself fails" in:
    val dir  = Files.createTempDirectory("qod-embedded-wiring-ensuredb-fail")
    val port = freePort()
    // The embedded double quote makes `CREATE DATABASE "qod"bad"` a syntax error: the
    // pg_database probe (a prepared statement) passes, so start() succeeds and the CREATE
    // fails inside ensureDatabase, after the server is already up.
    val cfg = base.copy(
      embeddedPostgres =
        EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString),
      defaultMetastore = base.defaultMetastore.copy(dbName = "qod\"bad")
    )
    an[Exception] should be thrownBy
      Main
        .withEmbeddedControlPlane(cfg, baseAuth)((_, _) => cats.effect.IO.pure(ExitCode.Success))
        .unsafeRunSync()
    // The port is free again, so stop() ran even though ensureDatabase threw before boot ran.
    assertPortFree(port)
