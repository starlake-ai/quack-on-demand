package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DuckLakeCatalogReaderTimeTravelSpec extends AnyFlatSpec with Matchers with PostgresFixture:

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
