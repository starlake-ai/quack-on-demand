package ai.starlake.quack.edge.rest

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** The REST edge's page budget: a data statement reserves a page's worst case, keeps what its body
  * holds, and gives it back once the response is written, so concurrent large pages cannot take the
  * heap.
  */
class RestPageBudgetSpec extends AnyFlatSpec with Matchers:

  private val MiB = 1024L * 1024

  /** Two pages' worth: 64 MiB of heap / 5 parts, each page reserving 2 x 4 MiB. */
  private def budget(hold: FiniteDuration = 1.minute) =
    RestPageBudget.forHeap(4 * MiB, 80 * MiB, hold)

  /** Whether `rid` is still held once `within` has passed, polling so a slow machine is waited for.
    */
  private def heldAfter(b: RestPageBudget, rid: String, within: FiniteDuration): Boolean =
    val deadline = within.fromNow
    while b.holds(rid) && deadline.hasTimeLeft() do Thread.sleep(20)
    b.holds(rid)

  "forHeap" should "take a fifth of the heap and reserve twice a page" in {
    val b = budget()
    b.totalKiB shouldBe 16L * 1024
    b.reserveKiB shouldBe 8L * 1024
    // A heap too small for one page still serves one at a time.
    RestPageBudget.forHeap(64 * MiB, 10 * MiB, 1.minute).reserveKiB shouldBe 2L * 1024
  }

  "reserve" should "refuse a page past the budget until one is given back" in {
    val b = budget()
    b.reserve("r1", 50.millis).unsafeRunSync() shouldBe true
    b.reserve("r2", 50.millis).unsafeRunSync() shouldBe true
    b.reserve("r3", 50.millis).unsafeRunSync() shouldBe false
    b.holds("r3") shouldBe false
    b.release("r1").unsafeRunSync()
    b.holds("r1") shouldBe false
    b.reserve("r3", 50.millis).unsafeRunSync() shouldBe true
  }

  it should "lose no permit to a cancel, wherever the cancel lands" in {
    val b = budget()
    // Each reservation is cancelled at a different point: while it waits, as the permits are
    // taken, or after they were recorded. The routes then give back what the request holds, as
    // they do for a cancelled request; permits taken but not recorded would be lost for good.
    (1 to 3000).foreach { i =>
      val rid = s"c$i"
      (for
        f <- b.reserve(rid, 1.second).start
        _ <- IO.cede.replicateA_(i % 24)
        _ <- f.cancel
        _ <- b.release(rid)
      yield ()).unsafeRunSync()
    }
    // The whole budget is free again: two pages at once.
    b.reserve("p", 50.millis).unsafeRunSync() shouldBe true
    b.reserve("q", 50.millis).unsafeRunSync() shouldBe true
  }

  "settle" should "keep only what the body holds" in {
    val b = budget()
    List("a", "b").foreach(r => b.reserve(r, 50.millis).unsafeRunSync() shouldBe true)
    // Both pages turned out small: room for more.
    b.settle("a", 10 * 1024).unsafeRunSync()
    b.settle("b", 10 * 1024).unsafeRunSync()
    b.holds("a") shouldBe true
    b.reserve("c", 50.millis).unsafeRunSync() shouldBe true
  }

  "the backstop" should "never give back a page that is still being encoded" in {
    // No body yet: the encoding holds its buffer, however long the hold.
    val b = budget(hold = 100.millis)
    b.reserve("a", 50.millis).unsafeRunSync() shouldBe true
    heldAfter(b, "a", 400.millis) shouldBe true
    b.release("a").unsafeRunSync()
  }

  it should "give back a body never pulled, a hold after the body exists" in {
    val b = budget(hold = 300.millis)
    b.reserve("a", 50.millis).unsafeRunSync() shouldBe true
    Thread.sleep(200)
    b.settle("a", 10 * 1024).unsafeRunSync()
    // Past the hold counted from the reservation, inside the one counted from the body.
    Thread.sleep(200)
    b.holds("a") shouldBe true
    heldAfter(b, "a", 5.seconds) shouldBe false
  }

  it should "leave a body whose writing has begun to the body's own release" in {
    // A slow client may take longer than the hold: once its body is pulled, the body's
    // finalizer gives the page back.
    val b = budget(hold = 100.millis)
    b.reserve("a", 50.millis).unsafeRunSync() shouldBe true
    b.settle("a", 10 * 1024).unsafeRunSync()
    b.pulled("a").unsafeRunSync()
    heldAfter(b, "a", 400.millis) shouldBe true
    b.release("a").unsafeRunSync()
    b.holds("a") shouldBe false
  }

  "release" should "be idempotent, and leave settle a no-op after it" in {
    val b = budget()
    List("a", "b").foreach(r => b.reserve(r, 50.millis).unsafeRunSync() shouldBe true)
    b.release("a").unsafeRunSync()
    b.release("a").unsafeRunSync()
    b.settle("a", 10 * 1024).unsafeRunSync()
    b.holds("a") shouldBe false
    b.reserve("c", 50.millis).unsafeRunSync() shouldBe true
    b.reserve("d", 50.millis).unsafeRunSync() shouldBe false
  }

  it should "stop the backstop, so no sleeper outlives its request" in {
    val b = budget(hold = 200.millis)
    b.reserve("a", 50.millis).unsafeRunSync() shouldBe true
    b.settle("a", 10 * 1024).unsafeRunSync()
    b.release("a").unsafeRunSync()
    // A settle after the release arms nothing either.
    b.settle("a", 10 * 1024).unsafeRunSync()
    // A sleeper left running would give back this later reservation under the same id.
    b.reserve("a", 50.millis).unsafeRunSync() shouldBe true
    heldAfter(b, "a", 600.millis) shouldBe true
    b.release("a").unsafeRunSync()
  }

  "Unbounded" should "admit everything and hold nothing" in {
    (1 to 100).foreach { i =>
      RestPageBudget.Unbounded.reserve(s"r$i", 1.milli).unsafeRunSync() shouldBe true
    }
    RestPageBudget.Unbounded.holds("r1") shouldBe false
  }
