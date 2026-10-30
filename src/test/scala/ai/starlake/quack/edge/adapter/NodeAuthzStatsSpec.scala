package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

class NodeAuthzStatsSpec extends AnyFlatSpec with Matchers:

  private val Row =
    "SELECT 1::BIGINT, 2::BIGINT, 3::BIGINT, 4::BIGINT, 5::BIGINT, 6::BIGINT, 7::BIGINT"

  private def stats(vs: Long*): NodeAuthzStats = vs.toList match
    case List(a, b, c, d, e, f, g) => NodeAuthzStats(a, b, c, d, e, f, g)
    case other                     => fail(s"seven counters expected, got $other")

  "NodeAuthzStats.sql" should "read every counter by name from the node's function" in:
    NodeAuthzStats.sql shouldBe
      "SELECT plan_cache_hits, plan_cache_misses, decide_calls, decide_unavailable, " +
      "decide_refused, local_refusals, screen_calls FROM system.main.qod_authz_stats()"

  "NodeAuthzStats.Counters" should "name the columns qod-node's qod_authz_stats() returns, in order" in:
    val source = Files.readString(Paths.get("native/qod-node/src/authz_stats.cpp"))
    val body   =
      source.substring(source.indexOf("AuthzStatsColumns()"), source.indexOf("AuthzStatsValues("))
    "\"([a-z_]+)\"".r.findAllMatchIn(body).map(_.group(1)).toList shouldBe NodeAuthzStats.Counters

  "NodeAuthzStats.fromReader" should "decode the one row of seven counters" in:
    NodeAuthzStats.fromReader(TestArrow.readerFor(Row)) shouldBe Some(stats(1, 2, 3, 4, 5, 6, 7))

  it should "return None on an empty result, another shape, a cell that is not a number" in:
    NodeAuthzStats.fromReader(TestArrow.emptyReader()) shouldBe None
    NodeAuthzStats.fromReader(TestArrow.oneRowReader()) shouldBe None
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(Row + ", 8::BIGINT")
    ) shouldBe None
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(
        "SELECT 'a', 2::BIGINT, 3::BIGINT, 4::BIGINT, 5::BIGINT, 6::BIGINT, 7::BIGINT"
      )
    ) shouldBe None

  it should "return None on a negative counter, which no node counts" in:
    NodeAuthzStats.fromReader(
      TestArrow.readerFor(
        "SELECT -1::BIGINT, 2::BIGINT, 3::BIGINT, 4::BIGINT, 5::BIGINT, 6::BIGINT, 7::BIGINT"
      )
    ) shouldBe None

  "NodeAuthzStats.delta" should "take a node's first reading whole" in:
    NodeAuthzStats.delta(None, stats(5, 1, 2, 0, 1, 3, 9)) shouldBe stats(5, 1, 2, 0, 1, 3, 9)

  it should "add each counter's increase since the last reading" in:
    NodeAuthzStats.delta(Some(stats(5, 1, 2, 0, 1, 3, 9)), stats(7, 1, 4, 1, 1, 3, 20)) shouldBe
      stats(2, 0, 2, 1, 0, 0, 11)
    NodeAuthzStats.delta(Some(stats(5, 1, 2, 0, 1, 3, 9)), stats(5, 1, 2, 0, 1, 3, 9)) shouldBe
      NodeAuthzStats.Zero

  it should "take the reading whole when any counter went down: the node restarted" in:
    NodeAuthzStats.delta(
      Some(stats(50, 10, 20, 0, 1, 3, 90)),
      stats(60, 2, 21, 0, 1, 3, 91)
    ) shouldBe
      stats(60, 2, 21, 0, 1, 3, 91)

  "NodeAuthzStatsTracker" should "total the increases, across a node restart too" in:
    val t = new NodeAuthzStatsTracker
    t.total("n1") shouldBe None
    t.update("n1", stats(5, 1, 2, 0, 1, 3, 9))
    t.total("n1") shouldBe Some(stats(5, 1, 2, 0, 1, 3, 9))
    t.update("n1", stats(8, 1, 3, 0, 1, 4, 15))
    t.total("n1") shouldBe Some(stats(8, 1, 3, 0, 1, 4, 15))
    t.update("n1", stats(1, 1, 1, 0, 0, 0, 2))
    t.total("n1") shouldBe Some(stats(9, 2, 4, 0, 1, 4, 17))

  it should "forget the nodes no longer live" in:
    val t = new NodeAuthzStatsTracker
    t.update("n1", stats(1, 0, 0, 0, 0, 0, 0))
    t.update("n2", stats(2, 0, 0, 0, 0, 0, 0))
    t.retain(Set("n2"))
    t.total("n1") shouldBe None
    t.total("n2").map(_.planCacheHits) shouldBe Some(2L)
