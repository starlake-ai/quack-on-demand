package ai.starlake.acl.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ai.starlake.acl.model.TableRef

class StatementResultTest extends AnyFunSuite with Matchers {

  test("Extracted variant is constructible with correct fields") {
    val tables = Set(TableRef("db", "schema", "orders"))
    val result: StatementResult.Extracted =
      StatementResult.Extracted(0, "SELECT * FROM orders", tables)
    result.index shouldBe 0
    result.sqlSnippet shouldBe "SELECT * FROM orders"
    result.tables shouldBe tables
  }

  test("ParseError variant is constructible with correct fields") {
    val result: StatementResult.ParseError =
      StatementResult.ParseError(1, "INVALID SQL", "unexpected token")
    result.index shouldBe 1
    result.sqlSnippet shouldBe "INVALID SQL"
    result.message shouldBe "unexpected token"
  }

  test("NonSelect variant is constructible with correct fields") {
    val result: StatementResult.NonSelect =
      StatementResult.NonSelect(2, "INSERT INTO t VALUES (1)", "Insert")
    result.index shouldBe 2
    result.sqlSnippet shouldBe "INSERT INTO t VALUES (1)"
    result.statementType shouldBe "Insert"
  }

  test("all variants are subtypes of StatementResult") {
    val extracted: StatementResult =
      StatementResult.Extracted(0, "sql", Set.empty)
    val parseError: StatementResult =
      StatementResult.ParseError(0, "sql", "msg")
    val nonSelect: StatementResult =
      StatementResult.NonSelect(0, "sql", "Insert")

    extracted shouldBe a[StatementResult]
    parseError shouldBe a[StatementResult]
    nonSelect shouldBe a[StatementResult]
  }

  test("ExtractionResult.fromStatements with empty list") {
    val result = ExtractionResult.fromStatements(List.empty)
    result.statements shouldBe Nil
    result.allTables shouldBe Set.empty
  }

  test("ExtractionResult.fromStatements unions tables from Extracted variants") {
    val t1 = TableRef("db", "s", "orders")
    val t2 = TableRef("db", "s", "users")
    val t3 = TableRef("db", "s", "products")

    val results = List(
      StatementResult.Extracted(0, "s1", Set(t1, t2)),
      StatementResult.ParseError(1, "s2", "error"),
      StatementResult.Extracted(2, "s3", Set(t2, t3)),
      StatementResult.NonSelect(3, "s4", "Insert")
    )

    val extraction = ExtractionResult.fromStatements(results)
    extraction.statements shouldBe results
    extraction.allTables shouldBe Set(t1, t2, t3)
  }

  test("ExtractionResult.fromStatements deduplicates tables across statements") {
    val t1 = TableRef("db", "s", "orders")
    val results = List(
      StatementResult.Extracted(0, "s1", Set(t1)),
      StatementResult.Extracted(1, "s2", Set(t1))
    )

    val extraction = ExtractionResult.fromStatements(results)
    extraction.allTables shouldBe Set(t1)
    extraction.allTables.size shouldBe 1
  }

  test("truncateSnippet returns short strings unchanged") {
    truncateSnippet("short") shouldBe "short"
    truncateSnippet("") shouldBe ""
    truncateSnippet("x" * 200) shouldBe ("x" * 200)
  }

  test("truncateSnippet truncates long strings with ellipsis") {
    val long = "x" * 300
    val result = truncateSnippet(long)
    result shouldBe ("x" * 200 + "...")
    result.length shouldBe 203
  }

  test("truncateSnippet respects custom maxLen") {
    truncateSnippet("hello world", maxLen = 5) shouldBe "hello..."
    truncateSnippet("hello", maxLen = 5) shouldBe "hello"
    truncateSnippet("hi", maxLen = 5) shouldBe "hi"
  }
}
