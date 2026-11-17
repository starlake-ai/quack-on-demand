package ai.starlake.quack.search

import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.policy.RewriteScope
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.model.{PoolKey, RoleDistribution, StatementKind}
import ai.starlake.quack.ondemand.PoolState
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Comparator

/** Search Phase 1, change 9 (ruling 2): the column-policy search separation's outputs, bound on a
  * DuckLake catalog through DuckDB JDBC, and written to
  * `native/qod-node/tests/golden/search-cls-outputs.sql`, one statement per line, which qod-node's
  * `search_engine_test` (case CS1) runs through the lineage check: every one is admitted, so rule 1
  * holds for the separation's input.
  *
  * The lake has the tables of `search_engine_test`'s fixture (`lake.main.pages(id, title, body,
  * lang, f10)`, `lake.main.other(id, body)`). The principals: a mask on `title` (the smoke stack's
  * `clsmask`), a mask on `lang` (a column the input's `WHERE` reads), and a mask on `title` with
  * the row policy `f10 = 0`, whose statements run through both rewriters in the router's order.
  * `qod_search` exists on no plain DuckDB, so a bind replaces the call by its own input subquery
  * with a constant score. Set QOD_CAPTURE to rewrite the golden file; without it, the file must
  * equal the current outputs.
  */
class SearchSeparationDuckLakeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val alice = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")

  private def mask(column: String, expr: String = "'***'") =
    RoleColumnPolicy(s"cp-$column", "r-1", "lake", "main", "pages", column, "mask", Some(expr))
  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "lake", "main", "pages", "f10 = 0")

  private val maskTitle = EffectiveSet(alice, Nil, Nil, Nil, Nil, List(mask("title")), Nil)
  // A mask reading its column, so the masked predicate still admits rows and the call runs (a
  // constant mask makes the predicate constant, and DuckDB then prunes the call's scan).
  private val maskLang =
    EffectiveSet(alice, Nil, Nil, Nil, Nil, List(mask("lang", "lower(lang)")), Nil)
  private val maskAndRows =
    EffectiveSet(alice, Nil, Nil, Nil, Nil, List(mask("title")), List(rowPolicy))

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
  private val clsCtx =
    ai.starlake.quack.edge.cls.SchemaContext(pathDb, pathSchema, Set("lake"))
  private val rlsCtx = ai.starlake.quack.edge.rls.SchemaContext(pathDb, pathSchema)

  private val catalog = new ColumnCatalog.MapCatalog(
    Map(
      ("lake", "main", "pages") -> List("id", "title", "body", "lang", "f10"),
      ("lake", "main", "other") -> List("id", "body")
    )
  )
  private val cls = new ColumnPolicyRewriter(catalog, enabled = true)
  private val rls = new RowPolicyRewriter(enabled = true)

  /** Both rewriters in the router's order, the flag read once from the client's text and the tables
    * handed from the column-policy step to the row-policy one.
    */
  private def sent(eff: EffectiveSet, sql: String): String =
    val flag = ai.starlake.quack.edge.search.SearchFunctions.callsSearch(sql)
    val r    = cls.rewriteWithTables(sql, StatementKind.Select, eff, clsCtx, flag).unsafeRunSync()
    val afterCls = r.outcome match
      case ColumnPolicyRewriter.Rewritten(out) => out
      case ColumnPolicyRewriter.Passthrough    => sql
      case other                               => fail(s"column-policy step: $other for: $sql")
    rls.rewrite(afterCls, StatementKind.Select, eff, rlsCtx, flag, r.realRowidTables) match
      case RowPolicyRewriter.Rewritten(out) => out
      case RowPolicyRewriter.Passthrough    => afterCls
      case other                            => fail(s"row-policy step: $other for: $afterCls")

  // ---------- the examples ----------

  private val topTen =
    "SELECT p.id, p.title, h.score FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = " +
      "'fr'), 'lake.main.pages', ['body'], 'w1 w10') h JOIN lake.main.pages p ON p.rowid = h.rowid " +
      "ORDER BY h.score DESC"

  private val facets =
    "SELECT p.f10, count(*) AS n FROM qod_search_matches((SELECT rowid FROM lake.main.pages WHERE " +
      "lang = 'fr'), 'lake.main.pages', ['body'], 'w1 w10') m JOIN lake.main.pages p ON p.rowid = " +
      "m.rowid GROUP BY ALL ORDER BY n DESC"

  /** (label, the client's statement, the principal, what the output must show). */
  private val examples: List[(String, String, EffectiveSet, String)] = List(
    ("Q14 S1, the join-back masked", topTen, maskTitle, "SELECT p.id, '***' AS title, h.score"),
    ("facets over qod_search_matches", facets, maskTitle, "qod_search_matches((SELECT rowid"),
    (
      "a mask on a column the input's WHERE reads",
      topTen,
      maskLang,
      "qod_search((SELECT rowid FROM lake.main.pages WHERE lower(lang) = 'fr')"
    ),
    (
      "bare reference, aliased input and join-back",
      "SELECT z.id, z.title, h.score FROM qod_search((SELECT q.rowid FROM pages q), 'pages', " +
        "['body'], 'w1 w10') h JOIN pages AS z ON z.rowid = h.rowid",
      maskTitle,
      "'***' AS title"
    ),
    (
      "schema-qualified reference, a named argument",
      "SELECT p.id, p.title FROM qod_search((SELECT rowid FROM main.pages), 'main.pages', " +
        "['body'], 'w1 w10', k := 5) h JOIN main.pages p ON p.rowid = h.rowid",
      maskTitle,
      "'w1 w10', k := 5) h"
    ),
    (
      "a mask and a row policy",
      topTen,
      maskAndRows,
      "(SELECT *, rowid FROM lake.main.pages WHERE (f10 = 0)) p"
    )
  )

  private lazy val outputs: List[String] = examples.map((_, sql, eff, _) => sent(eff, sql))

  private val GoldenPath = Paths.get("native/qod-node/tests/golden/search-cls-outputs.sql")

  private val GoldenHeader = List(
    "-- Generated by SearchSeparationDuckLakeSpec (search Phase 1, change 9): the column-policy",
    "-- search separation's output (then the row-policy rewriter's, for the holder of both kinds of",
    "-- policy) on lake.main.pages, one statement per line. Read by search_engine_test (CS1)."
  )

  private def goldenText: String = (GoldenHeader ++ outputs).mkString("", "\n", "\n")

  // ---------- the DuckLake catalog ----------

  private var base: Path = null

  override def beforeAll(): Unit =
    base = Files.createTempDirectory("qod-cls-ducklake-")
    Files.createDirectories(base.resolve("data"))

  override def afterAll(): Unit =
    if base != null then
      val paths = Files.walk(base)
      try paths.sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      finally paths.close()

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
        filled = true
      f(st)
    finally conn.close()

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

  /** Each search call replaced by `(SELECT rowid, 0.5 AS score, 1 AS rank FROM <its input>)`. */
  private def withoutCall(sql: String): String =
    val lowered = sql.toLowerCase
    val found   = List("qod_search_matches(", "qod_search(")
      .map(c => (lowered.indexOf(c), c))
      .filter(_._1 >= 0)
      .sortBy(_._1)
      .headOption
    found match
      case None             => sql
      case Some((at, call)) =>
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

  private def bindRows(sql: String): List[List[String]] =
    withDb { st =>
      val rs   = st.executeQuery(sql)
      val n    = rs.getMetaData.getColumnCount
      val rows = List.newBuilder[List[String]]
      while rs.next() do rows += (1 to n).map(i => String.valueOf(rs.getString(i))).toList
      rs.close()
      rows.result()
    }

  // ---------- the golden file ----------

  "the separation's outputs" should "be captured to the node's golden file" in {
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

  it should "hold one statement per line, each showing what its principal's policies do" in {
    outputs should have size examples.size.toLong
    for ((label, _, _, shows), out) <- examples.zip(outputs) do
      withClue(label) {
        out should not include "\n"
        out should include(shows)
        info(s"$label: $out")
      }
  }

  it should "bind on DuckLake, every masked value read as its mask" in {
    for ((label, _, eff, _), out) <- examples.zip(outputs) do
      withClue(s"$label: $out") {
        val rows = bindRows(withoutCall(out))
        rows should not be empty
        if out.contains("AS title") then all(rows.map(_(1))) shouldBe "***"
        if eff eq maskAndRows then all(rows.map(_.head.toLong % 10)) shouldBe 0L
        // The input's masked predicate still reads French rows only (even ids).
        if eff eq maskLang then all(rows.map(_.head.toLong % 2)) shouldBe 0L
      }
  }

  it should "pass RewriteScope against the client's statement" in {
    val cfg = ai.starlake.acl.model.Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))
    for ((label, sql, _, _), out) <- examples.zip(outputs) do
      withClue(label)(RewriteScope.reachesBeyond(sql, out, cfg, "lake", false) shouldBe None)
  }

  it should "leave a non-constant table as written, for the node's rule 7 to refuse (CS1)" in {
    val computed =
      topTen.replace("'lake.main.pages', ['body']", "'lake.main.' || 'pages', ['body']")
    sent(maskTitle, computed) should include(
      "qod_search((SELECT rowid FROM lake.main.pages WHERE lang = 'fr'), 'lake.main.' || " +
        "'pages', ['body'], 'w1 w10') h"
    )
  }
