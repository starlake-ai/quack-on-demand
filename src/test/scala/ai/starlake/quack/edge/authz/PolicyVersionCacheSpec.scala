package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PolicyVersionCacheSpec extends AnyFlatSpec with Matchers:

  "PolicyVersionCache" should "answer 0 for a tenant it never loaded" in {
    new PolicyVersionCache(() => Map.empty).current("acme") shouldBe 0L
  }

  it should "answer what the last refresh loaded" in {
    var loaded = Map("acme" -> 3L, "globex" -> 1L)
    val cache  = new PolicyVersionCache(() => loaded)
    cache.refresh()
    (cache.current("acme"), cache.current("globex")) shouldBe ((3L, 1L))
    loaded = Map("acme" -> 4L, "globex" -> 1L)
    cache.refresh()
    cache.current("acme") shouldBe 4L
  }

  it should "never move a version backwards, nor forget a tenant" in {
    var loaded = Map("acme" -> 9L)
    val cache  = new PolicyVersionCache(() => loaded)
    cache.refresh()
    loaded = Map("acme" -> 2L)
    cache.refresh()
    cache.current("acme") shouldBe 9L
    loaded = Map.empty
    cache.refresh()
    cache.current("acme") shouldBe 9L
  }

  it should "follow a recreated tenant, whose row starts above every version issued" in {
    // Liquibase 0050 takes every version from one sequence, so a deleted tenant's id comes back
    // above the version the cache still holds for it, and the cache moves with it.
    var loaded = Map("acme" -> 9L)
    val cache  = new PolicyVersionCache(() => loaded)
    cache.refresh()
    loaded = Map.empty
    cache.refresh()
    loaded = Map("acme" -> 14L)
    cache.refresh()
    cache.current("acme") shouldBe 14L
  }

  it should "keep the last values when a refresh fails, without throwing" in {
    var fail  = false
    val cache = new PolicyVersionCache(() =>
      if fail then throw new java.sql.SQLException("connection refused") else Map("acme" -> 5L)
    )
    cache.refresh()
    fail = true
    noException should be thrownBy cache.refresh()
    cache.current("acme") shouldBe 5L
  }

  it should "name a failed refresh by class and SQLState, never by its message" in {
    val cache = new PolicyVersionCache(() =>
      throw new java.sql.SQLException("FATAL: policy-version-marker-detail", "57P01")
    )
    val (_, events) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture
      .logged(classOf[PolicyVersionCache].getName)(cache.refresh())
    events.map(_.getFormattedMessage) shouldBe List(
      "policy versions: refresh failed, keeping the last values: SQLException (SQLState 57P01)"
    )
  }
