package ai.starlake.acl.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ai.starlake.acl.model.{Config, TableRef}

class SqlParserAdvancedTest extends AnyFunSuite with Matchers {

  private val config = Config.forGeneric("testdb", "public")

  // --- Nested subqueries ---

  test("extract tables from 3-level nested subquery") {
    val sql = "SELECT * FROM a WHERE id IN (SELECT id FROM b WHERE val IN (SELECT val FROM c))"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "a"),
      TableRef("testdb", "public", "b"),
      TableRef("testdb", "public", "c")
    )
  }

  test("extract tables from correlated subquery") {
    val sql = "SELECT * FROM orders o WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = o.cid)"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "customers")
    )
  }

  test("extract tables from scalar subquery in SELECT") {
    val sql =
      "SELECT o.*, (SELECT c.name FROM customers c WHERE c.id = o.cid) AS customer_name FROM orders o"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "customers")
    )
  }

  // --- Derived tables ---

  test("extract tables from derived table in FROM") {
    val sql = "SELECT * FROM (SELECT * FROM orders WHERE status = 'active') AS active_orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("testdb", "public", "orders"))
  }

  test("extract tables from nested derived tables") {
    val sql =
      "SELECT * FROM (SELECT * FROM (SELECT * FROM raw_events) AS events WHERE type = 'click') AS clicks"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("testdb", "public", "raw_events"))
  }

  // --- CTE edge cases ---

  test("extract tables from multiple CTEs") {
    val sql = "WITH a AS (SELECT * FROM t1), b AS (SELECT * FROM t2) SELECT * FROM a JOIN b ON a.id = b.id"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "t1"),
      TableRef("testdb", "public", "t2")
    )
  }

  test("extract tables from CTE referencing another CTE") {
    val sql =
      "WITH a AS (SELECT * FROM t1), b AS (SELECT * FROM a JOIN t2 ON a.id = t2.id) SELECT * FROM b"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "t1"),
      TableRef("testdb", "public", "t2")
    )
  }

  test("extract tables from recursive CTE") {
    val sql =
      """WITH RECURSIVE tree AS (
        |  SELECT * FROM nodes WHERE parent_id IS NULL
        |  UNION ALL
        |  SELECT n.* FROM nodes n JOIN tree t ON n.parent_id = t.id
        |) SELECT * FROM tree""".stripMargin
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("testdb", "public", "nodes"))
  }

  // --- Set operations ---

  test("extract tables from UNION ALL") {
    val sql = "SELECT * FROM current_orders UNION ALL SELECT * FROM archive_orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "current_orders"),
      TableRef("testdb", "public", "archive_orders")
    )
  }

  test("extract tables from INTERSECT") {
    val sql = "SELECT id FROM customers INTERSECT SELECT customer_id FROM orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "customers"),
      TableRef("testdb", "public", "orders")
    )
  }

  test("extract tables from EXCEPT") {
    val sql = "SELECT id FROM all_users EXCEPT SELECT id FROM banned_users"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "all_users"),
      TableRef("testdb", "public", "banned_users")
    )
  }

  test("extract tables from complex UNION with subqueries") {
    val sql = "SELECT * FROM a UNION SELECT * FROM b WHERE id IN (SELECT id FROM c)"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "a"),
      TableRef("testdb", "public", "b"),
      TableRef("testdb", "public", "c")
    )
  }

  // --- JOINs (comprehensive) ---

  test("extract tables from multiple JOINs") {
    val sql = "SELECT * FROM a JOIN b ON a.id = b.aid LEFT JOIN c ON b.id = c.bid CROSS JOIN d"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "a"),
      TableRef("testdb", "public", "b"),
      TableRef("testdb", "public", "c"),
      TableRef("testdb", "public", "d")
    )
  }

  test("extract tables from self-join") {
    val sql = "SELECT * FROM employees e1 JOIN employees e2 ON e1.manager_id = e2.id"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("testdb", "public", "employees"))
    result.allTables.size shouldBe 1
  }

  // --- Quoted identifiers ---

  test("extract and normalize quoted identifiers") {
    val sql = """SELECT * FROM "MySchema"."MyTable""""
    val result = SqlParser.extract(sql, config)
    result.allTables should have size 1
    val table = result.allTables.head
    table.database shouldBe "testdb"
    table.schema shouldBe "myschema"
    table.table shouldBe "mytable"
  }

  test("handle mixed quoted and unquoted") {
    val sql = """SELECT * FROM "Public".orders"""
    val result = SqlParser.extract(sql, config)
    result.allTables should have size 1
    val table = result.allTables.head
    table.database shouldBe "testdb"
    table.schema shouldBe "public"
    table.table shouldBe "orders"
  }

  // --- System tables ---

  test("extract information_schema tables") {
    val sql = "SELECT * FROM information_schema.columns WHERE table_name = 'orders'"
    val result = SqlParser.extract(sql, config)
    result.allTables should have size 1
    val table = result.allTables.head
    table.schema shouldBe "information_schema"
    table.table shouldBe "columns"
  }

  // --- Aliases ---

  test("ignore table aliases") {
    val sql = "SELECT o.id FROM orders AS o JOIN customers AS c ON o.cid = c.id"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "customers")
    )
  }

  // --- Deduplication ---

  test("deduplicate after qualification") {
    val sql = "SELECT * FROM orders JOIN public.orders ON 1=1"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("testdb", "public", "orders"))
    result.allTables.size shouldBe 1
  }

  // --- HAVING subqueries ---

  test("extract tables from HAVING subquery") {
    val sql =
      "SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id HAVING COUNT(*) > (SELECT AVG(cnt) FROM dept_counts)"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "employees"),
      TableRef("testdb", "public", "dept_counts")
    )
  }

  // --- Comments ---

  test("handle SQL with comments") {
    val sql =
      """SELECT * FROM orders -- main table
        |/* join customers */ JOIN customers ON orders.cid = customers.id""".stripMargin
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(
      TableRef("testdb", "public", "orders"),
      TableRef("testdb", "public", "customers")
    )
  }
}
