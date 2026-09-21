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

  it should "preserve a comment marker inside a single-quoted string literal verbatim" in:
    SqlCommentStripper.stripComments(
      "SELECT '/*not a comment*/' FROM t"
    ) shouldBe "SELECT '/*not a comment*/' FROM t"
    SqlCommentStripper.stripComments(
      "SELECT '-- not a comment' FROM t"
    ) shouldBe "SELECT '-- not a comment' FROM t"

  it should "not let a quote inside a comment open a string" in:
    // The comment consumes the quote; nothing after it is misread as inside a string literal.
    SqlCommentStripper.stripComments(
      "INSERT/* it's a comment */INTO t VALUES ('a')"
    ) shouldBe "INSERT INTO t VALUES ('a')"

  it should "behave exactly as before for a line (--) comment: it already tokenizes correctly" in:
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

  it should "still remove a comment that already had a leading/trailing space, without behavior change" in {
    // Same non-trimming contract: the pre-existing space before the comment survives, plus the
    // fix's own closing space with nothing after it to weld to.
    SqlCommentStripper.stripComments("SELECT 1 /* just a comment */") shouldBe "SELECT 1  "
  }

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
