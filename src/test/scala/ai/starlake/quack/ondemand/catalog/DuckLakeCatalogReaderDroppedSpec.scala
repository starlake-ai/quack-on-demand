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

  private val accents =
    s"""CREATE TABLE lake.tpch1."Ü" (id INTEGER);
       |DROP TABLE lake.tpch1."Ü";
       |CREATE TABLE lake.tpch1."ü" (id INTEGER);
       |CREATE TABLE lake.tpch1.Ascii_Case (id INTEGER);
       |DROP TABLE lake.tpch1.Ascii_Case;
       |""".stripMargin

  "the undrop name checks" should "fold ASCII letters only, as DuckDB resolves names (review 5 R3)" in
    withCatalog("undrop-fold", extraSql = accents) { (reader, _) =>
      val max = reader.maxSnapshotId().getOrElse(fail("no snapshot"))
      // "Ü" and "ü" are two names to DuckDB: the live "ü" neither hides nor holds "Ü".
      reader.listDroppedTables().map(_.table) should contain("Ü")
      reader.nameHeldAt("tpch1", "Ü", max) shouldBe false
      reader.nameHeldAt("tpch1", "ü", max) shouldBe true
      reader.findDroppedTable("tpch1", "ü") shouldBe empty
      // ASCII letters compare without case.
      reader.findDroppedTable("TPCH1", "ascii_case").map(_.table) shouldBe Some("Ascii_Case")
    }
