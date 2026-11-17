package ai.starlake.quack.edge.rest

import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** The REST edge's page budget: a data statement reserves a page's worst case, keeps what its body
  * holds, and gives it back once the response is written, so concurrent large pages cannot take
  * the heap.
  */
class RestPageBudgetSpec extends AnyFlatSpec with Matchers:

  private val MiB = 1024L * 1024

  /** Two pages' worth: 64 MiB of heap / 5 parts, each page reserving 2 x 4 MiB. */
  private def budget(hold: FiniteDuration = 1.minute) =
    RestPageBudget.forHeap(4 * MiB, 80 * MiB, hold)

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

  "settle" should "keep only what the body holds" in {
    val b = budget()
    List("a", "b").foreach(r => b.reserve(r, 50.millis).unsafeRunSync() shouldBe true)
    // Both pages turned out small: room for more.
    b.settle("a", 10 * 1024).unsafeRunSync()
    b.settle("b", 10 * 1024).unsafeRunSync()
    b.holds("a") shouldBe true
    b.reserve("c", 50.millis).unsafeRunSync() shouldBe true
  }

  "release" should "be idempotent, and a reservation never given back return on its own" in {
    val b = budget(hold = 200.millis)
    List("a", "b").foreach(r => b.reserve(r, 50.millis).unsafeRunSync() shouldBe true)
    b.release("a").unsafeRunSync()
    b.release("a").unsafeRunSync()
    b.reserve("c", 50.millis).unsafeRunSync() shouldBe true
    // b and c are never released: they come back after the hold.
    b.reserve("d", 1.second).unsafeRunSync() shouldBe true
  }

  "Unbounded" should "admit everything and hold nothing" in {
    (1 to 100).foreach { i =>
      RestPageBudget.Unbounded.reserve(s"r$i", 1.milli).unsafeRunSync() shouldBe true
    }
    RestPageBudget.Unbounded.holds("r1") shouldBe false
  }
