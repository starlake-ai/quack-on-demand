package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class FleetConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "FleetConfig" should "round-trip its defaults from the bundled application.conf" in {
    val cfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
    cfg.fleet.joinToken shouldBe ""
    cfg.fleet.heartbeatSec shouldBe 5
    cfg.fleet.heartbeatTimeoutSec shouldBe 30
    cfg.fleet.reassignAfterSec shouldBe 600
    cfg.fleet.startupTimeoutSec shouldBe 120
    cfg.fleet.stopTimeoutSec shouldBe 60
    cfg.fleet.ephemeral shouldBe "fleet"
  }

  it should "read the camelCase keys of an overlay, not only the defaults" in {
    // Without a camelCase ProductHint the derived reader looks for kebab-case keys, silently
    // ignores every configured value (QOD_FLEET_JOIN_TOKEN included) and keeps the defaults.
    val cfg = ConfigSource
      .string(
        """quack-on-demand.fleet { joinToken = "t", heartbeatSec = 1, heartbeatTimeoutSec = 5,
          |reassignAfterSec = 10, startupTimeoutSec = 7, stopTimeoutSec = 8, ephemeral = "local" }""".stripMargin
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.fleet shouldBe FleetConfig("t", 1, 5, 10, 7, 8, "local")
  }

  it should "flag joinToken as sensitive so ConfigHandlers masks it" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    entries
      .find(_.path == "quack-on-demand.fleet.joinToken")
      .map(_.sensitive) shouldBe Some(true)
  }

  it should "default to the spec values" in {
    val c = FleetConfig()
    (
      c.heartbeatSec,
      c.heartbeatTimeoutSec,
      c.reassignAfterSec,
      c.startupTimeoutSec,
      c.stopTimeoutSec
    ) shouldBe (5, 30, 600, 120, 60)
  }

  it should "refuse a heartbeat timeout at or below the interval" in {
    an[IllegalArgumentException] should be thrownBy
      FleetConfig(heartbeatSec = 30, heartbeatTimeoutSec = 30)
  }

  it should "refuse a reassign grace below the heartbeat timeout unless 0 or -1" in {
    an[IllegalArgumentException] should be thrownBy FleetConfig(reassignAfterSec = 10)
    noException should be thrownBy FleetConfig(reassignAfterSec = 0)
    noException should be thrownBy FleetConfig(reassignAfterSec = -1)
  }

  it should "expose validateForRuntime that refuses an empty join token in fleet mode only" in {
    FleetConfig().validateForRuntime("fleet", duckdbOnHost = true) shouldBe
      Left("QOD_FLEET_JOIN_TOKEN must be set when runtimeType=fleet")
    FleetConfig().validateForRuntime("local", duckdbOnHost = false) shouldBe Right(())
    FleetConfig(joinToken = "x").validateForRuntime("fleet", duckdbOnHost = false) shouldBe
      Right(())
  }

  it should
    "accept only fleet or local for ephemeral, and require duckdb on the host for local" in {
      an[IllegalArgumentException] should be thrownBy FleetConfig(ephemeral = "k8s")
      FleetConfig(joinToken = "x", ephemeral = "local")
        .validateForRuntime("fleet", duckdbOnHost = false) shouldBe
        Left(
          "QOD_FLEET_EPHEMERAL=local needs a duckdb binary on the manager host (DUCKDB_BIN or PATH)"
        )
      FleetConfig(joinToken = "x", ephemeral = "local")
        .validateForRuntime("fleet", duckdbOnHost = true) shouldBe Right(())
      FleetConfig(ephemeral = "local").ephemeralLocal shouldBe true
    }
