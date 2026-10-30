package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PoolLockerSpec extends AnyFlatSpec with Matchers:

  import TestPostgres.{pgHost, pgPass, pgPort, pgUser}
  private val url = s"jdbc:postgresql://$pgHost:$pgPort/postgres"

  "PgPoolLocker" should "serialize two holders of the same key" in {
    if !TestPostgres.reachable then cancel("local Postgres not reachable; skipping")
    val locker = new PgPoolLocker(url, pgUser, pgPass)
    val key    = ai.starlake.quack.model.PoolKey("acme", "default", "bi")
    val active = new java.util.concurrent.atomic.AtomicInteger(0)
    val peak   = new java.util.concurrent.atomic.AtomicInteger(0)
    def job    = locker.withLock(key) {
      IO.blocking {
        val n = active.incrementAndGet()
        peak.getAndUpdate(m => math.max(m, n))
        Thread.sleep(300)
        active.decrementAndGet()
      }
    }
    IO.both(job, job).unsafeRunSync()
    peak.get() shouldBe 1
  }

  it should "not serialize different keys" in {
    if !TestPostgres.reachable then cancel("local Postgres not reachable; skipping")
    val locker = new PgPoolLocker(url, pgUser, pgPass)
    val k1     = ai.starlake.quack.model.PoolKey("acme", "default", "bi")
    val k2     = ai.starlake.quack.model.PoolKey("globex", "default", "bi")
    val gate   = new java.util.concurrent.CountDownLatch(2)
    def job(k: ai.starlake.quack.model.PoolKey) = locker.withLock(k) {
      IO.blocking { gate.countDown(); gate.await(5, java.util.concurrent.TimeUnit.SECONDS) }
    }
    val (a, b) = IO.both(job(k1), job(k2)).unsafeRunSync()
    a shouldBe true; b shouldBe true
  }

  // -- PoolLocker.inProcess: one permit per pool, dropped once nobody holds or waits for it ------

  private val inKey = ai.starlake.quack.model.PoolKey("acme", "default", "bi")

  "PoolLocker.inProcess" should "keep no permit once its last holder left" in {
    val locker = PoolLocker.inProcess()
    locker.withLock(inKey)(IO.unit).unsafeRunSync()
    locker
      .withLock(ai.starlake.quack.model.PoolKey("acme", "default", "gone"))(IO.unit)
      .unsafeRunSync()
    locker.permitKeys shouldBe 0
  }

  it should "keep no permit after a holder failed" in {
    val locker = PoolLocker.inProcess()
    an[IllegalStateException] should be thrownBy
      locker.withLock(inKey)(IO.raiseError(new IllegalStateException("boom"))).unsafeRunSync()
    locker.permitKeys shouldBe 0
  }

  it should "serialize a holder, a waiter and a third caller arriving after the first left" in {
    // The permit must survive its first holder while a waiter is queued on it: a fresh permit
    // made for the third caller would let it run beside the waiter.
    val locker   = PoolLocker.inProcess()
    val active   = new java.util.concurrent.atomic.AtomicInteger(0)
    val peak     = new java.util.concurrent.atomic.AtomicInteger(0)
    val inFirst  = new java.util.concurrent.CountDownLatch(1)
    val release1 = new java.util.concurrent.CountDownLatch(1)
    val inSecond = new java.util.concurrent.CountDownLatch(1)
    val release2 = new java.util.concurrent.CountDownLatch(1)
    def body(
        entered: java.util.concurrent.CountDownLatch,
        release: java.util.concurrent.CountDownLatch
    ) =
      IO.blocking {
        val n = active.incrementAndGet()
        peak.getAndUpdate(m => math.max(m, n))
        entered.countDown()
        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        active.decrementAndGet()
        ()
      }
    val first = locker.withLock(inKey)(body(inFirst, release1)).start.unsafeRunSync()
    inFirst.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    val second = locker.withLock(inKey)(body(inSecond, release2)).start.unsafeRunSync()
    Thread.sleep(100)
    locker.permitKeys shouldBe 1
    release1.countDown()
    first.joinWithNever.unsafeRunSync()
    inSecond.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    val third = locker
      .withLock(inKey)(
        body(new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(0))
      )
      .start
      .unsafeRunSync()
    Thread.sleep(150)
    active.get() shouldBe 1
    release2.countDown()
    second.joinWithNever.unsafeRunSync()
    third.joinWithNever.unsafeRunSync()
    peak.get() shouldBe 1
    locker.permitKeys shouldBe 0
  }

  it should "release a cancelled waiter's hold on the permit" in {
    val locker  = PoolLocker.inProcess()
    val entered = new java.util.concurrent.CountDownLatch(1)
    val release = new java.util.concurrent.CountDownLatch(1)
    val holder  = locker
      .withLock(inKey)(IO.blocking {
        entered.countDown()
        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        ()
      })
      .start
      .unsafeRunSync()
    entered.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    val waiter = locker.withLock(inKey)(IO.unit).start.unsafeRunSync()
    Thread.sleep(100)
    waiter.cancel.unsafeRunSync()
    release.countDown()
    holder.joinWithNever.unsafeRunSync()
    locker.permitKeys shouldBe 0
  }
