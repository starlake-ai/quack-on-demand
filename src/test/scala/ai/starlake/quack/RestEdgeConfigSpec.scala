package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

// The camelCase ProductHint / ConfigReader givens live in object Main (see RoutingConfigSpec).
import Main.given

/** The `quack-rest` block, its boot validation, and the banner line with its ACL-disabled variants.
  */
class RestEdgeConfigSpec extends AnyFlatSpec with Matchers:

  private val defaults = ConfigSource.default.at("quack-rest").loadOrThrow[RestEdgeConfig]

  private val others = List("manager REST" -> 20900, "FlightSQL" -> 31338, "Quack" -> 9494)

  /** The router's resume hold as shipped (`quack-flightsql.resumeHoldTimeoutSec`). */
  private val hold =
    ConfigSource.default.at("quack-flightsql").loadOrThrow[FlightConfig].resumeHoldTimeoutSec

  "application.conf" should "ship the quack-rest block disabled, on 31339, with TLS on" in {
    defaults.enabled shouldBe false
    defaults.host shouldBe "0.0.0.0"
    defaults.port shouldBe 31339
    defaults.tlsEnabled shouldBe true
    defaults.tlsCertChain shouldBe "certs/server-cert.pem"
    defaults.tlsPrivateKey shouldBe "certs/server-key.pem"
    defaults.defaultLimit shouldBe 1000
    defaults.maxRows shouldBe 100000
    defaults.maxResponseBytes shouldBe 64L * 1024 * 1024
    defaults.stmtTimeoutSec shouldBe 90
    defaults.maxConnections shouldBe 512
    defaults.maxHeaderBytes shouldBe 16384
    defaults.headerReceiveTimeoutSec shouldBe 10
    defaults.idleTimeoutSec shouldBe 60
  }

  it should "ship the abuse controls with their documented defaults" in {
    defaults.trustedProxies shouldBe ""
    defaults.trustedProxyCidrs shouldBe Nil
    defaults.authFailuresPerWindow shouldBe 20
    defaults.authWindowSec shouldBe 60
    defaults.authBlockSec shouldBe 300
    defaults.authThrottleMaxEntries shouldBe 100000
    defaults.maxConcurrentPerUser shouldBe 4
    defaults.maxConcurrentTotal shouldBe 16
    defaults.maxStreamSec shouldBe 600
    defaults.maxConcurrentParquet shouldBe 2
  }

  it should "parse trustedProxies as IPv4 and IPv6 CIDRs" in {
    val cfg = ConfigSource
      .string("""quack-rest { trustedProxies = "10.0.0.0/8, fd00::/8,192.168.1.1" }""")
      .withFallback(ConfigSource.default)
      .at("quack-rest")
      .loadOrThrow[RestEdgeConfig]
    cfg.trustedProxyCidrs.map(_.toString) shouldBe List("10.0.0.0/8", "fd00::/8", "192.168.1.1")
    RestEdgeConfig.validate(cfg.copy(enabled = true), others, hold) shouldBe Right(())
  }

  it should "read camelCase overrides through the Main ProductHint" in {
    val cfg = ConfigSource
      .string("quack-rest { enabled = true, port = 40000, maxRows = 50, defaultLimit = 10 }")
      .withFallback(ConfigSource.default)
      .at("quack-rest")
      .loadOrThrow[RestEdgeConfig]
    (cfg.enabled, cfg.port, cfg.maxRows, cfg.defaultLimit) shouldBe (true, 40000, 50, 10)
  }

  "RestEdgeConfig.validate" should "accept the shipped defaults once enabled" in {
    RestEdgeConfig.validate(defaults.copy(enabled = true), others, hold) shouldBe Right(())
  }

  it should "skip every check while the edge is disabled" in {
    RestEdgeConfig.validate(defaults.copy(port = 20900, defaultLimit = 0), others, hold) shouldBe
      Right(())
  }

  it should "refuse each out-of-range number, naming its env var" in {
    val on    = defaults.copy(enabled = true)
    val cases = List(
      on.copy(port = 0)                    -> "QOD_REST_PORT",
      on.copy(port = 65536)                -> "QOD_REST_PORT",
      on.copy(defaultLimit = 0)            -> "QOD_REST_DEFAULT_LIMIT",
      on.copy(maxRows = 999)               -> "QOD_REST_MAX_ROWS",
      on.copy(maxResponseBytes = 1023)     -> "QOD_REST_MAX_RESPONSE_BYTES",
      on.copy(stmtTimeoutSec = 0)          -> "QOD_REST_STMT_TIMEOUT_SEC",
      on.copy(maxConnections = 0)          -> "QOD_REST_DATA_MAX_CONNECTIONS",
      on.copy(maxHeaderBytes = 1023)       -> "QOD_REST_DATA_MAX_HEADER_BYTES",
      on.copy(headerReceiveTimeoutSec = 0) -> "QOD_REST_HEADER_RECEIVE_TIMEOUT_SEC",
      on.copy(idleTimeoutSec = 0)          -> "QOD_REST_IDLE_TIMEOUT_SEC",
      on.copy(authFailuresPerWindow = 0)   -> "QOD_REST_AUTH_FAILURES_PER_WINDOW",
      on.copy(authWindowSec = 0)           -> "QOD_REST_AUTH_WINDOW_SEC",
      on.copy(authBlockSec = -1)           -> "QOD_REST_AUTH_BLOCK_SEC",
      on.copy(authThrottleMaxEntries = 0)  -> "QOD_REST_AUTH_THROTTLE_MAX_ENTRIES",
      on.copy(maxConcurrentPerUser = 0)    -> "QOD_REST_MAX_CONCURRENT_PER_USER",
      on.copy(maxConcurrentTotal = 0)      -> "QOD_REST_MAX_CONCURRENT_TOTAL",
      on.copy(maxStreamSec = 0)            -> "QOD_REST_MAX_STREAM_SEC",
      on.copy(maxConcurrentParquet = 0)    -> "QOD_REST_MAX_CONCURRENT_PARQUET"
    )
    cases.foreach { case (cfg, env) =>
      withClue(env) {
        RestEdgeConfig.validate(cfg, others, hold).left.toOption.getOrElse("") should include(env)
      }
    }
  }

  it should "refuse an unparsable trusted proxy, naming its env var" in
    List("10.0.0.0/33", "not-a-cidr", "10.0.0.0/8, example.com", "fd00::/129").foreach { raw =>
      withClue(raw) {
        RestEdgeConfig
          .validate(defaults.copy(enabled = true, trustedProxies = raw), others, hold)
          .left
          .toOption
          .getOrElse("") should include("QOD_REST_TRUSTED_PROXIES")
      }
    }

  it should "refuse a port another door already binds" in {
    val msg = RestEdgeConfig
      .validate(defaults.copy(enabled = true, port = 31338), others, hold)
      .left
      .toOption
      .getOrElse("")
    msg should include("31338")
    msg should include("FlightSQL")
  }

  it should "refuse a statement wait that does not outlast the router's resume hold" in {
    val on = defaults.copy(enabled = true)
    hold shouldBe 60L
    on.stmtTimeoutSec.toLong should be > hold
    for wait <- List(60, 30) do
      val msg = RestEdgeConfig.validate(on.copy(stmtTimeoutSec = wait), others, 60L).left.toOption
      withClue(wait) {
        msg.getOrElse("") should include("QOD_REST_STMT_TIMEOUT_SEC")
        msg.getOrElse("") should include("PROXY_RESUME_HOLD_TIMEOUT_SEC")
      }
    RestEdgeConfig.validate(on.copy(stmtTimeoutSec = 61), others, 60L) shouldBe Right(())
    // A lowered hold admits a lowered wait.
    RestEdgeConfig.validate(on.copy(stmtTimeoutSec = 30), others, 20L) shouldBe Right(())
  }

  private val meta = Map("pgHost" -> "localhost", "pgPort" -> "5432", "dbName" -> "qod")

  "the banner" should "carry the REST line next to the SQL ACL line" in {
    val b = Banner.startup(
      meta,
      "0.0.0.0",
      20900,
      "0.0.0.0",
      31338,
      tlsEnabled = true,
      rest = Some(("0.0.0.0", 31339, true))
    )
    b should include(
      "   REST (data)   : https://localhost:31339/api/v1  (PAT bearer, read-only)"
    )
    b.indexOf("REST (data)") should be < b.indexOf("SQL ACL")
  }

  it should "use http when the edge runs without TLS" in {
    Banner.restLine("127.0.0.1", 40000, tls = false) should include(
      "http://127.0.0.1:40000/api/v1"
    )
  }

  it should "print no REST line while the edge is disabled" in {
    Banner
      .startup(
        meta,
        "0.0.0.0",
        20900,
        "0.0.0.0",
        31338,
        true
      ) should not
    include("REST (data)")
  }
