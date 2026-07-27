package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

// The pureconfig ProductHint / ConfigReader givens that map application.conf's
// camelCase keys onto our config case classes live in object Main; import them
// here so this spec reads ManagerConfig exactly as the manager does at boot.
import Main.given

class RoutingConfigSpec extends AnyFlatSpec with Matchers:

  "application.conf" should "carry cache-aware routing defaults" in:
    val cfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
    cfg.routing.cacheAware shouldBe true
    cfg.routing.loadCapFactor shouldBe 2.0
    cfg.routing.directoryMaxTables shouldBe 4096
