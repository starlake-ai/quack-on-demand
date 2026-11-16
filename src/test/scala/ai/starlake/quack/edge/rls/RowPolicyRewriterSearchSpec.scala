package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.*
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Spec 4.2 rule 3 (search, option A): in a statement calling a search function, every row-policy
  * wrap keeps DuckDB's virtual `rowid`. Fixtures are those of
  * [[ai.starlake.quack.search.Phase0RewriterProbeSpec]].
  */
class RowPolicyRewriterSearchSpec extends AnyFlatSpec with Matchers:

  private val alice = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")

  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "*", "main", "pages", "cat = 3")

  private def withRows: EffectiveSet = EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(rowPolicy))

  private val rls    = new RowPolicyRewriter(enabled = true)
  private val rlsCtx = SchemaContext(Some("lake"), Some("main"))

  private val noSearch: List[String] = List(
    "SELECT * FROM lake.main.pages",
    "SELECT title FROM pages WHERE lang = 'fr'",
    "SELECT p.title FROM pages p JOIN lake.main.other o ON o.id = p.id",
    "SELECT title FROM main.pages AS x WHERE x.cat IN (SELECT cat FROM pages)",
    "SELECT count(*) FROM (SELECT * FROM pages) t",
    "WITH c AS (SELECT * FROM pages) SELECT * FROM c",
    "SELECT * FROM pages UNION ALL SELECT * FROM lake.main.pages",
    "SELECT (SELECT max(id) FROM pages) AS m",
    "SELECT * FROM \"pages\"",
    "SELECT * FROM lake.main.pages WHERE EXISTS (SELECT 1 FROM pages q WHERE q.id = pages.id)",
    "SELECT qod_searcher(1) FROM pages",
    "SELECT * FROM range(3) r JOIN pages p ON p.id = r.range"
  )

  private val GoldenPath = "src/test/resources/search/rls-no-search-golden.tsv"

  "the golden capture" should "capture today's rewrites of statements with no search call" in {
    // A plain Boolean: ScalaTest's assume prints the values of its expression when it cancels,
    // and `sys.env.contains(...)` would print the whole environment.
    val capture: Boolean = sys.env.contains("QOD_CAPTURE")
    assume(capture, "set QOD_CAPTURE to capture the golden file")
    val lines = noSearch.map { sql =>
      sql + "\t" + rls.rewrite(sql, StatementKind.Select, withRows, rlsCtx).toString
    }
    Files.write(
      Paths.get(GoldenPath),
      lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    )
    info(s"wrote ${lines.size} lines to ${Paths.get(GoldenPath).toAbsolutePath}")
  }

  // ---------- helpers ----------

  private val catalog = new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
    Map(
      ("lake", "main", "pages")  -> List("id", "title", "body", "lang", "cat"),
      ("lake", "main", "other")  -> List("id", "secret"),
      ("lake", "main", "rpages") -> List("id", "rowid", "secret")
    )
  )
  private val clsCtx =
    ai.starlake.quack.edge.cls.SchemaContext(Some("lake"), Some("main"), Set("lake"))

  private def rewrittenWith(eff: EffectiveSet, sql: String): String =
    rls.rewrite(sql, StatementKind.Select, eff, rlsCtx) match
      case RowPolicyRewriter.Rewritten(out) => out
      case other                            => fail(s"expected Rewritten, got $other for: $sql")

  private def rewritten(sql: String): String = rewrittenWith(withRows, sql)

  private def guardOutcome(sql: String): ai.starlake.quack.edge.policy.GuardOutcome =
    new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
      catalog,
      clsEnabled = true,
      rlsEnabled = true
    ).check(sql, StatementKind.Dml, withRows, clsCtx)

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

  /** `qod_search` does not exist on plain DuckDB: its call is replaced by its own input subquery
    * (the call's first argument), with a constant `score` so that the stand-in has the function's
    * output columns (`rowid`, `score`) and an `ORDER BY h.score` still binds.
    */
  private def withoutCall(sql: String): String =
    val call = "qod_search("
    val at   = sql.toLowerCase.indexOf(call)
    if at < 0 then sql
    else
      val open  = at + call.length - 1
      val close = closing(sql, open)
      // The first argument ends at the first comma at the call's own depth.
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
      sql.substring(0, at) + s"(SELECT rowid, 0.0 AS score FROM $input)" +
        sql.substring(close + 1)

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
      // `other.id` runs against its rowid (id = 9 - rowid), so a join on id differs from one on
      // id and rowid.
      st.execute("CREATE TABLE lake.main.other(id INT, secret VARCHAR)")
      st.execute("INSERT INTO lake.main.other SELECT 9 - i, 's' || i FROM range(10) r(i)")
      // A view has no virtual rowid.
      st.execute("CREATE VIEW lake.main.pv AS SELECT * FROM lake.main.pages")
      // A real column named rowid shadows the virtual one.
      st.execute("CREATE TABLE lake.main.rpages(id INT, rowid VARCHAR, secret VARCHAR)")
      st.execute(
        "INSERT INTO lake.main.rpages SELECT i, 'real' || i, 'x' || i FROM range(10) r(i)"
      )
      f(st)
    finally conn.close()

  /** The select list of the outermost query: the text before its first top-level FROM. */
  private def replaceSelectList(sql: String, projection: String): String =
    val upper = sql.toUpperCase
    var depth = 0
    var quote = false
    var i     = 0
    var at    = -1
    while at < 0 && i < sql.length do
      val c = sql.charAt(i)
      if quote then { if c == '\'' then quote = false }
      else if c == '\'' then quote = true
      else if c == '(' then depth += 1
      else if c == ')' then depth -= 1
      else if depth == 0 && upper.startsWith(" FROM ", i) then at = i
      i += 1
    if at < 0 then throw new IllegalStateException(s"no top-level FROM in: $sql")
    projection + sql.substring(at)

  private def bindOk(sql: String): Boolean = bindError(sql).isEmpty

  /** The first line of DuckDB's error when `sql` fails to run, else None. */
  private def bindError(sql: String): Option[String] =
    try
      withDb(_.executeQuery(sql).close())
      None
    catch
      case e: java.sql.SQLException =>
        val first = e.getMessage.linesIterator.next()
        info(s"bind failed: $first in: $sql")
        Some(first)

  private def bindRows(sql: String, projectionOverride: String): List[String] =
    val q = if projectionOverride.isEmpty then sql else replaceSelectList(sql, projectionOverride)
    withDb { st =>
      val rs   = st.executeQuery(q)
      val n    = rs.getMetaData.getColumnCount
      val rows = List.newBuilder[String]
      while rs.next() do rows += (1 to n).map(i => String.valueOf(rs.getString(i))).mkString("|")
      rs.close()
      rows.result()
    }

  private def bindColumns(sql: String): List[String] =
    withDb { st =>
      val rs = st.executeQuery(sql)
      val md = rs.getMetaData
      val cs = (1 to md.getColumnCount).map(md.getColumnLabel).toList
      rs.close()
      cs
    }

  // ---------- rule 3 ----------

  private val search =
    "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = 'fr'), " +
      "'lake.main.pages', ['title', 'body'], 'lakehouse') h " +
      "JOIN lake.main.pages p ON p.rowid = h.rowid ORDER BY h.score DESC"

  "rule 3" should "wrap the input and the join-back with rowid in a search statement" in {
    val out = rewritten(search)
    out should include(
      "qod_search((SELECT rowid FROM (SELECT *, rowid FROM lake.main.pages WHERE (cat = 3)) AS pages " +
        "WHERE lang = 'fr'), 'lake.main.pages', ['title', 'body'], 'lakehouse') h " +
        "JOIN (SELECT *, rowid FROM lake.main.pages WHERE (cat = 3)) p ON p.rowid = h.rowid"
    )
    bindRows(withoutCall(out), "SELECT p.title, h.rowid") shouldBe List("t3|3")
  }

  it should "wrap bare, schema-qualified and aliased references alike" in {
    for sql <- List(
        "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h",
        "SELECT h.rowid FROM qod_search((SELECT rowid FROM main.pages), 'main.pages', ['body'], 'x') h",
        "SELECT h.rowid FROM qod_search((SELECT q.rowid FROM pages q), 'pages', ['body'], 'x') h " +
          "JOIN pages AS z ON z.rowid = h.rowid"
      )
    do
      val out = rewritten(sql)
      out should not include "(SELECT * FROM"
      out should include("SELECT *, rowid FROM")
      bindOk(withoutCall(out)) shouldBe true
  }

  it should "keep a policy predicate holding a subquery" in {
    val withSub = RoleRowPolicy(
      "rp-2",
      "r-1",
      "*",
      "main",
      "pages",
      "cat IN (SELECT cat FROM lake.main.pages WHERE lang = 'fr')"
    )
    val out = rewrittenWith(EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(withSub)), search)
    out should include(
      "(SELECT *, rowid FROM lake.main.pages WHERE (cat IN (SELECT cat FROM lake.main.pages WHERE lang = 'fr')))"
    )
    bindOk(withoutCall(out)) shouldBe true
  }

  it should "rewrite every statement with no search call byte for byte as before" in {
    val golden = scala.io.Source.fromResource("search/rls-no-search-golden.tsv").getLines().toList
    golden should have size noSearch.size
    golden.map(_.split("\t", 2)(0)) shouldBe noSearch
    for line <- golden do
      val parts = line.split("\t", 2)
      parts should have length 2
      rls.rewrite(parts(0), StatementKind.Select, withRows, rlsCtx).toString shouldBe parts(1)
  }

  it should "over-include a name inside a string literal, adding rowid only" in {
    val out = rewritten("SELECT 'qod_search(' AS x, title FROM pages")
    out should include("(SELECT *, rowid FROM pages WHERE (cat = 3)) AS pages")
    bindRows(out, "") shouldBe List("qod_search(|t3")
  }

  // Spec 4.2 rule 3, consequences: a relation with no virtual rowid (a view here; some federated
  // tables too) wrapped as SELECT *, rowid fails to bind, which refuses the statement and shows
  // nothing.
  private val viewPolicy = RoleRowPolicy("rp-3", "r-1", "*", "main", "pv", "cat = 3")
  private def withViewRows: EffectiveSet =
    EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(rowPolicy, viewPolicy))

  "a row-policy view" should "make a search statement that reads it fail to bind" in {
    // No FROM item before the view's wrap has a rowid: not found.
    val first =
      "SELECT v.lang, p.title FROM pv v " +
        "JOIN qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h ON h.rowid = v.id " +
        "JOIN pages p ON p.rowid = h.rowid"
    val outFirst = rewrittenWith(withViewRows, first)
    outFirst should include("(SELECT *, rowid FROM pv WHERE (cat = 3)) v")
    bindError(withoutCall(outFirst)).getOrElse("") should include(
      "Referenced column \"rowid\" not found"
    )
    // Two FROM items before it have one: DuckDB's implicit lateral join finds both, ambiguous.
    val after =
      "SELECT p.title, v.lang FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
        "JOIN pages p ON p.rowid = h.rowid JOIN pv v ON v.id = p.id"
    val outAfter = rewrittenWith(withViewRows, after)
    outAfter should include("(SELECT *, rowid FROM pv WHERE (cat = 3)) v")
    bindError(withoutCall(outAfter)).getOrElse("") should include(
      "Ambiguous reference to column name \"rowid\""
    )
  }

  // Exactly one FROM item before the wrap has a rowid: DuckDB's implicit lateral join binds the
  // wrap's `rowid` to that item's, so the statement binds and `v.rowid` is h's rowid, a value the
  // principal reads anyway, under the view's name. The policy predicate still applies.
  it should "bind laterally to the rowid of the one FROM item before it" in {
    val eff = EffectiveSet(
      alice,
      Nil,
      Nil,
      Nil,
      Nil,
      Nil,
      List(rowPolicy, RoleRowPolicy("rp-5", "r-1", "*", "main", "pv", "cat = 4"))
    )
    val out = rewrittenWith(
      eff,
      "SELECT v.id, v.rowid, h.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], " +
        "'x') h JOIN pv v ON true"
    )
    out should include("(SELECT *, rowid FROM pv WHERE (cat = 4)) v")
    // pv's one admitted row is id 4 (pages rowid 4); its `rowid` reads h's 3.
    bindRows(withoutCall(out), "") shouldBe List("4|3|3")
  }

  it should "make a statement with a search name in a literal only fail to bind" in {
    val withName = rewrittenWith(withViewRows, "SELECT 'qod_search(' AS x, title FROM pv")
    withName should include("(SELECT *, rowid FROM pv WHERE (cat = 3)) AS pv")
    bindError(withName).getOrElse("") should include("Referenced column \"rowid\" not found")
    // The same statement without the name keeps today's wrap and binds.
    val without = rewrittenWith(withViewRows, "SELECT 'qod_x(' AS x, title FROM pv")
    without should include("(SELECT * FROM pv WHERE (cat = 3)) AS pv")
    bindRows(without, "") shouldBe List("qod_x(|t3")
  }

  // A real column named rowid shadows the virtual one: the wrap then repeats the real column.
  private val realRowidPolicy = RoleRowPolicy("rp-4", "r-1", "*", "main", "rpages", "id = 3")
  private val maskRealRowid   =
    RoleColumnPolicy("cp-4", "r-1", "*", "main", "rpages", "rowid", "mask", Some("'***'"))
  private val cls = new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(catalog, enabled = true)

  /** The router's order: the column-policy rewriter first, then the row-policy rewriter on its
    * output (FlightSqlRouter.clsRewritten, rlsRewritten), both told whether the CLIENT's text calls
    * a search function, read once from that text (never from the column-policy output).
    */
  private def clsThenRls(eff: EffectiveSet, sql: String): Either[String, String] =
    import ai.starlake.quack.edge.cls.ColumnPolicyRewriter as C
    val flag = ai.starlake.quack.edge.search.SearchFunctions.callsSearch(sql)
    def rls2(in: String): Either[String, String] =
      rls.rewrite(in, StatementKind.Select, eff, rlsCtx, flag) match
        case RowPolicyRewriter.Rewritten(out) => Right(out)
        case RowPolicyRewriter.Passthrough    => Right(in)
        case other                            => Left(other.toString)
    cls.rewrite(sql, StatementKind.Select, eff, clsCtx, flag).unsafeRunSync() match
      case C.Rewritten(out) => rls2(out)
      case C.Passthrough    => rls2(sql)
      case other            => Left(other.toString)

  /** The rows `sql` reads, or Nil when it does not bind. */
  private def readOrNothing(sql: String): List[String] =
    scala.util.Try(bindRows(sql, "")).toOption.getOrElse(Nil)

  "a real rowid column" should "be repeated by the wrap, reading the real value" in {
    val eff = EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(realRowidPolicy))
    val out = rewrittenWith(
      eff,
      "SELECT r.id, r.rowid FROM qod_search((SELECT rowid FROM rpages), 'rpages', ['secret'], 'x') h " +
        "JOIN rpages r ON r.id = 3"
    )
    out should include("(SELECT *, rowid FROM rpages WHERE (id = 3)) r")
    bindRows(withoutCall(out), "") shouldBe List("3|real3")
  }

  // Owner ruling 2026-11-14 (search spec 4.3): for a column-policy holder the column-policy step
  // denies every statement the search detector flags, as it denies a real search call, before the
  // row-policy wrap runs; the wrap's repeat of a real `rowid` column (`rowid_1`) is never read.
  private val denyRealRowid =
    RoleColumnPolicy("cp-5", "r-1", "*", "main", "rpages", "rowid", "deny", None)

  private def clsOutcome(
      eff: EffectiveSet,
      sql: String,
      kind: StatementKind = StatementKind.Select
  ) =
    cls.rewrite(sql, kind, eff, clsCtx).unsafeRunSync()

  it should "be denied by the column-policy step for a real search call and every over-inclusion" in {
    val flagged = List(
      // a real call
      "SELECT r.rowid FROM qod_search((SELECT rowid FROM rpages), 'rpages', ['secret'], 'x') h " +
        "JOIN rpages r ON r.id = 3",
      // a literal
      "SELECT 'qod_search(' AS x, * FROM rpages",
      // a CTE column list
      "WITH qod_search(a) AS (SELECT 1) SELECT a, rowid_1 FROM qod_search, rpages",
      // an alias column list
      "SELECT a, c FROM rpages AS qod_search(a, b, c)"
    )
    for
      policy <- List(maskRealRowid, denyRealRowid)
      q      <- flagged
    do
      val eff = EffectiveSet(alice, Nil, Nil, Nil, Nil, List(policy), List(realRowidPolicy))
      withClue(s"${policy.action}: $q") {
        clsOutcome(
          eff,
          q
        ) shouldBe ai.starlake.quack.edge.cls.ColumnPolicyRewriter.PassthroughParseFailed
        clsThenRls(eff, q).isLeft shouldBe true
      }
  }

  // Review of Tasks 5 and 7a, M6: the drain probes answer the count and maximum of their input, so
  // a column-policy holder's probe over a masked or denied BIGINT column must be denied like a search
  // call, never rewritten (the probe's TABLE argument was not shown to carry the mask). A SELECT was
  // already denied before the probes joined SearchFunctions.Names, by the column resolver's parse
  // failure on a table function; a statement of another kind passed through, and is what this pins.
  it should "deny the prototype's drain probes for a column-policy holder" in {
    for
      policy <- List(maskRealRowid, denyRealRowid)
      name   <- List("qod_search_drain", "qod_search_drain_parallel", "qod_search_drain_sink")
    do
      val q     = s"SELECT \"rows\", max_rowid FROM $name((SELECT rowid FROM rpages))"
      val write = s"INSERT INTO other SELECT \"rows\", 'x' FROM $name((SELECT rowid FROM rpages))"
      val eff   = EffectiveSet(alice, Nil, Nil, Nil, Nil, List(policy), Nil)
      withClue(s"${policy.action}: $q") {
        clsOutcome(
          eff,
          q
        ) shouldBe ai.starlake.quack.edge.cls.ColumnPolicyRewriter.PassthroughParseFailed
        clsThenRls(eff, q).isLeft shouldBe true
        clsOutcome(
          eff,
          write,
          StatementKind.Dml
        ) shouldBe ai.starlake.quack.edge.cls.ColumnPolicyRewriter.PassthroughParseFailed
      }
  }

  it should "deny a flagged statement of any kind for a column-policy holder" in {
    val eff = EffectiveSet(alice, Nil, Nil, Nil, Nil, List(maskRealRowid), Nil)
    clsOutcome(
      eff,
      "INSERT INTO other VALUES (1, 'qod_search(')",
      StatementKind.Dml
    ) shouldBe ai.starlake.quack.edge.cls.ColumnPolicyRewriter.PassthroughParseFailed
    // No column policy: the step stays out of the way.
    val rowsOnly = EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(realRowidPolicy))
    clsOutcome(rowsOnly, "SELECT 'qod_search(' AS x, id FROM rpages") shouldBe
      ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Passthrough
  }

  // Was the open bypass of fix round 1 (pendingUntilFixed): the wrap repeats a real `rowid` column
  // after the column-policy step, and DuckDB names the repeat `rowid_1`, a name no column policy
  // covers. Closed by the ruling above: the column-policy step denies each form first.
  it should "not let the wrap's duplicate of a masked or denied real rowid be read" in {
    for
      policy <- List(maskRealRowid, denyRealRowid)
      q      <- List(
        "SELECT 'qod_search(' AS x, rowid_1 FROM rpages",
        "SELECT 'qod_search(' AS x, rpages.rowid_1 FROM rpages",
        "SELECT 'qod_search(' AS x, r.rowid_1 FROM rpages r"
      )
    do
      val eff = EffectiveSet(alice, Nil, Nil, Nil, Nil, List(policy), List(realRowidPolicy))
      withClue(s"${policy.action}: $q") {
        clsOutcome(
          eff,
          q
        ) shouldBe ai.starlake.quack.edge.cls.ColumnPolicyRewriter.PassthroughParseFailed
        clsThenRls(eff, q).isLeft shouldBe true
      }
  }

  // Review of fix round 2, C1: a mask expression holding a search name puts the name into the
  // column-policy rewriter's output, which the client never wrote. Two layers keep the wrap from
  // repeating the real rowid there: the search flag is read from the client's text (the router's
  // order, clsThenRls), and the row-policy rewriter refuses to keep rowid for any column-policy
  // holder, whatever set the flag.
  private val maskSecretWithName =
    RoleColumnPolicy(
      "cp-7",
      "r-1",
      "*",
      "main",
      "rpages",
      "secret",
      "mask",
      Some("'hidden (see qod_search(x))'")
    )
  private val maskRowidWithName =
    RoleColumnPolicy(
      "cp-8",
      "r-1",
      "*",
      "main",
      "rpages",
      "rowid",
      "mask",
      Some("'qod_search(' || rowid")
    )

  "a search name carried by a mask expression" should "never make the wrap repeat a real rowid" in {
    val cases = List(
      // The review's reproduction: deny on the real rowid, the name in the other column's mask.
      (List(denyRealRowid, maskSecretWithName), "SELECT secret, rowid_1 FROM rpages"),
      // The name in the rowid mask itself.
      (List(maskRowidWithName), "SELECT rowid, rowid_1 FROM rpages")
    )
    for (policies, q) <- cases do
      val eff = EffectiveSet(alice, Nil, Nil, Nil, Nil, policies, List(realRowidPolicy))
      withClue(q) {
        val clsOut = clsOutcome(eff, q) match
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Rewritten(out) => out
          case other => fail(s"expected the column-policy rewrite, got $other")
        info(s"column-policy output: $clsOut")
        clsOut should include("qod_search(")
        // Layer 1, the router's order: the client's text calls no search function, so the wrap
        // is today's, and `rowid_1` names no column: nothing is read.
        val out = clsThenRls(eff, q) match
          case Right(o) => o
          case Left(o)  => fail(s"expected a rewrite, got $o")
        info(s"sent: $out")
        out should not include "*, rowid"
        bindError(out).isDefined shouldBe true
        readOrNothing(out) shouldBe Nil
        // Layer 2: told to keep rowid (the flag read from the column-policy output, as round 2
        // did), the row-policy rewriter refuses for a column-policy holder.
        rls.rewrite(clsOut, StatementKind.Select, eff, rlsCtx) shouldBe
          RowPolicyRewriter.Refused(RowPolicyRewriter.ColumnPolicySearchRefusal)
      }
  }

  it should "be refused by the row-policy rewriter for any column-policy holder told to keep rowid" in {
    val eff = EffectiveSet(alice, Nil, Nil, Nil, Nil, List(maskBodyForWrap), List(rowPolicy))
    rls.rewrite(search, StatementKind.Select, eff, rlsCtx, searchStatement = true) shouldBe
      RowPolicyRewriter.Refused(RowPolicyRewriter.ColumnPolicySearchRefusal)
    // Not told to: today's wrap, no refusal.
    rewrittenWith(eff, "SELECT title FROM pages") should include("(SELECT * FROM pages WHERE")
  }

  private val maskBodyForWrap =
    RoleColumnPolicy("cp-9", "r-1", "*", "main", "other", "secret", "mask", Some("'***'"))

  "a row policy predicate holding a search name" should "not switch the wrap to keep rowid" in {
    val namedPolicy = RoleRowPolicy("rp-9", "r-1", "*", "main", "pages", "title <> 'qod_search(x)'")
    val eff         = EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(namedPolicy))
    for q <- List(
        "SELECT title FROM pages",
        "SELECT p.title FROM pages p JOIN other o ON o.id = p.id"
      )
    do
      withClue(q) {
        val out = clsThenRls(eff, q) match
          case Right(o) => o
          case Left(o)  => fail(s"expected a rewrite, got $o")
        out should include("(SELECT * FROM pages WHERE (title <> 'qod_search(x)'))")
        out should not include "*, rowid"
        bindOk(out) shouldBe true
      }
  }

  // Captured on the column-policy rewriter before the ruling (QOD_CAPTURE=1, test "capture the
  // column-policy golden"): a column-policy holder's statements with no search name rewrite byte
  // for byte as before it.
  private val maskBody =
    RoleColumnPolicy("cp-6", "r-1", "*", "main", "pages", "body", "mask", Some("'***'"))
  private def withMasks: EffectiveSet =
    EffectiveSet(alice, Nil, Nil, Nil, Nil, List(maskBody, maskRealRowid), List(realRowidPolicy))

  private val clsNoSearch: List[(String, StatementKind)] =
    noSearch.map(_ -> StatementKind.Select) ++ List(
      "SELECT body FROM pages"                       -> StatementKind.Select,
      "SELECT * FROM rpages"                         -> StatementKind.Select,
      "SELECT rowid, secret FROM rpages"             -> StatementKind.Select,
      "SELECT qod_snippet(body, 'x') FROM pages"     -> StatementKind.Select,
      "SELECT 'qod_search' AS x, body FROM pages"    -> StatementKind.Select,
      "INSERT INTO other SELECT id, body FROM pages" -> StatementKind.Dml
    )

  private val ClsGoldenPath = "src/test/resources/search/cls-no-search-golden.tsv"

  private def clsLine(sql: String, kind: StatementKind): String =
    s"$kind\t$sql\t${clsOutcome(withMasks, sql, kind)}"

  "the column-policy golden" should "capture the column-policy golden" in {
    val capture: Boolean = sys.env.contains("QOD_CAPTURE")
    assume(capture, "set QOD_CAPTURE to capture the golden file")
    val lines = clsNoSearch.map((sql, kind) => clsLine(sql, kind))
    Files.write(
      Paths.get(ClsGoldenPath),
      lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    )
    info(s"wrote ${lines.size} lines to ${Paths.get(ClsGoldenPath).toAbsolutePath}")
  }

  it should "leave a column-policy holder's statements with no search name byte for byte" in {
    val golden = new String(
      Files.readAllBytes(Paths.get(ClsGoldenPath)),
      StandardCharsets.UTF_8
    ).linesIterator.toList
    golden should have size clsNoSearch.size.toLong
    clsNoSearch.map((sql, kind) => clsLine(sql, kind)) shouldBe golden
  }

  "the documented consequences" should "show rowid in SELECT *" in {
    val cols = bindColumns(
      withoutCall(
        rewritten(
          "SELECT * FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
            "JOIN pages p ON p.rowid = h.rowid"
        )
      )
    )
    info(s"columns: ${cols.mkString(", ")}")
    cols should contain("rowid")
    // h's own rowid and the join-back's: the wrap's rowid is part of p's star.
    cols.count(_ == "rowid") shouldBe 2
  }

  // DuckDB JDBC 2.0.0.0 labels both top-level columns `rowid` (observed), so the pin is the count;
  // DuckDB names the duplicate `rowid_1` only when the doubled output is read through a further
  // subquery star (Q4's CLI line).
  it should "list rowid twice for p.*, p.rowid (both labeled rowid)" in {
    val cols = bindColumns(
      withoutCall(
        rewritten(
          "SELECT p.*, p.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
            "JOIN pages p ON p.rowid = h.rowid"
        )
      )
    )
    info(s"columns: ${cols.mkString(", ")}")
    cols shouldBe List("id", "title", "body", "lang", "cat", "rowid", "rowid")
  }

  it should "carry a rowid field in a whole-row reference" in {
    val v = bindRows(
      withoutCall(
        rewritten(
          "SELECT p FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
            "JOIN pages p ON p.rowid = h.rowid"
        )
      ),
      ""
    )
    info(s"row: ${v.mkString}")
    v shouldBe List("{'id': 3, 'title': t3, 'body': b3, 'lang': fr, 'cat': 3, 'rowid': 3}")
  }

  // A positional reference counts the wrap's rowid: position 6 of `p.*, h.score` is `score` for a
  // principal with no row policy and `rowid` for the policy holder.
  it should "shift a positional ORDER BY past the wrap's rowid" in {
    val sql =
      "SELECT p.*, h.score FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
        "JOIN pages p ON p.rowid = h.rowid ORDER BY 6 DESC"
    val plain  = bindColumns(withoutCall(sql))
    val holder = bindColumns(withoutCall(rewritten(sql)))
    info(s"plain:  ${plain.mkString(", ")}")
    info(s"holder: ${holder.mkString(", ")}")
    plain(5) shouldBe "score"
    holder(5) shouldBe "rowid"
  }

  // DuckDB matches a NATURAL JOIN's left columns against the right side's columns, its virtual
  // rowid included, so a wrapped table on the left joins on rowid too.
  it should "add rowid to a NATURAL JOIN's keys" in {
    def stmt(join: String) =
      s"SELECT count(*) FROM pages a $join " +
        "JOIN qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h ON h.rowid = a.id"
    val natural                    = stmt("NATURAL JOIN other b")
    val usingId                    = stmt("JOIN other b USING (id)")
    def count(sql: String): String = bindRows(withoutCall(sql), "").mkString
    // No row policy: NATURAL JOIN keys on id alone, as USING (id) does.
    count(natural) shouldBe "10"
    count(usingId) shouldBe "10"
    // Policy holder: the one admitted row (id 3, rowid 3) meets other's id 3 at rowid 6, so
    // USING (id) finds it and NATURAL JOIN, keyed on id and rowid, does not.
    count(rewritten(usingId)) shouldBe "1"
    count(rewritten(natural)) shouldBe "0"
  }

  it should "leave INSERT ... SELECT to the protected-write guard" in {
    val sql =
      "INSERT INTO lake.main.other SELECT * FROM qod_search((SELECT rowid FROM pages), 'pages', " +
        "['body'], 'x') h JOIN pages p ON p.rowid = h.rowid"
    rls.rewrite(sql, StatementKind.Dml, withRows, rlsCtx) shouldBe RowPolicyRewriter.Passthrough
    guardOutcome(sql) shouldBe a[ai.starlake.quack.edge.policy.GuardOutcome.Deny]
  }

  "RewriteScope" should "pass the rewritten search statement" in {
    val cfg = ai.starlake.acl.model.Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))
    ai.starlake.quack.edge.policy.RewriteScope
      .reachesBeyond(search, rewritten(search), cfg, "lake", false) shouldBe None
  }
