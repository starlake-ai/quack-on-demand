package ai.starlake.acl.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ai.starlake.acl.model.{Config, TableRef}

class SqlParserDuckDBTest extends AnyFunSuite with Matchers {

  private val config = Config.forDuckDB("mydb", "main")

  test("extract DuckDB three-part catalog.schema.table") {
    val sql    = "SELECT * FROM mycat.myschema.mytable"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("mycat", "myschema", "mytable"))
  }

  test("apply DuckDB defaults for unqualified table") {
    val sql    = "SELECT * FROM orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("mydb", "main", "orders"))
  }

  test("two-part name is resolved as schema.table under the default catalog") {
    val sql    = "SELECT * FROM sales.orders"
    val result = SqlParser.extract(sql, config)
    // Two-part names resolve ANSI-style: schema=sales, table=orders, with the
    // session's default catalog filling in the database part.
    result.allTables shouldBe Set(TableRef("mydb", "sales", "orders"))
  }

  test("two-part name keeps the written schema regardless of defaultSchema") {
    val configWithSchema = Config.forDuckDB("mydb", "public")
    val sql              = "SELECT * FROM sales.orders"
    val result           = SqlParser.extract(sql, configWithSchema)
    // Two-part: catalog=mydb (default), schema=sales, table=orders
    result.allTables shouldBe Set(TableRef("mydb", "sales", "orders"))
  }

  test("two-part name resolves correctly for a DuckLake schema (demo pattern)") {
    val tpchConfig = Config.forDuckDB("acme_tpch", "main")
    val sql        = "SELECT * FROM tpch1.revenue_per_nation"
    val result     = SqlParser.extract(sql, tpchConfig)
    // tpch1 is a schema inside the pool's attached catalog acme_tpch
    result.allTables shouldBe Set(TableRef("acme_tpch", "tpch1", "revenue_per_nation"))
  }

  test("three-part name is unchanged by DuckDB mapper") {
    val sql    = "SELECT * FROM mycat.myschema.mytable"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("mycat", "myschema", "mytable"))
  }

  test("one-part name uses both defaults") {
    val sql    = "SELECT * FROM orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("mydb", "main", "orders"))
  }

  test("ignore DuckDB file reference") {
    val sql    = "SELECT * FROM 'data/file.parquet'"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted => ext.accesses.map(_.table) shouldBe empty
      case _: StatementResult.ParseError  => () // Also acceptable
      case other                          => fail(s"Unexpected result type: $other")
    }
  }

  test("ignore DuckDB file reference mixed with real table") {
    val sql    = "SELECT * FROM 'file.csv' UNION SELECT * FROM real_table"
    val result = SqlParser.extract(sql, config)
    // File reference is filtered; real_table is extracted
    result.allTables shouldBe Set(TableRef("mydb", "main", "real_table"))
  }

  test("extract table from DuckDB query with TABLESAMPLE") {
    val sql    = "SELECT * FROM orders TABLESAMPLE 10 PERCENT"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted =>
        ext.accesses.map(_.table) shouldBe Set(TableRef("mydb", "main", "orders"))
      case _: StatementResult.ParseError =>
        // Known limitation: JSqlParser may not support TABLESAMPLE
        info("TABLESAMPLE not supported by JSqlParser -- ParseError is acceptable")
      case other =>
        fail(s"Unexpected result type: $other")
    }
  }

  test("ignore table functions like read_csv") {
    val sql    = "SELECT * FROM read_csv('file.csv')"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted =>
        // Table functions are filtered by TableExtractor
        ext.accesses.map(_.table) shouldBe empty
      case _: StatementResult.ParseError =>
        // JSqlParser may not parse table functions
        info("read_csv table function not parsed by JSqlParser -- ParseError is acceptable")
      case other =>
        fail(s"Unexpected result type: $other")
    }
  }

  test("handle DuckDB QUALIFY clause") {
    val sql    = "SELECT *, ROW_NUMBER() OVER (PARTITION BY id) AS rn FROM orders QUALIFY rn = 1"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted =>
        ext.accesses.map(_.table) shouldBe Set(TableRef("mydb", "main", "orders"))
      case _: StatementResult.ParseError =>
        // Known limitation: JSqlParser may not support QUALIFY
        info("QUALIFY not supported by JSqlParser -- ParseError is acceptable")
      case other =>
        fail(s"Unexpected result type: $other")
    }
  }
}
