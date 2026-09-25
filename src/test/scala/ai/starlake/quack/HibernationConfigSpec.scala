package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the pureconfig camelCase hint for `quack-on-demand.hibernation`. Without a
  * `ProductHint[HibernationConfig]` in `Main`, the derived reader looks for kebab-case keys
  * (`sweep-seconds`), so `QOD_HIBERNATE_SWEEP_SEC` and `QOD_HIBERNATE_IDLE_MIN` are silently
  * ignored and the defaults win. A defaults-only round trip cannot catch that; an overlay can.
  */
class HibernationConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "HibernationConfig" should "read the camelCase keys of an overlay, not only the defaults" in {
    val cfg = ConfigSource
      .string(
        """quack-on-demand.hibernation { enabled = false, sweepSeconds = 61, defaultIdleMinutes = 7 }"""
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.hibernation shouldBe HibernationConfig(enabled = false, sweepSeconds = 61, defaultIdleMinutes = 7)
  }
