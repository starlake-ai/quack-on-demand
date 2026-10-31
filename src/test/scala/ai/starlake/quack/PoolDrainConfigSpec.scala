package ai.starlake.quack

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class PoolDrainConfigSpec extends AnyFunSpec with Matchers:
  import Main.given

  private def load(overlay: String): ManagerConfig =
    ConfigSource
      .string(overlay)
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]

  it("defaults the pool drain timeout to 20 s, below the qod CLI's 30 s REST timeout"):
    load("").poolDrainTimeoutSec shouldBe 20

  it("keeps it apart from the shutdown drain timeout"):
    val cfg = load("quack-on-demand.poolDrainTimeoutSec = 7")
    cfg.poolDrainTimeoutSec shouldBe 7
    cfg.drainTimeoutSec shouldBe 60

  it("accepts 0, which stops a teardown's nodes at once"):
    load("quack-on-demand.poolDrainTimeoutSec = 0").poolDrainTimeoutSec shouldBe 0

  it("refuses a negative timeout, naming the env var"):
    val e = intercept[IllegalArgumentException](load("quack-on-demand.poolDrainTimeoutSec = -1"))
    e.getMessage should include("QOD_POOL_DRAIN_TIMEOUT_SEC")
