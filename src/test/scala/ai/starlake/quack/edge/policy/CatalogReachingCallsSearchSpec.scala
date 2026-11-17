package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.search.SearchFunctions
import ai.starlake.quack.edge.sql.FunctionCalls
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Decision S1 (search spec 4.3, accepted 2026-11-13): the four search functions name their table
  * by a string, so they join the table-naming family of [[CatalogReachingCalls]], and S1 excepts
  * them, and only them, for principals holding column or row policies.
  */
class CatalogReachingCallsSearchSpec extends AnyFlatSpec with Matchers:

  private def searchCall(name: String): String =
    s"SELECT h.rowid FROM $name((SELECT rowid FROM t), 't', ['b'], 'x') h"

  "the search functions" should "be the family's table-naming calls and S1's exception" in {
    CatalogReachingCalls.SearchTableNaming shouldBe SearchFunctions.Names
    CatalogReachingCalls.S1Exception shouldBe CatalogReachingCalls.SearchTableNaming
    CatalogReachingCalls.SearchTableNaming shouldBe
      Set("qod_search", "qod_search_matches", "qod_knn", "qod_hybrid")
  }

  they should "be admitted by find under S1, and name a table by a string" in {
    for name <- SearchFunctions.Names do
      withClue(name) {
        CatalogReachingCalls.find(searchCall(name)) shouldBe None
        CatalogReachingCalls.namesTableByString(name) shouldBe true
      }
  }

  they should "be in the family, so a reader of it sees them" in {
    for name <- SearchFunctions.Names do
      withClue(name)(CatalogReachingCalls.ForTests.inFamily(name) shouldBe true)
  }

  "namesTableByString" should "hold for nothing else" in {
    val others =
      CatalogReachingCalls.ForTests.fixed ++ CatalogReachingCalls.ForTests.nodeEscaping ++
        CatalogReachingCalls.DuckLakeSchemaMacros ++
        Set("ducklake_table_insertions", "qod_snippet", "read_parquet", "qod_search_build_segment")
    for name <- others do
      withClue(name)(CatalogReachingCalls.namesTableByString(name) shouldBe false)
  }

  // The exception reaches no other call: every other listed name keeps its refusal beside a search
  // call, whichever comes first in the statement.
  "a statement calling qod_search and another listed call" should "be refused naming that call" in {
    val listed =
      CatalogReachingCalls.ForTests.fixed ++ CatalogReachingCalls.ForTests.nodeEscaping ++
        Set("ducklake_table_insertions", "ducklake_list_files", "ducklake_snapshots") ++
        CatalogReachingCalls.DuckLakeSchemaMacros
    listed should not be empty
    for name <- listed do
      val after  = s"${searchCall("qod_search")} JOIN $name('a', 'b') z ON true"
      val before = s"SELECT * FROM $name('a', 'b') z, qod_search((SELECT rowid FROM t), 't', " +
        "['b'], 'x') h"
      withClue(name) {
        CatalogReachingCalls.find(after) shouldBe Some(name)
        CatalogReachingCalls.find(before) shouldBe Some(name)
      }
  }

  "a search name in a literal beside query_table" should "be refused naming query_table" in {
    CatalogReachingCalls.find("SELECT 'qod_search(' AS x FROM query_table('x')") shouldBe
      Some("query_table")
  }

  "a search call with a literal nested too deeply" should "still be refused as too deep" in {
    def nest(n: Int): String =
      if n == 0 then "SELECT 1"
      else "SELECT '" + nest(n - 1).replace("'", "''") + "'"
    val deep = searchCall("qod_search") + " WHERE 1 = (" + nest(FunctionCalls.MaxDepth + 1) + ")"
    FunctionCalls.scan(deep).truncated shouldBe true
    val found = CatalogReachingCalls.find(deep)
    found shouldBe defined
    CatalogReachingCalls.denyReason(found.get) should include("too deeply")
  }
