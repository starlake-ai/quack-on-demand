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

  // Regression guard: this fails loudly if the camelCase ProductHint[RoutingConfig] given in
  // Main is ever dropped, since pureconfig's default kebab-case reader would reject these keys.
  it should "read non-default camelCase routing overrides via the Main ProductHint" in:
    val overlay = ConfigSource.string("""
      |quack-on-demand.routing {
      |  cacheAware = false
      |  loadCapFactor = 3.5
      |  directoryMaxTables = 128
      |}
      |""".stripMargin)
    val cfg = overlay
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.routing.cacheAware shouldBe false
    cfg.routing.loadCapFactor shouldBe 3.5
    cfg.routing.directoryMaxTables shouldBe 128
