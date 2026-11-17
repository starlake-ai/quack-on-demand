package ai.starlake.quack.edge.search

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The star rule of search spec 4.2 (owner ruling 2026-11-14): a statement calling a search
  * function holds no star projection, for every principal.
  */
class SearchStarScreenSpec extends AnyFlatSpec with Matchers:

  import SearchStarScreen.{refusal, Code}

  private val call = "qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h"
  private val join = s"FROM $call JOIN pages p ON p.rowid = h.rowid"

  private def refused(sql: String): Boolean = refusal(sql).exists(_.startsWith(Code))

  "the search star screen" should "refuse each star projection form" in {
    val forms = List(
      s"SELECT * $join",
      s"SELECT p.* $join",
      s"SELECT h.score, p.* $join",
      s"SELECT DISTINCT p.* $join",
      s"SELECT * EXCLUDE (body) $join",
      s"SELECT p.* EXCLUDE (body) $join",
      s"SELECT * REPLACE (upper(title) AS title) $join",
      s"SELECT COLUMNS(*) $join",
      s"SELECT max(COLUMNS(*)) $join",
      s"SELECT COLUMNS('ti.*') $join",
      s"SELECT h.score FROM (SELECT * FROM pages) q JOIN $call ON h.rowid = q.id",
      s"WITH c AS (SELECT * FROM pages) SELECT h.score FROM c JOIN $call ON h.rowid = c.id",
      s"SELECT h.score $join WHERE p.id IN (SELECT * FROM range(3))",
      s"SELECT h.score $join UNION ALL SELECT * FROM range(1)",
      s"SELECT h.score $join ORDER BY (SELECT max(r) FROM (SELECT * FROM range(3) t(r)))",
      s"SELECT h.score $join; SELECT * FROM pages"
    )
    for sql <- forms do withClue(sql)(refused(sql) shouldBe true)
    // Each form parses, so the refusal is the star's and not the unparsable fallback's.
    for sql <- forms do
      val first = ai.starlake.sql.JSqlStatements.firstText(
        ai.starlake.sql.ParserInput.render(sql).toOption.get.text
      )
      val parses = scala.util.Try(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(first)).isSuccess
      withClue(sql)(parses shouldBe true)
  }

  // Review of fix round 2, I2 and M5: stars no star node carries.
  it should "refuse the implicit stars" in {
    val forms = List(
      s"FROM $call JOIN pages p ON p.rowid = h.rowid",
      s"SELECT h.score, q.x FROM $call JOIN (SELECT x FROM (FROM pages) x) q ON true",
      s"SELECT h.score FROM $call UNION ALL FROM pages",
      s"SELECT h.score FROM $call UNION ALL TABLE pages",
      s"SELECT (FROM pages LIMIT 1), h.score FROM $call",
      s"SELECT h.score FROM $call WHERE EXISTS (FROM pages)",
      s"SELECT h.score FROM $call, LATERAL (FROM pages) l",
      s"DESCRIBE SELECT h.score $join",
      s"SUMMARIZE SELECT h.score $join"
    )
    for sql <- forms do withClue(sql)(refused(sql) shouldBe true)
    // Each form parses, so the refusal is the implicit star's, not the unparsable fallback's.
    for sql <- forms do
      val parses = scala.util
        .Try(
          net.sf.jsqlparser.parser.CCJSqlParserUtil
            .parse(ai.starlake.sql.ParserInput.render(sql).toOption.get.text)
        )
        .isSuccess
      withClue(sql)(parses shouldBe true)
  }

  it should "allow a FROM-first query whose select list is explicit" in {
    refusal(s"FROM $call JOIN pages p ON p.rowid = h.rowid SELECT p.title, h.score") shouldBe None
    refusal(s"FROM pages SELECT title WHERE id IN (SELECT h.rowid FROM $call)") shouldBe None
  }

  it should "carry the stable code and say to list the columns" in {
    val msg = refusal(s"SELECT * $join").get
    msg should startWith("search_star_unsupported: ")
    msg should include("list the columns")
  }

  it should "allow count(*), count(t.*), their window and filter forms, and multiplication" in {
    val allowed = List(
      s"SELECT count(*) $join",
      s"SELECT count(p.*) $join",
      s"SELECT count(*) OVER () $join",
      s"SELECT count(*) FILTER (WHERE p.cat = 3) $join",
      s"SELECT h.score * 2, p.id * p.cat $join",
      s"SELECT p.title, h.score $join ORDER BY h.score * -1",
      s"SELECT p.title $join WHERE p.id IN (SELECT count(*) FROM pages)"
    )
    for sql <- allowed do withClue(sql)(refusal(sql) shouldBe None)
  }

  it should "leave a statement with no search call unaffected" in {
    refusal("SELECT * FROM pages") shouldBe None
    refusal("SELECT p.*, COLUMNS(*) FROM pages p") shouldBe None
    refusal("SELECT * FROM qod_searcher(1)") shouldBe None
    refusal("SELECT * FROM t -- qod_search(") shouldBe None
  }

  it should "refuse through the detector's over-inclusion too" in {
    refused("SELECT 'qod_search(' AS x, * FROM pages") shouldBe true
    refused("WITH qod_search(a) AS (SELECT 1) SELECT * FROM qod_search") shouldBe true
    refusal("SELECT 'qod_search(' AS x, id FROM pages") shouldBe None
  }

  it should "refuse a search statement it cannot parse" in {
    refused(s"SELECT p.title $join WHERE (((") shouldBe true
    refused(s"SELECT p.title $join; SELEKT 1") shouldBe true
    refused("SELECT qod_search(") shouldBe true
  }

  // Task 18 fix round 1 (lead ruling A): DuckDB's time-travel clause, which JSqlParser cannot read
  // (`AT (VERSION => n)`), is looked past for the star check alone; nothing sent is rewritten.
  it should "judge a search statement with a time-travel clause on its stars alone" in {
    val at = List(
      "AT (VERSION => 6)",
      "AT (TIMESTAMP => TIMESTAMPTZ '2026-11-17 10:00:00+00')",
      "at ( version => 6 )"
    )
    for clause <- at do
      val input = s"qod_search((SELECT rowid FROM lake.main.pages $clause), 'lake.main.pages', " +
        "['body'], 'x') h"
      val joined = s"FROM $input JOIN lake.main.pages $clause p ON p.rowid = h.rowid"
      withClue(clause) {
        refusal(s"SELECT h.rowid, h.score, h.rank FROM $input ORDER BY h.rank") shouldBe None
        refusal(s"SELECT p.id, p.title, h.score $joined") shouldBe None
        refused(s"SELECT * FROM $input") shouldBe true
        refused(s"SELECT p.*, h.score $joined") shouldBe true
        refused(
          s"SELECT h.score FROM $input UNION ALL SELECT * FROM lake.main.pages $clause"
        ) shouldBe
          true
      }
  }

  it should "refuse a time-travel clause that holds a query or a star" in {
    for clause <- List(
        "AT (VERSION => (SELECT max(v) FROM t))",
        "AT (VERSION => (SELECT * FROM t))",
        "AT (VERSION => 2 * 3)",
        "AT (VERSION => (FROM t))"
      )
    do
      withClue(clause)(
        refused(
          s"SELECT h.rowid FROM qod_search((SELECT rowid FROM pages $clause), 'pages', ['body'], " +
            "'x') h"
        ) shouldBe true
      )
  }

  // Lead ruling A: each statement of a batch is judged on its own, and transaction control holds
  // no projection.
  it should "judge a batch with transaction control by its other statements" in {
    val search = s"SELECT p.id $join"
    val insert = "INSERT INTO pages VALUES (1, 't1', 'x lake')"
    for (begin, end) <- List(
        "BEGIN"             -> "COMMIT",
        "BEGIN TRANSACTION" -> "COMMIT TRANSACTION",
        "begin work"        -> "end",
        "START TRANSACTION" -> "ROLLBACK",
        "BEGIN"             -> "END TRANSACTION",
        "BEGIN"             -> "rollback work"
      )
    do
      withClue(s"$begin ... $end") {
        refusal(s"$begin; $insert; $search; $end") shouldBe None
        refusal(s"$begin; $search; $end;") shouldBe None
        refused(s"$begin; $insert; SELECT p.*, h.score $join; $end") shouldBe true
        refused(s"$begin; SELECT * $join; $end") shouldBe true
      }
  }

  it should "still refuse a batch statement that reads as transaction control but is not one" in {
    val search = s"SELECT p.id $join"
    for head <- List(
        "BEGIN foo",
        "BEGIN 1",
        "COMMIT AND CHAIN x",
        "START",
        "END x",
        "BEGIN; SELEKT 1"
      )
    do withClue(head)(refused(s"$head; $search") shouldBe true)
  }

  // Task 18 fix round 2 (review M3): every word that opens a query or lists columns, in a clause.
  it should "refuse a time-travel clause holding a pivot, a summary, a description or a listing" in {
    for clause <- List(
        "AT (VERSION => (PIVOT t ON a))",
        "AT (VERSION => (UNPIVOT t ON a INTO NAME k VALUE v))",
        "AT (VERSION => (SUMMARIZE t))",
        "AT (VERSION => (DESCRIBE t))",
        "AT (VERSION => (SHOW t))"
      )
    do
      withClue(clause)(
        refused(
          s"SELECT h.rowid FROM qod_search((SELECT rowid FROM pages $clause), 'pages', ['body'], " +
            "'x') h"
        ) shouldBe true
      )
  }

  // Task 18 fix round 2 (review M4): the rest of DuckDB's transaction control, and nothing longer.
  it should "skip ABORT and a transaction start with its mode" in {
    val search = s"SELECT p.id $join"
    for (begin, end) <- List(
        "BEGIN TRANSACTION READ ONLY"                      -> "ABORT",
        "BEGIN READ WRITE"                                 -> "ABORT TRANSACTION",
        "START TRANSACTION READ ONLY"                      -> "COMMIT",
        "START TRANSACTION READ WRITE"                     -> "abort work",
        "BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE"   -> "COMMIT",
        "START TRANSACTION ISOLATION LEVEL READ COMMITTED" -> "ROLLBACK",
        "begin isolation level repeatable read"            -> "end"
      )
    do
      withClue(s"$begin ... $end") {
        refusal(s"$begin; $search; $end") shouldBe None
        refused(s"$begin; SELECT * $join; $end") shouldBe true
      }
    for head <- List(
        "BEGIN SELECT * FROM pages",
        "BEGIN TRANSACTION READ ONLY READ WRITE",
        "BEGIN READ",
        "BEGIN ISOLATION LEVEL",
        "ABORT x",
        "BEGIN TRANSACTION READ ONLY x"
      )
    do withClue(head)(refused(s"$head; $search") shouldBe true)
    // Not skipped, so parsed: JSqlParser reads two modes, which hold no star.
    refusal(s"START TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY; $search") shouldBe None
  }

  // Task 18 fix round 2 (security O1, lead ruling): a whole-row reference is a star. Over-refusal
  // when a real column has a FROM item's name is accepted.
  it should "refuse a whole-row reference to a FROM item at any depth of a select list" in {
    val from = "FROM lake.main.pages p, qod_search((SELECT rowid FROM lake.main.pages), " +
      "'lake.main.pages', ['body'], 'x') h WHERE p.rowid = h.rowid"
    for sel <- List(
        "p",
        "unnest(p)",
        "to_json(p)",
        "p::VARCHAR",
        "CAST(p AS VARCHAR)",
        "struct_pack(r := p)",
        "h",
        "h.score, coalesce(NULL, p)",
        "\"P\"",
        "(SELECT p)"
      )
    do withClue(sel)(refused(s"SELECT $sel $from") shouldBe true)
    // A FROM item's own table name, with no alias.
    refused(
      "SELECT pages FROM lake.main.pages, qod_search((SELECT rowid FROM lake.main.pages), " +
        "'lake.main.pages', ['body'], 'x') h WHERE pages.rowid = h.rowid"
    ) shouldBe true
    for sel <- List("p.id", "p.title, h.score", "id", "upper(p.title)", "h.rank + 1")
    do withClue(sel)(refusal(s"SELECT $sel $from") shouldBe None)
  }

  // Task 18 fix round 3 (review M7): a whole row handed to a table function in FROM.
  it should "refuse a whole-row reference in a table function's arguments" in {
    val search = "qod_search((SELECT rowid FROM lake.main.pages), 'lake.main.pages', ['body'], " +
      "'x') h"
    for fn <- List(
        "unnest([p]) AS u(x)",
        "unnest([to_json(p)]) AS u(x)",
        "range(length(p::VARCHAR)) r"
      )
    do
      withClue(fn)(
        refused(
          s"SELECT h.score FROM lake.main.pages p JOIN $search ON p.rowid = h.rowid, $fn"
        ) shouldBe
          true
      )
    for fn <- List("unnest([p.id]) AS u(x)", "range(3) r")
    do
      withClue(fn)(
        refusal(
          s"SELECT h.score FROM lake.main.pages p JOIN $search ON p.rowid = h.rowid, $fn"
        ) shouldBe
          None
      )
  }

  // Task 18 fix round 3 (security R2-1): DuckDB's dot-call form on a FROM name reads the whole row.
  it should "refuse a dot-call on a FROM name at any depth" in {
    val from = "FROM lake.main.pages p, qod_search((SELECT rowid FROM lake.main.pages), " +
      "'lake.main.pages', ['body'], 'x') h WHERE p.rowid = h.rowid"
    for sel <- List(
        "p.to_json()",
        "upper(p.to_json())",
        "h.score, p.to_json()::VARCHAR",
        "(SELECT p.to_json())",
        "h.to_json()"
      )
    do withClue(sel)(refused(s"SELECT $sel $from") shouldBe true)
    refused(
      "SELECT pages.to_json() FROM lake.main.pages, qod_search((SELECT rowid FROM " +
        "lake.main.pages), 'lake.main.pages', ['body'], 'x') h WHERE pages.rowid = h.rowid"
    ) shouldBe true
    for sel <- List("p.id", "p.title || 'x'", "p.title.upper()", "upper(p.title)")
    do withClue(sel)(refusal(s"SELECT $sel $from") shouldBe None)
  }

  it should "say that a whole-row reference is refused too" in {
    SearchStarScreen.Message should include("whole-row reference")
  }

  // Task 18 fix round 4 (security R3-1, lead ruling): every expression of a search statement is
  // judged, wherever it sits, by the walk that reaches every child; a whole row in a predicate is
  // refused too (accepted over-refusal).
  it should "refuse a whole-row reference in every position of a search statement" in {
    val call  = "qod_search((SELECT rowid FROM pages), 'pages', ['body'], 'x') h"
    val forms = List(
      // R3-1: VALUES rows in FROM.
      s"SELECT v.x FROM pages p, LATERAL (VALUES (p)) v(x), $call",
      s"SELECT v.x FROM pages p, LATERAL (VALUES (p.to_json())) v(x), $call",
      // JOIN ON, WHERE, GROUP BY, HAVING, ORDER BY, QUALIFY, a window, LIMIT, OFFSET.
      s"SELECT h.score FROM $call JOIN pages p ON p IS NOT NULL",
      s"SELECT h.score FROM pages p, $call WHERE p IS NOT NULL",
      s"SELECT count(h.score) FROM pages p, $call GROUP BY p",
      s"SELECT count(h.score) FROM pages p, $call GROUP BY p.id HAVING count(p) > 0",
      s"SELECT h.score FROM pages p, $call ORDER BY p",
      s"SELECT h.score FROM pages p, $call QUALIFY row_number() OVER (ORDER BY p.to_json()) = 1",
      s"SELECT h.score, row_number() OVER (PARTITION BY p) FROM pages p, $call",
      s"SELECT h.score FROM pages p, $call LIMIT length(p::VARCHAR)",
      s"SELECT h.score FROM pages p, $call LIMIT 1 OFFSET length(p::VARCHAR)",
      // A CTE body, a subquery at depth, a set operation arm, RETURNING.
      s"WITH c AS (SELECT p.to_json() AS j FROM pages p) SELECT h.score FROM c, $call",
      s"SELECT h.score FROM $call WHERE h.rowid IN (SELECT q.id FROM pages q WHERE q IS NOT NULL)",
      s"SELECT h.score FROM $call UNION ALL SELECT length(to_json(p)) FROM pages p",
      s"DELETE FROM pages p WHERE p.rowid IN (SELECT h.rowid FROM $call) RETURNING p.to_json()"
    )
    for sql <- forms do
      withClue(sql) {
        // Each form parses, so the refusal is the whole row's, not the unparsable fallback's.
        val text = ai.starlake.sql.ParserInput.renderWithoutTimeTravel(sql).toOption.get.text
        scala.util.Try(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(text)).isSuccess shouldBe
          true
        refused(sql) shouldBe true
      }
    val allowed = List(
      s"SELECT p.id FROM pages p, $call WHERE p.rowid = h.rowid",
      s"SELECT p.title.upper() FROM pages p, $call WHERE p.id > 0 ORDER BY p.id",
      s"SELECT u.x FROM pages p, $call, unnest([p.id]) AS u(x)",
      s"SELECT r.range FROM pages p JOIN $call ON p.rowid = h.rowid, range(3) r",
      s"SELECT v.x FROM pages p, LATERAL (VALUES (p.id)) v(x), $call",
      s"SELECT count(h.score) FROM pages p, $call GROUP BY p.id HAVING count(p.id) > 0 LIMIT 3"
    )
    for sql <- allowed do withClue(sql)(refusal(sql) shouldBe None)
  }
