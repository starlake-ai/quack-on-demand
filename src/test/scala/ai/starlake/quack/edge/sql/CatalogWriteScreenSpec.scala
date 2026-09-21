package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.SqlParser
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.route.{StatementClassifier, StatementClassifierConfig}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CatalogWriteScreenSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val attached = Set("acme_db", "sales_lake", "memory", "system", "temp")
  private val cfg      = Config.forDuckDB(Some("acme_db"), Some("main"), attached)
  private val readOnly = Set("sales_lake")

  // Classifies with the same StatementClassifier the router runs, so these tests exercise the
  // real classify -> screen wiring rather than an assumption about it. The screen now classifies
  // per statement itself (see N1/N2), so it is handed the classifier function, not a single
  // precomputed kind for the whole submission.
  private def screen(sql: String, ro: Set[String] = readOnly): Option[String] =
    CatalogWriteScreen.screen(sql, StatementClassifier.default.classify, ro, cfg)

  "screen" should "admit a read against a read-only catalog" in {
    screen("SELECT * FROM sales_lake.main.orders") shouldBe None
  }

  it should "admit any statement when no catalog is read-only" in {
    screen("INSERT INTO sales_lake.main.orders VALUES (1)", Set.empty) shouldBe None
  }

  it should "deny an INSERT into a read-only catalog" in {
    val r = screen("INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("sales_lake")
    r.value should include("read-only")
  }

  it should "deny UPDATE, DELETE and MERGE into a read-only catalog" in {
    screen("UPDATE sales_lake.main.orders SET a = 1") should not be None
    screen("DELETE FROM sales_lake.main.orders") should not be None
    screen(
      "MERGE INTO sales_lake.main.orders t USING acme_db.main.stg s ON t.id = s.id " +
        "WHEN MATCHED THEN UPDATE SET t.a = s.a"
    ) should not be None
  }

  it should "deny DDL against a read-only catalog" in {
    screen("CREATE TABLE sales_lake.main.t (a INT)") should not be None
    screen("DROP TABLE sales_lake.main.orders") should not be None
    screen("ALTER TABLE sales_lake.main.orders ADD COLUMN b INT") should not be None
  }

  it should "admit a write to a writable catalog while a read-only one is attached" in {
    screen("INSERT INTO acme_db.main.orders VALUES (1)") shouldBe None
  }

  it should "deny a CTAS whose target is read-only even though its source is not" in {
    screen("CREATE TABLE sales_lake.main.t AS SELECT * FROM acme_db.main.orders") should not be None
  }

  it should "admit a CTAS that only READS the read-only catalog" in {
    screen("CREATE TABLE acme_db.main.t AS SELECT * FROM sales_lake.main.orders") shouldBe None
  }

  it should "admit control-flow statements that touch no table" in {
    screen("COMMIT") shouldBe None
    screen("SET memory_limit = '1GB'") shouldBe None
  }

  it should "fail closed on an unparseable WRITE while a read-only catalog is attached" in {
    // First token INSERT classifies as Dml; the unbalanced VALUES list is what breaks the parse.
    screen("INSERT INTO sales_lake.main.orders VALUES (1") should not be None
  }

  it should "stay open on an unparseable statement when nothing is read-only" in {
    screen("INSRT INTO whatever VALUES (1)", Set.empty) shouldBe None
  }

  it should "match the read-only alias case-insensitively" in {
    screen("INSERT INTO SALES_LAKE.main.orders VALUES (1)") should not be None
  }

  // --- I3: marking one catalog read-only must not narrow the SQL surface for READS ---

  it should "admit DuckDB-native read syntax the parser cannot handle, read-only catalog notwithstanding" in {
    // PRAGMA classifies StatementKind.Other, so it is not read-side-exempt at the cheap path and
    // IS parsed. It is outside SqlParser's allowlist (routes to ParseError: "unrecognized
    // statement type PragmaStatement"), but a ParseError on a non-Dml/Ddl-classified statement is
    // admitted rather than denied -- see the per-statement rule in CatalogWriteScreen's scaladoc.
    // Denying every ParseError regardless of classification (the pre-fix, pre-N2 behaviour) makes
    // this fail.
    screen("PRAGMA table_info('sales_lake.main.orders')") shouldBe None
  }

  // --- C1: a write the parser cannot fully resolve must not be admitted by omission ---

  it should "deny a two-part write whose head is an attached read-only catalog (ambiguous ref)" in {
    // TableQualifier.qualify drops this ref (AmbiguousCatalogRef) rather than including it in
    // `accesses`, so the pre-fix code (which only scanned `accesses`) saw nothing offending and
    // admitted it. Reverting the qualificationErrors check reproduces that: this becomes None.
    val r = screen("INSERT INTO sales_lake.orders VALUES (1)")
    r.value should include("could not be fully resolved")
  }

  it should "deny DROP with a two-part ambiguous ref against a read-only catalog" in {
    screen("DROP TABLE sales_lake.orders") should not be None
  }

  it should "deny a write with an ambiguous ref to a DIFFERENT (non-read-only) attached catalog" in {
    // acme_db is attached but not read-only; the ref is still unresolved, and while ANY catalog
    // is read-only an unresolved write cannot be proven safe, so it is denied too.
    val r = screen("INSERT INTO acme_db.orders VALUES (1)")
    r.value should include("could not be fully resolved")
  }

  it should "deny a write whose read side has an unsupported construct, even to a writable target" in {
    // Target acme_db.main.t fully resolves and is not read-only, so the offending-catalog scan
    // alone would admit this; the unresolved table-function source must deny it on its own.
    val r = screen("CREATE TABLE acme_db.main.t AS SELECT * FROM read_parquet('x.parquet')")
    r.value should include("could not be fully resolved")
    r.value should include("table function")
  }

  it should "distinguish an unresolvable write from a write resolved to a read-only catalog" in {
    val unresolved = screen("INSERT INTO sales_lake.orders VALUES (1)").value
    val resolved   = screen("INSERT INTO sales_lake.main.orders VALUES (1)").value
    unresolved should include("could not be fully resolved")
    resolved should include("read-only on this deployment")
    unresolved should not include "read-only on this deployment"
    resolved should not include "could not be fully resolved"
  }

  // --- N1: a batch must be judged statement by statement, not by its first token ---

  it should "deny a batch whose FIRST statement is a read and whose SECOND writes to the read-only catalog" in {
    // Regression witness: the first-token classifier alone sees SELECT and would exempt the
    // whole submission before ever parsing the INSERT. Reverting the fragment-level gate (going
    // back to `classify(sql)` on the whole submission) makes this None.
    val r = screen("SELECT 1; INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only on this deployment")
  }

  it should "deny a batch whose FIRST statement writes to the read-only catalog and whose second is a read" in {
    val r = screen("INSERT INTO sales_lake.main.orders VALUES (1); SELECT 1")
    r.value should include("read-only on this deployment")
  }

  it should "deny a three-statement batch with the write in the middle" in {
    val r = screen("SELECT 1; INSERT INTO sales_lake.main.orders VALUES (1); SELECT 2")
    r.value should include("read-only on this deployment")
  }

  it should "deny a submission whose first character is a semicolon followed by a write to the read-only catalog" in {
    val r = screen(";INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only on this deployment")
  }

  // --- N2: the classifier's Other catch-all must not be trusted as proof of "read" ---

  it should "deny a statement that classifies Other and RESOLVES to a write on the read-only catalog" in {
    // Forcing the classifier's verdict to Other (as a real Other-classified write, e.g. CALL,
    // would be) proves the offending-access scan runs independently of classification for a
    // statement SqlParser CAN resolve. Reverting to gating the scan on `isWriteShaped` makes this
    // None.
    val other = CatalogWriteScreen.screen(
      "INSERT INTO sales_lake.main.orders VALUES (1)",
      (_: String) => StatementKind.Other,
      readOnly,
      cfg
    )
    other.value should include("read-only on this deployment")
  }

  it should "admit a statement that classifies Other and does NOT resolve" in {
    // CALL some_udf() is outside SqlParser's allowlist (ParseError) and classifies Other; per the
    // per-statement rule a ParseError on a non-Dml/Ddl statement is admitted, matching the real
    // classifier's verdict on CALL (also Other, since it is on no keyword bucket).
    screen("CALL some_udf()") shouldBe None
  }

  // --- N4 robustness: the screen must not depend on the classifier's tunable dml/ddl buckets ---

  // --- P1: a whole-submission parse throw must not collapse the batch to first-token
  // classification. `SqlParser.extract` collapses a submission jsqlparser cannot split into a
  // single ParseError whose snippet is the ENTIRE submission; judging that snippet with
  // isWriteShaped would classify the whole batch by its first token, exactly what N1 closed for
  // the non-throwing case. ---

  it should "deny a batch that throws at the whole-submission level with a write fragment (START TRANSACTION)" in {
    // Regression witness: `START TRANSACTION` classifies Begin, so a first-token read of the
    // collapsed ParseError's snippet ("START TRANSACTION; INSERT ...; COMMIT") also classifies
    // Begin and is not write-shaped -- reverting the fragment-list fallback to trusting that
    // collapsed snippet makes this None.
    val r = screen("START TRANSACTION; INSERT INTO sales_lake.main.orders VALUES (1); COMMIT")
    r.value should include("read-only")
  }

  it should "deny a batch that throws at the whole-submission level with a write fragment (CHECKPOINT)" in {
    // Second witness: CHECKPOINT classifies Other, so the collapsed snippet's first token is
    // equally uninformative.
    val r = screen("CHECKPOINT; INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "admit a batch that collapses at the whole-submission level but has no write fragment" in {
    // jsqlparser also swallows BEGIN; <anything>; COMMIT into a single node regardless of what
    // <anything> is, so this collapses exactly like the two witnesses above -- but none of its
    // fragments classify Dml/Ddl or PREPARE/EXECUTE, so the fallback must not blanket-deny every
    // collapsed batch, only ones that actually look like a write.
    screen("BEGIN; PRAGMA database_list; COMMIT") shouldBe None
  }

  // --- P2: PREPARE ... AS <write>; EXECUTE composes into an executed write without either
  // statement classifying Dml/Ddl or resolving through SqlParser. ---

  it should "deny a PREPARE whose body is a write against the read-only catalog" in {
    val r = screen("PREPARE p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny the PREPARE/EXECUTE composition that executes a write against the read-only catalog" in {
    val r = screen(
      "PREPARE p AS INSERT INTO sales_lake.main.orders VALUES (1); EXECUTE p"
    )
    r.value should include("read-only")
  }

  it should "deny a bare EXECUTE while a catalog is read-only" in {
    // Over-denial is the accepted cost: the screen cannot tell what a bare EXECUTE runs, so it
    // fails closed on EXECUTE itself rather than only on the paired PREPARE.
    val r = screen("EXECUTE p")
    r.value should include("read-only")
  }

  // --- C1: a leading comment must not hide PREPARE/EXECUTE from isWriteShaped's first-token
  // check. Before the fix, `snippet.trim.takeWhile(...)` read the head straight off the raw
  // snippet, so a `/*x*/` or `--` prefix made the head e.g. "/*X*/PREPARE" (never equal to
  // "PREPARE"), the rule declined to fire, and classify (which DOES strip comments) resolved the
  // statement to Other -- not write-shaped -- admitting the write. ---

  it should "deny a comment-prefixed PREPARE whose body is a write against the read-only catalog" in {
    // Mutation-tested: this does NOT flip if the strip is removed, because a single-statement
    // submission never reaches the fragment-list fallback (fragments.length == statements.length
    // == 1) -- it is judged via denialFor(stmt), and jsqlparser's own ParseError#toString already
    // re-renders the statement without the original comment, independently of this fix. Kept
    // because it is the exact shape the review asked for and it does pin the correct verdict; see
    // the batch witness below for the assertion that actually discriminates the fix.
    val r = screen("/*x*/PREPARE p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a line-comment-prefixed bare EXECUTE while a catalog is read-only" in {
    // Same caveat as above: mutation-tested to NOT flip, for the same reason (single fragment,
    // jsqlparser's reconstructed snippet is already comment-free). Kept for the same reason.
    val r = screen("-- c\nEXECUTE p")
    r.value should include("read-only")
  }

  it should "deny the full comment-blind witness batch (collapsed batch, comment-prefixed PREPARE/EXECUTE)" in {
    // This is the robust witness from the review: CHECKPOINT forces SqlParser.extract to throw on
    // the whole submission (collapsing it to one ParseError), which routes judgment through the
    // fragment-list fallback rather than the per-statement path -- so this also proves the fix
    // reaches isWriteShaped when called from the P1 fallback, not just from the per-statement rule.
    // Mutation-tested: this DOES flip when the strip is removed from isPrepareOrExecute, because
    // the fallback judges the splitter's raw fragments directly (comments intact), unlike the
    // per-statement path above where jsqlparser's own toString reconstruction already drops
    // comments. Pinning why the fallback fires at all (m1's ask), rather than trusting it: three
    // fragments collapse to one StatementResult.
    val batch =
      "CHECKPOINT; /*x*/PREPARE p AS INSERT INTO sales_lake.main.orders VALUES (1); /*x*/EXECUTE p"
    SqlParser.extract(batch, cfg).statements.length shouldBe 1
    LockdownScreen.splitStatements(batch).length shouldBe 3
    val r = screen(batch)
    r.value should include("read-only")
  }

  // --- C1b: invisible LEADING TRIVIA (not just comments) must not hide a write's first token
  // from EITHER isPrepareOrExecute OR the classify arm. Before this fix, `.trim` alone (which only
  // removes characters <= U+0020) let a single NBSP/BOM/ZWSP survive in front of the keyword, so
  // the head became e.g. " INSERT", matched no bucket, classified Other, and was admitted -- a
  // PLAIN write, no PREPARE/EXECUTE needed, broader than C1's scope. Characters are written as
  // explicit \u escapes, never as literal bytes, so a future reader does not mistake them for
  // stray whitespace and delete them. ---

  it should "deny a plain INSERT hidden behind a leading NBSP (U+00A0)" in {
    // Mutation-tested: this DOES flip (None) if the leading-trivia strip is removed from
    // isWriteShaped -- NBSP survives both `.trim` (only <= U+0020) and
    // `Character.isWhitespace` (false for NBSP), so the un-normalized head is " INSERT", which
    // matches neither PREPARE/EXECUTE nor any classifier bucket and classifies Other.
    val r = screen("\u00A0INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a plain INSERT hidden behind a leading BOM (U+FEFF)" in {
    val r = screen("\uFEFFINSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a plain INSERT hidden behind a leading zero-width space (U+200B)" in {
    val r = screen("\u200BINSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a plain INSERT hidden behind a leading word joiner (U+2060)" in {
    // U+2060 is Unicode category Cf (format), the same category as BOM/ZWSP, but was not one of
    // the three characters LockdownScreen.isTriviaSpace enumerated by name. Confirmed separately
    // against a real DuckDB CLI that it still executes an INSERT prepended with this character
    // (SELECT count(*) returned 1 after the statement ran), so isTriviaSpace was broadened to
    // match the whole Cf category rather than naming characters one at a time.
    val r = screen("\u2060INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a plain INSERT hidden behind a leading soft hyphen (U+00AD)" in {
    // U+00AD is Cf too, same as U+2060, but unlike the word joiner, a real DuckDB CLI
    // (v1.5.4) rejects a statement prefixed with it, so it was never a live bypass against
    // DuckDB. Pinned anyway: this screen must not depend on which Cf characters DuckDB
    // happens to reject, and the broadened isTriviaSpace catches the whole category, not
    // only the ones already proven executable.
    val r = screen("\u00ADINSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a PREPARE hidden behind a leading NBSP" in {
    val r = screen("\u00A0PREPARE p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a PREPARE hidden behind a leading NBSP followed by a block comment" in {
    // Pins the composition order: comments are stripped, THEN leading trivia, and the result is
    // fed to isPrepareOrExecute -- neither strip alone (nor the wrong order) would leave a bare
    // "PREPARE" head from this input.
    val r = screen("\u00A0/*x*/PREPARE p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a batch whose write fragment alone carries a leading NBSP" in {
    // Mirrors the existing CHECKPOINT witness below (P1), but only the write fragment is
    // NBSP-prefixed, pinning that normalization runs per-fragment through the P1 fallback and not
    // only on a lone single-statement submission.
    val batch = "CHECKPOINT; \u00A0INSERT INTO sales_lake.main.orders VALUES (1)"
    SqlParser.extract(batch, cfg).statements.length shouldBe 1
    LockdownScreen.splitStatements(batch).length shouldBe 2
    val r = screen(batch)
    r.value should include("read-only")
  }

  it should "still admit a leading comment on an ordinary read reached via the parsed fallback" in {
    // The previous version of this guard (`screen("/*x*/SELECT * FROM sales_lake.main.orders")`)
    // returned at the cheap path (step 2): every fragment classifies read-side without being
    // parsed, so isWriteShaped -- and therefore this commit's strip -- was never reached. It pinned
    // a pre-existing StatementClassifier property, not this fix, and its stated purpose was false.
    // Pairing the read with CHECKPOINT forces SqlParser.extract to throw on the whole submission,
    // routing judgment through the P1 fragment-list fallback, where the /*x*/-prefixed SELECT
    // fragment is actually judged by isWriteShaped with the strip live.
    // Mutation-tested: this DOES flip (Some) if isWriteShaped is changed to treat every
    // non-blank normalized snippet as write-shaped, confirming it now exercises the changed code.
    screen("CHECKPOINT; /*x*/SELECT * FROM sales_lake.main.orders") shouldBe None
  }

  it should "still admit CALL, which classifies Other like PREPARE/EXECUTE but is not treated as write-shaped" in {
    screen("CALL some_udf()") shouldBe None
  }

  it should "still deny a resolvable INSERT even when the classifier's dml bucket is emptied" in {
    // Simulates an operator setting QOD_CLASSIFIER_DML to drop INSERT: the classifier now reports
    // Other for this statement. Because SqlParser resolves it to a Write access on sales_lake
    // regardless of how it classifies, the screen must still deny it. Reverting the offending-scan
    // to run only when `isWriteShaped` is true (i.e. trusting the classifier's bucket) makes this
    // None.
    val emptyDmlBucket =
      new StatementClassifier(StatementClassifierConfig.Defaults.copy(dml = Set.empty))
    emptyDmlBucket.classify(
      "INSERT INTO sales_lake.main.orders VALUES (1)"
    ) shouldBe StatementKind.Other
    val r = CatalogWriteScreen.screen(
      "INSERT INTO sales_lake.main.orders VALUES (1)",
      emptyDmlBucket.classify,
      readOnly,
      cfg
    )
    r.value should include("read-only on this deployment")
  }

  // --- N: INTERIOR invisible trivia (not just leading) must not hide PREPARE/EXECUTE's or a
  // plain write's first token either. C1b closed the LEADING position by stripping leading
  // trivia; this is the same gap one word to the right. `PREPARE<NBSP>p AS INSERT ...` has a
  // head of `PREPARE` already (nothing leading to strip), so `SqlTrivia.stripLeading` was a
  // no-op here: `isPrepareOrExecute`'s `takeWhile(!isWhitespace)` read `PREPARE<NBSP>p` as one
  // token matching neither `PREPARE` nor `EXECUTE`, and `classify` -- which DOES normalize
  // internally -- saw `PREPARE p AS INSERT ...`, a first token in no configured bucket, so
  // `Other`: ADMITTED. Verified against a real DuckDB 1.5.4 that `PREPARE<NBSP>p AS ...` and
  // `EXECUTE<NBSP>p` both execute identically to the space-separated form. Every character below
  // is written as a literal `\uXXXX` escape, never a raw invisible byte (see the byte-hygiene
  // test at the end of this file). ---

  it should "deny a PREPARE hidden behind an interior NBSP" in {
    // Mutation-tested: flips to None if isWriteShaped normalizes with SqlTrivia.stripLeading
    // instead of SqlTrivia.normalize.
    val r = screen("PREPARE\u00A0p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a PREPARE hidden behind an interior word joiner (U+2060)" in {
    val r = screen("PREPARE\u2060p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a PREPARE hidden behind an interior BOM (U+FEFF)" in {
    val r = screen("PREPARE\uFEFFp AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a bare EXECUTE hidden behind an interior NBSP while a catalog is read-only" in {
    val r = screen("EXECUTE\u00A0p")
    r.value should include("read-only")
  }

  it should "deny a PREPARE hidden behind interior trivia outside every other test's list" in {
    // I3 coverage witness, not a discriminating regression test (see the identical honesty note
    // in `StatementClassifierSpec`): U+205F (medium mathematical space) and U+2004 (three-per-em
    // space) are accepted by DuckDB as separators and appear in no other test in this file (which
    // names NBSP, word joiner and BOM), but both are ALSO `Character.isWhitespace` true, so
    // `isPrepareOrExecute`'s reader would still terminate on either even if `SqlTrivia`'s
    // category checks were narrowed to a hardcoded list, as long as the `isWhitespace` disjunct
    // survived. Kept as a coverage pin; `SqlTriviaSpec`'s category-derived property test is the
    // one that actually discriminates that narrowing (mutation-verified).
    val r1 = screen("PREPARE\u205Fp AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r1.value should include("read-only")
    val r2 = screen("PREPARE\u2004p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r2.value should include("read-only")
  }

  it should "deny a plain INSERT hidden behind an interior NBSP (not just leading)" in {
    // NOT discriminating for this commit's own fix -- confirmed by mutation: reverting
    // isWriteShaped's SqlTrivia.normalize back to SqlTrivia.stripLeading does NOT flip this one.
    // `StatementClassifier.classify` normalizes internally (its own earlier fix), so it correctly
    // reads INSERT as the verb and returns Dml regardless of whether the snippet handed to it was
    // already normalized. Kept anyway as a coverage/regression witness that the PREPARE/EXECUTE
    // fix's composition (comments -> strip leading -> normalize) does not regress this
    // already-protected path; pairing with CHECKPOINT (as the P1 witness above does) forces the
    // fragment-list fallback so the raw, un-reconstructed fragment is what gets judged.
    val batch = "CHECKPOINT; INSERT\u00A0INTO sales_lake.main.orders VALUES (1)"
    SqlParser.extract(batch, cfg).statements.length shouldBe 1
    LockdownScreen.splitStatements(batch).length shouldBe 2
    val r = screen(batch)
    r.value should include("read-only")
  }

  it should "still admit a read with interior trivia against a read-only catalog, via the parsed path" in {
    // Over-denial guard: this must reach the parsed per-statement path (denialFor), not just the
    // cheap classify-only path at step 2 -- CHECKPOINT forces the fragment-list fallback, and
    // fragments.exists(isWriteShaped) must come back false for a read with interior trivia, not
    // true. Mutation-tested: flips to Some if isWriteShaped treats every non-blank normalized
    // snippet as write-shaped (the same mutation the leading-trivia read guard above pins).
    screen("CHECKPOINT; SELECT\u00A0* FROM sales_lake.main.orders") shouldBe None
  }

  // ---- C1/C2 sibling: an interior comment must not hide a write from isWriteShaped either ----
  //
  // `SqlCommentStripper` used to delete a block comment with nothing in its place, welding the
  // tokens on either side of it. Verified against a real DuckDB 1.5.4 that both of these still
  // execute as writes. Before the fix, `INSERT/*x*/INTO ...` stripped to `INSERTINTO ...`
  // (classifies Other, not Dml) and `PREPARE/*x*/p AS ...` stripped to `PREPAREp` (matches
  // neither `isPrepareOrExecute` head), so neither was write-shaped and both were admitted while
  // the catalog was read-only.
  it should "deny an INSERT into a read-only catalog hidden behind a block comment" in {
    val r = screen("INSERT/*x*/INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  it should "deny a PREPARE'd write against a read-only catalog hidden behind a block comment" in {
    val r = screen("PREPARE/*x*/p AS INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("read-only")
  }

  // ---- escape hygiene of this file's own invisible-character test literals ----
  //
  // Every trivia character exercised in this file must be a literal `\uXXXX` escape, never a raw
  // invisible byte pasted into the source: an editor or an "helpful" formatting pass can silently
  // decode `\u00A0` back into a raw NBSP, at which point this file still compiles and every
  // assertion still passes (a `String` built from the escape and one built from the raw byte are
  // identical at runtime -- that's the whole point of an escape), while the next reader sees what
  // looks like ordinary blank space around a keyword with no way to tell the test asserts
  // anything about trivia at all. This test reads this very source file back and fails if any
  // codepoint above ASCII (U+007F) appears anywhere in it.
  it should "carry no raw non-ASCII codepoints in its own source file" in {
    val path = "src/test/scala/ai/starlake/quack/edge/sql/CatalogWriteScreenSpec.scala"
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
  }
