package ai.starlake.quack.search

import ai.starlake.quack.edge.policy.RewriteScope
import ai.starlake.quack.edge.rls.{RowPolicyRewriter, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Comparator

/** Search Phase 0 part 2, Task 12 (spec 4.2 rule 3, Q4, change 6): the binds of
  * [[ai.starlake.quack.edge.rls.RowPolicyRewriterSearchSpec]] (Task 2, in-memory tables) run again
  * on a DuckLake catalog through DuckDB JDBC, and the rewriter's output for the row-policy holder
  * of every example statement written to
  * `native/qod-node/tests/golden/search-rewriter-outputs.sql`, one statement per line, which
  * qod-node's `search_engine_test` (W1) feeds through its lineage check.
  *
  * The lake is attached as `lake` (a temporary directory per run, a DuckDB-file catalog), with the
  * tables of `search_engine_test`'s fixture: `lake.main.pages(id, title, body, lang, f10)` and
  * `lake.main.other(id, body)`, so the golden runs there with no table mapping. The row policy is
  * `f10 = 0` on `lake.main.pages`; one statement runs under a policy predicate holding a subquery
  * that admits the same rows (`f10 IN (SELECT min(id % 10) FROM lake.main.other)`, other holding id
  * 0). Every example's first output column is an id, a rowid or `f10` (rowid = id in both
  * fixtures), so "only rows of the policy" reads as "every first value is a multiple of 10".
  *
  * The examples, adapted to qod-node's prototype: spec 4.1's two examples with `qod_search` in
  * place of `qod_search_matches` and `qod_hybrid` (the hybrid's vector arguments and `qod_snippet`,
  * which no node registers, left out), one search column (the prototype takes exactly one), the
  * fixture's vocabulary (`w1 w10`) and columns (`f10` for the facet), and Task 2's search
  * statements (bare, schema-qualified and aliased references).
  *
  * `qod_search` exists on no plain DuckDB, so a bind replaces the call by its own input subquery
  * with a constant `score`, as Task 2 does. Set QOD_CAPTURE to rewrite the golden file (a `-D`
  * never reaches the forked test JVM); without it, the file must equal the current outputs.
  */
class RowPolicyRewriterSearchDuckLakeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val alice = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")

  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "lake", "main", "pages", "f10 = 0")

  private val subqueryPolicy = RoleRowPolicy(
    "rp-2",
    "r-1",
    "lake",
    "main",
    "pages",
    "f10 IN (SELECT min(id % 10) FROM lake.main.other)"
  )

  private def holding(policies: RoleRowPolicy*): EffectiveSet =
    EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, policies.toList)

  private val withRows = holding(rowPolicy)

  private val rls = new RowPolicyRewriter(enabled = true)
  // The pool's one session path (FlightSqlRouter.nameDefaults), as the router qualifies names.
  private val rlsCtx =
    val (db, schema) = ai.starlake.quack.edge.FlightSqlRouter.nameDefaults(
      Some(
        ai.starlake.quack.ondemand.PoolState(
          ai.starlake.quack.model.PoolKey("acme", "lake", "bi"),
          Nil,
          ai.starlake.quack.model.RoleDistribution(0, 0, 1),
          Map("dbName" -> "lake"),
          Map.empty
        )
      )
    )
    SchemaContext(db, schema)

  // ---------- the examples ----------

  private val facets =
    "SELECT p.f10, count(*) AS n FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = " +
      "'fr'), 'lake.main.pages', ['body'], 'w1 w10') m JOIN lake.main.pages p ON p.rowid = m.rowid " +
      "GROUP BY ALL ORDER BY n DESC"

  private val topTen =
    "SELECT p.id, p.title, h.score FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = " +
      "'fr'), 'lake.main.pages', ['body'], 'w1 w10') h JOIN lake.main.pages p ON p.rowid = h.rowid " +
      "ORDER BY h.score DESC"

  /** (label, the client's statement, the policies it runs under). */
  private val examples: List[(String, String, EffectiveSet)] = List(
    ("spec 4.1 facets", facets, withRows),
    ("spec 4.1 top 10", topTen, withRows),
    (
      "Task 2 bare reference",
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'w1 w10') h",
      withRows
    ),
    (
      "Task 2 schema-qualified reference",
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM main.pages), 'main.pages', ['body'], " +
        "'w1 w10') h",
      withRows
    ),
    (
      "Task 2 aliased input and join-back",
      "SELECT h.rowid FROM qod_search((SELECT q.rowid FROM pages q), 'pages', ['body'], 'w1 w10') h " +
        "JOIN pages AS z ON z.rowid = h.rowid",
      withRows
    ),
    ("a policy predicate holding a subquery", topTen, holding(subqueryPolicy))
  )

  private def rewrittenWith(eff: EffectiveSet, sql: String): String =
    // The flag as the router reads it, from the client's own text.
    val flag = ai.starlake.quack.edge.search.SearchFunctions.callsSearch(sql)
    rls.rewrite(sql, StatementKind.Select, eff, rlsCtx, flag) match
      case RowPolicyRewriter.Rewritten(out) => out
      case other                            => fail(s"expected Rewritten, got $other for: $sql")

  private def rewritten(sql: String): String = rewrittenWith(withRows, sql)

  private lazy val outputs: List[String] = examples.map((_, sql, eff) => rewrittenWith(eff, sql))

  private val GoldenPath = Paths.get("native/qod-node/tests/golden/search-rewriter-outputs.sql")

  private val GoldenHeader = List(
    "-- Generated by RowPolicyRewriterSearchDuckLakeSpec (search Phase 0 part 2, Task 12): the",
    "-- row-policy rewriter's output for the holder of policy f10 = 0 (one line: a predicate holding",
    "-- a subquery) on lake.main.pages, one statement per line. Read by search_engine_test (W1)."
  )

  private def goldenText: String = (GoldenHeader ++ outputs).mkString("", "\n", "\n")

  // ---------- the DuckLake catalog ----------

  private var base: Path = null

  override def beforeAll(): Unit =
    base = Files.createTempDirectory("qod-rls-ducklake-")
    Files.createDirectories(base.resolve("data"))

  override def afterAll(): Unit =
    if base != null then
      val paths = Files.walk(base)
      try paths.sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      finally paths.close()

  /** One connection on a fresh in-memory database with the lake attached and filled. The catalog
    * file and the data folder are created on the first call and reused (the tables are created
    * once).
    */
  private var filled = false

  private def withDb[A](f: java.sql.Statement => A): A =
    val conn = java.sql.DriverManager.getConnection("jdbc:duckdb:")
    try
      val st   = conn.createStatement()
      val meta = base.resolve("meta.ducklake").toString
      val data = base.resolve("data").toString + "/"
      st.execute("INSTALL ducklake")
      st.execute("LOAD ducklake")
      st.execute(s"ATTACH 'ducklake:$meta' AS lake (DATA_PATH '$data')")
      st.execute("USE lake")
      if !filled then
        st.execute(
          "CREATE TABLE lake.main.pages(id BIGINT, title VARCHAR, body VARCHAR, lang VARCHAR, " +
            "f10 INTEGER)"
        )
        st.execute(
          "INSERT INTO lake.main.pages SELECT i, 't' || i, 'w' || (i % 13) || ' w1', " +
            "CASE WHEN i % 2 = 0 THEN 'fr' ELSE 'en' END, (i % 10)::INTEGER FROM range(100) r(i) " +
            "ORDER BY i"
        )
        st.execute("CREATE TABLE lake.main.other(id BIGINT, body VARCHAR)")
        st.execute("INSERT INTO lake.main.other SELECT i * 3, 'w1 w2' FROM range(10) r(i)")
        st.execute("CREATE TABLE lake.main.ids(id BIGINT)")
        st.execute("INSERT INTO lake.main.ids SELECT 90 - 10 * i FROM range(10) r(i) ORDER BY i")
        // A view has no virtual rowid.
        st.execute("CREATE VIEW lake.main.pv AS SELECT * FROM lake.main.pages")
        filled = true
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

  /** The call replaced by `(SELECT rowid, 0.0 AS score FROM <its input>)`, as in Task 2. */
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
      sql.substring(0, at) + s"(SELECT rowid, 0.0 AS score FROM $input)" +
        sql.substring(close + 1)

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

  private def bindOk(sql: String): Boolean = bindError(sql).isEmpty

  private def bindRows(sql: String): List[List[String]] =
    withDb { st =>
      val rs   = st.executeQuery(sql)
      val n    = rs.getMetaData.getColumnCount
      val rows = List.newBuilder[List[String]]
      while rs.next() do rows += (1 to n).map(i => String.valueOf(rs.getString(i))).toList
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

  // ---------- the golden file ----------

  "the rewriter outputs" should "be captured to the node's golden file" in {
    // A plain Boolean: ScalaTest's assume prints the values of its expression when it cancels.
    val capture: Boolean = sys.env.contains("QOD_CAPTURE")
    assume(capture, "set QOD_CAPTURE to capture the golden file")
    Files.write(GoldenPath, goldenText.getBytes(StandardCharsets.UTF_8))
    info(s"wrote ${outputs.size} statements to ${GoldenPath.toAbsolutePath}")
  }

  it should "equal the node's golden file" in {
    val committed = new String(Files.readAllBytes(GoldenPath), StandardCharsets.UTF_8)
    committed shouldBe goldenText
  }

  it should "hold one statement per line, each keeping rowid in every wrap" in {
    outputs should have size examples.size.toLong
    for ((label, sql, _), out) <- examples.zip(outputs) do
      withClue(label) {
        out should not include "\n"
        out should include("(SELECT *, rowid FROM ")
        out should not include "(SELECT * FROM"
        info(s"$label: $out")
      }
  }

  it should "bind on DuckLake and read only rows of the policy" in {
    for ((label, sql, _), out) <- examples.zip(outputs) do
      withClue(s"$label: $out") {
        val rows = bindRows(withoutCall(out))
        rows should not be empty
        all(rows.map(_.head.toLong % 10)) shouldBe 0L
        // The client's statement, unrewritten, reads rows the policy hides: the check above is not
        // vacuous.
        val open = bindRows(withoutCall(sql))
        open.exists(_.head.toLong % 10 != 0) shouldBe true
      }
  }

  // Task 8 (ruling 3) taught RewriteScope the search calls: the client's scope is a reach that
  // names the call's target as a read, and the rewrite is compared with it, never vacuously.
  it should "pass RewriteScope against the client's statement, whose scope names the search target" in {
    val cfg = ai.starlake.acl.model.Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))
    for ((label, sql, _), out) <- examples.zip(outputs) do
      withClue(label) {
        RewriteScope.scopeOf(sql, cfg, "lake", false) match
          case RewriteScope.Scope.Reaches(gated, _) =>
            gated.map(a => a.table.canonical -> a.verb) should contain(
              "lake.main.pages" -> ai.starlake.acl.parser.Verb.Read
            )
          case other => fail(s"expected the client's scope to be a reach, got $other")
        // A policy predicate reading another table makes the rewrite reach it: refused by the
        // router's scope check, as for any statement, once the original's scope is judged.
        val beyond = RewriteScope.reachesBeyond(sql, out, cfg, "lake", false)
        if label == "a policy predicate holding a subquery" then
          beyond shouldBe Some(
            "the rewritten statement reaches what the original did not: lake.main.other (Read)"
          )
        else beyond shouldBe None
      }
  }

  // ---------- Task 2's binds, again on DuckLake ----------

  private val search = topTen

  "rule 3 on DuckLake" should "wrap the input and the join-back with rowid and keep true rowids" in {
    val out = rewritten(search)
    out should include(
      "qod_search((SELECT rowid FROM (SELECT *, rowid FROM lake.main.pages WHERE (f10 = 0)) AS " +
        "pages WHERE lang = 'fr'), 'lake.main.pages', ['body'], 'w1 w10') h JOIN (SELECT *, rowid " +
        "FROM lake.main.pages WHERE (f10 = 0)) p ON p.rowid = h.rowid"
    )
    // The policy's f10 = 0 admits ids 0, 10, ..., 90, all even, so all French.
    bindRows(withoutCall(out)).map(_.head.toLong).sorted shouldBe (0L to 90L by 10L).toList
  }

  it should "wrap bare, schema-qualified and aliased references alike" in {
    for (_, sql, _) <- examples.slice(2, 5) do
      val out = rewritten(sql)
      out should not include "(SELECT * FROM"
      out should include("SELECT *, rowid FROM")
      bindOk(withoutCall(out)) shouldBe true
  }

  it should "keep a policy predicate holding a subquery" in {
    val out = rewrittenWith(holding(subqueryPolicy), search)
    out should include(
      "(SELECT *, rowid FROM lake.main.pages WHERE (f10 IN (SELECT min(id % 10) FROM " +
        "lake.main.other)))"
    )
    bindOk(withoutCall(out)) shouldBe true
  }

  it should "over-include a name inside a string literal, adding rowid only" in {
    val out = rewritten("SELECT 'qod_search(' AS x, title FROM pages WHERE id = 10")
    out should include("(SELECT *, rowid FROM pages WHERE (f10 = 0)) AS pages")
    bindRows(out).map(_.mkString("|")) shouldBe List("qod_search(|t10")
  }

  // Change 43: with no search call the wrap is today's, and a holder's rowid read fails to bind.
  it should "leave a holder's rowid read with no search call failing to bind" in {
    val bare = rewritten("SELECT rowid FROM lake.main.pages WHERE id = 1")
    bare should include("(SELECT * FROM lake.main.pages WHERE (f10 = 0))")
    bindError(bare).getOrElse("") should include("Referenced column \"rowid\" not found")
    val joinBack = rewritten("SELECT p.title FROM lake.main.pages p WHERE p.rowid = 1")
    bindError(joinBack).getOrElse("") should include(
      "Values list \"p\" does not have a column named \"rowid\""
    )
  }

  private val viewPolicy = RoleRowPolicy("rp-3", "r-1", "lake", "main", "pv", "f10 = 0")

  "a row-policy view on DuckLake" should "make a search statement that reads it fail to bind" in {
    val eff   = holding(rowPolicy, viewPolicy)
    val first =
      "SELECT v.lang, p.title FROM pv v " +
        "JOIN qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h ON h.rowid = v.id " +
        "JOIN pages p ON p.rowid = h.rowid"
    val outFirst = rewrittenWith(eff, first)
    outFirst should include("(SELECT *, rowid FROM pv WHERE (f10 = 0)) v")
    bindError(withoutCall(outFirst)).getOrElse("") should include(
      "Referenced column \"rowid\" not found"
    )
    val after =
      "SELECT p.title, v.lang FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
        "JOIN pages p ON p.rowid = h.rowid JOIN pv v ON v.id = p.id"
    val outAfter = rewrittenWith(eff, after)
    outAfter should include("(SELECT *, rowid FROM pv WHERE (f10 = 0)) v")
    bindError(withoutCall(outAfter)).getOrElse("") should include(
      "Ambiguous reference to column name \"rowid\""
    )
  }

  it should "bind laterally to the rowid of the one FROM item before it" in {
    val eff = holding(rowPolicy, RoleRowPolicy("rp-5", "r-1", "lake", "main", "pv", "id = 4"))
    val out = rewrittenWith(
      eff,
      "SELECT v.id, v.rowid, h.rowid FROM qod_search((SELECT rowid FROM pages WHERE id = 30), " +
        "'pages', ['body'], 'x') h JOIN pv v ON true"
    )
    out should include("(SELECT *, rowid FROM pv WHERE (id = 4)) v")
    // pv's one admitted row is id 4; its `rowid` reads h's 30.
    bindRows(withoutCall(out)).map(_.mkString("|")) shouldBe List("4|30|30")
  }

  "the documented consequences on DuckLake" should "show rowid in SELECT *" in {
    val cols = bindColumns(
      withoutCall(
        rewritten(
          "SELECT * FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
            "JOIN pages p ON p.rowid = h.rowid"
        )
      )
    )
    info(s"columns: ${cols.mkString(", ")}")
    cols.count(_ == "rowid") shouldBe 2
  }

  it should "list rowid twice for p.*, p.rowid" in {
    val cols = bindColumns(
      withoutCall(
        rewritten(
          "SELECT p.*, p.rowid FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
            "JOIN pages p ON p.rowid = h.rowid"
        )
      )
    )
    info(s"columns: ${cols.mkString(", ")}")
    cols shouldBe List("id", "title", "body", "lang", "f10", "rowid", "rowid")
  }

  it should "carry a rowid field in a whole-row reference" in {
    val v = bindRows(
      withoutCall(
        rewritten(
          "SELECT p FROM qod_search((SELECT rowid FROM pages WHERE id = 30), 'pages', ['body'], " +
            "'x') h JOIN pages p ON p.rowid = h.rowid"
        )
      )
    )
    v.map(_.mkString) shouldBe List(
      "{'id': 30, 'title': t30, 'body': w4 w1, 'lang': fr, 'f10': 0, 'rowid': 30}"
    )
  }

  it should "shift a positional ORDER BY past the wrap's rowid" in {
    val sql =
      "SELECT p.*, h.score FROM qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h " +
        "JOIN pages p ON p.rowid = h.rowid ORDER BY 6 DESC"
    bindColumns(withoutCall(sql))(5) shouldBe "score"
    bindColumns(withoutCall(rewritten(sql)))(5) shouldBe "rowid"
  }

  it should "add rowid to a NATURAL JOIN's keys" in {
    def stmt(join: String) =
      s"SELECT count(*) FROM pages a $join " +
        "JOIN qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h ON h.rowid = a.id"
    def count(sql: String): String = bindRows(withoutCall(sql)).map(_.mkString).mkString
    // ids holds 90, 80, ..., 0 at rowids 0..9: every id is a multiple of 10, none at its own rowid.
    // No row policy: NATURAL JOIN keys on id alone, as USING (id) does.
    count(stmt("NATURAL JOIN ids b")) shouldBe "10"
    count(stmt("JOIN ids b USING (id)")) shouldBe "10"
    // Policy holder: the wrapped left side's rowid becomes a key too, and no row matches on both.
    count(rewritten(stmt("JOIN ids b USING (id)"))) shouldBe "10"
    count(rewritten(stmt("NATURAL JOIN ids b"))) shouldBe "0"
  }
