package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
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
