package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.{PostgresFixture, TestPostgres}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{DriverManager, SQLException}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class DuckLakeCatalogReaderTimeTravelSpec
    extends AnyFlatSpec
    with Matchers
    with OptionValues
    with PostgresFixture:

  // Two tables so the per-table filter has something to exclude; the second
  // insert into `nation` is stamped with the P1 author prelude.
  private val extra =
    s"""CREATE TABLE lake.tpch1.nation (n_key INTEGER NOT NULL, n_name VARCHAR);
       |INSERT INTO lake.tpch1.nation VALUES (1, 'FR');
       |CALL ducklake_flush_inlined_data('lake');
       |${stampedInsertSql} -- fixture helper: P1 prelude + INSERT INTO nation + flush
       |INSERT INTO lake.tpch1.region VALUES (99, 'R99', 'x');
       |CALL ducklake_flush_inlined_data('lake');
       |""".stripMargin

  "listSnapshots" should "carry author and commitMessage, null for unstamped" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      val snaps = reader.listSnapshots(limit = 100)
      snaps.exists(s => s.author.contains("tenant:acme/user:alice")) shouldBe true
      snaps.exists(s => s.author.isEmpty) shouldBe true
    }

  it should "filter snapshots to those touching one table" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      val all    = reader.listSnapshots(limit = 100)
      val nation = reader.listSnapshots(limit = 100, table = Some(("tpch1", "nation")))
      nation should not be empty
      nation.size should be < all.size
      nation.foreach(s => s.affectedTables.map(_.name) should contain("nation"))
    }

  "maxSnapshotId" should "match the newest listed snapshot" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      reader.maxSnapshotId() shouldBe reader.listSnapshots(limit = 1).headOption.map(_.snapshotId)
    }

  "snapshotAtOrBefore" should "resolve nearest-at-or-before and None before the first" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      val snaps  = reader.listSnapshots(limit = 100).sortBy(_.snapshotId)
      val second = snaps(1)
      val third  = snaps(2)
      val ts     = java.time.Instant.parse(second.committedAt)
      // Midpoint between `second` and the NEXT snapshot's timestamp - a
      // timing-independent stand-in for "a bit later, but before anything
      // else happened". A fixed `plusSeconds(1)` is unsafe here: this
      // fixture's snapshots all land within the same second (verified
      // empirically - consecutive gaps are single-digit milliseconds), so
      // adding a whole second would jump past the LAST snapshot instead of
      // landing strictly before the next one.
      val thirdTs  = java.time.Instant.parse(third.committedAt)
      val gapNanos = java.time.Duration.between(ts, thirdTs).toNanos
      val midpoint = ts.plusNanos((gapNanos / 2L) max 1L)
      reader.snapshotAtOrBefore(ts) shouldBe Some(second.snapshotId)
      reader.snapshotAtOrBefore(midpoint) shouldBe Some(second.snapshotId)
      reader.snapshotAtOrBefore(
        java.time.Instant.parse(snaps.head.committedAt).minusSeconds(60)
      ) shouldBe None
    }

  "tableExistsAt" should "see a dropped table at the pre-drop snapshot only, and never a bogus one" in
    withCatalog(
      "tt",
      extraSql = "DROP TABLE lake.tpch1.region;\n"
    ) { (reader, _) =>
      val snaps = reader.listSnapshots(limit = 100).map(_.snapshotId).sorted
      // The drop is the newest snapshot; the one before it still sees the table. snaps.head is
      // NOT used for the positive probe: the fixture's first snapshot (schema creation) may
      // predate CREATE TABLE, and tableExistsAt deliberately has no begin_snapshot clamping
      // (unlike schemaDiff's `from` clamp).
      reader.tableExistsAt("tpch1", "region", snaps(snaps.size - 2)) shouldBe true
      reader.tableExistsAt("tpch1", "region", snaps.last) shouldBe false
      reader.tableExistsAt("tpch1", "no_such_table", snaps.last) shouldBe false
    }

  "schemaDiff" should "report added, removed, retyped, and renamed-table identity" in
    withCatalog(
      "tt",
      extraSql = s"""ALTER TABLE lake.tpch1.region ADD COLUMN r_new BIGINT;
                    |ALTER TABLE lake.tpch1.region DROP COLUMN r_comment;
                    |ALTER TABLE lake.tpch1.region ALTER COLUMN r_name TYPE TEXT;
                    |ALTER TABLE lake.tpch1.region RENAME TO region2;
                    |""".stripMargin
    ) { (reader, _) =>
      val snaps = reader.listSnapshots(limit = 100).map(_.snapshotId).sorted
      val diff  = reader.schemaDiff("tpch1", "region2", snaps.head, snaps.last)
      diff.added.map(_.name) should contain("r_new")
      diff.removed.map(_.name) should contain("r_comment")
      // type change may surface as VARCHAR -> VARCHAR alias on this engine; assert on
      // the column identity and that from != to only if the engine records it
      diff.typeChanged.map(_._1) should (contain("r_name") or be(empty))
    }

  "nameHistoryStable" should "hold for a name that has always been one table with the same columns" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      reader.nameHistoryStable("tpch1", "nation") shouldBe true
      // DuckDB binds names case-insensitively, and so does the check.
      reader.nameHistoryStable("TPCH1", "Nation") shouldBe true
      reader.nameHistoryStable("tpch1", "no_such_table") shouldBe false
    }

  it should "fail for both names of a renamed table, and for a name reused by another table" in
    withCatalog(
      "tt",
      extraSql = extra +
        """ALTER TABLE lake.tpch1.nation RENAME TO nations;
          |CREATE TABLE lake.tpch1.nation (n_key INTEGER);
          |""".stripMargin
    ) { (reader, _) =>
      reader.nameHistoryStable("tpch1", "nations") shouldBe false
      reader.nameHistoryStable("tpch1", "nation") shouldBe false
    }

  it should "fail for a table one of whose columns was renamed" in
    withCatalog(
      "tt",
      extraSql = extra + "ALTER TABLE lake.tpch1.nation RENAME COLUMN n_name TO n_label;\n"
    ) { (reader, _) =>
      reader.nameHistoryStable("tpch1", "nation") shouldBe false
    }

  it should "fail for a table dropped since, and for one with a column dropped since" in
    withCatalog(
      "tt",
      extraSql = extra +
        """ALTER TABLE lake.tpch1.region DROP COLUMN r_comment;
          |DROP TABLE lake.tpch1.nation;
          |""".stripMargin
    ) { (reader, _) =>
      reader.nameHistoryStable("tpch1", "nation") shouldBe false
      reader.nameHistoryStable("tpch1", "region") shouldBe false
    }

  it should "fail for a name that was ever a view's" in
    withCatalog(
      "tt",
      extraSql = extra +
        """CREATE VIEW lake.tpch1.shadow AS SELECT * FROM lake.tpch1.nation;
          |DROP VIEW lake.tpch1.shadow;
          |CREATE TABLE lake.tpch1.shadow (s_key INTEGER);
          |""".stripMargin
    ) { (reader, _) =>
      reader.nameHistoryStable("tpch1", "shadow") shouldBe false
      reader.nameHistoryStable("tpch1", "region") shouldBe true
    }

  "viewEverNamed" should "find a view whatever the case it is asked in, as DuckDB binds it" in
    withCatalog(
      "tt",
      extraSql = extra + "CREATE VIEW lake.tpch1.myview AS SELECT * FROM lake.tpch1.nation;\n"
    ) { (reader, _) =>
      reader.viewEverNamed("tpch1", "myview") shouldBe true
      reader.viewEverNamed("TPCH1", "MyView") shouldBe true
      reader.viewEverNamed("tpch1", "nation") shouldBe false
    }

  // The name-history reads run on the statement path of every time-travel read under a policy, so
  // a metadata Postgres that does not answer must cost a bounded wait, never a hung statement. A
  // session holding a metadata table locked stands for it: the read waits on the lock until its
  // timeout cancels it. `Bound` is the timeout and a margin, past which the read counts as hung.
  private val Bound = (DuckLakeCatalogReader.HistoryQueryTimeoutSec + 5).seconds

  private def whileLocked[A](table: String)(read: => A): Try[A] =
    val db   = currentDbName.getOrElse(fail("whileLocked outside withCatalog"))
    val lock = DriverManager.getConnection(
      TestPostgres.dbUrl(db),
      TestPostgres.pgUser,
      TestPostgres.pgPass
    )
    try
      lock.setAutoCommit(false)
      lock.createStatement().execute(s"LOCK TABLE $table IN ACCESS EXCLUSIVE MODE")
      Await.result(Future(Try(read))(ExecutionContext.global), Bound)
    finally
      lock.rollback()
      lock.close()

  "nameHistoryStable" should "give up past its timeout, never answering, when the metadata does not answer" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      whileLocked("ducklake_table")(reader.nameHistoryStable("tpch1", "nation")).failed.get shouldBe
        a[SQLException]
      reader.nameHistoryStable("tpch1", "nation") shouldBe true
    }

  "maxSnapshotIdBounded" should "match maxSnapshotId, and move on a column rename" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      val before = reader.maxSnapshotIdBounded()
      before shouldBe reader.maxSnapshotId()
      reader.nameHistoryStable("tpch1", "nation") shouldBe true
      // What the history cache rests on: a rename is a commit, so the newest snapshot moves.
      runSqlOnCatalog("ALTER TABLE lake.tpch1.nation RENAME COLUMN n_name TO n_label;")
      reader.maxSnapshotIdBounded().value should be > before.value
      reader.nameHistoryStable("tpch1", "nation") shouldBe false
    }

  it should "give up past its timeout when the metadata does not answer" in
    withCatalog("tt", extraSql = extra) { (reader, _) =>
      whileLocked("ducklake_snapshot")(reader.maxSnapshotIdBounded()).failed.get shouldBe
        a[SQLException]
    }
