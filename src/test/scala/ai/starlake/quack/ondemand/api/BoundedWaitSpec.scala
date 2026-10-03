package ai.starlake.quack.ondemand.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** A token timeout is a bounded wait over a statement that keeps running, so a result that arrives
  * after the caller gave up has nobody left to close it. These cases pin that the helper closes it
  * exactly once, and that a result delivered in time is the caller's to close, never the helper's.
  *
  * Real short durations, as the rest of the suite does for IO timing (there is no cats-effect
  * testkit on the classpath).
  */
class BoundedWaitSpec extends AnyFlatSpec with Matchers:

  private final class Res(val id: Int):
    val closes = new AtomicInteger(0)

  private def closeRes(r: Res): Unit = r.closes.incrementAndGet(): Unit

  /** Waits (bounded) for a late close to land; the worker fiber finishes in the background. */
  private def awaitCloses(r: Res, n: Int): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while r.closes.get() < n && System.nanoTime() < deadline do Thread.sleep(5)

  "BoundedWait.closingLate" should "return a result delivered in time without closing it" in:
    val res = new Res(1)
    val out = BoundedWait
      .closingLate(IO.pure(Right(res)), 1.second, "timeout", closeRes)
      .unsafeRunSync()
    out shouldBe Right(res)
    Thread.sleep(50)
    res.closes.get() shouldBe 0

  it should "pass a failure delivered in time through untouched" in:
    val out = BoundedWait
      .closingLate[String, Res](IO.pure(Left("denied")), 1.second, "timeout", closeRes)
      .unsafeRunSync()
    out shouldBe Left("denied")

  it should "re-raise an error raised in time" in:
    val boom = new RuntimeException("boom")
    val out  = BoundedWait
      .closingLate[String, Res](IO.raiseError(boom), 1.second, "timeout", closeRes)
      .attempt
      .unsafeRunSync()
    out shouldBe Left(boom)

  it should "answer the timeout and close a late result exactly once" in:
    val res = new Res(2)
    val out = BoundedWait
      .closingLate(IO.sleep(200.millis).as(Right(res)), 20.millis, "timeout", closeRes)
      .unsafeRunSync()
    out shouldBe Left("timeout")
    awaitCloses(res, 1)
    Thread.sleep(50)
    res.closes.get() shouldBe 1

  it should "not wait on an uncancelable blocking node call past the limit" in:
    // QuackHttpClient.query is IO.blocking, which cancellation cannot interrupt: a plain
    // timeoutTo would sit on it until the node answered. The bounded wait must not.
    val release = new CountDownLatch(1)
    val res     = new Res(3)
    val run     = IO.blocking { release.await(); Right(res) }
    val t0      = System.nanoTime()
    val out     = BoundedWait.closingLate(run, 30.millis, "timeout", closeRes).unsafeRunSync()
    val waited  = (System.nanoTime() - t0).nanos
    out shouldBe Left("timeout")
    waited should be < 2.seconds
    res.closes.get() shouldBe 0
    release.countDown()
    awaitCloses(res, 1)
    Thread.sleep(50)
    res.closes.get() shouldBe 1

  it should "never close a late failure and never surface a late error" in:
    val lateLeft = BoundedWait
      .closingLate[String, Res](IO.sleep(100.millis).as(Left("late")), 10.millis, "t", closeRes)
      .unsafeRunSync()
    lateLeft shouldBe Left("t")
    val lateError = BoundedWait
      .closingLate[String, Res](
        IO.sleep(100.millis) *> IO.raiseError(new RuntimeException("late boom")),
        10.millis,
        "t",
        closeRes
      )
      .unsafeRunSync()
    lateError shouldBe Left("t")
    Thread.sleep(200)

  it should "close the result when the waiting caller is itself cancelled" in:
    val res     = new Res(4)
    val release = new CountDownLatch(1)
    val run     = IO.blocking { release.await(); Right(res) }
    val waiting = BoundedWait.closingLate(run, 10.seconds, "timeout", closeRes)
    // An outer timeout (preview's previewTimeoutSec) cancels the bounded wait mid-flight.
    val out = waiting.timeoutTo(30.millis, IO.pure(Left("outer"))).unsafeRunSync()
    out shouldBe Left("outer")
    release.countDown()
    awaitCloses(res, 1)
    Thread.sleep(50)
    res.closes.get() shouldBe 1

  it should "hand every result to exactly one owner when it races the deadline" in:
    val seed = System.nanoTime()
    info(s"seed=$seed")
    val rnd = new scala.util.Random(seed)
    val all = (1 to 200).toList.map { i =>
      val res   = new Res(i)
      val delay = rnd.nextInt(6).millis
      val limit = rnd.nextInt(6).millis + 1.milli
      val out   = BoundedWait
        .closingLate(IO.sleep(delay).as(Right(res)), limit, "timeout", closeRes)
        .unsafeRunSync()
      // The caller owns a delivered result and closes it itself, as the routed executor's callers do.
      out.foreach(closeRes)
      res
    }
    all.foreach(awaitCloses(_, 1))
    Thread.sleep(50)
    withClue(s"seed=$seed: ") {
      all.map(_.closes.get()).distinct shouldBe List(1)
    }
