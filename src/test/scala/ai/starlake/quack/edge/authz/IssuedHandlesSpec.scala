package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicLong

class IssuedHandlesSpec extends AnyFlatSpec with Matchers:

  "IssuedHandles" should "keep a nonce until DecideGraceMs past its handle's expiry" in {
    val clock = new AtomicLong(1000L)
    val r     = new IssuedHandles(maxEntries = 200_000L, clock = () => clock.get())
    r.put("n-1", "node-1", "u-1", expiresAt = 61_000L)
    r.get("n-1") shouldBe Some(IssuedHandles.Issued("node-1", "u-1"))
    clock.set(61_000L + NodeHandles.DecideGraceMs)
    r.get("n-1").isDefined shouldBe true
    clock.set(61_000L + NodeHandles.DecideGraceMs + 1)
    r.get("n-1") shouldBe None
    r.get("unknown") shouldBe None
  }

  it should "stay within its bound" in {
    val r = new IssuedHandles(maxEntries = 2)
    (1 to 5).foreach(i => r.put(s"n-$i", "node-1", "u-1", Long.MaxValue / 2))
    r.size should be <= 2L
  }
