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

  it should "return empty on statements over the size guard without parsing" in:
    val huge = "SELECT * FROM t WHERE x IN (" + "1," * 60000 + "1)"
    huge.length should be > RoutingRefs.MaxSqlLength
    RoutingRefs.extract(huge, cfg) shouldBe RoutingRefs.empty

  "RoutingRefsCache" should "memoize by sql text and config fingerprint" in:
    val cache = new RoutingRefsCache(maxEntries = 2)
    val a     = cache.extract("SELECT * FROM customer", cfg)
    val b     = cache.extract("SELECT * FROM customer", cfg)
    (a eq b) shouldBe true
    val otherCfg = Config.forDuckDB(Some("other"), Some("main"))
    cache.extract("SELECT * FROM customer", otherCfg).reads shouldBe Set("other.main.customer")

  it should "evict beyond maxEntries and recompute the evicted entry" in:
    val cache = new RoutingRefsCache(maxEntries = 2)
    val first = cache.extract("SELECT * FROM a", cfg)
    // b then c push a out of the 2-entry LRU (a was least-recently-used).
    cache.extract("SELECT * FROM b", cfg)
    val cResident = cache.extract("SELECT * FROM c", cfg)
    // a was evicted: the next lookup parses afresh, so it is a different instance.
    val aReloaded = cache.extract("SELECT * FROM a", cfg)
    (aReloaded eq first) shouldBe false
    aReloaded.reads shouldBe Set("tpch1.main.a")
    // c is still resident: a repeat lookup returns the very same instance.
    (cache.extract("SELECT * FROM c", cfg) eq cResident) shouldBe true
