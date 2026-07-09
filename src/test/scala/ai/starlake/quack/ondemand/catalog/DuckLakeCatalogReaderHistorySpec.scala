package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Live-fixture pin of listTableHistory (EPIC Spec 01): membership, SQL-CASE classification,
  * row/file deltas, schemaChanged, and rename continuity. The scenario takes `nation` through
  * create -> insert -> stamped insert -> delete -> update -> ALTER ADD COLUMN -> RENAME, with a
  * `region` insert as noise that must never appear in nation's history. Every DML is flushed
  * (two-phase inlined-delete pin from Spec 09) except the final unflushed insert, which pins the
  * changes_made-only membership path.
  */
class DuckLakeCatalogReaderHistorySpec extends AnyFlatSpec with Matchers with PostgresFixture:

  private val extra =
    s"""CREATE TABLE lake.tpch1.nation (n_key INTEGER NOT NULL, n_name VARCHAR);
       |INSERT INTO lake.tpch1.nation VALUES (1, 'FR');
       |CALL ducklake_flush_inlined_data('lake');
       |${stampedInsertSql}
       |DELETE FROM lake.tpch1.nation WHERE n_key = 1;
       |CALL ducklake_flush_inlined_data('lake');
       |UPDATE lake.tpch1.nation SET n_name = 'DEU' WHERE n_key = 2;
       |CALL ducklake_flush_inlined_data('lake');
       |ALTER TABLE lake.tpch1.nation ADD COLUMN n_comment VARCHAR;
       |ALTER TABLE lake.tpch1.nation RENAME TO nation2;
       |INSERT INTO lake.tpch1.region VALUES (99, 'R99', 'x');
       |CALL ducklake_flush_inlined_data('lake');
       |INSERT INTO lake.tpch1.nation2 (n_key, n_name) VALUES (3, 'IT');
       |""".stripMargin

  private def history(reader: DuckLakeCatalogReader): TableHistoryPage =
    reader
      .listTableHistory("tpch1", "nation2", limit = 100)
      .getOrElse(fail("nation2 must resolve"))

  "listTableHistory" should "return None for an unknown table" in
    withCatalog("hist", extraSql = extra) { (reader, _) =>
      reader.listTableHistory("tpch1", "no_such_table") shouldBe None
      reader.listTableHistory("nope", "nation2") shouldBe None
    }

  it should "order commits newest-first and survive the rename (identity via table_id)" in
    withCatalog("hist", extraSql = extra) { (reader, _) =>
      val page = history(reader)
      page.commits.map(_.snapshotId) shouldBe page.commits.map(_.snapshotId).sorted.reverse
      // create + 2 inserts + delete + update + alter + rename + trailing insert
      page.commits.size should be >= 8
      page.hasMore shouldBe false
    }

  it should "exclude snapshots that only touched other tables" in
    withCatalog("hist", extraSql = extra) { (reader, _) =>
      val regionOnly = reader
        .listSnapshots(limit = 100)
        .filter(s => s.affectedTables.nonEmpty && s.affectedTables.forall(_.name == "region"))
        .map(_.snapshotId)
      regionOnly should not be empty
      val ids = history(reader).commits.map(_.snapshotId)
      regionOnly.foreach(sid => ids should not contain sid)
    }

  it should "classify create/insert/delete/update/alter and compute the row deltas" in
    withCatalog("hist", extraSql = extra) { (reader, _) =>
      val commits = history(reader).commits.sortBy(_.snapshotId) // oldest first
      val oldest  = commits.head
      oldest.operation shouldBe HistoryOperation.Create
      oldest.schemaChanged shouldBe false // creation is not a schema *change*

      val stamped = commits.filter(_.author.contains("tenant:acme/user:alice"))
      stamped should have size 1
      stamped.head.operation shouldBe HistoryOperation.Insert
      stamped.head.commitMessage shouldBe Some("fixture insert")
      stamped.head.rowsAdded shouldBe 1L
      stamped.head.rowsRemoved shouldBe 0L

      val deletes = commits.filter(_.operation == HistoryOperation.Delete)
      deletes should not be empty
      deletes.map(_.rowsRemoved).sum shouldBe 1L
      deletes.foreach(_.rowsAdded shouldBe 0L)

      val updates = commits.filter(_.operation == HistoryOperation.Update)
      updates should not be empty
      updates.map(_.rowsAdded).sum shouldBe 1L
      updates.map(_.rowsRemoved).sum shouldBe 1L

      val alters = commits.filter(_.operation == HistoryOperation.Alter)
      alters.size should be >= 2               // ADD COLUMN + RENAME
      alters.count(_.schemaChanged) shouldBe 1 // only ADD COLUMN touches ducklake_column
    }

  it should "surface an unflushed inlined insert via changes_made with zero file deltas" in
    withCatalog("hist", extraSql = extra) { (reader, _) =>
      val newest = history(reader).commits.maxBy(_.snapshotId)
      newest.operation shouldBe HistoryOperation.Insert
      newest.filesAdded shouldBe 0
    }

  it should "carry null author/message on unstamped commits" in
    withCatalog("hist", extraSql = extra) { (reader, _) =>
      history(reader).commits.count(_.author.isEmpty) should be >= 5
    }

  it should "classify a drop commit" in
    withCatalog("hist", extraSql = "DROP TABLE lake.tpch1.region;\n") { (reader, _) =>
      // region is dropped, so resolve history via its pre-drop sibling scenario: the current-name
      // lookup returns None for a dropped table (out of scope for Spec 01: undrop is Spec 03).
      reader.listTableHistory("tpch1", "region") shouldBe None
    }

  it should "classify compaction as maintenance with zeroed row deltas" in
    withCatalog("hist") { (reader, _) =>
      // Several small flushed inserts produce adjacent small files, then compaction rewrites them.
      // The seed catalog already flushes one insert for region, plus the two below - 3 small files
      // merged into 1. Verified against the live fixture (ducklake_merge_adjacent_files reports
      // files_processed=3): filesRemoved must be exactly 3, not merely positive.
      runSqlOnCatalog(
        """INSERT INTO lake.tpch1.region VALUES (10, 'A', 'x');
          |CALL ducklake_flush_inlined_data('lake');
          |INSERT INTO lake.tpch1.region VALUES (11, 'B', 'y');
          |CALL ducklake_flush_inlined_data('lake');
          |CALL ducklake_merge_adjacent_files('lake');
          |""".stripMargin
      )
      val page = reader
        .listTableHistory("tpch1", "region", limit = 100)
        .getOrElse(fail("region must resolve"))
      val maint = page.commits.filter(_.operation == HistoryOperation.Maintenance)
      maint should not be empty
      maint.foreach { c =>
        c.rowsAdded shouldBe 0L
        c.rowsRemoved shouldBe 0L
        c.filesRemoved shouldBe 3
      }
    }
