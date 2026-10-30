package ai.starlake.quack.edge.policy

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CatalogReachingCallsSpec extends AnyFlatSpec with Matchers:

  "CatalogReachingCalls" should "see a call after a dollar quote that follows a Unicode space" in:
    // DuckDB opens the dollar quote after the space character, so the `--` sits inside the string
    // and the call after it runs; read as a comment it would hide the call from this screen.
    CatalogReachingCalls.find("SELECT\u00a0$$--$$, * FROM query_table('t')") shouldBe
      Some("query_table")
    CatalogReachingCalls.find("SELECT\u3000$$--$$, * FROM query('SELECT 1')") shouldBe
      Some("query")

  it should "ignore a call that only appears inside a comment" in:
    CatalogReachingCalls.find("SELECT 1 -- query_table('t')") shouldBe None

  it should "read a dollar sign after a soft hyphen as part of an identifier" in:
    // DuckDB names the column `AS<U+00AD>$$` and reads the rest of the line as a comment: the call
    // never runs (run through the 1.5.6 CLI against a missing table, which it did not resolve).
    CatalogReachingCalls.find("SELECT 1 AS\u00ad$$ -- $$, * FROM query_table('t')") shouldBe None
    CatalogReachingCalls.find("SELECT 1 AS\u00a0$$ -- $$, * FROM query_table('t')") shouldBe
      Some("query_table")

  it should "reach through DuckLake's per-schema macros, bare, quoted or catalog-qualified" in:
    // F09: DuckLake resolves these names in every schema of its catalogs and runs the ducklake_*
    // function of the same name there (2.0 CLI): `merge_adjacent_files()` under `USE lake` is
    // `ducklake_merge_adjacent_files('lake')`, which the prefix rule already refuses.
    Seq(
      "FROM merge_adjacent_files()"                                -> "merge_adjacent_files",
      "CALL merge_adjacent_files()"                                -> "merge_adjacent_files",
      "FROM lake.set_option('delete_older_than', '1 second')"      -> "set_option",
      "FROM lake.main.set_option('expire_older_than', '1 second')" -> "set_option",
      "CALL set_commit_message('a', 'b')"                          -> "set_commit_message",
      "SELECT * FROM snapshots()"                                  -> "snapshots",
      "SELECT * FROM \"lake\".`snapshots`()"                       -> "snapshots",
      "SELECT * FROM lake.\"Table_Info\"()"                        -> "table_info",
      "FROM table_info()"                                          -> "table_info",
      "FROM table_changes('t', 0, 3)"                              -> "table_changes",
      "FROM table_insertions('t', 0, 3)"                           -> "table_insertions",
      "FROM table_deletions('t', 0, 3)"                            -> "table_deletions",
      "FROM current_snapshot()"                                    -> "current_snapshot",
      "FROM last_committed_snapshot()"                             -> "last_committed_snapshot",
      "FROM lake.options()"                                        -> "options",
      "FROM lake.settings()"                                       -> "settings",
      "EXPLAIN ANALYZE FROM merge_adjacent_files()"                -> "merge_adjacent_files",
      "SELECT * FROM query('FROM merge_adjacent_files()')"         -> "query"
    ).foreach { (sql, fn) =>
      withClue(sql)(CatalogReachingCalls.find(sql) shouldBe Some(fn))
    }
    CatalogReachingCalls.denyReason("snapshots") should include("ducklake_snapshots")

  it should "leave DuckDB's own table_info pragma and look-alike names alone" in:
    Seq(
      "PRAGMA table_info('t')",
      "PRAGMA \"table_info\"('t')",
      "pragma TABLE_INFO('t')",
      "SELECT * FROM pragma_table_info('t')",
      "SELECT snapshots FROM t",
      "SELECT * FROM duckdb_settings()",
      "SELECT current_setting('threads')"
    ).foreach(sql => withClue(sql)(CatalogReachingCalls.find(sql) shouldBe None))

  "ReadingTableMacros" should "be every DuckDB 2.0 table macro that reads a table, directly or not" in {
    // native/qod-node/macros.json lists each built-in macro with its body's calls and whether it
    // reads a table itself; the set is closed over table calls of another such macro
    val entries = io.circe.parser
      .parse(
        java.nio.file.Files.readString(java.nio.file.Paths.get("native/qod-node/macros.json"))
      )
      .fold(e => fail(s"macros.json: ${e.getMessage}"), identity)
      .asArray
      .getOrElse(fail("macros.json is not an array"))
      .map(_.hcursor)
      .filter(_.get[String]("type").contains("table_macro"))
    def name(c: io.circe.HCursor): String =
      c.get[String]("name").fold(e => fail(e.toString), identity)
    def readsItself(c: io.circe.HCursor): Boolean = c.get[Boolean]("readsTables").getOrElse(false)
    def tableCalls(c: io.circe.HCursor): Set[String] =
      c.get[List[List[String]]]("calls")
        .getOrElse(Nil)
        .collect { case "table" :: _ :: _ :: n :: Nil => n }
        .toSet
    var reading = entries.filter(readsItself).map(name).toSet
    var grew    = true
    while grew do
      val next = reading ++ entries.filter(c => tableCalls(c).exists(reading)).map(name)
      grew = next.size > reading.size
      reading = next
    reading shouldBe CatalogReachingCalls.ReadingTableMacros
  }
