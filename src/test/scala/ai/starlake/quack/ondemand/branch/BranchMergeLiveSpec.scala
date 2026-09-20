package ai.starlake.quack.ondemand.branch

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Live spec (local Postgres + duckdb CLI): change-set classification over real metadata and the
  * fast-forward merge batch of design section 4.5, end to end.
  */
class BranchMergeLiveSpec extends AnyFlatSpec with Matchers with BranchLiveFixture:

  private val extra =
    """CREATE TABLE lake.tpch1.nation (n_nationkey INTEGER NOT NULL, n_name VARCHAR NOT NULL);
      |INSERT INTO lake.tpch1.nation VALUES (0, 'FR'), (1, 'DE');
      |CREATE TABLE lake.tpch1.untouched (x INTEGER);
      |INSERT INTO lake.tpch1.untouched VALUES (1);
      |CALL ducklake_flush_inlined_data('lake');
      |""".stripMargin

  private val branchWrites =
    """-- update a pre-existing row (rowid kept), delete one, insert two
      |UPDATE qod_branch.tpch1.region SET r_name = 'ASIA-EDITED' WHERE r_regionkey = 2;
      |DELETE FROM qod_branch.tpch1.region WHERE r_regionkey = 0;
      |INSERT INTO qod_branch.tpch1.region VALUES (6, 'NEW-A', 'g'), (7, 'NEW-B', 'h');
      |-- insert then delete (must merge to nothing)
      |INSERT INTO qod_branch.tpch1.region VALUES (8, 'GHOST', 'i');
      |DELETE FROM qod_branch.tpch1.region WHERE r_regionkey = 8;
      |-- update then delete a pre-existing row (must merge to a delete)
      |UPDATE qod_branch.tpch1.region SET r_name = 'DOOMED' WHERE r_regionkey = 1;
      |DELETE FROM qod_branch.tpch1.region WHERE r_regionkey = 1;
      |-- insert then update (must merge to the post-image)
      |INSERT INTO qod_branch.tpch1.region VALUES (9, 'DRAFT', 'j');
      |UPDATE qod_branch.tpch1.region SET r_name = 'FINAL' WHERE r_regionkey = 9;
      |CALL ducklake_flush_inlined_data('qod_branch');
      |-- a created table and a dropped table
      |CREATE TABLE qod_branch.tpch1.extra AS SELECT 42 AS answer;
      |DROP TABLE qod_branch.tpch1.nation;
      |""".stripMargin

  "BranchChanges + BranchMergeSql" should
    "classify a real branch and fast-forward it onto main in one stamped snapshot" in
    withCatalog("brmerge", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(branchWrites, Some(branchDb), Some(branchDir))
        val mainBefore     = parent.maxSnapshotId().get
        val expectedRegion = duck(
          "SELECT r_regionkey, r_name FROM qod_branch.tpch1.region ORDER BY 1;",
          Some(branchDb),
          Some(branchDir)
        )
        expectedRegion shouldBe
          List("2,ASIA-EDITED", "3,EUROPE", "4,MIDDLE EAST", "6,NEW-A", "7,NEW-B", "9,FINAL")

        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.forkSnapshot shouldBe fork
          cs.headSnapshot shouldBe br.maxSnapshotId().get
          cs.conflicts shouldBe Nil
          cs.unsupported shouldBe Nil
          cs.tables.map(t => (t.schema, t.table, t.kind)) shouldBe List(
            ("tpch1", "extra", ChangeKind.Created),
            ("tpch1", "nation", ChangeKind.Dropped),
            ("tpch1", "region", ChangeKind.Modified)
          )
          cs.mergeable shouldBe true

          val message = BranchMergeSql.commitMessage("feature", "mg-test", "alice")
          val sql     = BranchMergeSql.batch(
            parentAlias = "lake",
            branchAlias = BranchMergeSql.BranchAlias,
            changes = cs.tables,
            fork = fork,
            head = cs.headSnapshot,
            author = BranchMergeSql.author("acme", "bob"),
            message = message
          )
          duck(sql, Some(branchDb), Some(branchDir))

          duck(
            "SELECT r_regionkey, r_name FROM lake.tpch1.region ORDER BY 1;"
          ) shouldBe expectedRegion
          duck("SELECT answer FROM lake.tpch1.extra;") shouldBe List("42")
          duck("SELECT x FROM lake.tpch1.untouched;") shouldBe List("1")
          duck(
            "SELECT count(*) FROM duckdb_tables() WHERE database_name='lake' AND schema_name='tpch1' AND table_name='nation';"
          ) shouldBe List("0")
          // Exactly one new snapshot on main, stamped with author and the unique message.
          parent.maxSnapshotId() shouldBe Some(mainBefore + 1)
          val located = duck(BranchMergeSql.findSnapshotSql("lake", message) + ";")
          located shouldBe List((mainBefore + 1).toString)
          duck(
            s"SELECT author, commit_message FROM lake.snapshots() WHERE snapshot_id = ${mainBefore + 1};"
          ) shouldBe List(s"tenant:acme/user:bob,$message")
        finally br.close()
      }
    }

  it should "report a conflict when main touched a branch table after the fork" in
    withCatalog("brconf", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          "INSERT INTO qod_branch.tpch1.region VALUES (6, 'NEW', 'g');",
          Some(branchDb),
          Some(branchDir)
        )
        duck("INSERT INTO lake.tpch1.region VALUES (7, 'MAIN', 'h');")
        // A flush on main is maintenance, never a touch: nation flushes but was not written to.
        duck("CALL ducklake_flush_inlined_data('lake');")
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.tables.map(_.table) shouldBe List("region")
          cs.conflicts.map(c => (c.table, c.reason)) shouldBe
            List(("region", "changed on main since the fork"))
          cs.mergeable shouldBe false
        finally br.close()
      }
    }

  it should "refuse an altered table and ignore main-side flushes of untouched tables" in
    withCatalog("bralter", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          """ALTER TABLE qod_branch.tpch1.region ADD COLUMN r_extra INTEGER;
            |INSERT INTO qod_branch.tpch1.nation VALUES (2, 'IT');""".stripMargin,
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.tables.map(t => (t.table, t.kind)) shouldBe
            List(("nation", ChangeKind.Modified), ("region", ChangeKind.Altered))
          cs.conflicts shouldBe Nil
          cs.mergeable shouldBe false
          cs.tables.find(_.table == "region").flatMap(_.reason).isDefined shouldBe true
        finally br.close()
      }
    }
