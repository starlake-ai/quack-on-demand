package ai.starlake.quack.edge.search

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The search call detector (spec 4.2 rule 3). A missed real call fails closed (the policy holder's
  * `rowid` read does not bind), so these cases pin the detector's reading for its later consumers
  * rather than a security property.
  */
class SearchFunctionsSpec extends AnyFlatSpec with Matchers:

  import SearchFunctions.callsSearch

  "callsSearch" should "not trigger on a name in a comment or a quoted identifier with no paren" in {
    callsSearch("SELECT 1 -- qod_search(x)") shouldBe false
    callsSearch("SELECT 1 /* qod_search(x) */ FROM t") shouldBe false
    callsSearch("SELECT \"qod_search\" FROM t") shouldBe false
    callsSearch("SELECT qod_search FROM t") shouldBe false
    callsSearch("SELECT qod_searcher(1) FROM t") shouldBe false
    callsSearch("SELECT qod_snippet(body, 'x') FROM t") shouldBe false
  }

  it should "trigger on each of the four names" in {
    for name <- List("qod_search", "qod_search_matches", "qod_knn", "qod_hybrid") do
      callsSearch(s"SELECT * FROM $name((SELECT rowid FROM t), 't', ['b'], 'x') h") shouldBe true
  }

  it should "trigger on quoted, upper-case and qualified calls" in {
    callsSearch(
      "SELECT * FROM \"qod_search\"((SELECT rowid FROM t), 't', ['b'], 'x')"
    ) shouldBe true
    callsSearch("SELECT * FROM QOD_SEARCH((SELECT rowid FROM t), 't', ['b'], 'x')") shouldBe true
    callsSearch(
      "SELECT * FROM system.main.qod_search((SELECT rowid FROM t), 't', ['b'], 'x')"
    ) shouldBe true
  }

  it should "trigger with whitespace or a comment before the paren" in {
    callsSearch("SELECT * FROM qod_search  ((SELECT rowid FROM t), 't', ['b'], 'x')") shouldBe true
    callsSearch(
      "SELECT * FROM qod_search /* c */ ((SELECT rowid FROM t), 't', ['b'], 'x')"
    ) shouldBe true
  }

  it should "over-include a name in a literal, a CTE column list and an alias column list" in {
    callsSearch("SELECT 'qod_search(' AS x FROM t") shouldBe true
    callsSearch("WITH qod_search(a) AS (SELECT 1) SELECT a FROM qod_search") shouldBe true
    callsSearch("SELECT a FROM t AS qod_search(a, b)") shouldBe true
  }

  it should "count a scan cut short at its nesting bound as a call" in {
    // Ten levels of string literal, each the next one's body: past FunctionCalls.MaxDepth.
    val nested = (1 to 10).foldLeft("SELECT 1") { (inner, _) =>
      "SELECT '" + inner.replace("'", "''") + "'"
    }
    ai.starlake.quack.edge.sql.FunctionCalls.scan(nested).truncated shouldBe true
    callsSearch(nested) shouldBe true
  }
