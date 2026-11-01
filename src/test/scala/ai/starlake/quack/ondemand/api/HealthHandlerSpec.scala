package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

class HealthHandlerSpec extends AnyFlatSpec with Matchers:

  private def sup(): PoolSupervisor =
    new PoolSupervisor(
      new StubQuackBackend(tokenFor = StubQuackBackend.PerNodeToken),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )

  "HealthHandler.health (liveness)" should "return ok when the database is reachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => true)
    h.health.unsafeRunSync().isRight shouldBe true
  }

  it should "return ok even when the database is unreachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => false)
    h.health.unsafeRunSync().isRight shouldBe true
  }

  "HealthHandler.ready (readiness)" should "return ok when the database is reachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => true)
    h.ready.unsafeRunSync().isRight shouldBe true
  }

  it should "return 503 when the database is unreachable" in {
    val h = new HealthHandler(sup(), dbHealthy = () => false)
    h.ready.unsafeRunSync() shouldBe Left(StatusCode.ServiceUnavailable)
  }

  it should "check the database off the compute threads" in {
    val thread = new AtomicReference[String]("")
    val h      = new HealthHandler(
      sup(),
      dbHealthy = () => {
        thread.set(Thread.currentThread().getName)
        true
      }
    )
    h.ready.unsafeRunSync().isRight shouldBe true
    thread.get should not be empty
    thread.get should not fullyMatch regex("io-compute-\\d+")
  }

  "CachedDbHealth" should "ping at most once per interval" in {
    var now   = 0L
    val pings = new AtomicInteger
    val h     = new CachedDbHealth(
      () => {
        pings.incrementAndGet()
        true
      },
      ttlNanos = 100L,
      nanoTime = () => now
    )
    h.healthy() shouldBe true
    now = 99L
    h.healthy() shouldBe true
    pings.get shouldBe 1
    now = 100L
    h.healthy() shouldBe true
    pings.get shouldBe 2
  }

  it should "run one ping at a time, answering the others at once with the last answer" in {
    var now     = 0L
    var answer  = true
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val pings   = new AtomicInteger
    val h       = new CachedDbHealth(
      () => {
        if pings.incrementAndGet() == 2 then
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        answer
      },
      ttlNanos = 100L,
      nanoTime = () => now
    )
    h.healthy() shouldBe true
    now = 100L
    answer = false
    val slow = new Thread(() => { h.healthy(); () })
    slow.start()
    entered.await()
    // The second ping is stalled: the others get the last answer without waiting for it.
    h.healthy() shouldBe true
    pings.get shouldBe 2
    release.countDown()
    slow.join()
    h.healthy() shouldBe false
    pings.get shouldBe 2
  }

  it should "answer false until the first ping answered" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val h       = new CachedDbHealth(() => {
      entered.countDown()
      release.await(10, TimeUnit.SECONDS)
      true
    })
    val first = new Thread(() => { h.healthy(); () })
    first.start()
    entered.await()
    h.healthy() shouldBe false
    release.countDown()
    first.join()
    h.healthy() shouldBe true
  }

  it should "ping again after a ping that threw" in {
    val pings = new AtomicInteger
    val h     = new CachedDbHealth(() => {
      if pings.incrementAndGet() == 1 then throw new IllegalStateException("boom")
      true
    })
    an[IllegalStateException] should be thrownBy h.healthy()
    h.healthy() shouldBe true
  }
