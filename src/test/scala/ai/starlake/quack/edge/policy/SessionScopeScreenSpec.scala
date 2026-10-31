package ai.starlake.quack.edge.policy

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The policy rewriters qualify an unqualified table name against the pool's default schema, and
  * the router pins that schema with a `USE <db>.<schema>` prelude. A statement that moves the
  * session's schema or search path INSIDE the same batch runs after that prelude, so the node
  * resolves later bare names somewhere the policies were never matched. Every batch shape below was
  * confirmed against a real DuckDB 1.5.6 and 2.0 CLI to read `db.other.t` (or another catalog's
  * `t`) after `USE db.def`, while the policies were matched against `db.def.t`.
  */
class SessionScopeScreenSpec extends AnyFlatSpec with Matchers with OptionValues:

  // ---- the screen itself ---------------------------------------------------------------------

  private val bypassBatches = List(
    "SET search_path='db.other'; SELECT * FROM t",
    "SET search_path = 'db.other'; SELECT * FROM t",
    "SET search_path TO 'db.other'; SELECT * FROM t",
    "SET search_path=db.other; SELECT * FROM t",
    "SET SESSION search_path='db.other'; SELECT * FROM t",
    "SET search_path='db.other,db.def'; SELECT * FROM t",
    "SET search_path='oc.main'; SELECT * FROM t",
    "SET search_path='other'; SELECT * FROM t",
    "SET \"search_path\"='db.other'; SELECT * FROM t",
    "SET\"search_path\"='db.other'; SELECT * FROM t",
    "set Search_Path='db.other'; select * from t",
    "SET schema='other'; SELECT * FROM t",
    "SET SESSION schema='other'; SELECT * FROM t",
    "SET schema='db.other'; SELECT * FROM t",
    "PRAGMA search_path='db.other'; SELECT * FROM t",
    "RESET search_path; SELECT * FROM t",
    "RESET schema; SELECT * FROM t",
    "RESET ALL; SELECT * FROM t",
    "USE db.other; SELECT * FROM t",
    "USE other; SELECT * FROM t",
    "USE oc; SELECT * FROM t",
    "USE\"db\".\"other\"; SELECT * FROM t",
    "INSERT INTO t VALUES (1); USE db.other; DELETE FROM t",
    // trailing: harmless on a fresh node connection, but it would leak into a connection the
    // session keeps (a client transaction on the native front door), so it is refused too
    "SELECT * FROM t; USE db.other",
    // hidden from a first-token check by leading trivia
    "/* c */ SET search_path='db.other'; SELECT * FROM t",
    "-- c\nUSE db.other; SELECT * FROM t",
    "\u00a0\u200bSET search_path='db.other'; SELECT * FROM t",
    "SET/**/search_path='db.other'; SELECT * FROM t",
    "SET\u00a0search_path='db.other'; SELECT * FROM t",
    // the separator a naive quote tracker would swallow
    "SELECT $$'$$; USE db.other; SELECT * FROM t",
    "SELECT e'\\''; USE db.other; SELECT * FROM t",
    "SELECT 1 /* a /* b */ ; c */; USE db.other; SELECT * FROM t",
    // a `$` that continues an identifier opens no dollar quote, so the trailing `$$` hides nothing
    "SELECT 1 AS x$$; SET search_path='db.other'; SELECT * FROM t; -- $$",
    "SELECT 1 AS x$$; SET schema='other'; SELECT * FROM t; -- $$",
    "SELECT 1 AS x$a$; USE other; SELECT * FROM t; -- $a$",
    "SELECT 1 AS x$$; SET search_path='db.other'; DELETE FROM t; -- $$",
    // DuckDB 2.0: a backtick quotes the setting name, glued to the keyword or not, and EXPLAIN
    // ANALYZE executes the SET it carries (each run on the 2.0 CLI, moving the search path)
    "SET `search_path` = 'db.other'; SELECT * FROM t",
    "SET`search_path`='db.other'; SELECT * FROM t",
    "SET SESSION`schema`='other'; SELECT * FROM t",
    "RESET `search_path`; SELECT * FROM t",
    "PRAGMA `search_path`='db.other'; SELECT * FROM t",
    "EXPLAIN ANALYZE SET search_path = 'db.other'; SELECT * FROM t",
    "EXPLAIN (ANALYZE) SET schema = 'other'; SELECT * FROM t"
  )

  "SessionScopeScreen" should "refuse every batch that moves the schema or search path" in:
    bypassBatches.foreach { sql =>
      withClue(sql)(SessionScopeScreen.find(sql) shouldBe defined)
    }

  it should "tell the caller to send the statement on its own" in:
    val reason = SessionScopeScreen.find("SET search_path='db.other'; SELECT * FROM t").value
    reason should include("own statement")
    reason should include("SET search_path='db.other'")

  it should "admit a lone USE / SET search_path / SET schema / RESET, with or without a semicolon" in:
    List(
      "USE db.other",
      "USE db.other;",
      "USE db.other; -- switch",
      "SET search_path='db.other'",
      "SET schema='other';",
      "RESET search_path",
      "PRAGMA search_path='db.other'"
    ).foreach(sql => withClue(sql)(SessionScopeScreen.find(sql) shouldBe None))

  it should "admit batches whose SET statements do not touch name resolution" in:
    List(
      "SET threads=4; SELECT * FROM t",
      "SET VARIABLE x = 1; SELECT getvariable('x')",
      // a VARIABLE named search_path sets a variable, not the setting (DuckDB 1.5.6: still reads
      // db.def.t afterwards)
      "SET VARIABLE search_path = 'db.other'; SELECT * FROM t",
      "BEGIN; INSERT INTO t VALUES (1); COMMIT",
      "SELECT 1; SELECT 2"
    ).foreach(sql => withClue(sql)(SessionScopeScreen.find(sql) shouldBe None))

  it should "not mistake a quoted or commented USE for a statement" in:
    List(
      "SELECT 'x; USE db.other'; SELECT * FROM t",
      "SELECT \"USE db.other;\" FROM t; SELECT 1",
      "SELECT $$; USE db.other$$; SELECT 1",
      "SELECT 1 /* ; USE db.other */; SELECT 2",
      "SELECT 1; -- USE db.other\nSELECT 2",
      "SELECT * FROM useful; SELECT * FROM settings"
    ).foreach(sql => withClue(sql)(SessionScopeScreen.find(sql) shouldBe None))

  // DuckDB 1.5.6 runs the first submission as ONE statement (U+00A0 is a space to its pre-pass, so
  // the `$$` opens a string holding the SET) and the second as three (U+00AD continues the
  // identifier `AS<U+00AD>$$`, so the SET runs): both run through the 1.5.6 CLI.
  it should "split after a no-break space or a soft hyphen the way DuckDB does" in:
    SessionScopeScreen.find(
      "SELECT 1 AS\u00a0$$; SET search_path='db.other'; SELECT * FROM t; -- $$"
    ) shouldBe None
    SessionScopeScreen.find(
      "SELECT 1 AS\u00ad$$; SET search_path='db.other'; SELECT * FROM t; -- $$"
    ) shouldBe defined
