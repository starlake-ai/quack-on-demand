package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableExtractor}
import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter, SchemaContext}
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.route.StatementClassifier
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Search spec 4.3, decision S1, ground 1 ("rows enter only through the governed input, which the
  * rewriters do rewrite") holds for reads only: the row-policy rewriter rewrites a `Select` alone,
  * so in a write or a batch the governed input would reach the node unfiltered. What keeps that
  * closed is [[ProtectedWriteGuard]]: a policy holder's write or batch that reads a policy table,
  * or whose table extraction is incomplete (a search call is a `gatedUnsupported` table function
  * for [[TableExtractor]]), is denied. S1 moves none of that, and [[TableExtractor]]'s output for a
  * search statement is pinned here against its value before S1 (the golden below).
  */
class ProtectedWriteGuardSearchSpec extends AnyFlatSpec with Matchers:

  import GuardOutcome._

  private def user(name: String) =
    RbacUser(id = s"u-$name", tenant = Some("acme"), username = name, kind = "user")

  // The smoke stack's principals: rls10 (row policy f10 = 0), clsmask (mask on title), and one
  // holding both; `plain` holds neither.
  private val rowPolicy = RoleRowPolicy("rp-10", "r-10", "*", "main", "pages", "f10 = 0")
  private val maskTitle =
    RoleColumnPolicy("cp-t", "r-m", "*", "main", "pages", "title", "mask", Some("'***'"))
  private val rls10   = EffectiveSet(user("rls10"), Nil, Nil, Nil, Nil, Nil, List(rowPolicy))
  private val clsmask = EffectiveSet(user("clsmask"), Nil, Nil, Nil, Nil, List(maskTitle), Nil)
  private val both    =
    EffectiveSet(user("both"), Nil, Nil, Nil, Nil, List(maskTitle), List(rowPolicy))
  private val plain  = EffectiveSet(user("plain"), Nil, Nil, Nil, Nil, Nil, Nil)
  private val ctx    = SchemaContext(Some("acme_lake"), Some("main"), Set.empty)
  private val rlsCtx =
    ai.starlake.quack.edge.rls.SchemaContext(Some("acme_lake"), Some("main"))
  private val pagesCols = List("id", "title", "body", "lang", "category", "f10")
  private val catalog   =
    new ColumnCatalog.MapCatalog(Map(("acme_lake", "main", "pages") -> pagesCols))
  private val guard = new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)
  private val cfg   = Config.forDuckDB(Some("acme_lake"), Some("main"), Set.empty)

  private val search =
    "SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1') h"
  private val matches =
    "SELECT h.rowid, h.score FROM qod_search_matches((SELECT rowid FROM pages), 'pages', " +
      "['body'], 'w1') h"

  private val insertSearch  = "INSERT INTO t2 " + search
  private val insertMatches = "INSERT INTO t2 " + matches
  private val ctas          = "CREATE TABLE x AS " + search
  private val copy          = "COPY (" + search + ") TO '/tmp/qod-out.csv'"
  private val explain       = "EXPLAIN ANALYZE " + search
  private val batch         = search + "; SELECT 1"

  private val WriteText =
    "access denied: pages is protected by a column/row policy and cannot be read into a write " +
      "statement; query it directly instead"
  private val BatchText =
    "access denied: pages is protected by a column/row policy and cannot be read in a " +
      "multi-statement batch; send that query on its own"
  private val IncompleteText =
    "access denied: statement could not be fully analysed and reads a protected schema; query " +
      "the table directly instead"

  private def check(sql: String, eff: EffectiveSet): GuardOutcome =
    guard.check(sql, StatementClassifier.classify(sql), eff, ctx)

  "a policy holder's write or batch holding a search call" should
    "be denied by the guard with its existing text" in {
      val expected = List(
        insertSearch  -> WriteText,
        insertMatches -> WriteText,
        ctas          -> WriteText,
        copy          -> IncompleteText,
        batch         -> BatchText
      )
      for
        (label, eff)    <- List("rls10" -> rls10, "clsmask" -> clsmask, "both" -> both)
        (sql, expected) <- expected
      do withClue(s"$label: $sql")(check(sql, eff) shouldBe Deny(expected))
    }

  it should "pass the guard for a principal holding neither policy" in {
    for sql <- List(insertSearch, insertMatches, ctas, copy, explain, batch) do
      withClue(sql)(check(sql, plain) shouldBe Allow)
  }

  // EXPLAIN ANALYZE classifies as the statement it runs, a Select, so the guard leaves it to the
  // rewriters (as for any EXPLAIN ANALYZE), and both refuse it for a policy holder: the row-policy
  // rewriter refuses an EXPLAIN reading a row-policy table, the column-policy step denies every
  // statement holding a search name for a column-policy holder.
  // PREPARE and EXECUTE classify as Other and are no statement the parser knows, so the guard
  // denies them as incomplete (security review M2): a parser that learns PREPARE would change this
  // silently, so it is pinned. The row-policy rewriter passes Other through; the guard alone stops
  // these.
  "PREPARE and EXECUTE of a search statement" should
    "be denied by the guard as incomplete to every policy holder" in {
      val forms = List(
        "PREPARE p AS " + search,
        "EXECUTE p",
        "PREPARE p AS " + search + "; EXECUTE p"
      )
      for
        (label, eff) <- List("rls10" -> rls10, "clsmask" -> clsmask, "both" -> both)
        sql          <- forms
      do withClue(s"$label: $sql")(check(sql, eff) shouldBe Deny(IncompleteText))
    }

  "EXPLAIN ANALYZE of a search statement" should "be refused to a policy holder by the rewriters" in {
    StatementClassifier.classify(explain) shouldBe StatementKind.Select
    for eff <- List(rls10, clsmask, both) do
      withClue(eff.user.username)(check(explain, eff) shouldBe Allow)
    val rls = new RowPolicyRewriter(enabled = true)
    for eff <- List(rls10, both) do
      withClue(eff.user.username)(
        rls.rewrite(explain, StatementKind.Select, eff, rlsCtx, searchStatement = true) shouldBe
          a[RowPolicyRewriter.Refused]
      )
    val cls = new ColumnPolicyRewriter(catalog, enabled = true)
    for eff <- List(clsmask, both) do
      withClue(eff.user.username)(
        cls.rewrite(explain, StatementKind.Select, eff, ctx).unsafeRunSync() shouldBe
          ColumnPolicyRewriter.PassthroughParseFailed
      )
  }

  // TableExtractor's output for every statement above, captured on the base of this change before
  // any file was touched (search Phase 1 Task 8). S1 must leave it byte for byte.
  private val golden: List[(String, List[String])] = List(
    insertSearch -> List(
      "Extracted(0,INSERT INTO t2 SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM " +
        "pages), 'pages', ['body'], 'w1') h,Set(TableAccess(acme_lake.main.t2,Write), " +
        "TableAccess(acme_lake.main.pages,Read)),List(),List(table function qod_search((SELECT " +
        "rowid FROM pages), 'pages', ['body'], 'w1')))",
      "GATED List(table function qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1'))"
    ),
    insertMatches -> List(
      "Extracted(0,INSERT INTO t2 SELECT h.rowid, h.score FROM qod_search_matches((SELECT rowid " +
        "FROM pages), 'pages', ['body'], 'w1') h,Set(TableAccess(acme_lake.main.t2,Write), " +
        "TableAccess(acme_lake.main.pages,Read)),List(),List(table function " +
        "qod_search_matches((SELECT rowid FROM pages), 'pages', ['body'], 'w1')))",
      "GATED List(table function qod_search_matches((SELECT rowid FROM pages), 'pages', " +
        "['body'], 'w1'))"
    ),
    ctas -> List(
      "Extracted(0,CREATE TABLE x AS SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM " +
        "pages), 'pages', ['body'], 'w1') h,Set(TableAccess(acme_lake.main.x,Ddl), " +
        "TableAccess(acme_lake.main.pages,Read)),List(),List(table function qod_search((SELECT " +
        "rowid FROM pages), 'pages', ['body'], 'w1')))",
      "GATED List(table function qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1'))"
    ),
    copy -> List(
      "ParseError(0,COPY (SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM pages), " +
        "'pages', ['body'], 'w1') h) TO '/tmp/qod-out.csv',unrecognized statement type " +
        "CopyStatement; denied (fail-closed))"
    ),
    explain -> List(
      "Extracted(0,EXPLAIN ANALYZE SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM " +
        "pages), 'pages', ['body'], 'w1') h,Set(TableAccess(acme_lake.main.pages,Read)),List()," +
        "List(table function qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1')))",
      "GATED List(table function qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1'))"
    ),
    batch -> List(
      "Extracted(0,SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM pages), 'pages', " +
        "['body'], 'w1') h,Set(TableAccess(acme_lake.main.pages,Read)),List(),List(table function " +
        "qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1')))",
      "GATED List(table function qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1'))",
      "Extracted(1,SELECT 1,Set(),List(),List())",
      "GATED List()"
    )
  )

  "TableExtractor's output for each of them" should "be unchanged from before S1" in {
    for (sql, expected) <- golden do
      val lines = SqlParser.extract(sql, cfg).statements.flatMap { r =>
        r.toString :: (r match
          case StatementResult.Extracted(_, _, a, _, u) =>
            List("GATED " + TableExtractor.gatedUnsupported(a, u, clientDataScans = false))
          case _ => Nil)
      }
      withClue(sql)(lines shouldBe expected)
  }
