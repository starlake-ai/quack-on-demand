package ai.starlake.acl.parser

import ai.starlake.acl.model.{Config, TableRef}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Regression tests for the ACL parser fail-open cluster (security-audit-2026-07-02, finding
  * #3/#4).
  *
  * Each case is a statement that USED to slip past the validator by yielding an empty (or
  * incomplete) access set. The parser must now either extract the smuggled table reference or flag
  * the construct as `unsupported` so [[ai.starlake.quack.edge.sql.PostgresAclValidator]] fails
  * closed.
  */
class SqlParserAclBypassSpec extends AnyFunSuite with Matchers:

  private val config = Config.forDuckDB("db", "main")

  private def extracted(sql: String): StatementResult.Extracted =
    val result = SqlParser.extract(sql, config)
    result.statements.head match
      case e: StatementResult.Extracted => e
      case other => fail(s"expected Extracted, got ${other.getClass.getSimpleName}: $other")

  // --- 3a: CTE name-shadowing no longer drops the real qualified table ---

  test("qualified table shadowed by a same-named CTE is still extracted") {
    val sql = "WITH lineitem AS (SELECT 1 AS x) SELECT * FROM db.main.lineitem"
    extracted(sql).accesses.map(_.table) should contain(TableRef("db", "main", "lineitem"))
  }

  test("unqualified CTE self-reference is still treated as a CTE (not a base table)") {
    val sql    = "WITH cte AS (SELECT * FROM real_table) SELECT * FROM cte"
    val tables = extracted(sql).accesses.map(_.table)
    tables should contain(TableRef("db", "main", "real_table"))
    tables should not contain TableRef("db", "main", "cte")
  }

  // --- 3b: parenthesized joins are walked, not dropped ---

  test("parenthesized join extracts both sides") {
    val sql = "SELECT * FROM (db.main.a JOIN db.main.b ON a.id = b.id)"
    extracted(sql).accesses.map(_.table) shouldBe Set(
      TableRef("db", "main", "a"),
      TableRef("db", "main", "b")
    )
  }

  // --- 3c: table functions are flagged unsupported (they escape the catalog boundary) ---

  test("read_parquet table function is flagged unsupported") {
    val e = extracted("SELECT * FROM read_parquet('/data/secret/*.parquet')")
    e.unsupported should not be empty
    e.unsupported.exists(_.contains("read_parquet")) shouldBe true
  }

  test("string-literal file reference is flagged unsupported") {
    val e = extracted("SELECT * FROM 'secret.parquet'")
    e.unsupported should not be empty
  }

  // --- 3d: UPDATE SET-clause subquery read source is captured ---

  test("UPDATE SET value subquery captures the read source") {
    val sql      = "UPDATE db.main.t SET c = (SELECT max(x) FROM db.main.secret) WHERE id = 1"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
    accesses.exists(a =>
      a.table == TableRef("db", "main", "t") && a.verb == Verb.Write
    ) shouldBe true
  }

  // --- 3e: MERGE action subquery read source is captured ---

  test("MERGE update-action subquery captures the read source") {
    val sql =
      "MERGE INTO db.main.t USING db.main.s ON (t.id = s.id) " +
        "WHEN MATCHED THEN UPDATE SET c = (SELECT max(x) FROM db.main.secret)"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
    accesses.exists(a =>
      a.table == TableRef("db", "main", "s") && a.verb == Verb.Read
    ) shouldBe true
    accesses.exists(a =>
      a.table == TableRef("db", "main", "t") && a.verb == Verb.Write
    ) shouldBe true
  }

  // --- 4: EXPLAIN ANALYZE <dml> is classified by its inner statement ---

  test("EXPLAIN ANALYZE DELETE is authorized as the DELETE it executes") {
    val e = extracted("EXPLAIN ANALYZE DELETE FROM db.main.t WHERE id = 1")
    e.accesses.exists(a =>
      a.table == TableRef("db", "main", "t") && a.verb == Verb.Write
    ) shouldBe true
  }

  test("plain EXPLAIN SELECT reports the SELECT's read access") {
    val e = extracted("EXPLAIN SELECT * FROM db.main.t")
    e.accesses.exists(a =>
      a.table == TableRef("db", "main", "t") && a.verb == Verb.Read
    ) shouldBe true
  }

  // --- 3f/3g: unknown statement types fail closed instead of admitting ---

  test("control-flow allowlist admits COMMIT/SET/USE with no table refs") {
    SqlParser.extract("COMMIT", config).statements.head shouldBe a[StatementResult.ControlFlow]
    SqlParser
      .extract("SET threads = 4", config)
      .statements
      .head shouldBe a[StatementResult.ControlFlow]
    SqlParser.extract("USE db1", config).statements.head shouldBe a[StatementResult.ControlFlow]
  }
