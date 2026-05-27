package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoleDistributionSpec extends AnyFlatSpec with Matchers:

  "RoleDistribution" should "validate that counts sum to the given size" in:
    RoleDistribution(writeonly = 0, readonly = 2, dual = 1).isValidFor(3) shouldBe true
    RoleDistribution(writeonly = 1, readonly = 1, dual = 1).isValidFor(3) shouldBe true
    RoleDistribution(writeonly = 0, readonly = 0, dual = 0).isValidFor(0) shouldBe true

  it should "reject mismatched totals" in:
    RoleDistribution(writeonly = 0, readonly = 2, dual = 1).isValidFor(4) shouldBe false
    RoleDistribution(writeonly = 0, readonly = 0, dual = 0).isValidFor(1) shouldBe false

  it should "produce an ordered list of roles for a pool" in:
    RoleDistribution(writeonly = 1, readonly = 2, dual = 1).asRoleList shouldBe
      List(Role.WriteOnly, Role.ReadOnly, Role.ReadOnly, Role.Dual)