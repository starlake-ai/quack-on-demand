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

  // ---- invisible leading trivia cannot hide the verb (live security bypass) ----
  //
  // `String.trim` only strips characters <= U+0020, and `Character.isWhitespace` is false for
  // NBSP (U+00A0), the word joiner (U+2060), the BOM (U+FEFF), zero-width space (U+200B) and soft
  // hyphen (U+00AD). A statement prefixed with one of these classified as `Other` -- the same
  // reader-routed bypass the `WITH` special case below exists to close, reached here through a
  // hidden first token on a PLAIN INSERT/UPDATE/DELETE/DROP instead of a misleading one. Verified
  // against a real DuckDB 1.5.4: NBSP, BOM, ZWSP and the word joiner all still execute as a prefix;
  // soft hyphen is rejected by DuckDB itself but is stripped here too since nothing downstream
  // should ever see it as part of the token.
  it should "not let a leading NBSP hide a write's verb" in:
    StatementClassifier.classify("\u00A0INSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let a leading BOM hide a write's verb" in:
    StatementClassifier.classify("\uFEFFINSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let a leading zero-width space hide a write's verb" in:
    StatementClassifier.classify("\u200BINSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let a leading word joiner hide a write's verb" in:
    StatementClassifier.classify("\u2060INSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let a leading soft hyphen hide a write's verb" in:
    StatementClassifier.classify("\u00ADINSERT INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "strip the same invisible prefixes ahead of UPDATE, DELETE and DROP" in:
    StatementClassifier.classify("\u00A0UPDATE t SET x = 1") shouldBe StatementKind.Dml
    StatementClassifier.classify("\uFEFFDELETE FROM t WHERE x = 1") shouldBe StatementKind.Dml
    StatementClassifier.classify("\u200BDROP TABLE t") shouldBe StatementKind.Ddl

  it should "strip an invisible prefix ahead of CREATE TABLE as Ddl" in:
    StatementClassifier.classify("\u00A0CREATE TABLE t (x INT)") shouldBe StatementKind.Ddl
    StatementClassifier.classify("\uFEFFCREATE TABLE t (x INT)") shouldBe StatementKind.Ddl
    StatementClassifier.classify("\u200BCREATE TABLE t (x INT)") shouldBe StatementKind.Ddl
    StatementClassifier.classify("\u2060CREATE TABLE t (x INT)") shouldBe StatementKind.Ddl
    StatementClassifier.classify("\u00ADCREATE TABLE t (x INT)") shouldBe StatementKind.Ddl

  it should "not over-correct: an invisible prefix ahead of SELECT stays Select" in:
    StatementClassifier.classify("\u00A0SELECT * FROM t") shouldBe StatementKind.Select
    StatementClassifier.classify("\uFEFFSELECT * FROM t") shouldBe StatementKind.Select
    StatementClassifier.classify("\u200BSELECT * FROM t") shouldBe StatementKind.Select
    StatementClassifier.classify("\u2060SELECT * FROM t") shouldBe StatementKind.Select
    StatementClassifier.classify("\u00ADSELECT * FROM t") shouldBe StatementKind.Select

  it should "strip an invisible prefix ahead of the WITH ... INSERT special case too" in:
    StatementClassifier.classify(
      "\uFEFFWITH s AS (SELECT * FROM t) INSERT INTO m SELECT * FROM s"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "\u200BWITH cte AS (SELECT 1) SELECT * FROM cte"
    ) shouldBe StatementKind.Select

  it should "combine an invisible prefix with a leading comment" in:
    StatementClassifier.classify(
      "\uFEFF-- a comment\nINSERT INTO t VALUES (1)"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "-- a comment\n\uFEFFINSERT INTO t VALUES (1)"
    ) shouldBe StatementKind.Dml

  it should "leave ordinary, unprefixed statements unchanged" in:
    StatementClassifier.classify("INSERT INTO t VALUES (1)") shouldBe StatementKind.Dml
    StatementClassifier.classify("SELECT 1") shouldBe StatementKind.Select
    StatementClassifier.classify("CREATE TABLE t (x INT)") shouldBe StatementKind.Ddl

  // ---- interior invisible trivia cannot hide the verb either (C1 follow-up) ----
  //
  // Stripping only the LEADING position fixes one spot; the same character one word to the
  // right reproduces the identical bypass, because the token terminator was still
  // `Character.isWhitespace`, which is false for exactly these characters. DuckDB's parser
  // front end substitutes ASCII spaces for its Unicode space/format set ACROSS THE WHOLE
  // QUERY before parsing, so an interior NBSP between the verb and the next keyword is just
  // as executable as a leading one. Verified against a real DuckDB 1.5.4: every one of
  // `INSERT<char>INTO t VALUES (1)` below still writes a row. `SqlTrivia.normalize` mirrors
  // that whole-query substitution so every downstream scan (`firstToken`,
  // `verbAfterWithClause`) sees the same token boundaries DuckDB does.
  it should "not let an interior NBSP hide a write's verb" in:
    StatementClassifier.classify("INSERT\u00A0INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let an interior BOM hide a write's verb" in:
    StatementClassifier.classify("INSERT\uFEFFINTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let an interior zero-width space hide a write's verb" in:
    StatementClassifier.classify("INSERT\u200BINTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let an interior word joiner hide a write's verb" in:
    StatementClassifier.classify("INSERT\u2060INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let an interior figure space (U+2007) hide a write's verb" in:
    // A non-breaking Zs character `isTriviaSpace` already covered (via SPACE_SEPARATOR), but
    // that alone was not enough: `firstToken`'s old terminator was `Character.isWhitespace`,
    // which U+2007 also fails. Only whole-string normalization closes it.
    StatementClassifier.classify("INSERT\u2007INTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let an interior narrow no-break space (U+202F) hide a write's verb" in:
    StatementClassifier.classify("INSERT\u202FINTO t VALUES (1)") shouldBe StatementKind.Dml

  it should "not let interior trivia hide a DROP TABLE's verb" in:
    StatementClassifier.classify("DROP\u00A0TABLE t") shouldBe StatementKind.Ddl

  it should "not let interior trivia hide the verb throughout a WITH ... INSERT" in:
    StatementClassifier.classify(
      "WITH\u00A0x\u00A0AS\u00A0(SELECT\u00A01)\u00A0INSERT\u00A0INTO t SELECT 1"
    ) shouldBe StatementKind.Dml

  it should "not let interior trivia hide EXPLAIN ANALYZE's inner write" in:
    // Regression for the recursion gap (C2): `classifyStripped` re-entered itself for the
    // ANALYZE case with a freshly sliced substring that was never normalized, so a trivia
    // character right before the inner statement stayed hidden even though the entry point
    // was fixed. Verified against DuckDB 1.5.4: this writes a row.
    StatementClassifier.classify(
      "EXPLAIN ANALYZE\u00A0INSERT INTO t VALUES (1)"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "\u00A0EXPLAIN ANALYZE INSERT INTO t VALUES (1)"
    ) shouldBe StatementKind.Dml

  it should "not regress on interior trivia that was already safe" in:
    // U+2000 (en quad) and U+3000 (ideographic space) are true to `Character.isWhitespace`
    // already, so they terminated `firstToken` correctly even before normalization; pin that
    // whole-string normalization doesn't disturb them.
    StatementClassifier.classify("INSERT\u2000INTO t VALUES (1)") shouldBe StatementKind.Dml
    StatementClassifier.classify("DROP\u3000TABLE t") shouldBe StatementKind.Ddl

  it should "not over-correct: interior trivia in a SELECT stays Select" in:
    StatementClassifier.classify("SELECT\u00A01") shouldBe StatementKind.Select
    StatementClassifier.classify("SELECT * FROM\u00A0t") shouldBe StatementKind.Select

  it should "not let trivia inside a string literal change the classification" in:
    StatementClassifier.classify(
      "INSERT INTO t VALUES ('a\u00A0b')"
    ) shouldBe StatementKind.Dml
    StatementClassifier.classify(
      "SELECT 'a\u00A0b' FROM t"
    ) shouldBe StatementKind.Select

  it should "classify from a normalized copy without altering the original statement" in:
    // `SqlTrivia.normalize` must never be threaded anywhere but the classifier's own scan --
    // the original SQL text is what is sent to the node. Strings are immutable in the JVM, so
    // this also documents the property `normalize`'s scaladoc promises: pin that the input
    // string a caller holds still carries its original invisible characters unchanged, and
    // still has its original length, after `classify` has run.
    val original = "INSERT\u00A0INTO t VALUES ('a\u00A0b')"
    val length   = original.length
    StatementClassifier.classify(original) shouldBe StatementKind.Dml
    original.length shouldBe length
    original.charAt(6) shouldBe '\u00A0'
    original.contains("a\u00A0b") shouldBe true

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

  // ---- escape hygiene of this file's own invisible-character test literals (I1) ----
  //
  // Every trivia character exercised above is written as a literal `\uXXXX` escape, never as
  // a raw invisible byte pasted into the source: an editor or an "helpful" formatting pass can
  // silently decode `\u00A0` back into a raw NBSP, at which point this file still compiles,
  // every assertion above still passes (a `String` built from the escape and one built from
  // the raw byte are identical at runtime -- that's the whole point of an escape), and the
  // next reader sees what looks like ordinary blank space around a keyword with no way to
  // tell the test asserts anything about trivia at all. That already happened once in this
  // file. This test is the only thing in the suite that can catch a repeat: it reads this very
  // source file back and fails if any codepoint above ASCII (U+007F) appears anywhere in it.
  it should "carry no raw non-ASCII codepoints in its own source file" in:
    // Path is relative to the sbt project root, which is the forked test JVM's working
    // directory (`Test / fork := true` in build.sbt, no `Test / baseDirectory` override to
    // change it). If this ever proves brittle under a different launcher, the fallback is a
    // `Thread.currentThread.getContextClassLoader` resource lookup keyed off a copy of this
    // file placed under `src/test/resources`, or a CI-level grep step -- both discussed in the
    // review; the in-spec form is kept because it travels with the code and runs under plain
    // `sbt test`.
    val path = "src/test/scala/ai/starlake/quack/route/StatementClassifierSpec.scala"
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
