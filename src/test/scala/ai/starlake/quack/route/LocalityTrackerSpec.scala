package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalityTrackerSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  "LocalityTracker" should "count first-touch tables as new" in:
    val t = new LocalityTracker()
    t.observe(poolKey, Set("db.main.a", "db.main.b"), "n1") shouldBe LocalityObservation(2, 0, 0, 0)

  it should "count repeats and stays when the same node serves again" in:
    val t = new LocalityTracker()
    t.observe(poolKey, Set("db.main.a"), "n1")
    t.observe(poolKey, Set("db.main.a"), "n1") shouldBe LocalityObservation(0, 1, 1, 0)

  it should "count switches when a different node serves a seen table" in:
    val t = new LocalityTracker()
    t.observe(poolKey, Set("db.main.a"), "n1")
    t.observe(poolKey, Set("db.main.a"), "n2") shouldBe LocalityObservation(0, 1, 0, 1)

  it should "track pools independently and clear per pool" in:
    val t     = new LocalityTracker()
    val other = PoolKey("acme", "acme_default", "bi")
    t.observe(poolKey, Set("db.main.a"), "n1")
    t.observe(other, Set("db.main.a"), "n9") shouldBe LocalityObservation(1, 0, 0, 0)
    t.clear(poolKey)
    t.observe(poolKey, Set("db.main.a"), "n1") shouldBe LocalityObservation(1, 0, 0, 0)

  it should "evict oldest tables beyond the per-pool bound" in:
    val t = new LocalityTracker(maxTablesPerPool = 2)
    t.observe(poolKey, Set("db.main.a"), "n1")
    t.observe(poolKey, Set("db.main.b"), "n1")
    t.observe(poolKey, Set("db.main.c"), "n1")
    t.observe(poolKey, Set("db.main.a"), "n1") shouldBe LocalityObservation(1, 0, 0, 0)
