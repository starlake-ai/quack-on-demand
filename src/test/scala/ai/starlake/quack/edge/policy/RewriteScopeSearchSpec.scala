package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.Verb
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Search spec ruling 3 (OWNER RULING 2026-11-16, change 59, P12): [[RewriteScope]] reads each
  * search call's `table` argument into the statement's scope, so a search statement's scope is a
  * reach and the router compares its rewrite with it, never passing the rewrite vacuously.
  */
class RewriteScopeSearchSpec extends AnyFlatSpec with Matchers:

  private val cfg    = Config.forDuckDB(Some("acme_lake"), Some("main"), Set.empty)
  private val rlsCtx =
    ai.starlake.quack.edge.rls.SchemaContext(Some("acme_lake"), Some("main"))

  private def scope(sql: String): RewriteScope.Scope =
    RewriteScope.scopeOf(sql, cfg, "acme_lake", clientDataScans = false)

  private def beyond(original: String, rewritten: String): Option[String] =
    RewriteScope.reachesBeyond(original, rewritten, cfg, "acme_lake", clientDataScans = false)

  private def gated(sql: String): Set[(String, Verb)] =
    scope(sql) match
      case RewriteScope.Scope.Reaches(g, readers) =>
        readers shouldBe empty
        g.map(a => a.table.canonical -> a.verb)
      case other => fail(s"expected a reach, got $other for: $sql")

  // rls10's policy (the smoke stack's row-policy user), on pages.
  private val rls10 = EffectiveSet(
    RbacUser(id = "u-rls10", tenant = Some("acme"), username = "rls10", kind = "user"),
    Nil,
    Nil,
    Nil,
    Nil,
    Nil,
    List(RoleRowPolicy("rp-10", "r-10", "*", "main", "pages", "f10 = 0"))
  )

  /** The row-policy rewriter's real output for rls10, as the router asks it for a search statement.
    */
  private def rlsRewrite(sql: String): String =
    new RowPolicyRewriter(enabled = true)
      .rewrite(sql, StatementKind.Select, rls10, rlsCtx, searchStatement = true) match
      case RowPolicyRewriter.Rewritten(s) => s
      case other                          => fail(s"expected a rewrite, got $other for: $sql")

  private val Pages = "acme_lake.main.pages"

  // The spec 4.1 examples, adapted to qod_search and qod_search_matches, one column and two.
  private val shapes: List[String] = List(
    "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM pages WHERE lang = 'fr'), " +
      "'pages', ['body'], 'lakehouse souverain') h JOIN pages p ON p.rowid = h.rowid " +
      "ORDER BY h.score DESC",
    "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM acme_lake.main.pages " +
      "WHERE lang = 'fr'), 'acme_lake.main.pages', ['title', 'body'], 'lakehouse souverain', " +
      "k := 10) h JOIN acme_lake.main.pages p ON p.rowid = h.rowid ORDER BY h.score DESC",
    "SELECT p.category, count(*) AS n FROM qod_search_matches((SELECT rowid FROM pages " +
      "WHERE lang = 'fr'), 'pages', ['body'], 'lakehouse souverain') m " +
      "JOIN pages p ON p.rowid = m.rowid GROUP BY p.category ORDER BY n DESC",
    "SELECT p.category, count(*) AS n FROM qod_search_matches((SELECT rowid FROM main.pages " +
      "WHERE lang = 'fr'), 'main.pages', ['title', 'body'], 'lakehouse souverain') m " +
      "JOIN main.pages p ON p.rowid = m.rowid GROUP BY p.category ORDER BY n DESC"
  )

  "scopeOf a search statement" should "be a reach holding the target, read" in {
    for sql <- shapes do withClue(sql)(gated(sql) shouldBe Set(Pages -> Verb.Read))
  }

  it should "read the table argument into the scope, apart from the input and the join-back" in {
    // Only the table argument names `docs`: the input and the join-back read pages.
    gated(
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), 'docs', ['body'], 'x') h"
    ) shouldBe Set(Pages -> Verb.Read, "acme_lake.main.docs" -> Verb.Read)
    // Two calls: each table argument is read once.
    gated(
      "SELECT a.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') a " +
        "JOIN qod_search_matches((SELECT rowid FROM docs), 'docs', ['body'], 'y') b " +
        "ON a.rowid = b.rowid"
    ) shouldBe Set(Pages -> Verb.Read, "acme_lake.main.docs" -> Verb.Read)
  }

  it should "cover the vector and hybrid names too" in {
    gated(
      "SELECT h.rowid FROM qod_knn((SELECT rowid FROM pages), 'docs', 'embedding', [1.0, 2.0]) h"
    ) shouldBe Set(Pages -> Verb.Read, "acme_lake.main.docs" -> Verb.Read)
    gated(
      "SELECT h.rowid FROM qod_hybrid((SELECT rowid FROM pages), 'docs', ['body'], 'x', " +
        "'embedding', [1.0, 2.0]) h"
    ) shouldBe Set(Pages -> Verb.Read, "acme_lake.main.docs" -> Verb.Read)
  }

  "the row-policy rewrite of a search statement" should "be judged and admitted" in {
    for sql <- shapes do
      val rewritten = rlsRewrite(sql)
      withClue(rewritten) {
        rewritten should include("*, rowid")
        rewritten should not be sql
        beyond(sql, rewritten) shouldBe None
      }
  }

  "a rewrite of a search statement" should "be refused when it joins another table" in {
    val sql       = shapes.head
    val rewritten = rlsRewrite(sql).replace(" ORDER BY", " JOIN secret s ON s.id = p.id ORDER BY")
    beyond(sql, rewritten).getOrElse(fail("the extra join went unnoticed")) should
      include("acme_lake.main.secret (Read)")
  }

  it should "be refused when its table literal names another table" in {
    val sql       = "SELECT h.rowid FROM qod_search((SELECT rowid FROM t), 't', ['b'], 'x') h"
    val rewritten = "SELECT h.rowid FROM qod_search((SELECT rowid FROM t), 'u', ['b'], 'x') h"
    beyond(sql, rewritten).getOrElse(fail("the changed table literal went unnoticed")) should
      include("acme_lake.main.u (Read)")
    // The real rewrite of the same statement keeps the literal, and is admitted.
    val real = rlsRewrite(sql.replace("FROM t)", "FROM pages)").replace("'t'", "'pages'"))
    beyond(sql.replace("FROM t)", "FROM pages)").replace("'t'", "'pages'"), real) shouldBe None
  }

  "a search call whose table argument is not a constant" should "be refused" in {
    for arg <- List("'t' || ''", "?", "p.name", "$1", "'t'::VARCHAR", "upper('t')", "table := 't'")
    do
      val sql = s"SELECT h.rowid FROM qod_search((SELECT rowid FROM t), $arg, ['b'], 'x') h"
      withClue(sql) {
        scope(sql) match
          case RewriteScope.Scope.Refused(m) =>
            m should include("the table argument of qod_search is not a constant")
          case other => fail(s"expected a refusal, got $other")
      }
  }

  it should "be refused with a missing table argument" in {
    scope("SELECT h.rowid FROM qod_search((SELECT rowid FROM t)) h") shouldBe
      a[RewriteScope.Scope.Refused]
  }

  "a search call whose table literal is no table name" should "be refused" in {
    for lit <- List(
        "''",
        "'t u'",
        "'t; DROP TABLE u'",
        "'(SELECT 1)'",
        "'a.b.c.d'",
        "'t.'",
        "'\"a.b\"'",
        "'`t`'",
        // Unicode spaces: the lexer's pre-pass spaces (U+00A0, U+3000) and U+2028, which DuckDB's
        // tokenizer would keep in an identifier while the name check must not read it as one name.
        s"'pa${'\u00A0'}ges'",
        s"'pa${'\u3000'}ges'",
        s"'pa${'\u2028'}ges'",
        s"'pages${'\u00A0'}x'"
      )
    do
      val sql = s"SELECT h.rowid FROM qod_search((SELECT rowid FROM t), $lit, ['b'], 'x') h"
      withClue(sql)(scope(sql) shouldBe a[RewriteScope.Scope.Refused])
  }

  it should "qualify a quoted part as the rewriters do" in {
    gated(
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), '\"Docs\"', ['body'], 'x') h"
    ) should contain("acme_lake.main.docs" -> Verb.Read)
  }

  // The rewrite of an original carrying a search call the scope cannot read is refused, never
  // passed unjudged (no vacuous pass): the node would refuse it too (rule 7).
  "a rewrite of a search statement whose original cannot be read" should "be refused" in {
    val sql = "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), 'pa' || 'ges', ['b'], " +
      "'x') h"
    beyond(
      sql,
      sql.replace("FROM pages)", "FROM (SELECT *, rowid FROM pages WHERE f10 = 0) pages)")
    )
      .getOrElse(fail("admitted unjudged")) shouldBe
      "the statement holds a search name and cannot be authorized (the table argument of " +
      "qod_search is not a constant)"
  }

  "a search name in a literal only" should "add nothing to the scope" in {
    gated(
      "SELECT 'qod_search((SELECT rowid FROM t), ''u'', [''b''], ''x'')' AS x FROM pages"
    ) shouldBe
      Set(Pages -> Verb.Read)
  }
