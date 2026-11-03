package ai.starlake.quack.edge.auth

import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Semaphore

/** The helper the "no permit free" cases run their call in: a call that waits must be a failed
  * case, never a suite that does not end.
  */
class AtOnceSpec extends AnyFlatSpec with Matchers:

  "AtOnce" should "answer what the call answered, on a thread of its own" in {
    val caller = Thread.currentThread()
    AtOnce(Thread.currentThread() ne caller) shouldBe true
    AtOnce(6 * 7) shouldBe 42
  }

  it should "throw what the call threw" in {
    val thrown = intercept[IllegalStateException](
      AtOnce.within[Int](2000L)(throw new IllegalStateException("marker"))
    )
    thrown.getMessage shouldBe "marker"
  }

  it should "fail, rather than wait, for a call parked on a permit, and leave the permits as they were" in {
    val permits = new Semaphore(0)
    val started = System.nanoTime()

    val failure = intercept[TestFailedException](AtOnce.within[Unit](300L)(permits.acquire()))

    failure.getMessage should include("answer at once")
    permits.availablePermits shouldBe 0
    ((System.nanoTime() - started) / 1000000L) should be < 5000L
  }
