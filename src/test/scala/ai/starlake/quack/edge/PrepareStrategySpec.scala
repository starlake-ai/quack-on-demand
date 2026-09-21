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

  // ---- I2: the two readers of the same SQL must agree ----
  //
  // `choose` reads the statement twice: once through `StatementClassifier` (which produced the
  // `kind` it is handed) and once itself, to build the probe. `stripped` skipped the leading strip
  // the classifier does, so on a nested leading comment the two disagreed: `kind` came back
  // `Select` while `stripped` still carried the text between the inner and outer close. The probe
  // then went out as `SELECT * FROM (c */ SELECT 1) AS _qod_probe LIMIT 0`, which a real DuckDB
  // 1.5.4 rejects with `Parser Error: syntax error at or near "*/"` -- a prepared statement that
  // worked before, now failing and naming a query the caller never wrote. The original
  // `/* a /* b */ c */ SELECT 1 AS n` executes fine on that same DuckDB, and the probe this test
  // pins parses and returns zero rows.
  //
  // MUTATION NOTE, because it matters for what these three tests actually pin: TWO independent
  // changes close this, and each one alone masks a revert of the other. Reverting only the leading
  // strip here leaves them green (the stripper now nests, so it removes the comment itself);
  // reverting only the stripper's nesting leaves them green too (the leading strip has always
  // nested, and it runs first). They fail with both reverted, which is the real pre-fix state.
  // So they pin the OUTCOME -- a probe DuckDB accepts -- rather than either line of the fix, and
  // the per-line pins live in `SqlCommentStripperSpec` (nesting) and `SqlTriviaSpec` (the leading
  // strip) instead.
  it should "build a clean probe for a nested leading block comment" in {
    PrepareStrategy.choose("/* a /* b */ c */ SELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT 1) AS _qod_probe LIMIT 0")
  }

  it should "build a clean probe for a nested leading comment three levels deep" in {
    PrepareStrategy.choose(
      "/* L1 /* L2 /* L3 */ b2 */ b1 */ SELECT a FROM t",
      StatementKind.Select
    ) shouldBe PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT a FROM t) AS _qod_probe LIMIT 0")
  }

  it should "not count a semicolon that a nested leading comment leaked as multi-statement" in {
    // `isMultiStatement` reads the same `stripped` string. With the residue left in, the `;` in
    // the comment body counted as a statement separator and pushed a perfectly wrappable SELECT
    // onto the FullExecute path, which runs the statement for real to read its schema.
    PrepareStrategy.choose("/* a /* b; */ c */ SELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT 1) AS _qod_probe LIMIT 0")
  }

  it should "keep a nested leading comment in front of a non-subquery-safe verb on FullExecute" in {
    PrepareStrategy.choose("/* a /* b */ c */ EXPLAIN SELECT 1", StatementKind.Select) shouldBe
      PrepareStrategy.FullExecute
  }

  it should "build a probe that keeps a quoted comment marker intact" in {
    // The stripper used to close the statement at the `/*` inside the quoted identifier, so the
    // probe went out as `SELECT * FROM (SELECT 1 AS "x) AS _qod_probe LIMIT 0`, which a real
    // DuckDB 1.5.4 rejects with `Parser Error: unterminated quoted identifier`. The probe below
    // parses on that same DuckDB and returns zero rows with the column named x-slash-star-y.
    PrepareStrategy.choose("SELECT 1 AS \"x/*y\"", StatementKind.Select) shouldBe
      PrepareStrategy.ProbeWrap("SELECT * FROM (SELECT 1 AS \"x/*y\") AS _qod_probe LIMIT 0")
  }

  // ---- the same hygiene rule for CONTROL characters, which the check above cannot see ----
  //
  // The guard above only catches a codepoint above ASCII, so it says nothing about a raw carriage
  // return (U+000D): a tool-call parameter carrying the two characters backslash-r can arrive in
  // the file as one raw CR byte, and then a test whose whole point is "DuckDB ends a line comment
  // at a bare CR" reads, to the next maintainer, as an ordinary line break inside a string
  // literal. The comparison below is written as `0x0d.toChar` deliberately: spelling it as an
  // escape would put the very byte sequence this test polices into the test itself.
  it should "carry no raw carriage return in its own source file" in {
    val path = "src/test/scala/ai/starlake/quack/edge/PrepareStrategySpec.scala"
    val src  = scala.io.Source.fromFile(new java.io.File(path), "UTF-8")
    try
      val offenders = src.mkString.zipWithIndex.filter { case (c, _) => c == 0x0d.toChar }
      withClue(s"found raw CR bytes at offsets ${offenders.map(_._2).mkString(", ")}: ") {
        offenders shouldBe empty
      }
    finally src.close()
  }
