package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins defaults and the camelCase ProductHint for the autoscale block. */
class AutoscaleConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "AutoscaleConfig" should "load defaults from application.conf" in:
    val cfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
    cfg.autoscale.enabled shouldBe true
    cfg.autoscale.sweepSeconds shouldBe 60
    cfg.autoscale.windowMinutes shouldBe 5
    cfg.autoscale.highWatermark shouldBe 0.8
    cfg.autoscale.lowWatermark shouldBe 0.3
    cfg.autoscale.outStreak shouldBe 2
    cfg.autoscale.inStreak shouldBe 10
    cfg.autoscale.scaleOutCooldownSec shouldBe 180
    cfg.autoscale.scaleInCooldownSec shouldBe 600
    cfg.autoscale.assumedConcurrencyPerNode shouldBe 4
    cfg.autoscale.hardCap shouldBe 16
    cfg.autoscale.failureBackoffSweeps shouldBe 5

  it should "honor camelCase overlays" in:
    val overlay = ConfigSource.string(
      """quack-on-demand { autoscale { highWatermark = 0.9, sweepSeconds = 45 } }"""
    )
    val cfg = overlay
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.autoscale.highWatermark shouldBe 0.9
    cfg.autoscale.sweepSeconds shouldBe 45

  it should "clamp the sweep interval to the 30s floor" in:
    AutoscaleConfig(sweepSeconds = 5).sweepInterval.toSeconds shouldBe 30L

  it should "pass sweepSeconds through unclamped above the floor" in:
    AutoscaleConfig(sweepSeconds = 60).sweepInterval.toSeconds shouldBe 60L

  it should "refuse an inverted watermark band" in:
    an[IllegalArgumentException] should be thrownBy AutoscaleConfig(
      highWatermark = 0.3,
      lowWatermark = 0.8
    )

  it should "refuse a non-positive outStreak" in:
    an[IllegalArgumentException] should be thrownBy AutoscaleConfig(outStreak = 0)

  it should "refuse a non-positive inStreak" in:
    an[IllegalArgumentException] should be thrownBy AutoscaleConfig(inStreak = 0)

  it should "refuse a non-positive windowMinutes" in:
    an[IllegalArgumentException] should be thrownBy AutoscaleConfig(windowMinutes = 0)

  it should "refuse a non-positive failureBackoffSweeps" in:
    an[IllegalArgumentException] should be thrownBy AutoscaleConfig(failureBackoffSweeps = 0)

  it should "refuse a non-positive hardCap" in:
    an[IllegalArgumentException] should be thrownBy AutoscaleConfig(hardCap = 0)

  it should "refuse a negative scaleOutCooldownSec" in:
    an[IllegalArgumentException] should be thrownBy AutoscaleConfig(scaleOutCooldownSec = -1)
