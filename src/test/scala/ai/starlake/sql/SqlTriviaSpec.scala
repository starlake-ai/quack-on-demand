package ai.starlake.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Direct tests for the shared trivia-scanning primitive. Before this file, `SqlTrivia` had three
  * production consumers (`StatementClassifier`, `LockdownScreen`, `CatalogWriteScreen`) and zero
  * tests of its own -- every assertion about it was indirect, through a gate that also has its own
  * screening logic to test. Raised as M2/m1 in two prior reviews of the Unicode-trivia hardening
  * arc, unaddressed until now.
  */
class SqlTriviaSpec extends AnyFlatSpec with Matchers:

  // ---- isTriviaSpace: the rule, not a sample of it ----
  //
  // Every prior review of this arc found the SAME shape of bug: an implementation that names the
  // exact characters a test suite happens to exercise, rather than the Unicode category DuckDB
  // itself substitutes. A test suite built the same way -- asserting `isTriviaSpace` for a
  // hand-picked list of characters -- would not notice a regression FROM the category check BACK
  // TO a hardcoded list, because the list could simply be the one the tests name. This test
  // instead enumerates every UTF-16 code unit in the Basic Multilingual Plane and computes, from
  // `Character.getType` and `Character.isWhitespace` directly (NOT by calling `isTriviaSpace`),
  // which ones a "the whole class" implementation must accept -- then asserts `isTriviaSpace`
  // agrees on every single one. A regression to any hardcoded list, however long, fails this test
  // at the first category member the list omits.
  "isTriviaSpace" should "accept every BMP code unit in Zs/Zl/Zp/Cf or covered by Character.isWhitespace" in:
    val offenders = (Char.MinValue to Char.MaxValue).filter { code =>
      val c            = code.toChar
      val kind         = Character.getType(c)
      val mustBeTrivia =
        c.isWhitespace ||
          kind == Character.SPACE_SEPARATOR ||
          kind == Character.LINE_SEPARATOR ||
          kind == Character.PARAGRAPH_SEPARATOR ||
          kind == Character.FORMAT
      mustBeTrivia && !SqlTrivia.isTriviaSpace(c)
    }
    withClue(s"isTriviaSpace missed code units: ${offenders.map(c => f"U+$c%04X").mkString(", ")}") {
      offenders shouldBe empty
    }

  // Witnesses from outside every existing gate's test list (StatementClassifierSpec names 5,
  // LockdownScreenSpec 6, CatalogWriteScreenSpec 5 -- none of the three names either of these two),
  // so a regression to the union of those three lists would still pass every existing test but
  // fail here.
  it should "accept U+205F (medium mathematical space), untested by any gate's own suite" in:
    SqlTrivia.isTriviaSpace('\u205F') shouldBe true

  it should "accept U+2004 (three-per-em space), untested by any gate's own suite" in:
    SqlTrivia.isTriviaSpace('\u2004') shouldBe true

  it should "reject ordinary letters and digits" in:
    SqlTrivia.isTriviaSpace('A') shouldBe false
    SqlTrivia.isTriviaSpace('0') shouldBe false
    SqlTrivia.isTriviaSpace('_') shouldBe false

  // ---- firstToken: the one shared reader every classification call site now routes through ----

  "firstToken" should "read a plain verb" in:
    SqlTrivia.firstToken("SELECT * FROM t") shouldBe "SELECT"

  it should "strip a leading line comment before reading the verb" in:
    SqlTrivia.firstToken("-- hint\nINSERT INTO t VALUES (1)") shouldBe "INSERT"

  it should "strip a leading block comment before reading the verb" in:
    SqlTrivia.firstToken("/* hint */ CREATE TABLE t (x INT)") shouldBe "CREATE"

  it should "strip a leading trivia character before reading the verb" in:
    SqlTrivia.firstToken("\u00A0INSERT INTO t VALUES (1)") shouldBe "INSERT"
    SqlTrivia.firstToken("\uFEFFEXPLAIN SELECT 1") shouldBe "EXPLAIN"

  it should "normalize an interior trivia character so it doesn't extend the token" in:
    // The exact bug (I1/I2): a reader that only strips LEADING trivia still reads
    // "INSERT<NBSP>INTO" as one token when the trivia sits between the verb and what follows it.
    SqlTrivia.firstToken("INSERT\u00A0INTO t VALUES (1)") shouldBe "INSERT"

  it should "return the empty string for blank or all-trivia input" in:
    SqlTrivia.firstToken("") shouldBe ""
    SqlTrivia.firstToken("   \u00A0\uFEFF  ") shouldBe ""

  it should "not drop a leading '(' -- that is a call-site extra, not universal" in:
    SqlTrivia.firstToken("(SELECT 1)") shouldBe "(SELECT"

  it should "not stop at ';' -- that is also a call-site extra" in:
    // A trailing ';' with nothing after it is dropped by `SqlCommentStripper` itself (its
    // `removeEmptyLines` step), so a mid-statement ';' with no following space is the
    // discriminating case: `firstToken` reads straight through it.
    SqlTrivia.firstToken("COMMIT;SELECT 1") shouldBe "COMMIT;SELECT"

  it should "not change case -- callers choose their own" in:
    SqlTrivia.firstToken("select 1") shouldBe "select"

  // ---- a nested leading block comment must not put a non-keyword first ----
  //
  // `stripComments` has no concept of comment nesting -- it stops at the first closing marker it
  // finds, however deep -- while `stripLeading` tracks nesting depth correctly. DuckDB nests block
  // comments to arbitrary depth (verified against a real DuckDB 1.5.4: every one of the leading
  // examples below executes and writes the row). Composing `stripComments` before `stripLeading`
  // (the order this method used to have) stops at the INNER closing marker on a nested leading
  // comment, exposing the literal text between the inner and outer close as the "first token" --
  // not a missed normalization, a MISCLASSIFICATION into a bucket (`Other`) every gate treats as
  // safe. `stripLeading -> stripComments -> normalize` is the only order that survives this.
  it should "not let a nested leading block comment expose its own interior text as the first token" in:
    SqlTrivia.firstToken("/* a /* b */ c */ INSERT INTO t VALUES (1)") shouldBe "INSERT"

  it should "close arbitrarily deep nesting, not just two levels" in:
    SqlTrivia.firstToken(
      "/* L1 /* L2 /* L3 */ back2 */ back1 */ INSERT INTO t VALUES (1)"
    ) shouldBe "INSERT"

  it should "not over-correct: a non-nested leading comment still behaves as before" in:
    SqlTrivia.firstToken("/* x */ INSERT INTO t VALUES (1)") shouldBe "INSERT"

  it should "close a nested leading comment preceded by a plain trivia character" in:
    SqlTrivia.firstToken("\u00A0/* a /* b */ c */ INSERT INTO t VALUES (1)") shouldBe "INSERT"

  it should "not be defeated by a nested comment that sits AFTER the verb (benign shape, verb stays first)" in:
    SqlTrivia.firstToken("INSERT /* a /* b */ c */ INTO t VALUES (1)") shouldBe "INSERT"

  // ---- escape hygiene of this file's own invisible-character test literals ----
  //
  // Every trivia character exercised above is written as a literal `\uXXXX` escape, never as a
  // raw invisible byte pasted into the source -- see the identical guard in
  // `StatementClassifierSpec`, `LockdownScreenSpec` and `CatalogWriteScreenSpec` for why this
  // matters: an editor or an "helpful" formatting pass can silently decode the escape back into a
  // raw invisible character, at which point the file still compiles and every assertion above
  // still passes, with no way for the next reader to tell.
  it should "carry no raw non-ASCII codepoints in its own source file" in:
    val path = "src/test/scala/ai/starlake/sql/SqlTriviaSpec.scala"
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
