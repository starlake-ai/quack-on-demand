package ai.starlake.quack.route

import ai.starlake.quack.model.StatementKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StatementClassifierSpec extends AnyFlatSpec with Matchers:

  "StatementClassifier" should "classify SELECT" in:
    StatementClassifier.classify("SELECT * FROM t") shouldBe StatementKind.Select

  it should "classify the DuckDB / BigQuery FROM-first shorthand as Select" in:
    // `FROM t` and variations are valid read queries in DuckDB / BigQuery
    // pipe-syntax; route them to a ReadOnly node, not Other.
    StatementClassifier.classify("FROM t") shouldBe StatementKind.Select
    StatementClassifier.classify("FROM t SELECT a, b") shouldBe StatementKind.Select
    StatementClassifier.classify("from acme.public.financials") shouldBe StatementKind.Select
    StatementClassifier.classify("  FROM t JOIN u ON t.id = u.id") shouldBe StatementKind.Select

  it should "classify INSERT/UPDATE/DELETE as DML" in:
    StatementClassifier.classify("INSERT INTO t VALUES (1)") shouldBe StatementKind.Dml
    StatementClassifier.classify("UPDATE t SET x = 1") shouldBe StatementKind.Dml
    StatementClassifier.classify("DELETE FROM t WHERE x = 1") shouldBe StatementKind.Dml

  it should "classify DDL" in:
    StatementClassifier.classify("CREATE TABLE t (x INT)") shouldBe StatementKind.Ddl
    StatementClassifier.classify("DROP TABLE t") shouldBe StatementKind.Ddl
    StatementClassifier.classify("ALTER TABLE t ADD x INT") shouldBe StatementKind.Ddl
    StatementClassifier.classify("TRUNCATE TABLE t") shouldBe StatementKind.Ddl

  it should "classify transaction control" in:
    StatementClassifier.classify("BEGIN") shouldBe StatementKind.Begin
    StatementClassifier.classify("BEGIN TRANSACTION") shouldBe StatementKind.Begin
    StatementClassifier.classify("START TRANSACTION") shouldBe StatementKind.Begin
    StatementClassifier.classify("COMMIT") shouldBe StatementKind.Commit
    StatementClassifier.classify("ROLLBACK") shouldBe StatementKind.Rollback

  it should "classify unknown / DuckDB-specific as Other" in:
    StatementClassifier.classify("PRAGMA threads = 4") shouldBe StatementKind.Other
    StatementClassifier.classify("SET search_path = 'main'") shouldBe StatementKind.Other
    StatementClassifier.classify("") shouldBe StatementKind.Other

  it should "be case-insensitive and tolerate whitespace" in:
    StatementClassifier.classify("  select 1") shouldBe StatementKind.Select
    StatementClassifier.classify("\nINSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "strip a leading single-line comment before classifying" in:
    StatementClassifier.classify("-- a comment\nSELECT 1") shouldBe StatementKind.Select
    StatementClassifier.classify(
      "-- noop\n-- another\nINSERT INTO t VALUES (1)"
    ) shouldBe StatementKind.Dml

  it should "strip a leading block comment before classifying" in:
    StatementClassifier.classify("/* preamble */ SELECT 1") shouldBe StatementKind.Select
    StatementClassifier.classify(
      "/* multi\n   line */\nCREATE TABLE t (x INT)"
    ) shouldBe StatementKind.Ddl

  it should "strip mixed comments interleaved with the statement" in:
    StatementClassifier.classify(
      "-- pre\n/* mid */ UPDATE t SET x = 1 -- trailing"
    ) shouldBe StatementKind.Dml

  it should "preserve quoted strings that look like comments" in:
    // The `--` is inside a string literal and must NOT be treated as a comment.
    StatementClassifier.classify(
      "INSERT INTO t VALUES ('-- not a comment')"
    ) shouldBe StatementKind.Dml

  // ---- WITH-prefixed statements classify by their real verb (deep-review H2) ----
  //
  // First-token classification put every WITH-prefixed statement in the select bucket,
  // so a top-level WITH ... INSERT/UPDATE/DELETE/MERGE got kind=Select: routed to a
  // reader node, admitted by ProtectedWriteGuard's non-write short-circuit, passed
  // through the RLS/CLS rewriters, and never audited or author-stamped as a write.

  it should "classify a WITH-prefixed DML by the verb after the CTE list" in:
    StatementClassifier.classify(
      "WITH s AS (SELECT * FROM t) INSERT INTO m SELECT * FROM s"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "WITH s AS (SELECT 1) UPDATE m SET x = 1"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "with s as (select 1) delete from m where x in (select * from s)"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "WITH RECURSIVE s(a) AS (SELECT 1) " +
        "MERGE INTO m USING s ON m.id = s.a WHEN MATCHED THEN UPDATE SET x = 1"
    ) shouldBe StatementKind.Dml

  it should "classify a WITH-prefixed DDL by the verb after the CTE list" in:
    StatementClassifier.classify(
      "WITH s AS (SELECT 1) CREATE TABLE m AS SELECT * FROM s"
    ) shouldBe StatementKind.Ddl

  it should "still classify WITH-prefixed SELECTs as Select" in:
    StatementClassifier.classify(
      "WITH cte AS (SELECT 1) SELECT * FROM cte"
    ) shouldBe StatementKind.Select
    StatementClassifier.classify(
      "WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a JOIN b ON true"
    ) shouldBe StatementKind.Select
    // parens and verbs inside string literals must not desync the scan
    StatementClassifier.classify(
      "WITH s AS (SELECT '(' || ') INSERT ' AS p) SELECT * FROM s"
    ) shouldBe StatementKind.Select
    StatementClassifier.classify(
      "WITH s AS (SELECT $$) INSERT $$ AS p) SELECT * FROM s"
    ) shouldBe StatementKind.Select

  // ---- EXPLAIN ANALYZE executes its inner statement (deep-review H3) ----

  it should "classify EXPLAIN ANALYZE by the statement it executes" in:
    StatementClassifier.classify(
      "EXPLAIN ANALYZE INSERT INTO m SELECT * FROM t"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "explain analyze delete from m where x = 1"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "EXPLAIN ANALYZE WITH s AS (SELECT 1) UPDATE m SET x = 1"
    ) shouldBe StatementKind.Dml

  it should "keep plain EXPLAIN (no ANALYZE) and EXPLAIN ANALYZE SELECT as Select" in:
    StatementClassifier.classify("EXPLAIN SELECT 1") shouldBe StatementKind.Select
    // without ANALYZE the inner statement is planned, never executed
    StatementClassifier.classify(
      "EXPLAIN INSERT INTO m VALUES (1)"
    ) shouldBe StatementKind.Select
    StatementClassifier.classify(
      "EXPLAIN ANALYZE SELECT * FROM t"
    ) shouldBe StatementKind.Select

  // ---- configurable keyword set ----

  "StatementClassifierConfig.parseCsv" should "trim whitespace and drop empties" in:
    StatementClassifierConfig.parseCsv("SELECT,WITH , VALUES,,EXPLAIN ") shouldBe Set(
      "SELECT",
      "WITH",
      "VALUES",
      "EXPLAIN"
    )

  it should "return an empty set for null / blank input" in:
    StatementClassifierConfig.parseCsv(null) shouldBe Set.empty
    StatementClassifierConfig.parseCsv("") shouldBe Set.empty
    StatementClassifierConfig.parseCsv("   ") shouldBe Set.empty

  "A custom-configured classifier" should "honour operator-extended keywords" in:
    // Operator adds DuckDB's PRAGMA to the select bucket; default would
    // have classified PRAGMA as Other.
    val cfg = StatementClassifierConfig.Defaults.copy(
      select = StatementClassifierConfig.Defaults.select + "PRAGMA"
    )
    val c = new StatementClassifier(cfg)
    c.classify("PRAGMA threads = 4") shouldBe StatementKind.Select
    // Untouched buckets keep working.
    c.classify("INSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "honour operator-replaced keywords (defaults are REPLACED, not merged)" in:
    // If an operator overrides `select` with just SELECT, FROM/WITH/etc.
    // no longer classify -- they fall through to Other. Documents the
    // replace-not-merge semantic.
    val cfg = StatementClassifierConfig.Defaults.copy(select = Set("SELECT"))
    val c   = new StatementClassifier(cfg)
    c.classify("SELECT 1") shouldBe StatementKind.Select
    c.classify("WITH cte AS (SELECT 1) SELECT * FROM cte") shouldBe StatementKind.Other
    c.classify("FROM t") shouldBe StatementKind.Other

  it should "fail-closed when a bucket is empty" in:
    // An empty bucket never matches. A custom config that wipes DML
    // means INSERT no longer reads as DML; it falls to Other (which
    // routes like a read). Documents the misconfiguration outcome.
    val cfg = StatementClassifierConfig.Defaults.copy(dml = Set.empty)
    val c   = new StatementClassifier(cfg)
    c.classify("INSERT INTO t VALUES (1)") shouldBe StatementKind.Other
