package ai.starlake.quack.observability.metrics

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.authz.{
  DecideContext,
  DecidePurpose,
  DryRunOutcome,
  DryRunResults,
  IssuedHandles,
  MintedHandles
}
import ai.starlake.quack.edge.opa.{Decision, OpaCacheKey, OpaDecisionCache}
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Task 11 addendum 6: every bounded manager cache reports its size, hits, misses and evictions
  * under `cache=<name>` (Micrometer's Caffeine binder), and its configured capacity as
  * `qod_cache_capacity{cache=<name>}`, so a dashboard can show how full it is.
  */
class CacheMetricsSpec extends AnyFlatSpec with Matchers:

  private val Ctx = DecideContext(
    EffectiveSet(RbacUser("u-1", Some("acme"), "alice", "user"), Nil, Nil, Nil, Nil),
    PoolKey("acme", "acme_lake", "bi"),
    None,
    DecidePurpose.Live
  )

  private def size(reg: SimpleMeterRegistry, cache: String): Double =
    reg.find("cache.size").tag("cache", cache).gauge().value()

  private def gets(reg: SimpleMeterRegistry, cache: String, result: String): Double =
    reg.find("cache.gets").tags("cache", cache, "result", result).functionCounter().count()

  private def evictions(reg: SimpleMeterRegistry, cache: String): Double =
    reg.find("cache.evictions").tag("cache", cache).functionCounter().count()

  private def capacity(reg: SimpleMeterRegistry, cache: String): Double =
    reg.find("qod_cache_capacity").tag("cache", cache).gauge().value()

  "MintedHandles" should "report its size, hits, misses, evictions and capacity" in {
    val reg = new SimpleMeterRegistry
    val m   = new MintedHandles(maxEntries = 2)
    m.bindMetrics(reg)
    capacity(reg, "qod_minted_handles") shouldBe 2.0
    evictions(reg, "qod_minted_handles") shouldBe 0.0
    (1 to 10).foreach(i => m.put(s"n-$i", Ctx, "0" * 64, Long.MaxValue / 2))
    m.size should be <= 2L
    size(reg, "qod_minted_handles") should be <= 2.0
    evictions(reg, "qod_minted_handles") should be >= 8.0
    m.get("absent") shouldBe None
    gets(reg, "qod_minted_handles", "miss") shouldBe 1.0
  }

  "IssuedHandles" should "report its size, hits, misses, evictions and capacity" in {
    val reg = new SimpleMeterRegistry
    val r   = new IssuedHandles(maxEntries = 2)
    r.bindMetrics(reg)
    capacity(reg, "qod_issued_handles") shouldBe 2.0
    r.put("n-0", "node-1", "u-1", Long.MaxValue / 2)
    r.get("n-0").isDefined shouldBe true
    gets(reg, "qod_issued_handles", "hit") shouldBe 1.0
    (1 to 10).foreach(i => r.put(s"n-$i", "node-1", "u-1", Long.MaxValue / 2))
    r.size should be <= 2L
    size(reg, "qod_issued_handles") should be <= 2.0
    evictions(reg, "qod_issued_handles") should be >= 9.0
  }

  "DryRunResults" should "report its size, evictions and capacity" in {
    val reg = new SimpleMeterRegistry
    val r   = new DryRunResults(maxEntries = 2)
    r.bindMetrics(reg)
    capacity(reg, "qod_dry_run_results") shouldBe 2.0
    (1 to 10).foreach(i =>
      r.put(s"n-$i", DryRunOutcome("statement", Json.obj(), "allow", None, None))
    )
    r.size should be <= 2L
    size(reg, "qod_dry_run_results") should be <= 2.0
    evictions(reg, "qod_dry_run_results") should be >= 8.0
  }

  it should "report the default capacity" in {
    val reg = new SimpleMeterRegistry
    new DryRunResults().bindMetrics(reg)
    capacity(reg, "qod_dry_run_results") shouldBe DryRunResults.MaxEntries.toDouble
  }

  "OpaDecisionCache" should "report its hits, misses, size and capacity" in {
    val reg = new SimpleMeterRegistry
    val c   = new OpaDecisionCache(ttlSec = 60)
    c.bindMetrics(reg)
    capacity(reg, "qod_opa_decisions") shouldBe OpaDecisionCache.MaxEntries.toDouble
    val key = OpaCacheKey(
      "t",
      "s",
      "u",
      "u",
      Nil,
      Nil,
      Nil,
      "t/db/p",
      "statement",
      "READ",
      "e",
      Nil,
      Nil,
      ""
    )
    c.getOrCompute(key)(Decision.Allow(None))
    c.getOrCompute(key)(Decision.Allow(None))
    gets(reg, "qod_opa_decisions", "miss") shouldBe 1.0
    gets(reg, "qod_opa_decisions", "hit") shouldBe 1.0
    size(reg, "qod_opa_decisions") shouldBe 1.0
  }

  it should "report nothing while caching is off (ttlSec 0): there is no cache" in {
    val reg = new SimpleMeterRegistry
    new OpaDecisionCache(ttlSec = 0).bindMetrics(reg)
    reg.find("cache.size").tag("cache", "qod_opa_decisions").gauge() shouldBe null
    reg.find("qod_cache_capacity").tag("cache", "qod_opa_decisions").gauge() shouldBe null
  }

  "PoolSupervisor's effective-set cache" should "report its hits, misses, size and capacity" in {
    val reg   = new SimpleMeterRegistry
    val store = new InMemoryControlPlaneStore
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.bindEffectiveSetCacheMetrics(reg)
    capacity(reg, "qod_effective_sets") shouldBe PoolSupervisor.EffectiveCacheMaxEntries.toDouble
    sup.restore()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val userId = store.upsertUserWithHash(Some("acme"), "alice", "x", "user")
    sup.effectiveSetForUser(userId).isDefined shouldBe true
    sup.effectiveSetForUser(userId).isDefined shouldBe true
    gets(reg, "qod_effective_sets", "miss") shouldBe 1.0
    gets(reg, "qod_effective_sets", "hit") shouldBe 1.0
    size(reg, "qod_effective_sets") shouldBe 1.0
  }

  // The handle caches' bound is QOD_AUTHZ_HANDLE_CONTEXTS_MAX, never a constructor default: a site
  // that dropped it would run a bound the operator did not choose. Each refusal is paired with the
  // same construction given the bound, so it cannot pass for another reason.
  "the handle caches" should "need their bound" in {
    "new MintedHandles()" shouldNot typeCheck
    "new MintedHandles(maxEntries = 1000L)" should compile
    "new IssuedHandles()" shouldNot typeCheck
    "new IssuedHandles(maxEntries = 1000L)" should compile
  }
