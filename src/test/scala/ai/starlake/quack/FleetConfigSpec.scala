package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FleetConfigSpec extends AnyFlatSpec with Matchers:

  "FleetConfig" should "default to the spec values" in {
    val c = FleetConfig()
    (c.heartbeatSec, c.heartbeatTimeoutSec, c.reassignAfterSec, c.startupTimeoutSec, c.stopTimeoutSec) shouldBe
      (5, 30, 600, 120, 60)
  }

  it should "refuse a heartbeat timeout at or below the interval" in {
    an[IllegalArgumentException] should be thrownBy FleetConfig(heartbeatSec = 30, heartbeatTimeoutSec = 30)
  }

  it should "refuse a reassign grace below the heartbeat timeout unless 0 or -1" in {
    an[IllegalArgumentException] should be thrownBy FleetConfig(reassignAfterSec = 10)
    noException should be thrownBy FleetConfig(reassignAfterSec = 0)
    noException should be thrownBy FleetConfig(reassignAfterSec = -1)
  }

  it should "expose validateForRuntime that refuses an empty join token in fleet mode only" in {
    FleetConfig().validateForRuntime("fleet", duckdbOnHost = true) shouldBe Left("QOD_FLEET_JOIN_TOKEN must be set when runtimeType=fleet")
    FleetConfig().validateForRuntime("local", duckdbOnHost = false) shouldBe Right(())
    FleetConfig(joinToken = "x").validateForRuntime("fleet", duckdbOnHost = false) shouldBe Right(())
  }

  it should "accept only fleet or local for ephemeral, and require duckdb on the host for local" in {
    an[IllegalArgumentException] should be thrownBy FleetConfig(ephemeral = "k8s")
    FleetConfig(joinToken = "x", ephemeral = "local").validateForRuntime("fleet", duckdbOnHost = false) shouldBe
      Left("QOD_FLEET_EPHEMERAL=local needs a duckdb binary on the manager host (DUCKDB_BIN or PATH)")
    FleetConfig(joinToken = "x", ephemeral = "local").validateForRuntime("fleet", duckdbOnHost = true) shouldBe Right(())
    FleetConfig(ephemeral = "local").ephemeralLocal shouldBe true
  }
