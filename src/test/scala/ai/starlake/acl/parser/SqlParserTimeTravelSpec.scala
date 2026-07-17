package ai.starlake.acl.parser

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SqlParserTimeTravelSpec extends AnyFlatSpec with Matchers:

  private val config = Config.forGeneric("testdb", "public")

  private def headExtracted(sql: String): Set[(String, Verb)] =
    SqlParser.extract(sql, config).statements.headOption match
      case Some(StatementResult.Extracted(_, _, accesses, _, _)) =>
        accesses.map(a => a.table.canonical -> a.verb)
      case other =>
        fail(s"expected Extracted, got $other")

  "stripTimeTravelClauses" should "remove an AT (VERSION => n) clause" in {
    SqlParser
      .stripTimeTravelClauses("""SELECT * FROM "s"."t" AT (VERSION => 480)""")
      .trim shouldBe """SELECT * FROM "s"."t""""
  }

  it should "remove an AT (TIMESTAMP => ...) clause with a quoted literal" in {
    SqlParser
      .stripTimeTravelClauses(
        "SELECT * FROM t AT (TIMESTAMP => '2026-01-01 10:00:00') WHERE x = 1"
      ) shouldBe "SELECT * FROM t  WHERE x = 1"
  }

  it should "leave the clause alone inside a string literal" in {
    val sql = "SELECT 'AT (VERSION => 5)' FROM t"
    SqlParser.stripTimeTravelClauses(sql) shouldBe sql
  }

  it should "leave a parenthesized expression after a bare at identifier alone" in {
    val sql = "SELECT at (x) FROM t"
    SqlParser.stripTimeTravelClauses(sql) shouldBe sql
  }

  it should "handle balanced parens inside the clause value" in {
    SqlParser
      .stripTimeTravelClauses(
        "SELECT * FROM t AT (TIMESTAMP => now() - INTERVAL (1) DAY)"
      )
      .trim shouldBe "SELECT * FROM t"
  }

  it should "not treat a word ending in at as the keyword" in {
    val sql = "SELECT format (x) FROM t"
    SqlParser.stripTimeTravelClauses(sql) shouldBe sql
  }

  "extract" should "authorize a time-travel SELECT as a plain read" in {
    headExtracted("SELECT * FROM t AT (VERSION => 480)") shouldBe Set(
      "testdb.public.t" -> Verb.Read
    )
  }

  it should "authorize the restore CTAS as Ddl plus Read on the table" in {
    headExtracted(
      "CREATE OR REPLACE TABLE t AS SELECT * FROM t AT (VERSION => 480)"
    ) shouldBe Set(
      "testdb.public.t" -> Verb.Ddl,
      "testdb.public.t" -> Verb.Read
    )
  }
