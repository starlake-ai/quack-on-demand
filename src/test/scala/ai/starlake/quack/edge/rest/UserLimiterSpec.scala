package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** The per-user in-flight cap of the REST data edge: a slot per request, held by the request and by
  * every node call it started, freed once the last holder lets go, and never freed twice.
  */
class UserLimiterSpec extends AnyFlatSpec with Matchers:

  private val alice = ("acme", "u-alice")
  private val bob   = ("acme", "u-bob")

  "tryAcquire" should "admit maxConcurrentPerUser slots per user and refuse the next" in {
    val l    = new UserLimiter(perUser = 4, total = 64)
    val held = (1 to 4).map(_ => l.tryAcquire(alice))
    held.forall(_.isDefined) shouldBe true
    l.tryAcquire(alice) shouldBe None
    l.tryAcquire(bob) should not be empty
    l.inFlight(alice) shouldBe 4
  }

  it should "key by user, not by tenant alone" in {
    val l = new UserLimiter(perUser = 1, total = 64)
    l.tryAcquire(("acme", "u-1")) should not be empty
    l.tryAcquire(("globex", "u-1")) should not be empty
    l.tryAcquire(("acme", "u-1")) shouldBe None
  }

  it should "refuse past maxConcurrentTotal whoever asks" in {
    val l = new UserLimiter(perUser = 4, total = 5)
    (1 to 4).foreach(_ => l.tryAcquire(alice) should not be empty)
    l.tryAcquire(bob) should not be empty
    l.tryAcquire(("acme", "u-carol")) shouldBe None
    l.inFlightTotal shouldBe 5
  }

  "release" should "free the slot, and a second release must not raise the cap" in {
    val l = new UserLimiter(perUser = 1, total = 64)
    val h = l.tryAcquire(alice).get
    h.release()
    h.release()
    l.inFlight(alice) shouldBe 0
    l.tryAcquire(alice) should not be empty
    l.tryAcquire(alice) shouldBe None
    l.inFlightTotal shouldBe 1
  }

  it should "keep the slot until the request AND every retained node hold released it" in {
    val l    = new UserLimiter(perUser = 1, total = 64)
    val req  = l.tryAcquire(alice).get
    val node = req.retain()
    req.release()
    l.inFlight(alice) shouldBe 1
    node.release()
    node.release()
    l.inFlight(alice) shouldBe 0
    // A retain after the last release holds nothing and frees nothing.
    val stale = req.retain()
    stale.release()
    l.inFlight(alice) shouldBe 0
    l.tryAcquire(alice) should not be empty
    l.tryAcquire(alice) shouldBe None
  }

  "random acquire, retain and release sequences" should "leak no slot and never exceed a cap" in {
    val seed = System.nanoTime()
    info(s"seed = $seed")
    val rnd   = new Random(seed)
    val users = Vector(alice, bob, ("acme", "u-carol"), ("globex", "u-alice"))
    (1 to 10000).foreach { round =>
      val l     = new UserLimiter(perUser = 1 + rnd.nextInt(4), total = 1 + rnd.nextInt(8))
      val holds = ArrayBuffer.empty[UserLimiter.Hold]
      (1 to 1 + rnd.nextInt(30)).foreach { _ =>
        rnd.nextInt(5) match
          case 0 | 1 => l.tryAcquire(users(rnd.nextInt(users.size))).foreach(holds += _)
          case 2 if holds.nonEmpty => holds += holds(rnd.nextInt(holds.size)).retain()
          // A release, sometimes repeated, of any hold: a double release is a no-op.
          case _ if holds.nonEmpty => holds(rnd.nextInt(holds.size)).release()
          case _                   => ()
        withClue(s"seed=$seed round=$round") {
          l.inFlightTotal should be <= l.total
          users.foreach(u => l.inFlight(u) should be <= l.perUser)
        }
      }
      // A node call that fails or completes releases like any other holder.
      rnd.shuffle(holds).foreach(_.release())
      withClue(s"seed=$seed round=$round")(l.inFlightTotal shouldBe 0)
    }
  }
