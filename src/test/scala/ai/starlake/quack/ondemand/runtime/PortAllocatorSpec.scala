package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PortAllocatorSpec extends AnyFlatSpec with Matchers:

  "PortAllocator" should "lease and release ports in the configured range" in:
    val a = new PortAllocator(min = 22000, max = 22002)
    a.lease() shouldBe Some(22000)
    a.lease() shouldBe Some(22001)
    a.lease() shouldBe Some(22002)
    a.lease() shouldBe None
    a.release(22001)
    a.lease() shouldBe Some(22001)

  it should "ignore release of out-of-range or unleased ports" in:
    val a = new PortAllocator(22000, 22001)
    noException should be thrownBy a.release(99999)
    noException should be thrownBy a.release(22000)