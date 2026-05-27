package ai.starlake.acl.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ai.starlake.acl.model.{Config, DenyReason, TableRef}

class SqlParserMultiStatementTest extends AnyFunSuite with Matchers {

  private val config = Config.forGeneric("testdb", "public")

  // --- Multi-statement ---

  test("extract tables from two SELECT statements") {
    val sql = "SELECT * FROM t1; SELECT * FROM t2"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 2
    result.statements.foreach(_ shouldBe a[StatementResult.Extracted])
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "t1"),
      TableRef("testdb", "public", "t2")
    )
  }

  test("handle mix of SELECT and INSERT") {
    val sql = "SELECT * FROM t1; INSERT INTO t2 VALUES (1)"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 2
    result.statements(0) shouldBe a[StatementResult.Extracted]
    result.statements(1) shouldBe a[StatementResult.NonSelect]
  }

  test("handle invalid statement among valid ones") {
    val sql = "SELECT * FROM t1; GARBLED NONSENSE; SELECT * FROM t2"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 3
    result.statements(0) shouldBe a[StatementResult.Extracted]
    result.statements(1) shouldBe a[StatementResult.ParseError]
    result.statements(2) shouldBe a[StatementResult.Extracted]
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "t1"),
      TableRef("testdb", "public", "t2")
    )
  }

  test("handle all-invalid input") {
    val sql = "COMPLETELY INVALID"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head shouldBe a[StatementResult.ParseError]
    result.allTables shouldBe empty
  }

  test("handle empty input") {
    val sql = ""
    val result = SqlParser.extract(sql, config)
    // Empty input should produce either empty statements or a ParseError
    result.statements.foreach {
      case _: StatementResult.ParseError  => () // acceptable
      case _: StatementResult.Extracted   => () // acceptable if empty
      case other                          => fail(s"Unexpected for empty input: $other")
    }
  }

  test("handle whitespace-only input") {
    val sql = "   "
    val result = SqlParser.extract(sql, config)
    // Whitespace-only should not crash; result depends on parser behavior
    result.statements.foreach {
      case _: StatementResult.ParseError  => () // acceptable
      case _: StatementResult.Extracted   => () // acceptable if empty
      case other                          => fail(s"Unexpected for whitespace input: $other")
    }
  }

  test("extract tables across UNION in multi-statement") {
    val sql = "SELECT * FROM a UNION SELECT * FROM b; SELECT * FROM c"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 2
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "a"),
      TableRef("testdb", "public", "b"),
      TableRef("testdb", "public", "c")
    )
  }

  test("handle semicolons within quoted strings") {
    val sql = "SELECT * FROM t1 WHERE name = 'a;b'"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head shouldBe a[StatementResult.Extracted]
    result.allTables shouldBe Set(TableRef("testdb", "public", "t1"))
  }

  test("statement index is correct across multi-statement") {
    val sql = "SELECT * FROM t1; SELECT * FROM t2; SELECT * FROM t3"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 3
    result.statements.zipWithIndex.foreach { case (stmt, expectedIdx) =>
      val actualIdx = stmt match {
        case StatementResult.Extracted(idx, _, _, _) => idx
        case StatementResult.ParseError(idx, _, _)   => idx
        case StatementResult.NonSelect(idx, _, _)    => idx
      }
      actualIdx shouldBe expectedIdx
    }
  }

  test("sql snippet is truncated for long queries") {
    val longTable = "t" + ("x" * 250)
    val sql = s"SELECT * FROM $longTable"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    val snippet = result.statements.head match {
      case StatementResult.Extracted(_, s, _, _) => s
      case StatementResult.ParseError(_, s, _)   => s
      case StatementResult.NonSelect(_, s, _)    => s
    }
    snippet.length should be <= 203 // 200 + "..."
  }

  // --- Qualification errors ---

  test("UnqualifiedTable error for missing database default") {
    val noDbConfig = Config.forGeneric(defaultSchema = Some("public"))
    val result = SqlParser.extract("SELECT * FROM orders", noDbConfig)
    result.statements should have size 1
    val stmt = result.statements.head.asInstanceOf[StatementResult.Extracted]
    stmt.tables shouldBe empty
    stmt.qualificationErrors should have size 1
    val error = stmt.qualificationErrors.head.asInstanceOf[DenyReason.UnqualifiedTable]
    error.missingPart shouldBe "database"
  }

  test("UnqualifiedTable error for missing schema default") {
    val noSchemaConfig = Config.forGeneric(defaultDatabase = Some("db"))
    val result = SqlParser.extract("SELECT * FROM orders", noSchemaConfig)
    result.statements should have size 1
    val stmt = result.statements.head.asInstanceOf[StatementResult.Extracted]
    stmt.tables shouldBe empty
    stmt.qualificationErrors should have size 1
    val error = stmt.qualificationErrors.head.asInstanceOf[DenyReason.UnqualifiedTable]
    error.missingPart shouldBe "schema"
  }

  test("fully qualified table works with no defaults") {
    val noDefaultsConfig = Config.forGeneric(None, None)
    val result = SqlParser.extract("SELECT * FROM mydb.myschema.orders", noDefaultsConfig)
    result.statements should have size 1
    val stmt = result.statements.head.asInstanceOf[StatementResult.Extracted]
    stmt.tables shouldBe Set(TableRef("mydb", "myschema", "orders"))
    stmt.qualificationErrors shouldBe empty
  }
}
