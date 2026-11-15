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
