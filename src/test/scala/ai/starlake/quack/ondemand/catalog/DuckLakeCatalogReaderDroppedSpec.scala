package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Live-fixture pin of listDroppedTables (Spec 03 undrop): a dropped table appears with the correct
  * last-live snapshot; a renamed table does NOT (its table_id keeps a live row); a never-dropped
  * table does not appear.
  */
class DuckLakeCatalogReaderDroppedSpec extends AnyFlatSpec with Matchers with PostgresFixture:

  private val extra =
    s"""CREATE TABLE lake.tpch1.doomed (id INTEGER, v VARCHAR);
       |INSERT INTO lake.tpch1.doomed VALUES (1, 'a');
       |CALL ducklake_flush_inlined_data('lake');
       |CREATE TABLE lake.tpch1.renamed_src (id INTEGER);
       |ALTER TABLE lake.tpch1.renamed_src RENAME TO renamed_dst;
       |DROP TABLE lake.tpch1.doomed;
       |CREATE TABLE lake.tpch1.revived (id INTEGER);
       |DROP TABLE lake.tpch1.revived;
       |CREATE TABLE lake.tpch1.revived (id INTEGER);
       |""".stripMargin

  "listDroppedTables" should "list the dropped table with its last-live snapshot and exclude renames" in
    withCatalog("undrop", extraSql = extra) { (reader, _) =>
      val dropped = reader.listDroppedTables()
      val doomed  = dropped.find(_.table == "doomed").getOrElse(fail("doomed must be listed"))
      doomed.schema shouldBe "tpch1"
      doomed.lastLiveSnapshot shouldBe (doomed.droppedAtSnapshot - 1)
      doomed.recoverable shouldBe true
      doomed.droppedAt should not be empty
      reader.snapshotExists(doomed.lastLiveSnapshot) shouldBe true
      dropped.map(_.table) should not contain "renamed_src"
      dropped.map(_.table) should not contain "renamed_dst"
      dropped.map(_.table) should not contain "region"
    }

  it should "hide a drop whose name is live again, while findDroppedTable still resolves it" in
    withCatalog("undrop-revived", extraSql = extra) { (reader, _) =>
      // `revived` was dropped and then re-created under the same name (what an undrop
      // recovery does via CTAS). The discovery list must skip it - its Undrop action
      // would 409 name_conflict - but the targeted lookup keeps resolving the old drop
      // so an explicit undrop with asName can still recover it under another name.
      reader.listDroppedTables().map(_.table) should not contain "revived"
      reader.findDroppedTable("tpch1", "revived") should not be empty
    }
