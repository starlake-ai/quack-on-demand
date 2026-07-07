package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.api.CatalogTableRef
import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

class DuckLakeCatalogReaderSpec extends AnyFlatSpec with Matchers with PostgresFixture:

  "listSchemas" should "return every user schema in the catalog with table counts" in
    withCatalog("tpch") { (reader, _) =>
      val schemas = reader.listSchemas()
      schemas.map(_.name) should contain("tpch1")
      schemas.find(_.name == "tpch1").value.tableCount shouldBe 1
    }

  "listTables" should "return every table in a schema with row + data-file counts" in
    withCatalog("tpch") { (reader, _) =>
      val tables = reader.listTables(schema = "tpch1")
      tables.map(_.name).sorted shouldBe List("region")
      val region = tables.head
      region.schema shouldBe "tpch1"
      region.rowCount shouldBe 5L
      region.dataFileCount should be >= 1
    }

  "getTable" should "include columns with types, nullability, and PK flag" in
    withCatalog("tpch") { (reader, _) =>
      val detail = reader.getTable(schema = "tpch1", table = "region").value
      detail.columns.map(_.name) should contain allOf ("r_regionkey", "r_name", "r_comment")
      val rk = detail.columns.find(_.name == "r_regionkey").value
      rk.typeName.toUpperCase should startWith("INT")
      rk.nullable shouldBe false
      // DuckLake (as of v0.3) doesn't model PRIMARY KEY in its metadata, so
      // isPrimaryKey is always false. See DuckLakeCatalogReader for details.
      rk.isPrimaryKey shouldBe false
      val nullable = detail.columns.find(_.name == "r_comment").value
      nullable.nullable shouldBe true
    }

  "getTable" should "list the parquet data files backing the table" in
    withCatalog("tpch") { (reader, _) =>
      val detail = reader.getTable(schema = "tpch1", table = "region").value
      detail.dataFiles should not be empty
      val f = detail.dataFiles.head
      f.path should fullyMatch regex """.*\.parquet"""
      f.sizeBytes should be > 0L
      f.rowCount should be > 0L
    }

  "getTable" should "return None when the table doesn't exist" in
    withCatalog("tpch") { (reader, _) =>
      reader.getTable(schema = "tpch1", table = "does_not_exist") shouldBe None
    }

  "listSnapshots" should "list snapshots newest-first with change summaries and counts" in
    withCatalog("tpch") { (reader, _) =>
      val snaps = reader.listSnapshots()
      snaps should not be empty
      // newest first
      snaps.map(_.snapshotId) shouldBe snaps.map(_.snapshotId).sortBy(-_)
      // committedAt is ISO-8601 parseable
      noException should be thrownBy java.time.Instant.parse(snaps.head.committedAt)
      // the seed inserts 5 rows and flushes them to parquet: across all
      // snapshots exactly 5 rows / at least 1 file were added, none removed
      snaps.map(_.rowsAdded).sum shouldBe 5L
      snaps.map(_.filesAdded).sum should be >= 1
      snaps.map(_.filesRemoved).sum shouldBe 0
      // snapshots that touched the region table resolve its name
      val touching = snaps.filter(_.changes.contains("_table"))
      touching should not be empty
      touching.flatMap(_.affectedTables) should contain(CatalogTableRef("tpch1", "region"))
    }

  // Evolves the seeded catalog: schema change, second insert (new parquet
  // file), then a delete (delete file, not a data-file removal). Each
  // statement commits its own snapshot.
  // Note: DuckLake (this version) inlines small DELETEs; the extra
  // ducklake_flush_inlined_data call materialises the inline delete into a
  // parquet delete file so 'deleted_from_table' appears in changes_made and
  // ducklake_delete_file gets a row we can count.
  private val evolutionSql =
    """ALTER TABLE lake.tpch1.region ADD COLUMN r_flag BOOLEAN;
      |INSERT INTO lake.tpch1.region VALUES (5, 'ANTARCTICA', 'f', true);
      |CALL ducklake_flush_inlined_data('lake');
      |DELETE FROM lake.tpch1.region WHERE r_regionkey <= 1;
      |CALL ducklake_flush_inlined_data('lake');
      |""".stripMargin

  "getTable with asOf" should "show the column set as of a pre-ALTER snapshot" in
    withCatalog("tpch", evolutionSql) { (reader, _) =>
      val snaps     = reader.listSnapshots()
      val alterSnap = snaps.find(_.changes.contains("altered_table")).value.snapshotId
      val before    = reader.getTable("tpch1", "region", asOf = Some(alterSnap - 1)).value
      before.columns.map(_.name) should not contain "r_flag"
      val after = reader.getTable("tpch1", "region", asOf = Some(alterSnap)).value
      after.columns.map(_.name) should contain("r_flag")
    }

  it should "show the parquet file list as of each insert snapshot" in
    withCatalog("tpch", evolutionSql) { (reader, _) =>
      val inserts = reader.listSnapshots().filter(_.filesAdded > 0).sortBy(_.snapshotId)
      inserts should have size 2
      val atFirst = reader.getTable("tpch1", "region", asOf = Some(inserts.head.snapshotId)).value
      atFirst.dataFiles should have size 1
      val current = reader.getTable("tpch1", "region").value
      current.dataFiles should have size 2
    }

  it should "compute the AS OF row count net of deletes" in
    withCatalog("tpch", evolutionSql) { (reader, _) =>
      val snaps = reader.listSnapshots()
      // The logical delete is the `inlined_delete` snapshot: the flushed
      // delete file is backdated to it (begin_snapshot), and DuckDB's own
      // `AT (VERSION => n)` reads 4 rows there, 6 rows one before. The
      // later `deleted_from_table` flush snapshot only materialises the
      // parquet delete file.
      val delSnap = snaps.find(_.changes.contains("inlined_delete")).value.snapshotId
      reader.getTable("tpch1", "region", asOf = Some(delSnap - 1)).value.table.rowCount shouldBe 6L
      reader.getTable("tpch1", "region", asOf = Some(delSnap)).value.table.rowCount shouldBe 4L
    }

  it should "return None for a nonexistent snapshot id" in
    withCatalog("tpch") { (reader, _) =>
      reader.getTable("tpch1", "region", asOf = Some(999999L)) shouldBe None
    }

  it should "return None when the table did not exist yet at the snapshot" in
    withCatalog("tpch") { (reader, _) =>
      // snapshot 0 is DuckLake's initial empty snapshot
      reader.getTable("tpch1", "region", asOf = Some(0L)) shouldBe None
    }

  "listSnapshots" should "honor limit and keyset-paginate via before" in
    withCatalog("tpch") { (reader, _) =>
      val all = reader.listSnapshots()
      all.size should be > 3
      val firstPage = reader.listSnapshots(limit = 3)
      firstPage shouldBe all.take(3)
      val nextPage = reader.listSnapshots(limit = 3, before = Some(firstPage.last.snapshotId))
      nextPage shouldBe all.slice(3, 6)
      // pages are disjoint and ordered newest first
      (firstPage.map(_.snapshotId) ++ nextPage.map(_.snapshotId)) shouldBe
        (firstPage.map(_.snapshotId) ++ nextPage.map(_.snapshotId)).sortBy(-_)
    }
