package ai.starlake.quack.edge.search

import ai.starlake.quack.edge.rls.{RowPolicyRewriter, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** qod_knn and qod_hybrid are not built in Phase 1 (a node answers any call as an unknown function,
  * search_adversarial_test), and their names still switch the gateway's search rules on (Task 5's
  * detector, spec 4.2 rules 3 and 10): the star rule refuses a star projection in a statement
  * calling one, and the row-policy rewriter keeps rowid in the wrap of every policy table of such a
  * statement (Task 17).
  */
class SearchUnbuiltNamesSpec extends AnyFlatSpec with Matchers:

  private val alice = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "*", "main", "pages", "cat = 3")
  private val withRows  = EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(rowPolicy))
  private val rls       = new RowPolicyRewriter(enabled = true)
  private val ctx       = SchemaContext(Some("lake"), Some("main"))

  /** As the router calls it: the search flag read from the client's own text
    * (`SearchFunctions.callsSearch`).
    */
  private def rewrite(sql: String): RowPolicyRewriter.Outcome =
    rls.rewrite(sql, StatementKind.Select, withRows, ctx, SearchFunctions.callsSearch(sql))

  private val calls = List(
    "qod_knn((SELECT rowid FROM lake.main.pages), 'lake.main.pages', 'embedding', [1.0, 2.0], 3) h",
    "qod_hybrid((SELECT rowid FROM lake.main.pages), 'lake.main.pages', ['body'], 'x', 'embedding', [1.0, 2.0]) h"
  )

  "the star rule" should "refuse a star projection in a statement calling qod_knn or qod_hybrid" in {
    for call <- calls do
      withClue(call) {
        SearchStarScreen
          .refusal(s"SELECT * FROM $call")
          .exists(_.startsWith(SearchStarScreen.Code)) shouldBe true
        SearchStarScreen
          .refusal(s"SELECT p.* FROM $call JOIN lake.main.pages p ON p.rowid = h.rowid")
          .exists(_.startsWith(SearchStarScreen.Code)) shouldBe true
        SearchStarScreen.refusal(s"SELECT h.rowid FROM $call") shouldBe None
      }
  }

  "the row-policy rewriter" should "keep rowid in every policy table's wrap of a statement calling qod_knn or qod_hybrid" in {
    for call <- calls do
      withClue(call) {
        rewrite(s"SELECT p.title FROM $call JOIN lake.main.pages p ON p.rowid = h.rowid") match
          case RowPolicyRewriter.Rewritten(out) =>
            out should include("(SELECT *, rowid FROM lake.main.pages WHERE (cat = 3)) AS pages")
            out should include("JOIN (SELECT *, rowid FROM lake.main.pages WHERE (cat = 3)) p")
            out should not include "(SELECT * FROM"
          case other => fail(s"expected Rewritten, got $other")
      }
  }

  it should "wrap as before a statement naming neither (the control)" in {
    rewrite("SELECT p.title FROM lake.main.pages p") match
      case RowPolicyRewriter.Rewritten(out) =>
        out should include("(SELECT * FROM lake.main.pages WHERE (cat = 3)) p")
        out should not include "rowid"
      case other => fail(s"expected Rewritten, got $other")
  }
