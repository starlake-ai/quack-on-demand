package ai.starlake.quack.ondemand.catalog.iceberg

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IcebergCatalogSqlSpec extends AnyFlatSpec with Matchers:
  import IcebergCatalogSql.SnapshotFilter

  "every builder" should "quote identifiers, so a hostile name cannot break out" in {
    val sql = IcebergCatalogSql.preview("ice", "s\"x", "t", None, 10)
    sql should include("\"ice\".\"s\"\"x\".\"t\"")
  }

  "schemas / tables" should "filter duckdb_schemas / duckdb_tables on the alias as a literal" in {
    IcebergCatalogSql.schemas("o'x") should include("database_name = 'o''x'")
    IcebergCatalogSql.tables("ice", "probe") should include("schema_name = 'probe'")
  }

  "files" should "report DATA, not EXISTING, for a data file" in {
    val sql = IcebergCatalogSql.files("ice", "probe", "t")
    sql should include(
      "CASE WHEN manifest_content = 'DATA' THEN 'DATA' ELSE content END AS content"
    )
    sql should include("iceberg_metadata(\"ice\".\"probe\".\"t\")")
  }

  "snapshots" should "read only snapshots and current-snapshot-id, never credentials" in {
    val sql = IcebergCatalogSql.snapshots("ice", "probe", "t", SnapshotFilter.Page(None, None), 50)
    sql should include("iceberg_load_table_response(\"ice\".\"probe\".\"t\")")
    sql should include("'snapshots'")
    sql should include("'current-snapshot-id'")
    sql should not include "storage_credentials"
    sql should not include "config"
    sql should not include "request_url"
    sql should include("ORDER BY seq DESC")
    sql should include("LIMIT 50")
  }

  it should "page by sequence number and filter on a validated operation" in {
    val sql =
      IcebergCatalogSql.snapshots("ice", "p", "t", SnapshotFilter.Page(Some(3L), Some("append")), 2)
    sql should include("seq < 3")
    sql should include("summary_json::JSON->>'operation' = 'append'")
    an[IllegalArgumentException] should be thrownBy
      IcebergCatalogSql.snapshots(
        "ice",
        "p",
        "t",
        SnapshotFilter.Page(None, Some("x' OR 1=1 --")),
        2
      )
  }

  it should "select the row the catalog's current-snapshot-id points at, never credentials" in {
    val sql = IcebergCatalogSql.snapshots("ice", "probe", "t", SnapshotFilter.Current, 1)
    sql should include("WHERE is_current")
    sql should include("LIMIT 1")
    sql should not include "storage_credentials"
    sql should not include "config"
    sql should not include "request_url"
  }

  it should "select by ids and by timestamp" in {
    IcebergCatalogSql.snapshots("ice", "p", "t", SnapshotFilter.ById(List("1", "2")), 2) should
      include("snapshot_id IN ('1', '2')")
    IcebergCatalogSql.snapshots(
      "ice",
      "p",
      "t",
      SnapshotFilter.AtOrBefore(1790709486100L),
      1
    ) should
      include("ts_ms <= 1790709486100")
    an[IllegalArgumentException] should be thrownBy
      IcebergCatalogSql.snapshots("ice", "p", "t", SnapshotFilter.ById(List("1 OR 1=1")), 1)
  }

  "preview" should "add AT (VERSION) only for a snapshot, and refuse a non-numeric id" in {
    IcebergCatalogSql.preview("ice", "p", "t", None, 11) shouldBe
      "SELECT * FROM \"ice\".\"p\".\"t\" LIMIT 11"
    IcebergCatalogSql.preview("ice", "p", "t", Some("7761858545720969174"), 11) shouldBe
      "SELECT * FROM \"ice\".\"p\".\"t\" AT (VERSION => 7761858545720969174) LIMIT 11"
    an[IllegalArgumentException] should be thrownBy
      IcebergCatalogSql.preview("ice", "p", "t", Some("main"), 1)
  }

  "diff" should "union both EXCEPT ALL directions under a reserved change column" in {
    val sql = IcebergCatalogSql.diff("ice", "p", "t", "1", "2", None, 21)
    sql should include("'removed' AS \"__qod_change\"")
    sql should include("'added' AS \"__qod_change\"")
    sql should include(
      "AT (VERSION => 1) EXCEPT ALL SELECT * FROM \"ice\".\"p\".\"t\" AT (VERSION => 2)"
    )
    sql should endWith("LIMIT 21")
  }

  it should "keep only one direction when a change type is given" in {
    val added = IcebergCatalogSql.diff("ice", "p", "t", "1", "2", Some("added"), 5)
    added should include("'added'")
    added should not include "'removed'"
    an[IllegalArgumentException] should be thrownBy
      IcebergCatalogSql.diff("ice", "p", "t", "1", "2", Some("update"), 5)
  }
