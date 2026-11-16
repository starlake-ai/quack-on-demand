package ai.starlake.quack.ondemand.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** A token timeout is a bounded wait over a statement whose result may take effect when it is
  * closed: a stamped write's close COMMITs. These cases pin that a result the caller gave up on
  * never takes effect (the statement's fiber is cancelled, so its own release path rolls it back,
  * and one that escapes that path is abandoned, never closed), and that a result delivered in time
  * is the caller's to close, never the helper's.
  *
  * Real short durations, as the rest of the suite does for IO timing (there is no cats-effect
  * testkit on the classpath).
  */
class BoundedWaitSpec extends AnyFlatSpec with Matchers:

  /** A stamped write's result: `commits` counts closes (a close COMMITs), `releases` the release
    * path a cancel takes (the inner reader closed, the write rolled back), `abandons` the helper's
    * `abandon`.
    */
  private final class Res(val id: Int):
    val commits  = new AtomicInteger(0)
    val releases = new AtomicInteger(0)
    val abandons = new AtomicInteger(0)
    def ends: Int = commits.get() + releases.get() + abandons.get()

  private def closeRes(r: Res): Unit   = r.commits.incrementAndGet(): Unit
  private def abandonRes(r: Res): Unit = r.abandons.incrementAndGet(): Unit

  private def bounded(
      run: IO[Either[String, Res]],
      limit: FiniteDuration,
      onDelivered: Res => IO[Unit] = _ => IO.unit
  ): IO[Either[String, Res]] =
    BoundedWait.closingLate(run, limit, "timeout", closeRes, onDelivered, Some(abandonRes))

  /** A node call shaped like `QuackHttpClient`'s: an uninterruptible wait for the node (`node`),
    * then the result handed over at a cancellation point that runs the release path when a cancel
    * is pending (`handOver`).
    */
  private def nodeCall(res: Res, node: IO[Unit]): IO[Either[String, Res]] =
    IO.uncancelable { poll =>
      node *> poll(IO.pure[Either[String, Res]](Right(res)))
        .onCancel(IO(res.releases.incrementAndGet()).void)
    }

  /** Waits (bounded) for `r` to reach `n` ends; the worker fiber finishes in the background. */
  private def awaitEnds(r: Res, n: Int): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while r.ends < n && System.nanoTime() < deadline do Thread.sleep(5)

  "BoundedWait.closingLate" should "return a result delivered in time without closing it" in:
    val res = new Res(1)
    bounded(IO.pure(Right(res)), 1.second).unsafeRunSync() shouldBe Right(res)
    Thread.sleep(50)
    res.ends shouldBe 0

  it should "pass a failure delivered in time through untouched" in:
    bounded(IO.pure(Left("denied")), 1.second).unsafeRunSync() shouldBe Left("denied")

  it should "re-raise an error raised in time" in:
    val boom = new RuntimeException("boom")
    bounded(IO.raiseError(boom), 1.second).attempt.unsafeRunSync() shouldBe Left(boom)

  it should "answer the timeout and roll a late write back, never committing it" in:
    val res = new Res(2)
    bounded(nodeCall(res, IO.sleep(200.millis)), 20.millis).unsafeRunSync() shouldBe
      Left("timeout")
    awaitEnds(res, 1)
    Thread.sleep(50)
    res.commits.get() shouldBe 0
    res.releases.get() shouldBe 1
    res.abandons.get() shouldBe 0

  it should "not wait on an uncancelable blocking node call past the limit" in:
    // QuackHttpClient.query is IO.blocking, which cancellation cannot interrupt: a plain
    // timeoutTo would sit on it until the node answered. The bounded wait must not.
    val release = new CountDownLatch(1)
    val res     = new Res(3)
    val t0      = System.nanoTime()
    val out     = bounded(nodeCall(res, IO.blocking(release.await())), 30.millis).unsafeRunSync()
    val waited  = (System.nanoTime() - t0).nanos
    out shouldBe Left("timeout")
    waited should be < 2.seconds
    res.ends shouldBe 0
    release.countDown()
    awaitEnds(res, 1)
    Thread.sleep(50)
    res.commits.get() shouldBe 0
    res.ends shouldBe 1

  it should "never commit a late result whose call has no cancellation point" in:
    // A run masked whole: its result can only reach the worker after the caller gave up, where it
    // is abandoned (or dropped by the cancel), never closed, which would COMMIT.
    val res = new Res(4)
    val run = IO.sleep(150.millis).as[Either[String, Res]](Right(res)).uncancelable
    bounded(run, 20.millis).unsafeRunSync() shouldBe Left("timeout")
    Thread.sleep(400)
    res.commits.get() shouldBe 0
    res.releases.get() shouldBe 0
    res.abandons.get() should be <= 1

  it should "never close a late failure and never surface a late error" in:
    bounded(IO.sleep(100.millis).as(Left("late")), 10.millis).unsafeRunSync() shouldBe
      Left("timeout")
    bounded(
      IO.sleep(100.millis) *> IO.raiseError(new RuntimeException("late boom")),
      10.millis
    ).unsafeRunSync() shouldBe Left("timeout")
    Thread.sleep(200)

  it should "roll the write back when the waiting caller is itself cancelled" in:
    val res     = new Res(5)
    val release = new CountDownLatch(1)
    val waiting = bounded(nodeCall(res, IO.blocking(release.await())), 10.seconds)
    // An outer timeout (a restore's restoreTimeoutSec) cancels the bounded wait mid-flight.
    val out = waiting.timeoutTo(30.millis, IO.pure(Left("outer"))).unsafeRunSync()
    out shouldBe Left("outer")
    release.countDown()
    awaitEnds(res, 1)
    Thread.sleep(50)
    res.commits.get() shouldBe 0
    res.ends shouldBe 1

  it should "end every result exactly once, committing only those returned, when it races" in:
    val seed = System.nanoTime()
    info(s"seed=$seed")
    val rnd = new scala.util.Random(seed)
    val all = (1 to 200).toList.map { i =>
      val res   = new Res(i)
      val delay = rnd.nextInt(6).millis
      val limit = rnd.nextInt(6).millis + 1.milli
      val out   = bounded(nodeCall(res, IO.sleep(delay)), limit).unsafeRunSync()
      // The caller owns a delivered result and closes it itself, as the routed executor's callers do.
      out.foreach(closeRes)
      (res, out.isRight)
    }
    all.foreach((r, _) => awaitEnds(r, 1))
    Thread.sleep(50)
    withClue(s"seed=$seed: ") {
      all.map(_._1.ends).distinct shouldBe List(1)
      all.foreach((r, returned) => r.commits.get() shouldBe (if returned then 1 else 0))
    }

  it should "show a result delivered in time to onDelivered before returning it, and a late one never" in:
    val seen = new java.util.concurrent.atomic.AtomicReference[Option[Res]](None)
    val res  = new Res(7)
    bounded(IO.pure(Right(res)), 1.second, r => IO(seen.set(Some(r)))).unsafeRunSync() shouldBe
      Right(res)
    seen.get shouldBe Some(res)
    val late = new Res(8)
    seen.set(None)
    bounded(nodeCall(late, IO.sleep(300.millis)), 50.millis, r => IO(seen.set(Some(r))))
      .unsafeRunSync() shouldBe Left("timeout")
    awaitEnds(late, 1)
    late.commits.get() shouldBe 0
    seen.get shouldBe None
