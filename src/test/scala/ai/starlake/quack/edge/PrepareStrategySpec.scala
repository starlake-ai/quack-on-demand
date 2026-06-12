package ai.starlake.quack.edge

import ai.starlake.quack.model.StatementKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PrepareStrategySpec extends AnyFlatSpec with Matchers:

  "PrepareStrategy.choose" should "skip execute for DML so the INSERT does not run twice" in:
    PrepareStrategy.choose("INSERT INTO t VALUES (1)", StatementKind.Dml) shouldBe
      PrepareStrategy.SkipExecute

  it should "skip execute for DDL" in:
    PrepareStrategy.choose("CREATE TABLE t(x INT)", StatementKind.Ddl) shouldBe
      PrepareStrategy.SkipExecute

  it should "skip execute for transaction control" in:
    PrepareStrategy.choose("BEGIN", StatementKind.Begin) shouldBe PrepareStrategy.SkipExecute
    PrepareStrategy.choose("COMMIT", StatementKind.Commit) shouldBe PrepareStrategy.SkipExecute
    PrepareStrategy.choose("ROLLBACK", StatementKind.Rollback) shouldBe PrepareStrategy.SkipExecute

  it should "wrap a plain SELECT in a LIMIT-0 subquery probe" in:
    PrepareStrategy.choose("SELECT a, b FROM t WHERE c = 1", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap(
        "SELECT * FROM (SELECT a, b FROM t WHERE c = 1) AS _qod_probe LIMIT 0"
      )

  it should "wrap a CTE-prefixed SELECT" in:
    val sql = "WITH cte AS (SELECT 1) SELECT * FROM cte"
    PrepareStrategy.choose(sql, StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap(s"SELECT * FROM ($sql) AS _qod_probe LIMIT 0")

  it should "wrap a SELECT carrying ORDER BY (DuckDB allows ORDER BY in subqueries)" in:
    val sql = "SELECT a FROM t ORDER BY a"
    PrepareStrategy.choose(sql, StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap(s"SELECT * FROM ($sql) AS _qod_probe LIMIT 0")

  it should "fall back to full execute for SHOW (not subquery-safe)" in:
    PrepareStrategy.choose("SHOW TABLES", StatementKind.Select) shouldBe PrepareStrategy.FullExecute

  it should "fall back to full execute for EXPLAIN" in:
    PrepareStrategy.choose("EXPLAIN SELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute

  it should "fall back to full execute for DESCRIBE" in:
    PrepareStrategy.choose("DESCRIBE customer", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute

  it should "fall back to full execute for PRAGMA" in:
    PrepareStrategy.choose("PRAGMA database_list", StatementKind.Other) shouldBe
      PrepareStrategy.FullExecute

  it should "fall back to full execute when the statement contains a non-trailing semicolon" in:
    val multi = "SELECT 1; SELECT 2"
    PrepareStrategy.choose(multi, StatementKind.Select) shouldBe PrepareStrategy.FullExecute

  it should "tolerate a trailing semicolon on an otherwise wrappable SELECT" in:
    PrepareStrategy.choose("SELECT 1;", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT 1) AS _qod_probe LIMIT 0")

  it should "fall back to full execute for the conservative Other bucket" in:
    PrepareStrategy.choose("VACUUM", StatementKind.Other) shouldBe PrepareStrategy.FullExecute

  it should "ignore leading whitespace and comments when classifying SHOW-style verbs" in:
    PrepareStrategy.choose("  -- a comment\n  SHOW TABLES", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute

  it should "strip a leading line comment from the wrapped probe" in:
    PrepareStrategy.choose("-- foo\nSELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT 1) AS _qod_probe LIMIT 0")

  it should "strip a leading block comment from the wrapped probe" in:
    PrepareStrategy.choose("/* foo */ SELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT 1) AS _qod_probe LIMIT 0")

  it should "strip both comments and a trailing semicolon before wrapping" in:
    PrepareStrategy.choose("-- foo\nSELECT a FROM t ;", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT a FROM t) AS _qod_probe LIMIT 0")