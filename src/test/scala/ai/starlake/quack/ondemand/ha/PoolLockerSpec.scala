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
