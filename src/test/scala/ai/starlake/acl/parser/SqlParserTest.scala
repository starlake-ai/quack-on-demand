package ai.starlake.acl.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ai.starlake.acl.model.{Config, DenyReason, TableRef}

class SqlParserTest extends AnyFunSuite with Matchers {

  private val config = Config.forGeneric("testdb", "public")

  // --- Basic extraction ---

  test("extract simple SELECT FROM") {
    val result = SqlParser.extract("SELECT * FROM orders", config)
    result.statements should have size 1
    result.allTables shouldBe Set(TableRef("testdb", "public", "orders"))
  }

  test("extract fully qualified table") {
    val result = SqlParser.extract("SELECT * FROM mydb.myschema.orders", config)
    result.allTables shouldBe Set(TableRef("mydb", "myschema", "orders"))
  }

  test("extract two-part qualified table") {
    val result = SqlParser.extract("SELECT * FROM sales.orders", config)
    result.allTables shouldBe Set(TableRef("testdb", "sales", "orders"))
  }

  // --- JOINs ---

  test("extract tables from JOIN") {
    val sql = "SELECT * FROM orders o JOIN customers c ON o.cid = c.id"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "customers")
    )
  }

  // --- Subqueries ---

  test("extract tables from WHERE subquery") {
    val sql = "SELECT * FROM orders WHERE cid IN (SELECT id FROM customers)"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "customers")
    )
  }

  test("extract tables from SELECT subquery") {
    val sql = "SELECT (SELECT name FROM departments LIMIT 1), * FROM employees"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "departments"),
      TableRef("testdb", "public", "employees")
    )
  }

  // --- CTEs ---

  test("exclude CTE names from results") {
    val sql = "WITH cte AS (SELECT * FROM orders) SELECT * FROM cte"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("testdb", "public", "orders"))
  }

  // --- Set operations ---

  test("extract tables from UNION") {
    val sql = "SELECT * FROM orders UNION SELECT * FROM archive_orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "archive_orders")
    )
  }

  // --- Parse errors ---

  test("return ParseError for invalid SQL") {
    val result = SqlParser.extract("INVALID SQL GARBAGE", config)
    result.statements should have size 1
    result.statements.head shouldBe a[StatementResult.ParseError]
  }

  // --- Non-SELECT ---

  test("return NonSelect for INSERT") {
    val result = SqlParser.extract("INSERT INTO orders VALUES (1)", config)
    result.statements should have size 1
    result.statements.head shouldBe a[StatementResult.NonSelect]
  }

  // --- Multi-statement ---

  test("handle multi-statement input") {
    val sql = "SELECT * FROM t1; SELECT * FROM t2"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 2
    result.statements.foreach(_ shouldBe a[StatementResult.Extracted])
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "t1"),
      TableRef("testdb", "public", "t2")
    )
  }

  // --- Deduplication ---

  test("deduplicate same table referenced multiple times") {
    val sql = "SELECT * FROM orders JOIN orders ON 1=1"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("testdb", "public", "orders"))
    result.allTables.size shouldBe 1
  }

  // --- Case normalization ---

  test("normalize table names to lowercase") {
    val sql = "SELECT * FROM MyDB.Public.ORDERS"
    val result = SqlParser.extract(sql, config)
    result.allTables should have size 1
    val table = result.allTables.head
    table.database shouldBe "mydb"
    table.schema shouldBe "public"
    table.table shouldBe "orders"
  }

  // --- File references ---

  test("ignore string-literal file references") {
    val sql = "SELECT * FROM 'data.parquet'"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted => ext.tables shouldBe empty
      case _: StatementResult.ParseError  => () // Also acceptable if JSqlParser can't parse it
      case other                          => fail(s"Unexpected result type: $other")
    }
  }

  // --- Missing defaults ---

  test("return UnqualifiedTable when database default missing") {
    val noDbConfig = Config.forGeneric(defaultSchema = Some("public"))
    val result = SqlParser.extract("SELECT * FROM orders", noDbConfig)
    result.statements should have size 1
    val stmt = result.statements.head.asInstanceOf[StatementResult.Extracted]
    stmt.tables shouldBe empty
    stmt.qualificationErrors should have size 1
    val error = stmt.qualificationErrors.head.asInstanceOf[DenyReason.UnqualifiedTable]
    error.missingPart shouldBe "database"
  }

  // --- extractSingle ---

  test("extractSingle returns single statement result") {
    val result = SqlParser.extractSingle("SELECT * FROM orders", config)
    result shouldBe a[StatementResult.Extracted]
    val extracted = result.asInstanceOf[StatementResult.Extracted]
    extracted.tables shouldBe Set(TableRef("testdb", "public", "orders"))
  }

  test("extractSingle returns ParseError for invalid SQL") {
    val result = SqlParser.extractSingle("INVALID SQL", config)
    result shouldBe a[StatementResult.ParseError]
  }

  // --- EXISTS subquery ---

  test("extract tables from EXISTS subquery") {
    val sql = "SELECT * FROM orders WHERE EXISTS (SELECT 1 FROM customers WHERE customers.id = orders.cid)"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "customers")
    )
  }
}
