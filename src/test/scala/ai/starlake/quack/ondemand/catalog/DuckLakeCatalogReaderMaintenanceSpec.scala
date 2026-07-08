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
          .rewriteTable("lake", "tpch1", "region")
      )
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
