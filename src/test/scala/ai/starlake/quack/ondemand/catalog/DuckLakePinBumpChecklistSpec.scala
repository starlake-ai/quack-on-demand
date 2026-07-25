package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.{PostgresFixture, TestPostgres}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.collection.mutable

/** Automates the live faces of docs/duckdb-pin-bump-checklist.md items 1 and 2, so an engine pin
  * bump turns verb renames/additions and sched-join drift into red tests instead of silent
  * misclassification (item 1's landmine) or double-counted `filesRemoved` (item 2's).
  *
  * The battery drives one table through every commit shape QoD produces: DDL, inlined DML, flushed
  * DML, a non-inlined bulk insert, UPDATE, RENAME, compaction, DROP. Assertions read
  * `ducklake_snapshot_changes` / `ducklake_files_scheduled_for_deletion` straight from the
  * catalog's Postgres database - the same rows `listTableHistory` classifies.
  */
class DuckLakePinBumpChecklistSpec extends AnyFlatSpec with Matchers with PostgresFixture:

  /** Every commit shape in one scenario. Small inserts inline (DuckLake default), the range()
    * insert exceeds the inlining threshold and writes a data file directly, three insert+flush
    * cycles leave adjacent files for the compaction to merge.
    */
  private val battery =
    """CREATE TABLE lake.tpch1.verbs (id INTEGER, v VARCHAR);
      |INSERT INTO lake.tpch1.verbs VALUES (1,'a'),(2,'b'),(3,'c');
      |DELETE FROM lake.tpch1.verbs WHERE id = 3;
      |CALL ducklake_flush_inlined_data('lake');
      |INSERT INTO lake.tpch1.verbs VALUES (4,'d');
      |CALL ducklake_flush_inlined_data('lake');
      |INSERT INTO lake.tpch1.verbs VALUES (5,'e');
      |CALL ducklake_flush_inlined_data('lake');
      |DELETE FROM lake.tpch1.verbs WHERE id = 4;
      |CALL ducklake_flush_inlined_data('lake');
      |INSERT INTO lake.tpch1.verbs SELECT range, 'bulk' FROM range(100000);
      |UPDATE lake.tpch1.verbs SET v = 'upd' WHERE id = 1;
      |CALL ducklake_flush_inlined_data('lake');
      |ALTER TABLE lake.tpch1.verbs ADD COLUMN w INTEGER;
      |ALTER TABLE lake.tpch1.verbs RENAME TO verbs2;
      |CALL ducklake_merge_adjacent_files('lake');
      |DROP TABLE lake.tpch1.verbs2;
      |""".stripMargin

  /** All `changes_made` rows of the current catalog, newest first. */
  private def changesMade(): List[String] =
    val dbName = currentDbName.getOrElse(fail("outside withCatalog"))
    val conn   = DriverManager.getConnection(
      TestPostgres.dbUrl(dbName),
      TestPostgres.pgUser,
      TestPostgres.pgPass
    )
    try
      val rs = conn
        .createStatement()
        .executeQuery("SELECT changes_made FROM ducklake_snapshot_changes ORDER BY snapshot_id")
      val out = mutable.ListBuffer.empty[String]
      while rs.next() do out += rs.getString(1)
      out.toList
    finally conn.close()

  private def verbsOf(changes: List[String]): Set[String] =
    changes.flatMap(_.split(',')).map(_.takeWhile(_ != ':').trim).filter(_.nonEmpty).toSet

  "changes_made verbs" should "stay within the classified + known-ignored vocabulary (item 1)" in
    withCatalog("pinverbs", extraSql = battery) { (_, _) =>
      val observed = verbsOf(changesMade())
      val known    =
        DuckLakeCatalogReader.HistoryVerbs.toSet ++ DuckLakeCatalogReader.HistoryIgnoredVerbs

      withClue(
        s"engine emitted changes_made verbs outside the pinned vocabulary; " +
          s"listTableHistory silently drops such commits from the timeline. " +
          s"Re-derive per docs/duckdb-pin-bump-checklist.md item 1. Observed: $observed. "
      ) {
        (observed -- known) shouldBe empty
      }

      // Guard against a vacuous pass: the battery must actually produce every interesting shape.
      val required = Set(
        "created_table",
        "inlined_insert",
        "inlined_delete",
        "inline_flush",
        "deleted_from_table",
        "inserted_into_table",
        "altered_table",
        "merge_adjacent",
        "dropped_table"
      )
      withClue(s"battery no longer exercises all commit shapes; observed only: $observed. ") {
        (required -- observed) shouldBe empty
      }
    }

  "compaction scheduling" should "backdate schedule_start to the snapshot and hard-delete (item 2)" in
    withCatalog("pinsched", extraSql = battery) { (_, _) =>
      val dbName = currentDbName.getOrElse(fail("outside withCatalog"))
      val conn   = DriverManager.getConnection(
        TestPostgres.dbUrl(dbName),
        TestPostgres.pgUser,
        TestPostgres.pgPass
      )
      try
        val st = conn.createStatement()

        val sidRs = st.executeQuery(
          "SELECT max(snapshot_id) FROM ducklake_snapshot_changes WHERE changes_made LIKE '%merge_adjacent%'"
        )
        sidRs.next() shouldBe true
        val mergeSid = sidRs.getLong(1)

        // The battery must have left something to compact, or the invariants pass vacuously.
        val schedCount = st.executeQuery(
          "SELECT count(*) FROM ducklake_files_scheduled_for_deletion"
        )
        schedCount.next() shouldBe true
        withClue("compaction scheduled no files; battery no longer produces adjacent files. ") {
          schedCount.getLong(1) should be > 0L
        }

        // Invariant A: EVERY sched row's schedule_start equality-joins the snapshot_time of its
        // own triggering snapshot (same transaction). Compaction is not the only scheduler:
        // flushing inlined deletes/updates rewrites a delete file and schedules the superseded
        // one at the FLUSH snapshot (observed on 1.5.5, snapshot-mapped via LEFT JOIN). The sched
        // CTE in listTableHistory relies on the strict-equality join attributing each row.
        val orphans = st.executeQuery(
          """SELECT count(*) FROM ducklake_files_scheduled_for_deletion f
            |LEFT JOIN ducklake_snapshot s ON s.snapshot_time = f.schedule_start
            |WHERE s.snapshot_id IS NULL""".stripMargin
        )
        orphans.next() shouldBe true
        withClue(
          "schedule_start rows no longer match any snapshot_time exactly; " +
            "the sched CTE equality join in listTableHistory silently loses filesRemoved. "
        ) {
          orphans.getLong(1) shouldBe 0L
        }

        // The compaction snapshot itself must be among the schedulers.
        val mergeSched = st.executeQuery(
          s"""SELECT count(*) FROM ducklake_files_scheduled_for_deletion f
             |JOIN ducklake_snapshot s ON s.snapshot_time = f.schedule_start
             |WHERE s.snapshot_id = $mergeSid""".stripMargin
        )
        mergeSched.next() shouldBe true
        withClue("compaction no longer schedules its merged files at its own snapshot. ") {
          mergeSched.getLong(1) should be > 0L
        }

        // Invariant B: no scheduling snapshot also end-dates ducklake_data_file rows.
        // listTableHistory SUMS tr (end_snapshot-keyed removals) and sched, so any overlap
        // double-counts filesRemoved. Compaction hard-deletes superseded data-file rows.
        val overlap = st.executeQuery(
          """SELECT count(*) FROM (
            |  SELECT DISTINCT s.snapshot_id FROM ducklake_files_scheduled_for_deletion f
            |  JOIN ducklake_snapshot s ON s.snapshot_time = f.schedule_start
            |) sched JOIN ducklake_data_file df ON df.end_snapshot = sched.snapshot_id""".stripMargin
        )
        overlap.next() shouldBe true
        withClue(
          "a snapshot now both schedules files AND end-dates data files; " +
            "tr+sched would double-count filesRemoved in listTableHistory. "
        ) {
          overlap.getLong(1) shouldBe 0L
        }
      finally conn.close()
    }
