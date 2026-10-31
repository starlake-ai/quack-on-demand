package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

class NodeAuthzStatsSpec extends AnyFlatSpec with Matchers:

  private val Row =
    "SELECT 1::BIGINT, 2::BIGINT, 3::BIGINT, 4::BIGINT, 5::BIGINT, 6::BIGINT, 7::BIGINT, " +
      "8::BIGINT, 9::BIGINT, 10::BIGINT"

  /** The seven counters of Task 8 and the verdict cache's evictions, then the two sizes. */
  private def stats(vs: Long*): NodeAuthzStats = vs.toList match
    case List(a, b, c, d, e, f, g, ev) => NodeAuthzStats(a, b, c, d, e, f, g, 0L, ev, 0L)
    case other                         => fail(s"eight counters expected, got $other")

  private def sized(s: NodeAuthzStats, verdicts: Long, credentialSets: Long): NodeAuthzStats =
    s.copy(verdictCacheSize = verdicts, credentialSetsSize = credentialSets)

  "NodeAuthzStats.sql" should "read every column by name from the node's function" in:
    NodeAuthzStats.sql shouldBe
      "SELECT plan_cache_hits, plan_cache_misses, decide_calls, decide_unavailable, " +
      "decide_refused, local_refusals, screen_calls, verdict_cache_size, verdict_cache_evictions, " +
      "credential_sets_size FROM system.main.qod_authz_stats()"

  "NodeAuthzStats.Columns" should "name the columns qod-node's qod_authz_stats() returns, in order" in:
    val source = Files.readString(Paths.get("native/qod-node/src/authz_stats.cpp"))
    val body   =
      source.substring(source.indexOf("AuthzStatsColumns()"), source.indexOf("AuthzStatsValues("))
    "\"([a-z_]+)\"".r.findAllMatchIn(body).map(_.group(1)).toList shouldBe NodeAuthzStats.Columns

  it should "keep Task 8's seven counters first, in their order, and append the cache columns" in:
    NodeAuthzStats.Columns shouldBe List(
      "plan_cache_hits",
      "plan_cache_misses",
      "decide_calls",
      "decide_unavailable",
      "decide_refused",
      "local_refusals",
      "screen_calls",
      "verdict_cache_size",
      "verdict_cache_evictions",
      "credential_sets_size"
    )
    NodeAuthzStats.Counters shouldBe NodeAuthzStats.Columns.take(7) :+ "verdict_cache_evictions"
    NodeAuthzStats.Sizes shouldBe List("verdict_cache_size", "credential_sets_size")

  "NodeAuthzStats.fromReader" should "decode the one row of ten columns" in:
    NodeAuthzStats.fromReader(TestArrow.readerFor(Row)) shouldBe
      Some(NodeAuthzStats(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

  it should "return None on an empty result, another shape, a cell that is not a number" in:
    NodeAuthzStats.fromReader(TestArrow.emptyReader()) shouldBe None
    NodeAuthzStats.fromReader(TestArrow.oneRowReader()) shouldBe None
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(Row + ", 11::BIGINT")
    ) shouldBe None
    // A node from before the cache columns: seven counters only.
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(
        "SELECT 1::BIGINT, 2::BIGINT, 3::BIGINT, 4::BIGINT, 5::BIGINT, 6::BIGINT, 7::BIGINT"
      )
    ) shouldBe None
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(Row.replace("SELECT 1::BIGINT", "SELECT 'a'"))
    ) shouldBe None

  it should "return None on a negative counter, which no node counts" in:
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(Row.replace("SELECT 1::BIGINT", "SELECT -1::BIGINT"))
    ) shouldBe None
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(Row.replace("10::BIGINT", "-10::BIGINT"))
    ) shouldBe None

  "NodeAuthzStats.delta" should "take a node's first reading whole" in:
    NodeAuthzStats.delta(None, stats(5, 1, 2, 0, 1, 3, 9, 4)) shouldBe stats(5, 1, 2, 0, 1, 3, 9, 4)

  it should "add each counter's increase since the last reading" in:
    NodeAuthzStats.delta(
      Some(stats(5, 1, 2, 0, 1, 3, 9, 4)),
      stats(7, 1, 4, 1, 1, 3, 20, 6)
    ) shouldBe
      stats(2, 0, 2, 1, 0, 0, 11, 2)
    NodeAuthzStats.delta(
      Some(stats(5, 1, 2, 0, 1, 3, 9, 4)),
      stats(5, 1, 2, 0, 1, 3, 9, 4)
    ) shouldBe
      NodeAuthzStats.Zero

  it should "take the reading whole when any counter went down: the node restarted" in:
    NodeAuthzStats.delta(
      Some(stats(50, 10, 20, 0, 1, 3, 90, 7)),
      stats(60, 2, 21, 0, 1, 3, 91, 7)
    ) shouldBe
      stats(60, 2, 21, 0, 1, 3, 91, 7)
    NodeAuthzStats.delta(
      Some(stats(50, 10, 20, 0, 1, 3, 90, 7)),
      stats(60, 10, 21, 0, 1, 3, 91, 1)
    ) shouldBe
      stats(60, 10, 21, 0, 1, 3, 91, 1)

  it should "carry the current reading's sizes, which are no counters: a size may go down" in:
    NodeAuthzStats.delta(
      Some(sized(stats(5, 1, 2, 0, 1, 3, 9, 4), 100, 7)),
      sized(stats(7, 1, 4, 1, 1, 3, 20, 6), 40, 2)
    ) shouldBe
      sized(stats(2, 0, 2, 1, 0, 0, 11, 2), 40, 2)

  "NodeAuthzStatsTracker" should "total the increases, across a node restart too" in:
    val t = new NodeAuthzStatsTracker
    t.total("n1") shouldBe None
    t.update("n1", stats(5, 1, 2, 0, 1, 3, 9, 3))
    t.total("n1") shouldBe Some(stats(5, 1, 2, 0, 1, 3, 9, 3))
    t.update("n1", stats(8, 1, 3, 0, 1, 4, 15, 5))
    t.total("n1") shouldBe Some(stats(8, 1, 3, 0, 1, 4, 15, 5))
    t.update("n1", stats(1, 1, 1, 0, 0, 0, 2, 1))
    t.total("n1") shouldBe Some(stats(9, 2, 4, 0, 1, 4, 17, 6))

  it should "hold the latest reading's sizes beside the totals" in:
    val t = new NodeAuthzStatsTracker
    t.update("n1", sized(stats(5, 1, 2, 0, 1, 3, 9, 3), 100, 7))
    t.update("n1", sized(stats(8, 1, 3, 0, 1, 4, 15, 5), 40, 2))
    t.total("n1") shouldBe Some(sized(stats(8, 1, 3, 0, 1, 4, 15, 5), 40, 2))
    // A restart: the counters add the new reading whole, the sizes are the new reading's.
    t.update("n1", sized(stats(1, 1, 1, 0, 0, 0, 2, 0), 3, 0))
    t.total("n1") shouldBe Some(sized(stats(9, 2, 4, 0, 1, 4, 17, 5), 3, 0))

  it should "forget the nodes no longer live" in:
    val t = new NodeAuthzStatsTracker
    t.update("n1", stats(1, 0, 0, 0, 0, 0, 0, 0))
    t.update("n2", stats(2, 0, 0, 0, 0, 0, 0, 0))
    t.retain(Set("n2"))
    t.total("n1") shouldBe None
    t.total("n2").map(_.planCacheHits) shouldBe Some(2L)
