package ai.starlake.acl.parser

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.sys.process.{Process, ProcessLogger}

/** Pins [[TableExtractor.DuckDbDefaultViews]] against the DuckDB actually installed: every default
  * view DuckDB creates in the `system` catalog's `main` and `pg_catalog` schemas resolves from a
  * bare table name on a node, so each must be one the ACL parser refuses to qualify as a grantable
  * table. A DuckDB bump that adds a view (as 1.3 added `duckdb_logs`) fails here rather than
  * reopening the class silently. Cancelled without `duckdb` on PATH, like the other real-node
  * specs; the iceberg CI channel installs the CLI, so it runs there.
  */
class DuckDbSystemViewsSpec extends AnyFlatSpec with Matchers:

  private val duckdbPresent: Boolean = Process("which duckdb").!(ProcessLogger(_ => ())) == 0

  private def query(sql: String): List[String] =
    val out = new StringBuilder
    val rc  = Process(Seq("duckdb", "-csv", "-noheader", "-c", sql))
      .!(ProcessLogger(l => out.append(l).append('\n'), _ => ()))
    rc shouldBe 0
    out.toString.linesIterator.map(_.trim).filter(_.nonEmpty).toList

  "DuckDB's bare-resolvable default views" should "all be refused as grantable table names" in {
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val views = query(
      "SELECT view_name FROM duckdb_views() WHERE internal AND database_name = 'system' " +
        "AND schema_name IN ('main', 'pg_catalog') ORDER BY 1"
    )
    withClue("scan found too little: the listing query no longer matches DuckDB's catalog") {
      views should contain allOf ("duckdb_tables", "sqlite_master", "pg_class")
    }
    val uncovered = views.filterNot(TableExtractor.isBareSystemView)
    withClue(
      "DuckDB ships a default view this build does not refuse; add it to " +
        "TableExtractor.DuckDbDefaultViews"
    ) {
      uncovered shouldBe empty
    }
  }

  it should "resolve from a bare name on this DuckDB, which is why the refusal exists" in {
    assume(duckdbPresent, "duckdb CLI not on PATH")
    // Under the production shape (attached catalog, USE'd schema) the bare names still reach the
    // system views: sqlite_master carries the user table's DDL and pg_class its name.
    val rows = query(
      "ATTACH ':memory:' AS acme_db; CREATE SCHEMA acme_db.tpch1; " +
        "CREATE TABLE acme_db.tpch1.customer(c INT); USE acme_db.tpch1; " +
        "SELECT count(*) FROM sqlite_master WHERE name = 'customer'; " +
        "SELECT count(*) FROM pg_class WHERE relname = 'customer'; " +
        "SELECT count(*) FROM main.duckdb_databases WHERE database_name = 'acme_db';"
    )
    rows shouldBe List("1", "1", "1")
  }
