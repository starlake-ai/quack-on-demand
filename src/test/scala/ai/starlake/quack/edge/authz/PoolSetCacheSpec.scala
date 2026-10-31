package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** The per-pool set cache behind the read-only and attached catalogs (Task 11 addendum 7): kept for
  * its TTL, dropped at once by a write, and never keeping a computation that straddled a drop.
  */
class PoolSetCacheSpec extends AnyFlatSpec with Matchers:

  private val A = PoolKey("acme", "acme_db", "bi")
  private val B = PoolKey("acme", "acme_db", "etl")
  private val C = PoolKey("acme", "other_db", "bi")

  "PoolSetCache" should "keep a pool's set for its TTL, then compute it again" in {
    val now   = new java.util.concurrent.atomic.AtomicLong(0L)
    val reads = new AtomicInteger(0)
    val cache = new PoolSetCache(() => now.get(), ttlMs = 1000L)
    def get   = cache.get(A) { reads.incrementAndGet(); Set("x") }
    get shouldBe Set("x")
    now.set(999L)
    get
    reads.get shouldBe 1
    now.set(1000L)
    get
    reads.get shouldBe 2
  }

  it should "drop the pools a write names at once, and keep the others" in {
    val value           = new AtomicReference(Set("old"))
    val reads           = new AtomicInteger(0)
    val cache           = new PoolSetCache(() => 0L, ttlMs = 60_000L)
    def get(k: PoolKey) = cache.get(k) { reads.incrementAndGet(); value.get() }
    List(A, B, C).foreach(get)
    reads.get shouldBe 3
    value.set(Set("new"))
    cache.invalidate(k => k.tenantDb == "acme_db")
    get(A) shouldBe Set("new")
    get(B) shouldBe Set("new")
    get(C) shouldBe Set("old")
    reads.get shouldBe 5
  }

  it should "drop every pool on invalidateAll" in {
    val value = new AtomicReference(Set("old"))
    val cache = new PoolSetCache(() => 0L, ttlMs = 60_000L)
    List(A, C).foreach(k => cache.get(k)(value.get()))
    value.set(Set("new"))
    cache.invalidateAll()
    cache.get(A)(value.get()) shouldBe Set("new")
    cache.get(C)(value.get()) shouldBe Set("new")
  }

  it should "not keep a computation that read the state before a drop and finished after it" in {
    val value   = new AtomicReference(Set("old"))
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val cache   = new PoolSetCache(() => 0L, ttlMs = 60_000L)
    val stale   = new Thread(() =>
      cache.get(A) {
        val read = value.get()
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
        read
      }
      ()
    )
    stale.start()
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    value.set(Set("new"))
    cache.invalidate(_ == A)
    release.countDown()
    stale.join(10_000L)
    cache.get(A)(value.get()) shouldBe Set("new")
  }

  it should "keep nothing when the computation throws" in {
    val cache = new PoolSetCache(() => 0L, ttlMs = 60_000L)
    intercept[IllegalStateException](cache.get(A)(throw new IllegalStateException("down")))
    cache.get(A)(Set("x")) shouldBe Set("x")
  }
