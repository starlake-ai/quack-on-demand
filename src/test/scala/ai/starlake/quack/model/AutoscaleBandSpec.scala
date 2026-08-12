package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AutoscaleBandSpec extends AnyFlatSpec with Matchers:
  private val dist = RoleDistribution(writeonly = 1, readonly = 2, dual = 0)

  "AutoscaleBand.validate" should "accept no band" in:
    AutoscaleBand.validate(None, None, dist, size = 3, hardCap = 16) shouldBe None

  it should "accept a well-formed band" in:
    AutoscaleBand.validate(Some(1), Some(4), dist, size = 3, hardCap = 16) shouldBe None

  it should "reject one-sided bands" in:
    AutoscaleBand.validate(Some(1), None, dist, 3, 16).isDefined shouldBe true
    AutoscaleBand.validate(None, Some(4), dist, 3, 16).isDefined shouldBe true

  it should "reject min below 1" in:
    AutoscaleBand.validate(Some(0), Some(4), dist, 3, 16).isDefined shouldBe true

  it should "accept min == max (pinned size: the band never scales)" in:
    // size must equal the band value, since size has to lie inside [3, 3].
    AutoscaleBand.validate(Some(3), Some(3), dist, size = 3, hardCap = 16) shouldBe None

  it should "reject min above max" in:
    AutoscaleBand.validate(Some(4), Some(3), dist, 3, 16).isDefined shouldBe true

  it should "reject max above the hard cap" in:
    AutoscaleBand.validate(Some(1), Some(17), dist, 3, 16).isDefined shouldBe true

  it should "reject a floor below the write-capable node count" in:
    // writeonly + dual = 2 write-capable nodes; a floor of 1 would force removing one.
    val writers = RoleDistribution(writeonly = 1, readonly = 1, dual = 1)
    AutoscaleBand.validate(Some(1), Some(4), writers, 3, 16).isDefined shouldBe true

  it should "reject a size outside the band" in:
    AutoscaleBand.validate(Some(2), Some(4), dist, size = 1, hardCap = 16).isDefined shouldBe true
    AutoscaleBand.validate(Some(1), Some(2), dist, size = 3, hardCap = 16).isDefined shouldBe true
