package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PortAllocatorSpec extends AnyFlatSpec with Matchers:

  private def listening(port: Int): Boolean =
    val s = new java.net.Socket()
    try
      s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200)
      true
    catch case _: java.io.IOException => false
    finally s.close()

  /** The first of `n` consecutive ports nothing listens on: the allocator skips one that answers,
    * so what a case expects it to lease must be free on this host, whatever else runs here.
    */
  private def freePorts(n: Int): Int =
    Iterator
      .from(23300, n)
      .find(first => (first until first + n).forall(!listening(_)))
      .get

  "PortAllocator" should "lease and release ports in the configured range" in:
    val first = freePorts(3)
    val a     = new PortAllocator(min = first, max = first + 2)
    a.lease() shouldBe Some(first)
    a.lease() shouldBe Some(first + 1)
    a.lease() shouldBe Some(first + 2)
    a.lease() shouldBe None
    a.release(first + 1)
    a.lease() shouldBe Some(first + 1)

  it should "ignore release of out-of-range or unleased ports" in:
    val a = new PortAllocator(22000, 22001)
    noException should be thrownBy a.release(99999)
    noException should be thrownBy a.release(22000)

  it should "skip a port something listens on, as a node a dead manager left behind does" in:
    val first    = freePorts(2)
    val a        = new PortAllocator(first, first + 1)
    val leftover = new java.net.ServerSocket(first)
    try
      a.lease() shouldBe Some(first + 1)
      a.lease() shouldBe None
    finally leftover.close()
    a.lease() shouldBe Some(first)
