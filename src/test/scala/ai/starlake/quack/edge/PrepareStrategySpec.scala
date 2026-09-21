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

  // ---- I2 regression: a leading trivia character must not desync this reader from `kind` ----
  //
  // `kind` is `StatementClassifier`'s verdict, computed over a fully trivia-normalized copy of
  // the SQL. This method's own verb reader used to run its own hand-rolled first-token scan over
  // the RAW `sql`, with no trivia handling of its own. A BOM (or any other leading trivia
  // character) ahead of EXPLAIN left `kind` correctly Select but this reader's own token as
  // "<BOM>EXPLAIN", matching no entry in `NotSubquerySafe`, so a previously-working prepared
  // EXPLAIN took the ProbeWrap path and DuckDB rejected the resulting
  // `SELECT * FROM (<BOM>EXPLAIN ...) LIMIT 0` with a parser error naming a query the caller never
  // wrote. Verified against a real DuckDB 1.5.4 that a BOM- or NBSP-prefixed EXPLAIN executes
  // identically to the unprefixed form. Every character below is a literal `\uXXXX` escape, never
  // a raw invisible byte (see the byte-hygiene test at the end of this file).
  it should "fall back to full execute for a BOM-prefixed EXPLAIN, not wrap it as a subquery" in:
    PrepareStrategy.choose("\uFEFFEXPLAIN SELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute

  it should "fall back to full execute for an NBSP-prefixed EXPLAIN, not wrap it as a subquery" in:
    PrepareStrategy.choose("\u00A0EXPLAIN SELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute

  it should "fall back to full execute for a BOM-prefixed SHOW / DESCRIBE too" in:
    PrepareStrategy.choose("\uFEFFSHOW TABLES", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute
    PrepareStrategy.choose("\uFEFFDESCRIBE customer", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute

  // ---- escape hygiene of this file's own invisible-character test literals ----
  //
  // Every trivia character exercised above is written as a literal `\uXXXX` escape, never as a
  // raw invisible byte pasted into the source -- see the identical guard in
  // `StatementClassifierSpec`, `LockdownScreenSpec`, `CatalogWriteScreenSpec` and `SqlTriviaSpec`
  // for why this matters: an editor or an "helpful" formatting pass can silently decode the
  // escape back into a raw invisible character, at which point the file still compiles and every
  // assertion above still passes, with no way for the next reader to tell.
  it should "carry no raw non-ASCII codepoints in its own source file" in:
    val path = "src/test/scala/ai/starlake/quack/edge/PrepareStrategySpec.scala"
    val file = new java.io.File(path)
    withClue(s"expected to find $path relative to the working directory ${file.getAbsolutePath}") {
      file.exists shouldBe true
    }
    val src = scala.io.Source.fromFile(file, "UTF-8")
    try
      val offenders = src.mkString.zipWithIndex.filter { case (c, _) => c.toInt > 0x7f }
      withClue(s"found non-ASCII codepoints at offsets ${offenders.map(_._2).mkString(", ")}: ") {
        offenders shouldBe empty
      }
    finally src.close()
