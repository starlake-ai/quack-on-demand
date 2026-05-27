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