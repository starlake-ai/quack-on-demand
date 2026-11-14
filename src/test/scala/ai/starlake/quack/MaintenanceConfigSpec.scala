package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** `quack-on-demand.maintenance.leaderGraceSec` (issue #210): read from an overlay, 0 meaning three
  * ticks, and a set value shorter than one tick refused.
  */
class MaintenanceConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "MaintenanceConfig" should "read leaderGraceSec from an overlay" in {
    val cfg = ConfigSource
      .string("""quack-on-demand.maintenance { tickSec = 30, leaderGraceSec = 90 }""")
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.maintenance.leaderGraceSec shouldBe 90
    cfg.maintenance.effectiveLeaderGraceSec shouldBe 90
  }

  it should "read 0, the default, as three ticks" in {
    MaintenanceConfig().leaderGraceSec shouldBe 0
    MaintenanceConfig(tickSec = 40).effectiveLeaderGraceSec shouldBe 120
  }

  it should "refuse a grace shorter than one tick" in {
    an[IllegalArgumentException] should be thrownBy
      MaintenanceConfig(tickSec = 60, leaderGraceSec = 30)
  }

  it should "refuse a negative grace" in {
    an[IllegalArgumentException] should be thrownBy MaintenanceConfig(leaderGraceSec = -1)
  }
