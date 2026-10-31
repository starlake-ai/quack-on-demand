package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Statements JSqlParser maps to a shape that names its table somewhere other than a FROM item:
  * SUMMARIZE (an ExplainStatement carrying a bare table), DESCRIBE of a query, and the
  * parenthesized statement forms DuckDB accepts in table position (`(SUMMARIZE t)`, `(TABLE t)`,
  * ...), which JSqlParser reads as a table named after the keyword with the real table as its
  * alias. Each table here is the one DuckDB 2.0's json_serialize_sql reports for the statement, so
  * the parse must read exactly that table, and refuse a string literal in its place. Pinned on
  * [[RewriteScope]], the parse the policy steps keep.
  */
class RewriteScopeStatementFormsSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  private def scope(sql: String): RewriteScope.Scope =
    RewriteScope.scopeOf(sql, cfg, "lake", clientDataScans = false)

  private def gated(sql: String): Set[String] =
    scope(sql) match
      case RewriteScope.Scope.Reaches(g, _) if g.nonEmpty => g.map(_.table.canonical)
      case other => fail(s"expected tables, got $other for: $sql")

  private def refused(sql: String): String =
    scope(sql) match
      case RewriteScope.Scope.Refused(msg) => msg
      case other                           => fail(s"expected a refusal, got $other for: $sql")

  "SUMMARIZE of a table" should "be a Read on that table" in:
    gated("SUMMARIZE secret") shouldBe Set("lake.main.secret")
    gated("SUMMARIZE lake.main.secret") shouldBe Set("lake.main.secret")
    gated("summarize \"secret\"") shouldBe Set("lake.main.secret")

  it should "keep reading the query forms through their inner statement" in:
    gated("SUMMARIZE SELECT * FROM secret") shouldBe Set("lake.main.secret")
    gated("SUMMARIZE FROM secret") shouldBe Set("lake.main.secret")
    gated("SUMMARIZE (SELECT * FROM secret)") shouldBe Set("lake.main.secret")

  it should "refuse a string literal in the table's place" in:
    refused("SUMMARIZE '/data/p.csv'")

  it should "not take DuckDB's bare system views for a session-catalog table" in:
    // `SUMMARIZE duckdb_tables` summarizes the system view, which lists every catalog
    refused("SUMMARIZE duckdb_tables") should include("duckdb_tables")

  "DESCRIBE of a query" should "be a Read on every table the query reads" in:
    gated("DESCRIBE SELECT v FROM secret") shouldBe Set("lake.main.secret")
    gated("DESCRIBE FROM secret") shouldBe Set("lake.main.secret")
    gated("DESCRIBE (SELECT v FROM secret JOIN other ON true)") shouldBe
      Set("lake.main.secret", "lake.main.other")

  "DESCRIBE and SHOW of a string literal" should "be refused" in:
    refused("DESCRIBE '/data/p.csv'")
    refused("SHOW '/data/p.csv'")

  "a parenthesized statement in table position" should "gate the table it names" in:
    gated("SELECT * FROM (SUMMARIZE secret)") shouldBe Set("lake.main.secret")
    gated("SELECT * FROM (SUMMARIZE \"secret\") AS s") shouldBe Set("lake.main.secret")
    gated("SELECT * FROM (TABLE secret)") shouldBe Set("lake.main.secret")
    gated("SELECT * FROM (DESCRIBE secret)") shouldBe Set("lake.main.secret")
    gated("SELECT * FROM (SHOW secret)") shouldBe Set("lake.main.secret")
    gated("SELECT * FROM t JOIN (TABLE secret) ON true") shouldBe
      Set("lake.main.t", "lake.main.secret")
    gated("SELECT 1 WHERE 1 IN (SELECT count(*) FROM (TABLE secret))") shouldBe
      Set("lake.main.secret")

  it should "refuse a string literal in the table's place" in:
    refused("SELECT * FROM (SUMMARIZE '/data/p.csv')")
    refused("SELECT * FROM (DESCRIBE '/data/p.csv')")

  it should "leave an ordinary parenthesized table with an alias alone" in:
    gated("SELECT * FROM (secret s)") shouldBe Set("lake.main.secret")
