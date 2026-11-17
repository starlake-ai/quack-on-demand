package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.rls.{RealRowidTables, RowPolicyRewriter}
import ai.starlake.quack.model.{PoolKey, RoleDistribution, StatementKind}
import ai.starlake.quack.ondemand.PoolState
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Search spec 12 Phase 1, change 9 (ruling 2): the column-policy search separation. A
  * column-policy holder's search statement is rewritten with each call's input as a statement of
  * its own and the call replaced by a placeholder while the outer statement resolves, then put
  * back. Each statement is asserted as text, then bound on an in-memory DuckDB with the test schema
  * of [[ai.starlake.quack.edge.rls.RowPolicyRewriterSearchSpec]] (`qod_search` exists on no plain
  * DuckDB, so a bind replaces the call by its own input with a constant score).
  */
class SearchSeparationSpec extends AnyFlatSpec with Matchers:

  import ColumnPolicyRewriter as C

  private val alice = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")

  private val (pathDb, pathSchema) = ai.starlake.quack.edge.FlightSqlRouter.nameDefaults(
    Some(
      PoolState(
        PoolKey("acme", "lake", "bi"),
        Nil,
        RoleDistribution(0, 0, 1),
        Map("dbName" -> "lake"),
        Map.empty
      )
    )
  )
  private val clsCtx = SchemaContext(pathDb, pathSchema, Set("lake"))
  private val rlsCtx = ai.starlake.quack.edge.rls.SchemaContext(pathDb, pathSchema)

  private val tables: Map[(String, String, String), List[String]] = Map(
    ("lake", "main", "pages")               -> List("id", "title", "body", "lang", "cat"),
    ("lake", "main", "other")               -> List("id", "secret"),
    ("lake", "main", "rpages")              -> List("id", "rowid", "secret"),
    ("lake", "main", "__qod_search_call_0") -> List("id", "secret")
  )
  private val catalog = new ColumnCatalog.MapCatalog(tables)
  private val cls     = new ColumnPolicyRewriter(catalog, enabled = true)
  private val rls     = new RowPolicyRewriter(enabled = true)

  private def mask(table: String, column: String, expr: String = "'***'"): RoleColumnPolicy =
    RoleColumnPolicy(s"cp-$table-$column", "r-1", "*", "main", table, column, "mask", Some(expr))
  private def deny(table: String, column: String): RoleColumnPolicy =
    RoleColumnPolicy(s"cd-$table-$column", "r-1", "*", "main", table, column, "deny", None)

  private def holding(cols: List[RoleColumnPolicy], rows: List[RoleRowPolicy] = Nil) =
    EffectiveSet(alice, Nil, Nil, Nil, Nil, cols, rows)

  private val maskTitle = holding(List(mask("pages", "title")))

  /** The column-policy step as the router calls it: the flag read from the client's own text. */
  private def clsResult(eff: EffectiveSet, sql: String, rewriter: ColumnPolicyRewriter = cls) =
    val flag = ai.starlake.quack.edge.search.SearchFunctions.callsSearch(sql)
    rewriter.rewriteWithTables(sql, StatementKind.Select, eff, clsCtx, flag).unsafeRunSync()

  private def clsOutcome(eff: EffectiveSet, sql: String): C.Outcome = clsResult(eff, sql).outcome

  private def rewritten(eff: EffectiveSet, sql: String): String =
    clsOutcome(eff, sql) match
      case C.Rewritten(out) => out
      case other            => fail(s"expected Rewritten, got $other for: $sql")

  /** The router's order: the column-policy step, then the row-policy rewriter on its output with
    * the tables the column-policy step resolved.
    */
  private def clsThenRls(
      eff: EffectiveSet,
      sql: String,
      rewriter: ColumnPolicyRewriter = cls
  ): Either[String, String] =
    val flag = ai.starlake.quack.edge.search.SearchFunctions.callsSearch(sql)
    val r    = clsResult(eff, sql, rewriter)
    def rows(in: String): Either[String, String] =
      rls.rewrite(in, StatementKind.Select, eff, rlsCtx, flag, r.realRowidTables) match
        case RowPolicyRewriter.Rewritten(out) => Right(out)
        case RowPolicyRewriter.Passthrough    => Right(in)
        case other                            => Left(other.toString)
    r.outcome match
      case C.Rewritten(out) => rows(out)
      case C.Passthrough    => rows(sql)
      case other            => Left(other.toString)

  // ---------- binding ----------

  private def withDb[A](f: java.sql.Statement => A): A =
    val conn = java.sql.DriverManager.getConnection("jdbc:duckdb:")
    try
      val st = conn.createStatement()
      st.execute("ATTACH ':memory:' AS lake")
      st.execute("USE lake")
      st.execute(
        "CREATE TABLE lake.main.pages(id INT, title VARCHAR, body VARCHAR, lang VARCHAR, cat INT)"
      )
      st.execute(
        "INSERT INTO lake.main.pages SELECT i, 't' || i, 'b' || i, " +
          "CASE WHEN i = 5 THEN 'en' ELSE 'fr' END, i % 7 FROM range(10) r(i)"
      )
      st.execute("CREATE TABLE lake.main.other(id INT, secret VARCHAR)")
      st.execute("INSERT INTO lake.main.other SELECT 9 - i, 's' || i FROM range(10) r(i)")
      st.execute("CREATE TABLE lake.main.rpages(id INT, rowid VARCHAR, secret VARCHAR)")
      st.execute(
        "INSERT INTO lake.main.rpages SELECT i, 'real' || i, 'x' || i FROM range(10) r(i)"
      )
      st.execute("CREATE TABLE lake.main.__qod_search_call_0(id INT, secret VARCHAR)")
      st.execute("INSERT INTO lake.main.__qod_search_call_0 VALUES (1, 'own1'), (2, 'own2')")
      f(st)
    finally conn.close()

  /** Index of the `)` closing the `(` at `open`, skipping single-quoted literals. */
  private def closing(sql: String, open: Int): Int =
    var depth = 0
    var i     = open
    var found = -1
    var quote = false
    while found < 0 && i < sql.length do
      val c = sql.charAt(i)
      if quote then { if c == '\'' then quote = false }
      else if c == '\'' then quote = true
      else if c == '(' then depth += 1
      else if c == ')' then
        depth -= 1
        if depth == 0 then found = i
      i += 1
    if found < 0 then throw new IllegalStateException(s"unbalanced parentheses in: $sql")
    found

  /** Each `qod_search(` call replaced by its own input (its first argument) with a constant score
    * and rank, so the stand-in has the function's output columns.
    */
  private def withoutCall(sql: String): String =
    val call = "qod_search("
    val at   = sql.toLowerCase.indexOf(call)
    if at < 0 then sql
    else
      val open  = at + call.length - 1
      val close = closing(sql, open)
      var depth = 0
      var quote = false
      var i     = open + 1
      while i < close && !(depth == 0 && !quote && sql.charAt(i) == ',') do
        val c = sql.charAt(i)
        if quote then { if c == '\'' then quote = false }
        else if c == '\'' then quote = true
        else if c == '(' then depth += 1
        else if c == ')' then depth -= 1
        i += 1
      val input = sql.substring(open + 1, i).trim
      withoutCall(
        sql.substring(0, at) + s"(SELECT rowid, 0.5::FLOAT AS score, 1 AS rank FROM $input)" +
          sql.substring(close + 1)
      )

  private def bindRows(sql: String): List[String] =
    withDb { st =>
      val rs   = st.executeQuery(sql)
      val n    = rs.getMetaData.getColumnCount
      val rows = List.newBuilder[String]
      while rs.next() do rows += (1 to n).map(i => String.valueOf(rs.getString(i))).mkString("|")
      rs.close()
      rows.result()
    }

  // ---------- Q14 shapes ----------

  private val s1 =
    "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = 'fr'), " +
      "'lake.main.pages', ['title', 'body'], 'lakehouse') h " +
      "JOIN lake.main.pages p ON p.rowid = h.rowid ORDER BY h.score DESC"

  "the Q14 shapes" should "mask the join-back and leave the call's outputs untouched" in {
    val out = rewritten(maskTitle, s1)
    out shouldBe
      "SELECT '***' AS title, h.score FROM qod_search((SELECT rowid FROM lake.main.pages WHERE " +
      "lang = 'fr'), 'lake.main.pages', ['title', 'body'], 'lakehouse') h JOIN lake.main.pages p " +
      "ON p.rowid = h.rowid ORDER BY h.score DESC"
    bindRows(withoutCall(out)).toSet shouldBe Set("***|0.5")
    bindRows(withoutCall(out)) should have size 9
  }

  it should "mask a column the snippet reads (S6) and pass the call alone (D1, D3) unchanged" in {
    val s6 =
      "SELECT qod_snippet(p.body, 'lakehouse') AS s, h.rank FROM qod_search((SELECT rowid FROM " +
        "pages), 'pages', ['title'], 'lakehouse') h JOIN pages p ON p.rowid = h.rowid"
    rewritten(holding(List(mask("pages", "body"))), s6) shouldBe
      "SELECT qod_snippet('***', 'lakehouse') AS s, h.rank FROM qod_search((SELECT rowid FROM " +
      "pages), 'pages', ['title'], 'lakehouse') h JOIN pages p ON p.rowid = h.rowid"
    val d1 = "SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM lake.main.pages), " +
      "'lake.main.pages', ['title'], 'x') h"
    clsOutcome(maskTitle, d1) shouldBe C.Passthrough
    bindRows(withoutCall(d1)) should have size 10
    val d3 = "SELECT h.rowid FROM qod_search('lake.main.pages', ['title'], 'lakehouse') h"
    clsOutcome(maskTitle, d3) shouldBe C.Passthrough
  }

  it should "mask the join-back of qod_search_matches and a call with a named argument" in {
    val matches =
      "SELECT p.title, count(*) AS n FROM qod_search_matches((SELECT rowid FROM pages WHERE " +
        "lang = 'fr'), 'pages', ['body'], 'x') m JOIN pages p ON p.rowid = m.rowid GROUP BY " +
        "p.title"
    rewritten(maskTitle, matches) shouldBe
      "SELECT '***' AS title, count(*) AS n FROM qod_search_matches((SELECT rowid FROM pages WHERE " +
      "lang = 'fr'), 'pages', ['body'], 'x') m JOIN pages p ON p.rowid = m.rowid GROUP BY '***'"
    val named =
      "SELECT p.title FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x', k := 5) h " +
        "JOIN pages p ON p.rowid = h.rowid"
    rewritten(maskTitle, named) shouldBe
      "SELECT '***' AS title FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x', " +
      "k := 5) h JOIN pages p ON p.rowid = h.rowid"
  }

  it should "separate a call in a derived table, as the Flight SQL prepare probe wraps it" in {
    val probe = s"SELECT * FROM ($s1) AS _qod_probe LIMIT 0"
    val out   = rewritten(maskTitle, probe)
    out should include("SELECT '***' AS title, h.score FROM qod_search((SELECT rowid FROM")
    out should include("'lake.main.pages', ['title', 'body'], 'lakehouse') h")
    bindRows(withoutCall(out)) shouldBe Nil
  }

  "a column policy only on another table" should "leave the statement byte for byte (S7)" in {
    clsOutcome(holding(List(mask("other", "secret"))), s1) shouldBe C.Passthrough
  }

  "a mask on a column the input's WHERE reads" should "rewrite the input as alone" in {
    val eff   = holding(List(mask("pages", "lang")))
    val alone = cls
      .rewrite("SELECT rowid FROM pages WHERE lang = 'fr'", StatementKind.Select, eff, clsCtx)
      .unsafeRunSync()
    val sql =
      "SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM pages WHERE lang = 'fr'), " +
        "'pages', ['body'], 'x') h"
    alone shouldBe C.Rewritten("SELECT rowid FROM pages WHERE '***' = 'fr'")
    rewritten(eff, sql) shouldBe
      "SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM pages WHERE '***' = 'fr'), " +
      "'pages', ['body'], 'x') h"
  }

  "the separation" should "refuse nested, correlated and unreplaced calls" in {
    val refused = List(
      // a call in another call's input
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM qod_search((SELECT rowid FROM pages), " +
        "'pages', ['body'], 'x') i), 'pages', ['body'], 'y') h",
      // correlated, qualified and unqualified
      "SELECT p.title FROM pages p JOIN qod_search((SELECT rowid FROM other WHERE other.id = " +
        "p.id), 'other', ['secret'], 'x') h ON h.rowid = p.rowid",
      "SELECT p.title FROM pages p JOIN qod_search((SELECT q.rowid FROM pages q WHERE q.id = " +
        "p.cat), 'pages', ['body'], 'x') h ON h.rowid = p.rowid",
      "SELECT p.title FROM pages p, qod_search((SELECT rowid FROM other WHERE secret = title), " +
        "'other', ['secret'], 'x') h",
      "SELECT o.id FROM pages, other o JOIN qod_search((SELECT q.rowid FROM pages q WHERE " +
        "pages.id = 1), 'pages', ['body'], 'x') h ON true",
      // a CTE
      "WITH c AS (SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
        "'x') h) SELECT p.title FROM c JOIN pages p ON p.rowid = c.rowid",
      "WITH c AS (SELECT 1) SELECT p.title FROM qod_search((SELECT rowid FROM pages), 'pages', " +
        "['body'], 'x') h JOIN pages p ON p.rowid = h.rowid",
      // an IN subquery
      "SELECT p.title FROM pages p WHERE p.rowid IN (SELECT h.rowid FROM qod_search((SELECT " +
        "rowid FROM pages), 'pages', ['body'], 'x') h)",
      // a set-operation arm
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
        "UNION ALL SELECT rowid FROM pages",
      "SELECT rowid FROM pages UNION ALL SELECT h.rowid FROM qod_search((SELECT rowid FROM " +
        "pages), 'pages', ['body'], 'x') h",
      // a derived table holding a set operation
      "SELECT x.rowid FROM (SELECT rowid FROM pages UNION ALL SELECT h.rowid FROM qod_search(" +
        "(SELECT rowid FROM pages), 'pages', ['body'], 'x') h) x",
      // a scalar subquery
      "SELECT (SELECT max(h.score) FROM qod_search((SELECT rowid FROM pages), 'pages', " +
        "['body'], 'x') h) AS m",
      // an input that is not one plain SELECT
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages UNION SELECT rowid FROM pages), " +
        "'pages', ['body'], 'x') h",
      // a subquery in another argument
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), (SELECT 'pages'), ['body'], " +
        "'x') h",
      // LATERAL and an alias column list
      "SELECT h.a FROM pages p, LATERAL qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
        "'x') h",
      "SELECT h.a FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') AS h(a, b, c)",
      // qod_knn and qod_hybrid have no fixed outputs yet
      "SELECT h.rowid FROM qod_knn((SELECT rowid FROM pages), 'pages', 'v', [1.0], 3) h"
    )
    for q <- refused do withClue(q)(clsOutcome(maskTitle, q) shouldBe C.PassthroughParseFailed)
  }

  it should "restore the table and column constants byte-identical" in {
    val quoted =
      "SELECT p.title FROM qod_search((SELECT rowid FROM pages), 'acme_lake.main.\"Pages\"', " +
        "[ 'title' ,'body' ], 'lake  house', k := 3) h JOIN pages p ON p.rowid = h.rowid"
    rewritten(maskTitle, quoted) shouldBe
      "SELECT '***' AS title FROM qod_search((SELECT rowid FROM pages), " +
      "'acme_lake.main.\"Pages\"', [ 'title' ,'body' ], 'lake  house', k := 3) h JOIN pages p " +
      "ON p.rowid = h.rowid"
    // A non-constant table passes the column-policy rewriter as written (rule 7 refuses it on
    // the node).
    val computed =
      "SELECT p.title FROM qod_search((SELECT rowid FROM pages), 'lake.main.' || 'pages', " +
        "['body'], 'x') h JOIN pages p ON p.rowid = h.rowid"
    rewritten(maskTitle, computed) should include(
      "qod_search((SELECT rowid FROM pages), 'lake.main.' || 'pages', ['body'], 'x') h"
    )
  }

  it should "report each cut with its constants as written" in {
    val sql = "SELECT h.rowid FROM qod_search(( SELECT rowid FROM pages ), 'pages', ['body'], " +
      "'x') h, system.main.qod_search_matches('pages', ['title'], 'y')"
    SearchSeparation.separate(sql, detectorFlagged = true) match
      case Right(Some(sep)) =>
        sep.cuts.map(c => (c.call, c.function, c.input, c.constants)) shouldBe List(
          (0, "qod_search", "SELECT rowid FROM pages", "), 'pages', ['body'], 'x')"),
          (1, "system.main.qod_search_matches", "", "'pages', ['title'], 'y')")
        )
        sep.placeholders.keySet shouldBe Set("__qod_search_call_0", "__qod_search_call_1")
        sep.placeholders("__qod_search_call_0").map(_._1) shouldBe List("rowid", "score", "rank")
        sep.placeholders("__qod_search_call_1").map(_._1) shouldBe List("rowid", "score")
        sep.outer shouldBe
          "SELECT h.rowid FROM __qod_search_call_0 h, __qod_search_call_1 qod_search_matches"
        sep.restore(sep.outer, Map.empty) shouldBe Right(sql)
      case other => fail(s"expected a separation, got $other")
    SearchSeparation.separate("SELECT 1", detectorFlagged = false) shouldBe Right(None)
    SearchSeparation.separate("SELECT 1", detectorFlagged = true).isLeft shouldBe true
  }

  // ---------- no search call ----------

  "a statement with no search call" should "be rewritten byte for byte as today (golden)" in {
    val maskBody =
      RoleColumnPolicy("cp-6", "r-1", "*", "main", "pages", "body", "mask", Some("'***'"))
    val maskRealRowid =
      RoleColumnPolicy("cp-4", "r-1", "*", "main", "rpages", "rowid", "mask", Some("'***'"))
    val eff = holding(
      List(maskBody, maskRealRowid),
      List(RoleRowPolicy("rp-4", "r-1", "*", "main", "rpages", "id = 3"))
    )
    val golden = new String(
      Files.readAllBytes(Paths.get("src/test/resources/search/cls-no-search-golden.tsv")),
      StandardCharsets.UTF_8
    ).linesIterator.toList
    golden should not be empty
    for line <- golden do
      val Array(kind, sql, expected) = line.split("\t", 3)
      val k                          = StatementKind.valueOf(kind)
      withClue(sql) {
        cls.rewrite(sql, k, eff, clsCtx).unsafeRunSync().toString shouldBe expected
      }
  }

  "a search name in a literal only" should "be denied as today" in {
    for q <- List(
        "SELECT 'qod_search(' AS x, title FROM pages",
        "SELECT title FROM pages WHERE body = 'qod_search_matches(x)'"
      )
    do withClue(q)(clsOutcome(maskTitle, q) shouldBe C.PassthroughParseFailed)
  }

  "the restored statement" should "pass RewriteScope against the original" in {
    val cfg = ai.starlake.acl.model.Config.forDuckDB(pathDb, pathSchema, Set.empty)
    for q <- List(
        s1,
        "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM pages WHERE lang = 'fr'), " +
          "'pages', ['body'], 'x') h JOIN pages p ON p.rowid = h.rowid"
      )
    do
      val out = rewritten(holding(List(mask("pages", "title"), mask("pages", "lang"))), q)
      withClue(out) {
        ai.starlake.quack.edge.policy.RewriteScope
          .reachesBeyond(q, out, cfg, "lake", clientDataScans = false) shouldBe None
      }
  }

  "a placeholder" should "take another suffix than a table the statement names" in {
    val maskOwn = holding(List(mask("pages", "title"), mask("__qod_search_call_0", "secret")))
    for name <- List("__qod_search_call_0", "\"__qod_search_call_0\"", "\"__QOD_SEARCH_CALL_0\"")
    do
      val q =
        s"SELECT p.title, t.secret FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
          s"'x') h JOIN pages p ON p.rowid = h.rowid JOIN $name t ON t.id = p.id"
      withClue(q) {
        val sep = SearchSeparation.separate(
          ai.starlake.sql.ParserInput.render(q).toOption.get.text,
          detectorFlagged = true
        )
        sep.toOption.flatten.map(_.placeholders.keySet) shouldBe Some(Set("__qod_search_call_1"))
        if name.exists(_.isUpper) then
          // A quoted name in another case than the catalog's is one the column-policy resolver
          // does not match (as with no search call): refused, never passed.
          clsOutcome(maskOwn, q) shouldBe a[C.Denied]
        else
          val out = rewritten(maskOwn, q)
          out shouldBe
            s"SELECT '***' AS title, '***' AS secret FROM qod_search((SELECT rowid FROM pages), " +
            s"'pages', ['body'], 'x') h JOIN pages p ON p.rowid = h.rowid JOIN $name t ON " +
            "t.id = p.id"
          bindRows(withoutCall(out)).toSet shouldBe Set("***|***")
      }
  }

  "a column policy whose table part is *" should "mask the placeholder's columns too" in {
    // Over-masking, documented: such a policy reaches every table of the statement, the
    // placeholder included, and leaks nothing.
    val star = RoleColumnPolicy("cp-star", "r-1", "*", "main", "*", "score", "mask", Some("0"))
    val q    = "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM pages), 'pages', " +
      "['body'], 'x') h JOIN pages p ON p.rowid = h.rowid"
    rewritten(holding(List(star)), q) shouldBe
      "SELECT p.title, 0 AS score FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
      "'x') h JOIN pages p ON p.rowid = h.rowid"
  }

  // ---------- rowid ----------

  /** The row-policy rewriter's fixed text (owner ruling 2026-11-14, review I6). */
  private val RealRowidText =
    "a search statement cannot keep rowid for a table holding a real rowid column"

  private val rowPolicyPages  = RoleRowPolicy("rp-1", "r-1", "*", "main", "pages", "cat = 3")
  private val rowPolicyRpages = RoleRowPolicy("rp-4", "r-1", "*", "main", "rpages", "id = 3")

  "a holder of a row policy and a column policy" should "search a table with no real rowid" in {
    val eff = holding(List(mask("pages", "title")), List(rowPolicyPages))
    val out = clsThenRls(eff, s1) match
      case Right(o) => o
      case Left(o)  => fail(s"expected a rewrite, got $o")
    out should include("SELECT '***' AS title, h.score FROM qod_search((SELECT rowid FROM (SELECT")
    out should include("(SELECT *, rowid FROM lake.main.pages WHERE (cat = 3)) p")
    bindRows(withoutCall(out)) shouldBe List("***|0.5")
  }

  it should "never read a masked or denied real rowid through rowid_1" in {
    val forms = List(
      "SELECT r.id, r.rowid_1 FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
        "'x') h JOIN rpages r ON r.id = h.rowid",
      "SELECT r.id, rpages.rowid_1 FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
        "'x') h JOIN rpages ON rpages.id = h.rowid",
      "SELECT rowid_1 FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
        "JOIN rpages r ON r.id = h.rowid",
      "SELECT h.score FROM qod_search((SELECT rowid FROM rpages), 'rpages', ['secret'], 'x') h",
      "SELECT 'qod_search(' AS x, rowid_1 FROM rpages",
      "SELECT 'qod_search(' AS x, rpages.rowid_1 FROM rpages",
      "SELECT 'qod_search(' AS x, r.rowid_1 FROM rpages r"
    )
    // Review m3: each form's closing layer, so a change of it shows. The three real-call forms
    // read rpages through the join-back: masked, then the row-policy rewriter's fixed text (the
    // column-policy step passes rowid_1, a name it does not know, in Pass mode). The input reading
    // rpages' masked rowid changes the input's select list (refused) or is denied. The literals are
    // the column-policy step's denial of a flagged statement it cannot separate.
    val fixed    = s"Refused($RealRowidText)"
    val failed   = C.PassthroughParseFailed.toString
    val expected = Map(
      "mask" -> List(fixed, fixed, fixed, failed, failed, failed, failed),
      "deny" -> List(
        fixed,
        fixed,
        fixed,
        "Denied(column rpages.rowid is denied)",
        failed,
        failed,
        failed
      )
    )
    for
      policy   <- List(mask("rpages", "rowid"), deny("rpages", "rowid"))
      (q, why) <- forms.zip(expected(policy.action))
    do
      val eff = holding(List(policy), List(rowPolicyRpages, rowPolicyPages))
      withClue(s"${policy.action}: $q")(clsThenRls(eff, q) shouldBe Left(why))
  }

  it should "be refused with the fixed text for a search reading a real rowid table" in {
    val eff = holding(List(mask("pages", "title")), List(rowPolicyRpages))
    val q = "SELECT p.title FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
      "JOIN pages p ON p.rowid = h.rowid JOIN rpages r ON r.id = p.id"
    clsThenRls(eff, q) shouldBe Left(
      RowPolicyRewriter.Refused(RealRowidText).toString
    )
  }

  "a table whose columns the catalog cannot list" should "count as holding a real rowid" in {
    val blind = new ColumnCatalog:
      def columnsOf(
          tenant: String,
          pool: Option[PoolKey],
          c: String,
          s: String,
          t: String
      ): IO[List[String]] =
        if t == "other" then IO.pure(Nil) else catalog.columnsOf(tenant, pool, c, s, t)
    val blindCls = new ColumnPolicyRewriter(blind, enabled = true)
    val eff      = holding(
      List(mask("pages", "title")),
      List(RoleRowPolicy("rp-o", "r-1", "*", "main", "other", "id > 0"))
    )
    val q =
      "SELECT p.title, o.id FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
        "JOIN pages p ON p.rowid = h.rowid JOIN other o ON o.id = p.id"
    clsResult(eff, q, blindCls).realRowidTables.holdsRealRowid("lake.main.other") shouldBe true
    clsThenRls(eff, q, blindCls) shouldBe Left(
      RowPolicyRewriter.Refused(RealRowidText).toString
    )
    // The same with the columns listed: admitted.
    clsThenRls(eff, q).isRight shouldBe true
  }

  "the tables the step resolved" should "name every table, the real rowid ones apart" in {
    val q =
      "SELECT p.title FROM qod_search((SELECT rowid FROM rpages), 'rpages', ['secret'], 'x') " +
        "h JOIN pages p ON p.rowid = h.rowid"
    clsResult(maskTitle, q).realRowidTables shouldBe RealRowidTables(
      Set("lake.main.rpages", "lake.main.pages"),
      Set("lake.main.rpages")
    )
  }

  // ---------- the security focus ----------

  "a masked or denied column the input reads" should "be rewritten or denied, never dropped" in {
    val q =
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages WHERE body = 'b3'), 'pages', " +
        "['title'], 'x') h"
    rewritten(holding(List(mask("pages", "body"))), q) shouldBe
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages WHERE '***' = 'b3'), 'pages', " +
      "['title'], 'x') h"
    clsOutcome(holding(List(deny("pages", "body"))), q) shouldBe a[C.Denied]
    // In a subquery of the input too.
    val nested = "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages WHERE id IN (SELECT id " +
      "FROM other WHERE secret = 's1')), 'pages', ['title'], 'x') h"
    rewritten(holding(List(mask("other", "secret"))), nested) should include(
      "(SELECT id FROM other WHERE '***' = 's1')"
    )
  }

  "a mask expression holding a search name" should "be denied, never sent as a second call" in {
    val named = holding(List(mask("pages", "title", "'qod_search(x)'")))
    val q = "SELECT p.title FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
      "JOIN pages p ON p.rowid = h.rowid"
    clsOutcome(named, q) match
      case C.Rewritten(out) => fail(s"expected a denial, got $out")
      case other            => other should not be C.Passthrough
  }

  "two calls in one statement" should "each be separated and put back" in {
    val q =
      "SELECT p.title, a.score, b.score FROM qod_search((SELECT rowid FROM pages), 'pages', " +
        "['body'], 'x') a JOIN qod_search_matches((SELECT rowid FROM pages WHERE lang = 'fr'), " +
        "'pages', ['body'], 'y') b ON b.rowid = a.rowid JOIN pages p ON p.rowid = a.rowid"
    rewritten(holding(List(mask("pages", "title"), mask("pages", "lang"))), q) shouldBe
      "SELECT '***' AS title, a.score, b.score FROM qod_search((SELECT rowid FROM pages), " +
      "'pages', ['body'], 'x') a JOIN qod_search_matches((SELECT rowid FROM pages WHERE '***' = " +
      "'fr'), 'pages', ['body'], 'y') b ON b.rowid = a.rowid JOIN pages p ON p.rowid = a.rowid"
  }

  "a table holding a real rowid anywhere in the statement" should "refuse the rowid wrap" in {
    // The column-policy holder's wrap keeping rowid is judged on every table of the statement, the
    // search input's subqueries included: a view's wrap binds an earlier item's rowid laterally.
    val eff = holding(List(mask("pages", "title")), List(rowPolicyPages))
    val q = "SELECT p.title FROM qod_search((SELECT rowid FROM pages WHERE id IN (SELECT id FROM " +
      "rpages)), 'pages', ['body'], 'x') h JOIN pages p ON p.rowid = h.rowid"
    clsThenRls(eff, q) shouldBe Left(RowPolicyRewriter.Refused(RealRowidText).toString)
  }

  "a row-policy holder with no column policy" should "keep today's wrap, whatever the tables" in {
    val eff = holding(Nil, List(rowPolicyRpages))
    val q   =
      "SELECT r.id FROM qod_search((SELECT rowid FROM rpages), 'rpages', ['secret'], 'x') h " +
        "JOIN rpages r ON r.id = 3"
    clsThenRls(eff, q).map(_.contains("(SELECT *, rowid FROM rpages WHERE (id = 3)) r")) shouldBe
      Right(true)
  }

  // ---------- fix round 1 ----------

  // Security review I1: a name bound only in a nested subquery of the input does not bind at the
  // input's own level, where DuckDB binds it to the outer statement's earlier FROM item.
  private val scopeShapes = List(
    "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM other WHERE p.title = 'x' AND " +
      "EXISTS (SELECT 1 FROM other p)), table => 'pages', columns => ['body'], query => 'q') h",
    "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM other WHERE title = 'x' AND " +
      "EXISTS (SELECT 1 AS title)), table => 'pages', columns => ['body'], query => 'q') h",
    "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM pages WHERE p.title = 'x' AND " +
      "EXISTS (SELECT 1 FROM other p)), table => 'pages', columns => ['body'], query => 'q') h",
    "SELECT h.score FROM other o, qod_search((SELECT rowid FROM pages WHERE secret = 'x' AND " +
      "EXISTS (SELECT 1 AS secret)), table => 'pages', columns => ['body'], query => 'q') h",
    // Fix round 2 (the first security review): the input binds the qualifier to a table lacking
    // the column, which DuckDB then reads from the outer item of that name.
    "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM other p WHERE p.title = 'x'), " +
      "table => 'pages', columns => ['body'], query => 'q') h",
    "SELECT h.score FROM other p, qod_search((SELECT rowid FROM pages p WHERE p.secret = 'x'), " +
      "table => 'pages', columns => ['body'], query => 'q') h",
    // two levels down, qualified
    "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM pages WHERE EXISTS (SELECT 1 FROM " +
      "other WHERE EXISTS (SELECT 1 FROM other q WHERE p.title = 'x'))), table => 'pages', " +
      "columns => ['body'], query => 'q') h"
  )

  "a correlated input" should "be refused, scope by scope, under both unresolved modes" in {
    val eff = holding(List(mask("pages", "title"), mask("other", "secret")))
    for
      mode <- List(UnresolvedMode.Pass, UnresolvedMode.Deny)
      q    <- scopeShapes
    do
      val rewriter = new ColumnPolicyRewriter(catalog, unresolvedMode = mode, enabled = true)
      withClue(s"$mode: $q")(clsResult(eff, q, rewriter).outcome shouldBe C.PassthroughParseFailed)
  }

  it should "admit a reference to an enclosing level inside the input" in {
    val eff = holding(List(mask("pages", "title"), mask("other", "secret")))
    for q <- List(
        // qualified: q is the input's root binding, read from its EXISTS subquery
        "SELECT h.score FROM qod_search((SELECT q.rowid FROM pages q WHERE EXISTS (SELECT 1 FROM " +
          "other o WHERE o.id = q.id)), 'pages', ['body'], 'x') h",
        // unqualified: lang is a column of pages, the enclosing level of the IN subquery
        "SELECT h.score FROM qod_search((SELECT rowid FROM pages WHERE id IN (SELECT id FROM other " +
          "WHERE id > 0 AND lang = 'fr')), 'pages', ['body'], 'x') h",
        // a select-list alias read in its own SELECT
        "SELECT h.score FROM qod_search((SELECT rowid AS r FROM pages WHERE r > 2), 'pages', " +
          "['body'], 'x') h"
      )
    do withClue(q)(admitted(clsOutcome(eff, q)) shouldBe true)
  }

  private def admitted(o: C.Outcome): Boolean =
    o match
      case C.Passthrough | C.Rewritten(_) => true
      case _                              => false

  it should "refuse an input whose FROM holds anything but tables, or an unlisted table" in {
    for q <- List(
        "SELECT h.score FROM qod_search((SELECT rowid FROM (SELECT rowid FROM pages) d), 'pages', " +
          "['body'], 'x') h",
        "SELECT h.score FROM qod_search((SELECT rowid FROM pages, range(3) r), 'pages', ['body'], " +
          "'x') h",
        "SELECT h.score FROM qod_search((SELECT rowid FROM unlisted WHERE a = 1), 'unlisted', " +
          "['body'], 'x') h"
      )
    do withClue(q)(clsOutcome(maskTitle, q) shouldBe C.PassthroughParseFailed)
  }

  // Security review M2: an argument after the input holding a column is refused at the gateway too.
  "an argument after the input" should "hold no column reference" in {
    for q <- List(
        "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM pages), p.title, ['body'], 'x') h",
        "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM pages), 'pages', [p.body], " +
          "'x') h",
        "SELECT h.score FROM pages p, qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
          "'x', k := p.id) h",
        "SELECT h.score FROM qod_search('pages', ['body'], title) h"
      )
    do withClue(q)(clsOutcome(maskTitle, q) shouldBe C.PassthroughParseFailed)
  }

  // Review m1: an unaliased call followed by a keyword pairs with its text site.
  "an unaliased call" should "be separated before JOIN, WHERE and ORDER BY" in {
    for q <- List(
        "SELECT p.title FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') JOIN " +
          "pages p ON p.rowid = qod_search.rowid",
        "SELECT qod_search.score FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
          "'x') WHERE qod_search.score > 0 ORDER BY qod_search.score"
      )
    do withClue(q)(admitted(clsOutcome(maskTitle, q)) shouldBe true)
  }

  // Review m6: Q14's D2, another table function beside the table, keeps today's fail-closed path.
  "Q14 D2" should "stay denied" in {
    clsOutcome(
      maskTitle,
      "SELECT p.title FROM range(3) h(rowid) JOIN pages p ON p.rowid = h.rowid"
    ) shouldBe
      C.PassthroughParseFailed
  }

  // Review m6: the production column source (a node's DESCRIBE, PolicyColumnSources) lists no
  // virtual rowid and lists a real one.
  "the production column source" should "tell a real rowid column from the virtual one" in {
    val sources = new PolicyColumnSources(
      duckLake = (_, _, _, _) => IO.pure(Nil),
      node = (_, sql) =>
        IO.blocking(withDb { st =>
          val rs   = st.executeQuery(sql)
          val rows = List.newBuilder[List[io.circe.Json]]
          while rs.next() do rows += List(io.circe.Json.fromString(rs.getString(1)))
          rs.close()
          Right(rows.result())
        }),
      nodeTimeout = scala.concurrent.duration.FiniteDuration(30, "seconds")
    )
    val described = new ColumnCatalog:
      def columnsOf(
          tenant: String,
          pool: Option[PoolKey],
          c: String,
          s: String,
          t: String
      ): IO[List[String]] = sources.fetch(tenant, pool, c, s, t)
    val rewriter = new ColumnPolicyRewriter(described, enabled = true)
    val onPool   = clsCtx.copy(pool = Some(PoolKey("acme", "lake", "bi")))
    val q = "SELECT p.title FROM qod_search((SELECT rowid FROM rpages), 'rpages', ['secret'], " +
      "'x') h JOIN pages p ON p.rowid = h.rowid"
    val flag = ai.starlake.quack.edge.search.SearchFunctions.callsSearch(q)
    rewriter
      .rewriteWithTables(q, StatementKind.Select, maskTitle, onPool, flag)
      .unsafeRunSync()
      .realRowidTables shouldBe RealRowidTables(
      Set("lake.main.rpages", "lake.main.pages"),
      Set("lake.main.rpages")
    )
  }

  // ---------- fix round 2 ----------

  // Verification I1: a qualifier the input binds names a table that lacks the column, or the
  // reference names a schema the binding's table does not: DuckDB 2.0 then binds the reference to
  // the outer statement's item of that name, reading its masked column raw.
  private val qualCatalog = new ColumnCatalog.MapCatalog(
    Map(
      ("lake", "main", "pages")  -> List("id", "title", "body", "lang", "cat"),
      ("lake", "main", "other")  -> List("id", "pin", "code"),
      ("lake", "main", "rpages") -> List("id", "rowid", "pin"),
      ("lake", "sec", "pages")   -> List("id", "code")
    )
  )
  private val qualEff = holding(
    List(
      RoleColumnPolicy("cq-1", "r-1", "*", "main", "other", "code", "mask", Some("'***'")),
      RoleColumnPolicy("cq-2", "r-1", "*", "sec", "pages", "code", "mask", Some("'***'"))
    )
  )
  private val qualShapes = List(
    "SELECT h.rowid FROM other o, qod_search((SELECT rowid FROM pages WHERE id IN (SELECT id FROM " +
      "rpages o WHERE o.code = 's1')), 'pages', ['body'], 'x') h",
    "SELECT h.rowid FROM other o, qod_search((SELECT o.rowid FROM pages o WHERE o.code = 's1'), " +
      "'pages', ['body'], 'x') h",
    "SELECT h.rowid FROM other o JOIN qod_search((SELECT o.rowid FROM pages o WHERE o.code = " +
      "'s1'), 'pages', ['body'], 'x') h ON true",
    "SELECT h.rowid FROM other, qod_search((SELECT rowid FROM pages WHERE id IN (SELECT id FROM " +
      "pages other WHERE other.code = 's1')), 'pages', ['body'], 'x') h",
    "SELECT h.rowid FROM sec.pages, qod_search((SELECT rowid FROM main.pages WHERE " +
      "sec.pages.code = 's1'), 'main.pages', ['body'], 'x') h"
  )

  "a qualified reference the input's binding cannot hold" should "be refused in both modes" in {
    for
      mode <- List(UnresolvedMode.Pass, UnresolvedMode.Deny)
      q    <- qualShapes
    do
      val rewriter = new ColumnPolicyRewriter(qualCatalog, unresolvedMode = mode, enabled = true)
      withClue(s"$mode: $q") {
        clsResult(qualEff, q, rewriter).outcome shouldBe C.PassthroughParseFailed
      }
  }

  it should "admit a qualified column the bound table holds, schema parts matching" in {
    for
      mode <- List(UnresolvedMode.Pass, UnresolvedMode.Deny)
      q    <- List(
        "SELECT h.rowid FROM other o, qod_search((SELECT o.rowid FROM pages o WHERE o.lang = " +
          "'fr'), 'pages', ['body'], 'x') h",
        "SELECT h.rowid FROM sec.pages, qod_search((SELECT rowid FROM main.pages WHERE " +
          "main.pages.lang = 'fr'), 'main.pages', ['body'], 'x') h"
      )
    do
      val rewriter = new ColumnPolicyRewriter(qualCatalog, unresolvedMode = mode, enabled = true)
      withClue(s"$mode: $q")(admitted(clsResult(qualEff, q, rewriter).outcome) shouldBe true)
  }

  // Verification m1: under unresolvedTable = deny the strict resolver must place the virtual rowid
  // of a separated statement's tables, so a column-policy holder can still search.
  "the deny mode" should "rewrite a plain input and its join-back" in {
    val strict =
      new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny, enabled = true)
    val q = "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM pages WHERE lang = " +
      "'fr'), 'pages', ['body'], 'x') h JOIN pages p ON p.rowid = h.rowid"
    clsResult(
      holding(List(mask("pages", "lang"), mask("pages", "title"))),
      q,
      strict
    ).outcome shouldBe
      C.Rewritten(
        "SELECT '***' AS title, h.score FROM qod_search((SELECT rowid FROM pages WHERE '***' = " +
          "'fr'), 'pages', ['body'], 'x') h JOIN pages p ON p.rowid = h.rowid"
      )
    clsResult(maskTitle, s1, strict).outcome shouldBe a[C.Rewritten]
    // The cause: with no search call, the strict resolver cannot place the virtual rowid (today's
    // behaviour, unchanged).
    strict
      .rewrite(
        "SELECT rowid FROM pages WHERE lang = 'fr'",
        StatementKind.Select,
        holding(List(mask("pages", "lang"))),
        clsCtx
      )
      .unsafeRunSync() shouldBe C.DeniedUnresolvedTable
    // A name no table holds still refuses there.
    val unknown =
      "SELECT p.nope FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') " +
        "h JOIN pages p ON p.rowid = h.rowid"
    admitted(clsResult(maskTitle, unknown, strict).outcome) shouldBe false
  }

  // ---------- fix round 3 ----------

  // Verification (round 3): the deny mode's rowid declaration reaches the resolver's star
  // expansion, which would add a rowid column DuckDB's star never has. The resolver reads one list
  // per table for both, so a star in a separated statement is refused under deny (fail closed); the
  // router's star screen refuses a search statement's star for every principal before this step
  // anyway (rule 10). Under pass the output carries no rowid column.
  private val starShapes = List(
    "SELECT p.* FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h JOIN pages p " +
      "ON p.rowid = h.rowid",
    "SELECT * FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h JOIN pages p " +
      "ON p.rowid = h.rowid",
    "SELECT h.*, p.* FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h JOIN " +
      "pages p ON p.rowid = h.rowid",
    "SELECT p.* EXCLUDE (body) FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') " +
      "h JOIN pages p ON p.rowid = h.rowid",
    "SELECT o.* FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h JOIN other o " +
      "ON o.id = h.rowid"
  )

  "a star in a separated statement" should "gain no rowid column, refused under deny" in {
    val eff    = holding(List(mask("pages", "title"), mask("other", "secret")))
    val strict =
      new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny, enabled = true)
    for q <- starShapes do
      withClue(q) {
        clsResult(eff, q, strict).outcome shouldBe C.PassthroughParseFailed
        clsResult(eff, q).outcome match
          case C.Rewritten(out) =>
            // the call's own rowid output (h.rowid) stays; no table's rowid is added
            val list = out.take(out.indexOf(" FROM "))
            list should not include "p.rowid"
            list should not include "o.rowid"
          case other => info(s"pass: $other")
      }
  }

  it should "keep the prepare probe's star over a derived table under deny" in {
    val strict =
      new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny, enabled = true)
    val probe = s"SELECT * FROM ($s1) AS _qod_probe LIMIT 0"
    clsResult(maskTitle, probe, strict).outcome shouldBe clsResult(maskTitle, probe).outcome
  }
