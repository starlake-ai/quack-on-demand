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
