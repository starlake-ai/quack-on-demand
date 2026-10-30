package ai.starlake.acl.parser

import ai.starlake.acl.model.{Config, DenyReason, TableRef}
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

  // --- 3c': DuckDB resolves a bare `duckdb_tables` (no parentheses) to the catalog function when
  // no table of that name shadows it, so qualifying it as `db.main.duckdb_tables` and grant-checking
  // THAT would admit a schema-wide grant to an unfiltered catalog dump (issue #114).

  test(
    "bare duckdb_tables reference (no parentheses) is flagged unsupported, not qualified as a table"
  ) {
    val e = extracted("SELECT sql FROM duckdb_tables")
    e.accesses shouldBe empty
    e.unsupported.exists(_.contains("duckdb_tables")) shouldBe true
  }

  test("main- and system-qualified bare catalog function names are flagged too") {
    extracted("SELECT * FROM main.duckdb_views").unsupported.exists(
      _.contains("duckdb_views")
    ) shouldBe true
    extracted("SELECT * FROM system.main.duckdb_columns").unsupported
      .exists(_.contains("duckdb_columns")) shouldBe true
  }

  test("a fully-qualified non-system spelling stays an ordinary table ref") {
    // DuckDB only falls back to the function for unqualified / main / system spellings; a real
    // catalog's three-part name resolves to a table or errors, so it is grant-gated as a table.
    val e = extracted("SELECT * FROM db.main.duckdb_tables")
    e.unsupported shouldBe empty
    e.accesses.map(_.table) shouldBe Set(TableRef("db", "main", "duckdb_tables"))
  }

  test("a CTE named like a catalog function still shadows the bare reference") {
    val e = extracted("WITH duckdb_tables AS (SELECT 1 AS x) SELECT * FROM duckdb_tables")
    e.unsupported shouldBe empty
    e.accesses shouldBe empty
  }

  // --- 3c'': the whole class behind 3c'. DuckDB's default views in system.main and
  // system.pg_catalog (sqlite_master, duckdb_databases, pg_class, ...) resolve from an unqualified
  // name the same way, and several of them dump every catalog's DDL, table or column names.

  test("every DuckDB default view resolvable from a bare name is flagged unsupported") {
    for name <- List(
        "sqlite_master",
        "duckdb_databases",
        "pragma_database_list",
        "pg_class",
        "pg_attribute",
        "pg_tables",
        "duckdb_constraints",
        "duckdb_logs"
      )
    do
      val e = extracted(s"SELECT * FROM $name")
      withClue(name) {
        e.accesses shouldBe empty
        e.unsupported.exists(_.contains(name)) shouldBe true
      }
  }

  test("main- and system-qualified default views are flagged too") {
    extracted("SELECT * FROM main.sqlite_master").unsupported should not be empty
    extracted("SELECT * FROM system.main.duckdb_databases").unsupported should not be empty
    // An explicit non-main schema is a schema ref; under the system catalog it is then gated on
    // a catalog no tenant grant can match.
    val e = extracted("SELECT * FROM system.pg_catalog.pg_class")
    e.unsupported shouldBe empty
    e.accesses.map(_.table) shouldBe Set(TableRef("system", "pg_catalog", "pg_class"))
  }

  test("a default view under a real catalog stays an ordinary table ref") {
    val e = extracted("SELECT * FROM db.main.sqlite_master")
    e.unsupported shouldBe empty
    e.accesses.map(_.table) shouldBe Set(TableRef("db", "main", "sqlite_master"))
  }

  test("a schema-qualified pg_catalog reference stays a grant-gated schema ref") {
    // `pg_catalog.pg_tables` names the schema explicitly: that is the documented, grant-gated
    // surface (schema pg_catalog), not the bare-name class.
    val e = extracted("SELECT * FROM pg_catalog.pg_tables")
    e.unsupported shouldBe empty
    e.accesses.map(_.table) shouldBe Set(TableRef("db", "pg_catalog", "pg_tables"))
  }

  test("the prefix backstop catches a default view this build does not list, never a pg_ table") {
    // A future DuckDB adding, say, duckdb_variables as a default view must not reopen the class
    // on a manager built before it; pg_-prefixed names are only covered by the exact list so an
    // ordinary user table like pg_events keeps working.
    extracted("SELECT * FROM duckdb_some_future_view").unsupported should not be empty
    extracted("SELECT * FROM sqlite_something").unsupported should not be empty
    extracted("SELECT * FROM pragma_whatever").unsupported should not be empty
    val e = extracted("SELECT * FROM pg_events")
    e.unsupported shouldBe empty
    e.accesses.map(_.table) shouldBe Set(TableRef("db", "main", "pg_events"))
  }

  test("a CTE named like a default view still shadows the bare reference") {
    val e = extracted("WITH pg_class AS (SELECT 1 AS x) SELECT * FROM pg_class")
    e.unsupported shouldBe empty
    e.accesses shouldBe empty
  }

  test("the parenthesized call keeps its table-function marker, carrying the call shape") {
    val e = extracted("SELECT * FROM duckdb_tables()")
    e.accesses shouldBe empty
    e.unsupported should contain(TableExtractor.tableFunctionMarker("duckdb_tables()"))
    TableExtractor.catalogFunctionCall("duckdb_tables()") shouldBe Some("duckdb_tables")
    TableExtractor.catalogFunctionCall("\"duckdb_tables\"()") shouldBe Some("duckdb_tables")
    TableExtractor.catalogFunctionCall("duckdb_tables('x')") shouldBe None
    TableExtractor.catalogFunctionCall("main.duckdb_tables()") shouldBe None
    TableExtractor.catalogFunctionCall("duckdb_tables") shouldBe None
    extracted("SELECT * FROM duckdb_tables('x')").unsupported
      .exists(_.contains("duckdb_tables('x')")) shouldBe true
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

  test("MERGE matched delete-action AND predicate subquery captures the read source") {
    val sql =
      "MERGE INTO db.main.t USING (SELECT 1 AS n) s ON t.k = s.n " +
        "WHEN MATCHED AND (SELECT count(*) FROM db.main.secret) > 0 THEN DELETE"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
    accesses.exists(a =>
      a.table == TableRef("db", "main", "t") && a.verb == Verb.Write
    ) shouldBe true
  }

  test(
    "MERGE not-matched-by-source delete-action AND predicate subquery captures the read source"
  ) {
    val sql =
      "MERGE INTO db.main.t USING db.main.s ON t.k = s.k " +
        "WHEN NOT MATCHED BY SOURCE AND EXISTS (SELECT 1 FROM db.main.secret) THEN DELETE"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
  }

  // --- 3f: top-level WITH on DML statements is walked (CTE bodies are read sources,
  //         and an unqualified CTE reference does not launder as a base-table read) ---

  test("WITH-prefixed INSERT extracts the CTE body read source") {
    val sql =
      "WITH mine AS (SELECT * FROM db.main.secret) INSERT INTO mine SELECT * FROM mine"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
    accesses.exists(a =>
      a.table == TableRef("db", "main", "mine") && a.verb == Verb.Write
    ) shouldBe true
  }

  test("WITH-prefixed UPDATE extracts the CTE body read source") {
    val sql =
      "WITH s AS (SELECT id FROM db.main.secret) " +
        "UPDATE db.main.t SET c = 1 WHERE id IN (SELECT id FROM s)"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
    // the unqualified `s` in the IN subquery names the CTE, not a base table
    accesses.map(_.table) should not contain TableRef("db", "main", "s")
  }

  test("WITH-prefixed DELETE extracts the CTE body read source") {
    val sql =
      "WITH s AS (SELECT id FROM db.main.secret) " +
        "DELETE FROM db.main.t WHERE id IN (SELECT id FROM s)"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
    accesses.map(_.table) should not contain TableRef("db", "main", "s")
  }

  test("WITH-prefixed MERGE extracts the CTE body read source, not the CTE name") {
    val sql =
      "WITH s AS (SELECT * FROM db.main.secret) " +
        "MERGE INTO db.main.t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET c = s.c"
    val accesses = extracted(sql).accesses
    accesses.exists(a =>
      a.table == TableRef("db", "main", "secret") && a.verb == Verb.Read
    ) shouldBe true
    accesses.map(_.table) should not contain TableRef("db", "main", "s")
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

  // --- SET VARIABLE evaluates its value as a query: DuckDB (1.5.6 and 2.0) runs
  // `SET VARIABLE x = (SELECT v FROM db.other.t)` and `getvariable('x')` hands the row back, so a
  // SET whose value reads a table is a read, not table-free control flow ---

  test("SET VARIABLE whose value reads a table is not admitted as control flow") {
    List(
      "SET VARIABLE x = (SELECT v FROM db.other.t)",
      "SET VARIABLE x = (SELECT v FROM t)",
      "SET VARIABLE x = (SELECT string_agg(v, ',') FROM t WHERE v IN (SELECT v FROM db.other.u))",
      "SET VARIABLE x = (SELECT content FROM read_text('/etc/passwd'))",
      "EXPLAIN SET VARIABLE x = (SELECT v FROM db.other.t)"
    ).foreach { sql =>
      withClue(sql) {
        SqlParser.extract(sql, config).statements.head shouldBe a[StatementResult.ParseError]
      }
    }
  }

  test("a bare select passed as a function argument is walked (DuckDB ARRAY(SELECT ...))") {
    extracted("SELECT ARRAY(SELECT v FROM db.other.t)").accesses shouldBe Set(
      TableAccess(TableRef("db", "other", "t"), Verb.Read)
    )
    extracted("SELECT ARRAY(SELECT v FROM t)").accesses shouldBe Set(
      TableAccess(TableRef("db", "main", "t"), Verb.Read)
    )
    SqlParser
      .extract("SET VARIABLE x = ARRAY(SELECT v FROM db.other.t)", config)
      .statements
      .head shouldBe a[StatementResult.ParseError]
  }

  test("SET / SET VARIABLE with a table-free value stays control flow") {
    List("SET VARIABLE x = 42", "SET VARIABLE x = [1, 2, 3]", "SET threads = 4").foreach { sql =>
      withClue(sql) {
        SqlParser.extract(sql, config).statements.head shouldBe a[StatementResult.ControlFlow]
      }
    }
  }

  // --- metadata enumeration: DESCRIBE / SHOW <table> reveal a table's shape, so they
  // are Read accesses on that table rather than unconditionally admitted control flow ---

  test("DESCRIBE extracts a Read access on the described table") {
    extracted("DESCRIBE tpch1.customer").accesses shouldBe Set(
      TableAccess(TableRef("db", "tpch1", "customer"), Verb.Read)
    )
  }

  test("unqualified DESCRIBE resolves against the config defaults") {
    extracted("DESCRIBE customer").accesses shouldBe Set(
      TableAccess(TableRef("db", "main", "customer"), Verb.Read)
    )
  }

  test("DuckDB's SHOW <table> alias is treated like DESCRIBE") {
    extracted("SHOW tpch1.customer").accesses shouldBe Set(
      TableAccess(TableRef("db", "tpch1", "customer"), Verb.Read)
    )
  }

  test("unqualified SHOW COLUMNS FROM is treated like DESCRIBE") {
    extracted("SHOW COLUMNS FROM customer").accesses shouldBe Set(
      TableAccess(TableRef("db", "main", "customer"), Verb.Read)
    )
  }

  test("qualified SHOW COLUMNS FROM and SHOW ALL TABLES stay denied fail-closed") {
    // Neither form is in the grammar of the pinned parser, so both land on the
    // ParseError arm: no change here, just pinning that they are not admitted.
    SqlParser
      .extract("SHOW COLUMNS FROM tpch1.customer", config)
      .statements
      .head shouldBe a[StatementResult.ParseError]
    SqlParser
      .extract("SHOW ALL TABLES", config)
      .statements
      .head shouldBe a[StatementResult.ParseError]
  }

  test("SHOW TABLES stays ControlFlow") {
    SqlParser.extract("SHOW TABLES", config).statements.head shouldBe
      a[StatementResult.ControlFlow]
  }

  test("SHOW DATABASES and SHOW SCHEMAS stay ControlFlow, whatever their case") {
    for stmt <- List("SHOW DATABASES", "SHOW SCHEMAS", "show databases", "show schemas") do
      SqlParser.extract(stmt, config).statements.head shouldBe a[StatementResult.ControlFlow]
  }

  test("a data-modifying CTE is unsupported, not a crash (DuckDB 2.0 DML inside WITH)") {
    Seq(
      "WITH w AS (INSERT INTO db.main.t VALUES (1) RETURNING *) SELECT * FROM w",
      "WITH w AS (DELETE FROM db.main.t RETURNING *) SELECT * FROM w",
      "WITH w AS (UPDATE db.main.t SET a = 1 RETURNING *) SELECT * FROM w"
    ).foreach { sql =>
      withClue(sql) {
        extracted(sql).unsupported.exists(_.startsWith("data-modifying CTE")) shouldBe true
      }
    }
  }

  test("a data-modifying CTE inside a subquery is unsupported and its target is not a read") {
    val e = extracted(
      "SELECT * FROM (WITH w AS (DELETE FROM db.main.t RETURNING *) SELECT * FROM w) s"
    )
    e.unsupported.exists(_.startsWith("data-modifying CTE")) shouldBe true
    e.accesses.map(_.table) should not contain TableRef("db", "main", "t")
  }

  test("an ordinary CTE still extracts its reads") {
    val e = extracted("WITH w AS (SELECT * FROM db.main.t) SELECT * FROM w")
    e.unsupported shouldBe empty
    e.accesses.map(_.table) should contain(TableRef("db", "main", "t"))
  }

  test("a four-part (nested schema) name is a qualification error, never a three-part ref") {
    val e = extracted("SELECT * FROM db.finance.reports.r")
    e.qualificationErrors should contain(DenyReason.NestedSchemaRef("db.finance.reports.r"))
    e.accesses.map(_.table) should not contain TableRef("finance", "reports", "r")
  }

  test("a four-part DML target is a qualification error too") {
    extracted("INSERT INTO db.finance.reports.r VALUES (1)").qualificationErrors should contain(
      DenyReason.NestedSchemaRef("db.finance.reports.r")
    )
  }

  // The generic dialect maps names through the ANSI mapper: the guard must hold there too, or
  // a four-part name qualifies as some three-part ref the grants may cover.
  private val genericConfig = Config.forGeneric("db", "main")

  private def extractedGeneric(sql: String): StatementResult.Extracted =
    SqlParser.extract(sql, genericConfig).statements.head match
      case e: StatementResult.Extracted => e
      case other => fail(s"expected Extracted, got ${other.getClass.getSimpleName}: $other")

  test("a four-part name is a qualification error on the generic dialect") {
    val e = extractedGeneric("SELECT * FROM db.finance.reports.r")
    e.qualificationErrors should contain(DenyReason.NestedSchemaRef("db.finance.reports.r"))
    e.accesses shouldBe empty
  }

  test("a four-part DML target is a qualification error on the generic dialect") {
    val e = extractedGeneric("INSERT INTO db.finance.reports.r SELECT * FROM db.main.t")
    e.qualificationErrors should contain(DenyReason.NestedSchemaRef("db.finance.reports.r"))
    e.accesses.map(_.table) should not contain TableRef("finance", "reports", "r")
  }

  test("three-part and shorter names still qualify on the generic dialect") {
    extractedGeneric("SELECT * FROM db.main.t JOIN s.u ON true JOIN v ON true").accesses
      .map(_.table) shouldBe Set(
      TableRef("db", "main", "t"),
      TableRef("db", "s", "u"),
      TableRef("db", "main", "v")
    )
  }
