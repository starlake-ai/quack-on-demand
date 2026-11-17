package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

// The camelCase ProductHint / ConfigReader givens live in object Main (see RoutingConfigSpec).
import Main.given

/** The `quack-data-edge` block, its boot validation, and the banner line. */
class RestEdgeConfigSpec extends AnyFlatSpec with Matchers:

  private val defaults = ConfigSource.default.at("quack-data-edge").loadOrThrow[RestEdgeConfig]

  /** The router's resume hold as shipped (`quack-flightsql.resumeHoldTimeoutSec`). */
  private val hold =
    ConfigSource.default.at("quack-flightsql").loadOrThrow[FlightConfig].resumeHoldTimeoutSec

  "application.conf" should "ship the quack-data-edge block disabled, with its limits" in {
    defaults.enabled shouldBe false
    defaults.defaultLimit shouldBe 1000
    defaults.maxRows shouldBe 100000
    defaults.maxResponseBytes shouldBe 16L * 1024 * 1024
    defaults.stmtTimeoutSec shouldBe 90
    defaults.maxConcurrentRequests shouldBe 256
  }

  it should "read camelCase overrides through the Main ProductHint" in {
    val cfg = ConfigSource
      .string(
        "quack-data-edge { enabled = true, maxRows = 50, defaultLimit = 10, " +
          "maxConcurrentRequests = 7 }"
      )
      .withFallback(ConfigSource.default)
      .at("quack-data-edge")
      .loadOrThrow[RestEdgeConfig]
    (cfg.enabled, cfg.maxRows, cfg.defaultLimit, cfg.maxConcurrentRequests) shouldBe
      (true, 50, 10, 7)
  }

  "RestEdgeConfig.validate" should "accept the shipped defaults once enabled" in {
    RestEdgeConfig.validate(defaults.copy(enabled = true), hold) shouldBe Right(())
  }

  it should "skip every check while the edge is disabled" in {
    RestEdgeConfig.validate(
      defaults.copy(defaultLimit = 0, maxConcurrentRequests = 0, stmtTimeoutSec = 1),
      hold
    ) shouldBe Right(())
  }

  it should "refuse each out-of-range number, naming its env var" in {
    val on    = defaults.copy(enabled = true)
    val cases = List(
      on.copy(defaultLimit = 0)        -> "QOD_DATA_EDGE_DEFAULT_LIMIT",
      on.copy(maxRows = 999)           -> "QOD_DATA_EDGE_MAX_ROWS",
      on.copy(maxResponseBytes = 1023) -> "QOD_DATA_EDGE_MAX_RESPONSE_BYTES",
      on.copy(maxResponseBytes = RestEdgeConfig.MaxResponseBytes + 1) ->
        "QOD_DATA_EDGE_MAX_RESPONSE_BYTES",
      on.copy(stmtTimeoutSec = 0)        -> "QOD_DATA_EDGE_STMT_TIMEOUT_SEC",
      on.copy(maxConcurrentRequests = 0) -> "QOD_DATA_EDGE_MAX_CONCURRENT_REQUESTS"
    )
    cases.foreach { case (cfg, env) =>
      withClue(env) {
        val msg = RestEdgeConfig.validate(cfg, hold).left.toOption.getOrElse("")
        msg should include(env)
        msg should startWith("quack-data-edge: ")
      }
    }
  }

  it should "refuse a statement wait that does not outlast the router's resume hold" in {
    val on = defaults.copy(enabled = true)
    hold shouldBe 60L
    on.stmtTimeoutSec.toLong should be > hold
    for wait <- List(60, 30) do
      val msg = RestEdgeConfig.validate(on.copy(stmtTimeoutSec = wait), 60L).left.toOption
      withClue(wait) {
        msg.getOrElse("") should include("QOD_DATA_EDGE_STMT_TIMEOUT_SEC")
        msg.getOrElse("") should include("PROXY_RESUME_HOLD_TIMEOUT_SEC")
      }
    RestEdgeConfig.validate(on.copy(stmtTimeoutSec = 61), 60L) shouldBe Right(())
    // A lowered hold admits a lowered wait.
    RestEdgeConfig.validate(on.copy(stmtTimeoutSec = 30), 20L) shouldBe Right(())
  }

  private val meta = Map("pgHost" -> "localhost", "pgPort" -> "5432", "dbName" -> "qod")

  "the banner" should "carry the data edge line on the manager port, next to the SQL ACL line" in {
    val b = Banner.startup(
      meta,
      "0.0.0.0",
      20900,
      "0.0.0.0",
      31338,
      tlsEnabled = true,
      dataEdge = true
    )
    b should include(
      "   REST (data)   : http://localhost:20900/data/v1  (PAT bearer, read-only)"
    )
    b.indexOf("REST (data)") should be < b.indexOf("SQL ACL")
  }

  it should "print the host and port it is given" in {
    Banner.restLine("127.0.0.1", 40000) should include("http://127.0.0.1:40000/data/v1")
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
