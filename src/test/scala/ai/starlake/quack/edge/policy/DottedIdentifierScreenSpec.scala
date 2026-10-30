package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.sql.{AccessGate, GateOutcome, LockdownScreen}

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DuckDB reads `"a.b"` as one identifier (a table literally named `a.b` in the default schema),
  * while JSqlParser splits it into schema `a`, table `b`. On the 2.0 CLI, with both `a.b` and
  * `main."a.b"` present, each statement below acts on `main."a.b"` (or creates `main."a.c"`) while
  * the access check gated `lake.a.b` (or `lake.a.*`).
  */
class DottedIdentifierScreenSpec extends AnyFlatSpec with Matchers:

  private val B = "`"

  private val refusedForms = List(
    // reads
    "SELECT * FROM \"a.b\"",
    "SELECT * FROM t JOIN \"a.b\" ON true",
    "SELECT * FROM t, \"a.b\"",
    "SELECT (SELECT v FROM \"a.b\")",
    "WITH c AS (SELECT * FROM \"a.b\") SELECT * FROM c",
    "SUMMARIZE \"a.b\"",
    "DESCRIBE \"a.b\"",
    "SELECT * FROM \"a.b.c\"",
    s"SELECT * FROM ${B}a.b$B",
    "SELECT * FROM lake.main.\"a.b\"",
    "SELECT \"a.b\".v FROM t",
    // writes
    "INSERT INTO \"a.b\" VALUES (1)",
    "UPDATE \"a.b\" SET v = 1",
    "DELETE FROM \"a.b\"",
    "MERGE INTO \"a.b\" t USING s ON t.id = s.id WHEN MATCHED THEN DELETE",
    "TRUNCATE \"a.b\"",
    "COPY \"a.b\" FROM 's3://b/x.csv'",
    // DDL
    "CREATE TABLE \"a.c\" (x INT)",
    "CREATE TABLE \"a.c\" AS SELECT 1",
    "CREATE VIEW \"a.v\" AS SELECT 1",
    "ALTER TABLE \"a.b\" ADD COLUMN y INT",
    "ALTER TABLE t RENAME TO \"a.b\"",
    "DROP TABLE \"a.b\"",
    // in a batch, behind EXPLAIN
    "SELECT 1; SELECT * FROM \"a.b\"",
    "EXPLAIN ANALYZE SELECT * FROM \"a.b\""
  )

  private val admittedForms = List(
    "SELECT * FROM \"a\".\"b\"",
    "SELECT * FROM lake.\"main\".\"orders\"",
    "SELECT v AS \"t.v\" FROM t",
    "SELECT v \"x\" FROM t AS \"a.b\"",
    s"SELECT v AS ${B}orders.total$B FROM t",
    "SELECT 'a.b', \"col\" FROM t",
    "SELECT 1 -- \"a.b\"\nFROM t",
    "SELECT 1 /* \"a.b\" */ FROM t",
    "SELECT $$\"a.b\"$$ FROM t",
    "SELECT * FROM \"s3://b/x.parquet\"",
    "SELECT 1.5, t.v FROM main.t"
  )

  "DottedIdentifierScreen" should "refuse a quoted name holding a dot in every table position" in:
    refusedForms.foreach { sql =>
      withClue(sql)(DottedIdentifierScreen.find(sql) shouldBe Some(DottedIdentifierScreen.Reason))
    }

  it should "admit separate quoted parts, aliases, and dots in literals or comments" in:
    admittedForms.foreach(sql => withClue(sql)(DottedIdentifierScreen.find(sql) shouldBe None))

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  "AccessGate" should "refuse it as not wildcard-coverable, for QoD and OPA alike" in:
    refusedForms.foreach { sql =>
      withClue(sql) {
        AccessGate.evaluate(sql, cfg, "lake", false, clientDataScans = false) match
          case GateOutcome.Refuse(reason, coverable, _) =>
            reason shouldBe DottedIdentifierScreen.Reason
            coverable shouldBe false
          case other => fail(s"expected Refuse, got $other")
      }
    }

  it should "still gate separate quoted parts as the table they name" in:
    AccessGate.evaluate("SELECT * FROM \"a\".\"b\"", cfg, "lake", false, false) match
      case GateOutcome.Gated(g, _) => g.map(_.table.canonical) shouldBe Set("lake.a.b")
      case other                   => fail(s"expected Gated, got $other")

  "LockdownScreen" should "refuse it" in:
    refusedForms.foreach { sql =>
      withClue(sql)(LockdownScreen.screen(sql, Set.empty) shouldBe defined)
    }
    LockdownScreen.screen("SELECT v AS \"t.v\" FROM \"main\".\"t\"", Set.empty) shouldBe None

  // JSqlParser also splits a quoted name at an `@` (an Oracle database link: `"secret@x"` is gated
  // as table `secret`), and keeps an escaped quote doubled (`"secret""x"` is gated as `secret""x`
  // while DuckDB reads `secret"x`). Every other printable ASCII and unicode character was probed in
  // both quoting styles and reaches the check as the name DuckDB 2.0 reads.
  private val refusedSeparators = List(
    "SELECT * FROM \"secret@x\"",
    s"SELECT * FROM ${B}secret@x$B",
    "SELECT * FROM lake.main.\"secret@x\"",
    "SELECT * FROM t JOIN \"secret@x\" ON true",
    "INSERT INTO \"secret@x\" VALUES (1)",
    "DELETE FROM \"secret@x\"",
    "DROP TABLE \"secret@x\"",
    "CREATE TABLE \"t@x\" (a INT)",
    "SELECT * FROM \"secret\"\"x\"",
    s"SELECT * FROM ${B}secret$B${B}x$B",
    "INSERT INTO \"a\"\"b\" VALUES (1)",
    // a `://` that is not a URL scheme is no exception
    "SELECT * FROM \"other.x://\""
  )

  private val admittedSeparators = List(
    "SELECT v AS \"a@b\" FROM t",
    "SELECT * FROM t AS \"a@b\"",
    "SELECT 'secret@x' FROM t",
    "SELECT * FROM \"s3://user@bucket/x.parquet\"",
    "SELECT * FROM \"secret#x\", \"secret:x\", \"secret$x\", \"secret/x\""
  )

  "DottedIdentifierScreen" should "refuse a quoted name holding an @ or an escaped quote" in:
    refusedSeparators.foreach { sql =>
      withClue(sql)(DottedIdentifierScreen.find(sql) shouldBe Some(DottedIdentifierScreen.Reason))
    }
    refusedSeparators.foreach { sql =>
      withClue(sql) {
        AccessGate.evaluate(sql, cfg, "lake", false, clientDataScans = false) shouldBe a[
          GateOutcome.Refuse
        ]
        LockdownScreen.screen(sql, Set.empty) shouldBe defined
      }
    }

  it should "admit them as aliases, literals and URL paths, and the other separators" in:
    admittedSeparators.foreach(sql => withClue(sql)(DottedIdentifierScreen.find(sql) shouldBe None))

  // JSqlParser also strips SQL Server bracket quoting and a stray quote of the other style at a
  // quoted name's edges: `"[secret]"`, `"secret]"`, `` "`secret`" `` and `` `"secret"` `` were all
  // gated as table `secret`, while DuckDB 2.0 reads the table literally named `[secret]` (a
  // `BRACKETED` row beside a `secret` table). The screen now compares JSqlParser's reading of every
  // quoted name with the name itself.
  private val refusedMisreads = List(
    "SELECT * FROM \"[secret]\"",
    s"SELECT * FROM ${B}[secret]$B",
    "SELECT * FROM \"secret]\"",
    "SELECT * FROM \"[secret\"",
    "SELECT * FROM main.\"[secret]\"",
    "SELECT * FROM \"[main]\".\"[secret]\"",
    "INSERT INTO \"[secret]\" VALUES (1)",
    "DROP TABLE \"[secret]\"",
    "CREATE TABLE \"[secret]\" (a INT)",
    s"SELECT * FROM \"${B}secret$B\"",
    s"SELECT * FROM $B\"secret\"$B",
    s"SELECT * FROM \"${B}secret\"",
    s"SELECT * FROM $B\"secret$B"
  )

  "DottedIdentifierScreen" should "refuse a quoted name JSqlParser strips brackets or quotes from" in
    refusedMisreads.foreach { sql =>
      withClue(sql) {
        DottedIdentifierScreen.find(sql) shouldBe Some(DottedIdentifierScreen.Reason)
        AccessGate.evaluate(sql, cfg, "lake", false, clientDataScans = false) shouldBe a[
          GateOutcome.Refuse
        ]
        LockdownScreen.screen(sql, Set.empty) shouldBe defined
      }
    }

  // Every printable ASCII character, in both quoting styles, inside, before, after and around a
  // name: the access check either refuses the statement or gates exactly the name DuckDB reads.
  it should "never let a quoted name be gated as another name" in {
    for
      q     <- List('"', '`')
      c     <- (32 to 126).map(_.toChar)
      shape <- List(s"se${c}cret", s"${c}secret", s"secret$c", s"${c}secret$c")
    do
      val body = if c == q then shape.replace(s"$c", s"$c$c") else shape
      val sql  = s"SELECT * FROM $q$body$q"
      withClue(sql) {
        AccessGate.evaluate(sql, cfg, "lake", true, clientDataScans = false) match
          case GateOutcome.Gated(g, _) =>
            g.map(_.table.canonical) shouldBe Set("lake.main." + shape.toLowerCase)
          case GateOutcome.Refuse(_, _, _) => succeed
          case other                       => fail(s"expected Gated or Refuse, got $other")
      }
  }

  it should "admit ordinary quoted names, unicode included" in
    List(
      "SELECT * FROM \"orders\"",
      "SELECT * FROM \"Order Items\"",
      "SELECT * FROM \"émile\"",
      "SELECT \"c-1\", \"a b\" FROM \"t#1\"",
      s"SELECT * FROM ${B}order items$B"
    ).foreach(sql => withClue(sql)(DottedIdentifierScreen.find(sql) shouldBe None))
