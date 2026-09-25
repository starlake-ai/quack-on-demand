package ai.starlake.quack.ondemand.fleet

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FleetLivenessSpec extends AnyFlatSpec with Matchers:

  "classify" should "be Reachable inside the heartbeat timeout" in {
    FleetLiveness.classify(29, 30, 600) shouldBe ServerLiveness.Reachable
    FleetLiveness.classify(30, 30, 600) shouldBe ServerLiveness.Reachable
  }
  it should "be Unreachable(silent) between the two windows" in {
    FleetLiveness.classify(31, 30, 600) shouldBe ServerLiveness.Unreachable(31)
    FleetLiveness.classify(599, 30, 600) shouldBe ServerLiveness.Unreachable(599)
  }
  it should "be Dead past the grace" in {
    FleetLiveness.classify(601, 30, 600) shouldBe ServerLiveness.Dead
  }
  it should "be Dead as soon as unreachable when reassignAfterSec = 0" in {
    FleetLiveness.classify(31, 30, 0) shouldBe ServerLiveness.Dead
  }
  it should "never be Dead when reassignAfterSec = -1" in {
    FleetLiveness.classify(999999, 30, -1) shouldBe ServerLiveness.Unreachable(999999)
  }
