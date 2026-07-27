package ai.starlake.quack.route

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoutingRefsSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("tpch1"), Some("main"))

  "RoutingRefs.extract" should "qualify unqualified reads against the defaults" in:
    val refs = RoutingRefs.extract("SELECT * FROM customer", cfg)
    refs.reads shouldBe Set("tpch1.main.customer")
    refs.writes shouldBe empty

  it should "split write targets from read sources" in:
    val refs = RoutingRefs.extract("INSERT INTO orders SELECT * FROM staging", cfg)
    refs.writes shouldBe Set("tpch1.main.orders")
    refs.reads shouldBe Set("tpch1.main.staging")

  it should "collapse DDL targets into writes" in:
    val refs = RoutingRefs.extract("DROP TABLE old_facts", cfg)
    refs.writes shouldBe Set("tpch1.main.old_facts")

  it should "return empty on parse errors (fail open for routing)" in:
    RoutingRefs.extract("SELEKT garbage FROM", cfg) shouldBe RoutingRefs.empty

  it should "return empty on control-flow statements" in:
    RoutingRefs.extract("COMMIT", cfg) shouldBe RoutingRefs.empty

  it should "strip time-travel AT clauses via the parser" in:
    val refs = RoutingRefs.extract("SELECT * FROM customer AT (VERSION => 3)", cfg)
    refs.reads shouldBe Set("tpch1.main.customer")

  "RoutingRefsCache" should "memoize by sql text and config fingerprint" in:
    val cache = new RoutingRefsCache(maxEntries = 2)
    val a     = cache.extract("SELECT * FROM customer", cfg)
    val b     = cache.extract("SELECT * FROM customer", cfg)
    (a eq b) shouldBe true
    val otherCfg = Config.forDuckDB(Some("other"), Some("main"))
    cache.extract("SELECT * FROM customer", otherCfg).reads shouldBe Set("other.main.customer")

  it should "evict beyond maxEntries without failing" in:
    val cache = new RoutingRefsCache(maxEntries = 2)
    cache.extract("SELECT * FROM a", cfg)
    cache.extract("SELECT * FROM b", cfg)
    cache.extract("SELECT * FROM c", cfg)
    cache.extract("SELECT * FROM a", cfg).reads shouldBe Set("tpch1.main.a")
