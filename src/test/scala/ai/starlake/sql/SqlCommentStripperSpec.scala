package ai.starlake.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Direct tests for `SqlCommentStripper`. Before this file, the stripper had three production
  * consumers (`StatementClassifier`, `LockdownScreen`'s leading scan plus its interior scan added
  * alongside this file, `CatalogWriteScreen`, `PrepareStrategy`) and zero tests of its own -- every
  * assertion about it was indirect, through a gate that also has its own screening logic to test.
  *
  * C1: a stripped comment must become a SEPARATOR, not a WELD. DuckDB's own parser treats a comment
  * as whitespace between the tokens on either side of it, confirmed executing against a real DuckDB
  * 1.5.4 (`INSERT/*x*/INTO t VALUES (1)` writes the row). Before this fix, the block-comment arm of
  * `stripComments` appended nothing on close, so the two tokens welded into one (`INSERTINTO`) that
  * matches no classifier keyword bucket -- the same class of bypass the Unicode-trivia hardening
  * arc closed for invisible characters, one comment class over.
  */
class SqlCommentStripperSpec extends AnyFlatSpec with Matchers:

  "stripComments" should "replace a block comment between two keywords with a space, not weld them" in:
    SqlCommentStripper.stripComments(
      "INSERT/*x*/INTO t VALUES (1)"
    ) shouldBe "INSERT INTO t VALUES (1)"

  it should "replace a block comment anywhere interior to the statement, not just between the first two tokens" in:
    SqlCommentStripper.stripComments(
      "CREATE/*x*/TABLE t9(a int)"
    ) shouldBe "CREATE TABLE t9(a int)"
    SqlCommentStripper.stripComments(
      "SELECT * FROM/*x*/t"
    ) shouldBe "SELECT * FROM t"
    SqlCommentStripper.stripComments(
      "SELECT a/*x*/FROM t"
    ) shouldBe "SELECT a FROM t"

  it should "leave a comment that already sits next to whitespace producing only harmless extra spaces" in:
    // No welding risk here either way; pinning that the fix doesn't corrupt the already-safe case.
    // (Extra spaces are harmless: no SQL tokenizer's keyword adjacency check requires exactly one.)
    SqlCommentStripper.stripComments(
      "INSERT /*x*/ INTO t VALUES (1)"
    ) shouldBe "INSERT   INTO t VALUES (1)"

  it should "preserve a comment marker inside a single-quoted string literal verbatim (guard)" in:
    // GUARD, not a regression witness: the plain single-quote arm is the one quoting form this
    // stripper has always tracked, so these pass with every fix in this file reverted. Pinned so
    // that the quoting work below, which added three more forms, cannot regress the original one.
    SqlCommentStripper.stripComments(
      "SELECT '/*not a comment*/' FROM t"
    ) shouldBe "SELECT '/*not a comment*/' FROM t"
    SqlCommentStripper.stripComments(
      "SELECT '-- not a comment' FROM t"
    ) shouldBe "SELECT '-- not a comment' FROM t"

  it should "not let a quote inside a comment open a string (guard)" in:
    // GUARD: also passes with every fix reverted. The comment consumes the quote; nothing after
    // it is misread as inside a string literal.
    SqlCommentStripper.stripComments(
      "INSERT/* it's a comment */INTO t VALUES ('a')"
    ) shouldBe "INSERT INTO t VALUES ('a')"

  it should "behave as before for a line (--) comment: it already tokenizes right (guard)" in:
    // Verified by inspection: the line-comment arm appends its terminating newline (or, for an
    // unterminated line comment, runs to end of string, leaving nothing to weld). Untouched by
    // this fix; pinned here so a future change to this arm is caught the same way.
    SqlCommentStripper.stripComments(
      "INSERT-- c\nINTO t VALUES (1)"
    ) shouldBe "INSERT\nINTO t VALUES (1)"
    // Nothing follows an unterminated trailing line comment, so there is no weld risk; the
    // original space before `--` is preserved untouched (trim is a call-site concern, not this
    // stripper's -- see `removeEmptyLines`, which only trims a line ending in `;`).
    SqlCommentStripper.stripComments("SELECT 1 -- trailing, unterminated") shouldBe "SELECT 1 "

  it should "still remove a comment that had a leading/trailing space, unchanged (guard)" in {
    // Same non-trimming contract: the pre-existing space before the comment survives, plus the
    // fix's own closing space with nothing after it to weld to.
    SqlCommentStripper.stripComments("SELECT 1 /* just a comment */") shouldBe "SELECT 1  "
  }

  // ---- block comments NEST, the way DuckDB's lexer nests them ----
  //
  // Every statement below was executed against a real DuckDB 1.5.4 and does what the test name
  // says it does. Closing on the first closing marker left the text between the inner and the
  // outer close in the stream, which is not "less stripped" but a WRONG token in a keyword slot:
  // `EXPLAIN /* a /* b */ c */ ANALYZE INSERT INTO t VALUES (1)` writes the row on DuckDB while
  // the classifier read the residue where ANALYZE should be and called the statement read-shaped
  // (C3), and `SELECT * FROM/* a /* b */ c */'/etc/passwd.parquet'` reads the file while the
  // residue sat between FROM and its literal so no lockdown regex matched (I1).
  "stripComments" should "close a nested block comment at its OUTER marker, leaving no residue" in:
    SqlCommentStripper.stripComments(
      "EXPLAIN /* a /* b */ c */ ANALYZE INSERT INTO t VALUES (1)"
    ) shouldBe "EXPLAIN   ANALYZE INSERT INTO t VALUES (1)"

  it should "close arbitrarily deep nesting, not just two levels" in:
    SqlCommentStripper.stripComments(
      "SELECT/* L1 /* L2 /* L3 */ b2 */ b1 */1"
    ) shouldBe "SELECT 1"

  it should "keep the keyword-argument adjacency a nested comment used to break" in:
    SqlCommentStripper.stripComments(
      "SELECT * FROM/* a /* b */ c */'/etc/passwd.parquet'"
    ) shouldBe "SELECT * FROM '/etc/passwd.parquet'"

  // ---- a comment marker inside a QUOTED region is not a comment ----
  //
  // DuckDB has four quoting forms and accepts a comment opener verbatim inside every one of them
  // (each probed individually against a real DuckDB 1.5.4). Tracking only single quotes let a
  // `/*` in a column alias open a comment that never closed, truncating the statement before any
  // screen saw it: `SELECT 1 AS "x/*y", * FROM read_csv('/etc/passwd.csv')` executes on DuckDB
  // and reached `LockdownScreen` as `select 1 as "x`, admitted (C1).
  it should "not open a comment inside a double-quoted identifier" in:
    SqlCommentStripper.stripComments(
      "SELECT 1 AS \"x/*y\", * FROM read_csv('/etc/passwd.csv')"
    ) shouldBe "SELECT 1 AS \"x/*y\", * FROM read_csv('/etc/passwd.csv')"
    SqlCommentStripper.stripComments(
      "SELECT 1 AS \"x--y\", * FROM '/etc/passwd.parquet'"
    ) shouldBe "SELECT 1 AS \"x--y\", * FROM '/etc/passwd.parquet'"

  it should "honour doubling inside a quoted identifier, ending it where DuckDB ends it" in:
    // `"x""/*y"` is ONE identifier on DuckDB (verified: the column comes back named x"/*y). A
    // scanner that closed the identifier at the doubled quote would re-enter normal state inside
    // it and open a comment at the marker that follows.
    SqlCommentStripper.stripComments(
      "SELECT 1 AS \"x\"\"/*y\", * FROM read_csv('/etc/passwd.csv')"
    ) shouldBe "SELECT 1 AS \"x\"\"/*y\", * FROM read_csv('/etc/passwd.csv')"

  it should "not open a comment inside a dollar-quoted string, tagged or untagged" in:
    SqlCommentStripper.stripComments(
      "SELECT $$a/*b$$ AS s, * FROM read_csv('/etc/passwd.csv')"
    ) shouldBe "SELECT $$a/*b$$ AS s, * FROM read_csv('/etc/passwd.csv')"
    SqlCommentStripper.stripComments(
      "SELECT $tg$a--b$tg$ AS s, * FROM read_csv('/etc/passwd.csv')"
    ) shouldBe "SELECT $tg$a--b$tg$ AS s, * FROM read_csv('/etc/passwd.csv')"

  it should "not read a positional parameter as a dollar-quote opener (guard)" in:
    // GUARD: mutation-tested, this passes with the digit-start tag rule reverted, because a
    // `$1` with no second `$` after it is not an opener under either rule. DuckDB parses
    // `$1$x$1$` as the parameter `$1` followed by an (unterminated) `$x$` string, so a tag that
    // starts with a digit is unreachable from SQL DuckDB actually accepts, and no valid statement
    // discriminates the rule. Pinned because the shape (a parameter next to a comment) is what a
    // future reader would expect this file to cover.
    SqlCommentStripper.stripComments(
      "PREPARE p AS SELECT $1, * FROM read_csv('/etc/passwd.csv') /*c*/"
    ) shouldBe "PREPARE p AS SELECT $1, * FROM read_csv('/etc/passwd.csv')  "

  it should "not read a dollar with no matching closing tag as a quote opener" in:
    // An unmatched opener is a parser error on DuckDB, so nothing executes either way; treating
    // the remainder as one long "string" would nonetheless stop stripping comments from it, which
    // is why the interior comment below is what makes this test discriminating.
    SqlCommentStripper.stripComments(
      "SELECT $tag$abc/*c*/, * FROM read_csv('/etc/passwd.csv')"
    ) shouldBe "SELECT $tag$abc , * FROM read_csv('/etc/passwd.csv')"

  it should "let a backslash escape the closing quote of an e-string, as DuckDB does" in:
    // DuckDB: `e'a\'b'` is the three-character string a'b (verified), so the first `'` after the
    // backslash does NOT end the literal. A scanner that ended it there would re-enter normal
    // state one character early and misread everything after it.
    SqlCommentStripper.stripComments(
      "SELECT e'a" + "\\" + "'/*x*/b' AS s, 1 AS n"
    ) shouldBe "SELECT e'a" + "\\" + "'/*x*/b' AS s, 1 AS n"

  it should "treat a backslash in a PLAIN string as an ordinary character, as DuckDB does" in:
    // The mirror image: DuckDB reads `'a\'` as the complete two-character string a-backslash
    // (verified), so the literal ENDS at that quote. A scanner that treated the backslash as an
    // escape everywhere would run past the close and swallow the read_csv call.
    SqlCommentStripper.stripComments(
      "SELECT 'a" + "\\" + "' AS s, * FROM read_csv('/etc/passwd.csv') /*c*/"
    ) shouldBe "SELECT 'a" + "\\" + "' AS s, * FROM read_csv('/etc/passwd.csv')  "

  it should "not take an e that is part of a longer identifier as an escape-string prefix" in:
    // DuckDB rejects `ze'a\'b'` (the `e` is the tail of the identifier `ze`, so the string is a
    // plain one ending at the quote after the backslash). Matching that rule keeps the scanner
    // from running past a close DuckDB honours.
    SqlCommentStripper.stripComments(
      "SELECT ze'a" + "\\" + "' AS s, * FROM read_csv('/etc/passwd.csv') /*c*/"
    ) shouldBe "SELECT ze'a" + "\\" + "' AS s, * FROM read_csv('/etc/passwd.csv')  "

  // ---- a line comment ends at a bare carriage return too ----
  //
  // Already true of this stripper before the fix and pinned here as the unit-level statement of
  // the rule, because `SqlTrivia.stripLeading` disagreed with it (scanning to a line feed alone)
  // and the composition put that scanner first, so a write behind a bare CR classified `Other`.
  // DuckDB terminates at a line feed and at a bare CR and at NOTHING else -- vertical tab, form
  // feed, U+0085, U+2028 and U+2029 were each probed and all leave the comment open.
  it should "end a line comment at a bare carriage return (guard: this arm already did)" in:
    SqlCommentStripper.stripComments(
      "-- x" + "\r" + "INSERT INTO t VALUES (1)"
    ) shouldBe "INSERT INTO t VALUES (1)"

  // ---- escape hygiene of this file's own invisible-character test literals ----
  //
  // This file names no invisible/Unicode trivia characters at all (that is SqlTriviaSpec's job);
  // this test only pins that no raw non-ASCII codepoint crept in by accident, matching the
  // identical guard in `SqlTriviaSpec`, `StatementClassifierSpec`, `LockdownScreenSpec` and
  // `CatalogWriteScreenSpec`.
  it should "carry no raw non-ASCII codepoints in its own source file" in:
    val path = "src/test/scala/ai/starlake/sql/SqlCommentStripperSpec.scala"
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

  // ---- the same hygiene rule for CONTROL characters, which the check above cannot see ----
  //
  // The guard above only catches a codepoint above ASCII, so it says nothing about a raw carriage
  // return (U+000D): a tool-call parameter carrying the two characters backslash-r can arrive in
  // the file as one raw CR byte, and then a test whose whole point is "DuckDB ends a line comment
  // at a bare CR" reads, to the next maintainer, as an ordinary line break inside a string
  // literal. The comparison below is written as `0x0d.toChar` deliberately: spelling it as an
  // escape would put the very byte sequence this test polices into the test itself.
  it should "carry no raw carriage return in its own source file" in {
    val path = "src/test/scala/ai/starlake/sql/SqlCommentStripperSpec.scala"
    val src  = scala.io.Source.fromFile(new java.io.File(path), "UTF-8")
    try
      val offenders = src.mkString.zipWithIndex.filter { case (c, _) => c == 0x0d.toChar }
      withClue(s"found raw CR bytes at offsets ${offenders.map(_._2).mkString(", ")}: ") {
        offenders shouldBe empty
      }
    finally src.close()
  }
