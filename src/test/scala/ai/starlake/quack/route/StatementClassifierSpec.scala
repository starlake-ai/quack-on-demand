package ai.starlake.quack.route

import ai.starlake.quack.model.StatementKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StatementClassifierSpec extends AnyFlatSpec with Matchers:

  "StatementClassifier" should "classify SELECT" in:
    StatementClassifier.classify("SELECT * FROM t") shouldBe StatementKind.Select

  it should "classify INSERT/UPDATE/DELETE as DML" in:
    StatementClassifier.classify("INSERT INTO t VALUES (1)")   shouldBe StatementKind.Dml
    StatementClassifier.classify("UPDATE t SET x = 1")          shouldBe StatementKind.Dml
    StatementClassifier.classify("DELETE FROM t WHERE x = 1")   shouldBe StatementKind.Dml

  it should "classify DDL" in:
    StatementClassifier.classify("CREATE TABLE t (x INT)")   shouldBe StatementKind.Ddl
    StatementClassifier.classify("DROP TABLE t")             shouldBe StatementKind.Ddl
    StatementClassifier.classify("ALTER TABLE t ADD x INT")  shouldBe StatementKind.Ddl
    StatementClassifier.classify("TRUNCATE TABLE t")         shouldBe StatementKind.Ddl

  it should "classify transaction control" in:
    StatementClassifier.classify("BEGIN")               shouldBe StatementKind.Begin
    StatementClassifier.classify("BEGIN TRANSACTION")   shouldBe StatementKind.Begin
    StatementClassifier.classify("START TRANSACTION")   shouldBe StatementKind.Begin
    StatementClassifier.classify("COMMIT")              shouldBe StatementKind.Commit
    StatementClassifier.classify("ROLLBACK")            shouldBe StatementKind.Rollback

  it should "classify unknown / DuckDB-specific as Other" in:
    StatementClassifier.classify("PRAGMA threads = 4") shouldBe StatementKind.Other
    StatementClassifier.classify("SET search_path = 'main'") shouldBe StatementKind.Other
    StatementClassifier.classify("") shouldBe StatementKind.Other

  it should "be case-insensitive and tolerate whitespace" in:
    StatementClassifier.classify("  select 1") shouldBe StatementKind.Select
    StatementClassifier.classify("\nINSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "strip a leading single-line comment before classifying" in:
    StatementClassifier.classify("-- a comment\nSELECT 1") shouldBe StatementKind.Select
    StatementClassifier.classify("-- noop\n-- another\nINSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "strip a leading block comment before classifying" in:
    StatementClassifier.classify("/* preamble */ SELECT 1")           shouldBe StatementKind.Select
    StatementClassifier.classify("/* multi\n   line */\nCREATE TABLE t (x INT)") shouldBe StatementKind.Ddl

  it should "strip mixed comments interleaved with the statement" in:
    StatementClassifier.classify("-- pre\n/* mid */ UPDATE t SET x = 1 -- trailing") shouldBe StatementKind.Dml

  it should "preserve quoted strings that look like comments" in:
    // The `--` is inside a string literal and must NOT be treated as a comment.
    StatementClassifier.classify("INSERT INTO t VALUES ('-- not a comment')") shouldBe StatementKind.Dml