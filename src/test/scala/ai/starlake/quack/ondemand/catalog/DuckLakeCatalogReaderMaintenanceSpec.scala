package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DuckLakeCatalogReaderMaintenanceSpec extends AnyFlatSpec with Matchers with PostgresFixture:

  private val extra =
    (1 to 5)
      .map(i =>
        s"INSERT INTO lake.tpch1.region VALUES (${100 + i}, 'R$i', 'x'); CALL ducklake_flush_inlined_data('lake');"
      )
      .mkString("\n")

  "snapshotsOlderThan" should "exclude the latest snapshot and honor the cutoff" in
    withCatalog("mreader", extraSql = extra) { (reader, _) =>
      val all = reader.listSnapshots(limit = 1000).map(_.snapshotId).sorted
      val far = java.time.Instant.now().plusSeconds(3600)
      val old = reader.snapshotsOlderThan(far)
      old should not contain all.last
      old.toSet shouldBe all.dropRight(1).toSet
      reader.snapshotsOlderThan(java.time.Instant.EPOCH) shouldBe empty
    }

  "smallFileCounts" should "count current sub-threshold files per table" in
    withCatalog("mreader", extraSql = extra) { (reader, _) =>
      val counts = reader.smallFileCounts(maxBytes = 10L * 1024 * 1024)
      counts.getOrElse(("tpch1", "region"), 0) should be >= 5
      reader.smallFileCounts(maxBytes = 1L) shouldBe empty
    }

  "filesScheduledForDeletion" should "be empty before the chain, populated after expire+merge, drained by cleanup" in
    withCatalog("mreader", extraSql = extra) { (reader, _) =>
      reader.filesScheduledForDeletion() shouldBe empty
      // Expiry ALONE schedules nothing on this DuckLake version (a file is only superseded
      // once compaction rewrites it), so run expire + merge like the real chain does.
      val all = reader.listSnapshots(limit = 1000).map(_.snapshotId).sorted
      runSqlOnCatalog(
        List(
          ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
            .expireVersions("lake", all.dropRight(1)),
          ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql.mergeAdjacent("lake")
        ).mkString(";\n")
      )
      val scheduled = reader.filesScheduledForDeletion()
      scheduled should not be empty
      scheduled.forall(_.endsWith(".parquet")) shouldBe true
      runSqlOnCatalog(
        ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
          .cleanupOldFiles("lake", graceDays = 0)
      )
      reader.filesScheduledForDeletion() shouldBe empty
    }

  "rewriteTable" should "succeed against the live fixture (pins the positional signature, F5)" in
    withCatalog("mreader", extraSql = extra) { (_, _) =>
      // A plain no-throw execution assertion: ducklake_rewrite_data_files has no table_name
      // named parameter on the pinned DuckDB/DuckLake version, only positional
      // (catalog, table, delete_threshold, schema) -- this pins that against the live engine
      // instead of only against the SQL-string builder.
      noException should be thrownBy runSqlOnCatalog(
        ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
          .rewriteTable("lake", "tpch1", "region", 0.2)
      )
    }

  // A sibling table with five small files of its own, beside region's.
  private val withSibling =
    extra + "\nCREATE TABLE lake.tpch1.cold (i INTEGER);\n" +
      (1 to 5)
        .map(i =>
          s"INSERT INTO lake.tpch1.cold VALUES ($i); CALL ducklake_flush_inlined_data('lake');"
        )
        .mkString("\n")

  "mergeAdjacentTable" should "merge the scoped table's files and leave its siblings' alone" in
    withCatalog("mreader", extraSql = withSibling) { (reader, _) =>
      val before = reader.smallFileCounts(maxBytes = 10L * 1024 * 1024)
      before.getOrElse(("tpch1", "region"), 0) should be >= 5
      before.getOrElse(("tpch1", "cold"), 0) shouldBe 5
      runSqlOnCatalog(
        ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
          .mergeAdjacentTable("lake", "tpch1", "region")
      )
      val after = reader.smallFileCounts(maxBytes = 10L * 1024 * 1024)
      after.getOrElse(("tpch1", "region"), 0) shouldBe 1
      after.getOrElse(("tpch1", "cold"), 0) shouldBe 5
    }

  // One file of 100 rows, 10 of them deleted: 10% of the file is deleted rows.
  private val tenPercentDeleted =
    "CREATE TABLE lake.tpch1.cold (i INTEGER);\n" +
      "INSERT INTO lake.tpch1.cold SELECT range FROM range(100); " +
      "CALL ducklake_flush_inlined_data('lake');\n" +
      "DELETE FROM lake.tpch1.cold WHERE i < 10; CALL ducklake_flush_inlined_data('lake');"

  /** The files the latest snapshot references, before and after `sql` runs. A rewrite replaces the
    * data file and drops its delete file in a new snapshot; one that rewrites nothing leaves the
    * latest snapshot's files as they were.
    */
  private def filesAroundStatement(reader: DuckLakeCatalogReader, sql: String) =
    val before = reader.filesReferencedAt(reader.maxSnapshotId().get)
    runSqlOnCatalog(sql)
    (before, reader.filesReferencedAt(reader.maxSnapshotId().get))

  "rewriteTable" should "rewrite a file whose deleted share passes the configured threshold" in
    withCatalog("mreader", extraSql = tenPercentDeleted) { (reader, _) =>
      val (before, after) = filesAroundStatement(
        reader,
        ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
          .rewriteTable("lake", "tpch1", "cold", 0.05)
      )
      after should not be before
      after.size should be < before.size
    }

  it should "leave a file whose deleted share is under the configured threshold" in
    withCatalog("mreader", extraSql = tenPercentDeleted) { (reader, _) =>
      val (before, after) = filesAroundStatement(
        reader,
        ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
          .rewriteTable("lake", "tpch1", "cold", 0.5)
      )
      after shouldBe before
    }

  "totalDataFileBytes" should "shrink once the chain releases superseded files" in
    withCatalog("mreader", extraSql = extra) { (reader, _) =>
      val before = reader.totalDataFileBytes()
      before should be > 0L
      val all = reader.listSnapshots(limit = 1000).map(_.snapshotId).sorted
      runSqlOnCatalog(
        List(
          ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
            .expireVersions("lake", all.dropRight(1)),
          ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql.mergeAdjacent("lake"),
          ai.starlake.quack.ondemand.maintenance.MaintenanceChainSql
            .cleanupOldFiles("lake", graceDays = 0)
        ).mkString(";\n")
      )
      reader.totalDataFileBytes() should be < before
    }
