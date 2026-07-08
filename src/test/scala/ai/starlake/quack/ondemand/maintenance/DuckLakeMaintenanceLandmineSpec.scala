package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.sys.process._

/** THE Spec 09 landmine test (spec section 10). ducklake_cleanup_old_files() has been reported to
  * delete nothing under an external Postgres catalog with explicit DATA_PATH - exactly QoD's
  * configuration. This spec pins, on the exact fixture QoD uses in production shape: (a) compaction
  * alone does NOT reduce on-disk bytes, (b) the full chain (expire -> merge -> cleanup_all)
  * physically deletes files and reduces on-disk bytes. If (b) fails, Spec 09 work STOPS until the
  * DuckLake pin is fixed.
  */
class DuckLakeMaintenanceLandmineSpec extends AnyFlatSpec with Matchers with PostgresFixture:

  private val Alias = "lake"

  /** 30 snapshots of one row each, flushed individually so each lands as its own parquet file. */
  private val manySmallFiles: String =
    (1 to 30)
      .map(i =>
        s"INSERT INTO lake.tpch1.region VALUES ($i, 'R$i', 'x'); CALL ducklake_flush_inlined_data('lake');"
      )
      .mkString("\n")

  private def dirBytes(p: Path): Long =
    Files.walk(p).filter(Files.isRegularFile(_)).mapToLong(Files.size(_)).sum

  private def parquetCount(p: Path): Long =
    Files.walk(p).filter(f => f.toString.endsWith(".parquet")).count()

  /** Run SQL against the fixture catalog in a fresh duckdb CLI session (same mechanism the
    * fixture's seeder uses). Fails the test on a non-zero exit.
    */
  private def runChainSql(body: String): Unit =
    // The reader was built from the fixture's metastore map; rebuild the ATTACH from env the
    // same way PostgresFixture.seedCatalog does.
    val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST", "localhost")
    val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT", "5432")
    val pgUser = sys.env.getOrElse("SL_TEST_PG_USER", "postgres")
    val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")
    val attach =
      s"""INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres;
         |ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=${currentDbName.get} user=$pgUser password=$pgPass' AS $Alias
         |  (DATA_PATH '${currentDataPath.get}');
         |$body
         |""".stripMargin
    val tmp = Files.createTempFile("chain", ".sql")
    Files.writeString(tmp, attach)
    try
      val rc = (s"duckdb" #< tmp.toFile).!
      assert(rc == 0, s"duckdb chain exit=$rc")
    finally Files.deleteIfExists(tmp)

  "compaction alone" should "NOT reduce on-disk bytes (the chain is required)" in
    withCatalog("maint", extraSql = manySmallFiles) { (reader, dataPath) =>
      val before = dirBytes(dataPath)
      runChainSql(MaintenanceChainSql.mergeAdjacent(Alias))
      val after = dirBytes(dataPath)
      withClue(s"before=$before after=$after") {
        // merge writes NEW files; old ones stay referenced by old snapshots.
        after should be >= before
      }
    }

  "the grace window" should "keep expired-but-not-cleanable files on disk" in
    withCatalog("maint", extraSql = manySmallFiles) { (reader, dataPath) =>
      val snaps       = reader.listSnapshots(limit = 1000).map(_.snapshotId).sorted
      val filesBefore = parquetCount(dataPath)
      runChainSql(
        List(
          MaintenanceChainSql.expireVersions(Alias, snaps.dropRight(1)),
          // A large grace window: nothing scheduled today is old enough to delete.
          MaintenanceChainSql.cleanupOldFiles(Alias, graceDays = 7)
        ).mkString(";\n")
      )
      withClue("expiry ran but the grace window must defer physical deletion") {
        parquetCount(dataPath) shouldBe filesBefore
      }
    }

  "the full chain" should "physically delete files under external Postgres + DATA_PATH" in
    withCatalog("maint", extraSql = manySmallFiles) { (reader, dataPath) =>
      val snaps = reader.listSnapshots(limit = 1000).map(_.snapshotId).sorted
      snaps.size should be > 10
      val bytesBefore = dirBytes(dataPath)
      val filesBefore = parquetCount(dataPath)
      // Expire everything except the latest snapshot, compact, then cleanup with no grace
      // (grace-window behavior is covered separately in Task 5's runner spec).
      val toExpire = snaps.dropRight(1)
      runChainSql(
        List(
          MaintenanceChainSql.expireVersions(Alias, toExpire),
          MaintenanceChainSql.mergeAdjacent(Alias),
          MaintenanceChainSql.cleanupOldFiles(Alias, graceDays = 0),
          MaintenanceChainSql.deleteOrphans(Alias, minAgeDays = 0)
        ).mkString(";\n")
      )
      val bytesAfter = dirBytes(dataPath)
      val filesAfter = parquetCount(dataPath)
      withClue(s"bytes $bytesBefore -> $bytesAfter, parquet $filesBefore -> $filesAfter") {
        filesAfter should be < filesBefore
        bytesAfter should be < bytesBefore
      }
      // The surviving data must still be readable at the latest snapshot.
      reader.snapshotExists(snaps.last) shouldBe true
    }
