package ai.starlake.quack.ondemand.branch

import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Live spec (local Postgres + duckdb CLI): change-set classification over real metadata and the
  * row merge, end to end.
  */
class BranchMergeLiveSpec extends AnyFlatSpec with Matchers with ThreeWayMergeFixture:

  /** Clones the open catalog into `branchDb` and answers the snapshot the branch forked at. */
  private def forkAt(branchDb: String, branchDir: Path): Long =
    BranchCloner(parentMeta)
      .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
      .toOption
      .get
      .forkSnapshot

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

  "BranchChanges + the row merge" should
    "classify a real branch and land it on main in one stamped snapshot" in
    withCatalog("brmerge", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = cloneBranch(branchDb, branchDir)
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
          withNode(branchDb, branchDir)(runMerge(_, cs, br, parent, message)).isRight shouldBe true

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

  it should "refuse to commit when the branch moved after validation, leaving main unchanged" in
    withCatalog("brmoved", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          "UPDATE qod_branch.tpch1.region SET r_name = 'VALIDATED' WHERE r_regionkey = 2;",
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.mergeable shouldBe true
          // A branch write AFTER validation: the merge must not commit it, nor drop it silently.
          duck(
            "UPDATE qod_branch.tpch1.region SET r_name = 'LATER' WHERE r_regionkey = 2;",
            Some(branchDb),
            Some(branchDir)
          )
          val message = BranchMergeSql.commitMessage("feature", "mg-moved", "alice")
          val out     = withNode(branchDb, branchDir)(runMerge(_, cs, br, parent, message))
          out.left.toOption.get should include("branch moved past snapshot")
          parent.snapshotByCommitMessage(message) shouldBe None
          duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe
            List("ASIA")
        finally br.close()
      }
    }

  it should "read the branch at the validated head, whatever it holds now" in
    withCatalog("brpinned", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          "UPDATE qod_branch.tpch1.region SET r_name = 'VALIDATED' WHERE r_regionkey = 2;",
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          duck(
            "UPDATE qod_branch.tpch1.region SET r_name = 'LATER' WHERE r_regionkey = 2;",
            Some(branchDb),
            Some(branchDir)
          )
          // Apply's own statements past its head guard, run against the moved branch: the pinned
          // reads still see the validated row.
          val tables = planned(cs, br, parent)
          val runner = new RowMergeRunner("lake", BranchMergeSql.BranchAlias, fork, None)
          withNode(branchDb, branchDir) { node =>
            runner.prepare(node, tables).unsafeRunSync() shouldBe Right(())
            val counts = runner.decide(node, tables).unsafeRunSync().toOption.get
            val body   = tables
              .zip(counts)
              .flatMap((t, c) => RowMergeSql.applyStatements("lake", t, c, false, 1000000L))
            node.exec(("BEGIN" :: body) :+ "COMMIT").unsafeRunSync() shouldBe Right(())
          }
          duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe
            List("VALIDATED")
        finally br.close()
      }
    }

  it should "mark a table with its own rowid column not mergeable" in
    withCatalog(
      "brrowid",
      """CREATE TABLE lake.tpch1.keyed (rowid BIGINT, v VARCHAR);
        |INSERT INTO lake.tpch1.keyed VALUES (100, 'a'), (200, 'b');
        |CALL ducklake_flush_inlined_data('lake');
        |""".stripMargin
    ) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          "UPDATE qod_branch.tpch1.keyed SET v = 'B' WHERE v = 'b';",
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.tables.map(t => (t.table, t.kind, t.reservedColumn)) shouldBe
            List(("keyed", ChangeKind.Modified, Some("rowid")))
          cs.mergeable shouldBe false
        finally br.close()
      }
    }

  it should "mark a table main also changed as three-way, ignoring a flush on main" in
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
          cs.tables.map(t => (t.table, t.threeWay)) shouldBe List(("region", true))
          cs.conflicts shouldBe Nil
          cs.mergeable shouldBe true
        finally br.close()
      }
    }

  it should "report a conflict when main renamed a table the branch changed" in
    withCatalog("brconf3", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          "INSERT INTO qod_branch.tpch1.nation VALUES (2, 'IT');",
          Some(branchDb),
          Some(branchDir)
        )
        duck("ALTER TABLE lake.tpch1.nation RENAME TO nation2;")
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.conflicts.map(c => (c.table, c.reason)) shouldBe
            List(("nation", "schema changed on main since the fork"))
        finally br.close()
      }
    }

  it should "report a conflict when main dropped a table the branch changed" in
    withCatalog("brconf2", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          "INSERT INTO qod_branch.tpch1.nation VALUES (2, 'IT');",
          Some(branchDb),
          Some(branchDir)
        )
        duck("DROP TABLE lake.tpch1.nation;")
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.conflicts.map(c => (c.table, c.reason)) shouldBe
            List(("nation", "dropped on main since the fork"))
          cs.mergeable shouldBe false
        finally br.close()
      }
    }

  it should "report a conflict when main dropped a column and added it again under the same name" in
    withCatalog("brcolid", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = forkAt(branchDb, branchDir)
        duck(
          "UPDATE qod_branch.tpch1.region SET r_name = 'ASIA-EDITED' WHERE r_regionkey = 2;",
          Some(branchDb),
          Some(branchDir)
        )
        duck(
          """ALTER TABLE lake.tpch1.region DROP COLUMN r_comment;
            |ALTER TABLE lake.tpch1.region ADD COLUMN r_comment VARCHAR;""".stripMargin
        )
        // Same name, type and nullability, yet every value of the column is gone on main.
        duck("SELECT count(r_comment) FROM lake.tpch1.region;") shouldBe List("0")
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.conflicts.map(c => (c.table, c.reason)) shouldBe
            List(("region", "schema changed on main since the fork"))
          cs.mergeable shouldBe false
        finally br.close()
      }
    }

  it should "not take fields nested in a STRUCT for columns named rowid or snapshot_id" in
    withCatalog(
      "brnested",
      """CREATE TABLE lake.tpch1.nested (id INTEGER, s STRUCT(rowid INTEGER, snapshot_id VARCHAR));
        |INSERT INTO lake.tpch1.nested VALUES (1, {'rowid': 7, 'snapshot_id': 'x'});
        |CALL ducklake_flush_inlined_data('lake');
        |""".stripMargin
    ) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = forkAt(branchDb, branchDir)
        duck(
          "UPDATE qod_branch.tpch1.nested SET s = {'rowid': 8, 'snapshot_id': 'y'} WHERE id = 1;",
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.tables.map(t => (t.table, t.kind, t.reservedColumn)) shouldBe
            List(("nested", ChangeKind.Modified, None))
          cs.mergeable shouldBe true
        finally br.close()
      }
    }

  it should "report a conflict when main dropped the schema a branch table was created in" in
    withCatalog("brschema", extra + "CREATE SCHEMA lake.s2;\n") { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = forkAt(branchDb, branchDir)
        duck("CREATE TABLE qod_branch.s2.t AS SELECT 1 AS x;", Some(branchDb), Some(branchDir))
        duck("DROP SCHEMA lake.s2 CASCADE;")
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.tables.map(t => (t.schema, t.table, t.kind)) shouldBe
            List(("s2", "t", ChangeKind.Created))
          cs.conflicts.map(c => (c.schema, c.table, c.reason)) shouldBe
            List(("s2", "t", "schema 's2' was dropped on main"))
          cs.mergeable shouldBe false
        finally br.close()
      }
    }

  it should "count rows the branch wrote under a temporary name of the table" in
    withCatalog("brtmpname", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = forkAt(branchDb, branchDir)
        duck(
          """ALTER TABLE qod_branch.tpch1.nation RENAME TO nation_tmp;
            |INSERT INTO qod_branch.tpch1.nation_tmp VALUES (2, 'IT');
            |ALTER TABLE qod_branch.tpch1.nation_tmp RENAME TO nation;""".stripMargin,
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.tables.map(t => (t.table, t.kind)) shouldBe List(("nation", ChangeKind.Modified))
          cs.conflicts shouldBe Nil
        finally br.close()
      }
    }

  it should "mark a table renamed on the branch as altered, not as created" in
    withCatalog("brrename", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = forkAt(branchDb, branchDir)
        duck(
          "ALTER TABLE qod_branch.tpch1.nation RENAME TO nation2;",
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          cs.tables.map(t => (t.table, t.kind)) shouldBe List(("nation2", ChangeKind.Altered))
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

  // ---- main writes landing between validation and the merge commit -------------------
  //
  // The merge validates the change set against main snapshot S, then spawns a node and runs the
  // batch. A main write committed in between must refuse the merge, never be overwritten. DuckLake's
  // own commit-time check cannot be relied on: it is blind to a write that lands before the merge
  // transaction's first read, and to any concurrent insert into a table the merge DROPs.

  private def raceCase(
      parent: ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader,
      branchSql: String,
      mainSql: String,
      survivor: (String, List[String])
  ) =
    withBranchDb { (branchDb, branchDir) =>
      val fork = BranchCloner(parentMeta)
        .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
        .toOption
        .get
        .forkSnapshot
      duck(branchSql, Some(branchDb), Some(branchDir))
      val br = branchReader(branchDb)
      try
        val cs = BranchChanges.compute(br, parent, fork)
        cs.mergeable shouldBe true
        // The racing main write, AFTER validation.
        duck(mainSql)
        val message = BranchMergeSql.commitMessage("feature", "mg-race", "alice")
        PostgresMergeFence.arm(
          parentMeta,
          message,
          cs.mainSnapshot,
          cs.tables.flatMap(_.tableId).toSet,
          None
        ) shouldBe Right(())
        val out =
          try withNode(branchDb, branchDir)(runMerge(_, cs, br, parent, message))
          finally PostgresMergeFence.disarm(parentMeta, message)
        out.left.toOption.get should include("qod merge fence:")
        parent.snapshotByCommitMessage(message) shouldBe None
        duck(survivor._1) shouldBe survivor._2
      finally br.close()
    }

  it should "refuse to overwrite a main update of a row the branch also updated" in
    withCatalog("brrace1", extra) { (parent, _) =>
      raceCase(
        parent,
        "UPDATE qod_branch.tpch1.region SET r_name = 'BRANCH' WHERE r_regionkey = 2;",
        "UPDATE lake.tpch1.region SET r_name = 'MAIN' WHERE r_regionkey = 2;",
        "SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;" -> List("MAIN")
      )
    }

  it should "refuse to drop a table main inserted into after validation" in
    withCatalog("brrace2", extra) { (parent, _) =>
      raceCase(
        parent,
        "DROP TABLE qod_branch.tpch1.nation;",
        "INSERT INTO lake.tpch1.nation VALUES (2, 'IT');",
        "SELECT n_name FROM lake.tpch1.nation ORDER BY 1;" -> List("DE", "FR", "IT")
      )
    }

  it should "let a merge commit when main has not moved since validation" in
    withCatalog("brrace3", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .toOption
          .get
          .forkSnapshot
        duck(
          "UPDATE qod_branch.tpch1.region SET r_name = 'BRANCH' WHERE r_regionkey = 2;",
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs      = BranchChanges.compute(br, parent, fork)
          val message = BranchMergeSql.commitMessage("feature", "mg-clean", "alice")
          PostgresMergeFence.arm(
            parentMeta,
            message,
            cs.mainSnapshot,
            cs.tables.flatMap(_.tableId).toSet,
            None
          ) shouldBe Right(())
          try
            withNode(branchDb, branchDir)(
              runMerge(_, cs, br, parent, message)
            ).isRight shouldBe true
          finally PostgresMergeFence.disarm(parentMeta, message)
          parent.snapshotByCommitMessage(message) shouldBe Some(cs.mainSnapshot + 1)
          duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe
            List("BRANCH")
          // A second arm on the same catalog reuses the installed trigger.
          PostgresMergeFence.arm(
            parentMeta,
            "other",
            cs.mainSnapshot + 1,
            Set.empty,
            None
          ) shouldBe Right(())
          PostgresMergeFence.disarm(parentMeta, "other")
        finally br.close()
      }
    }

  // ---- the fence is table-scoped, and the hold keeps writers out ------------------------------

  /** A branch with one committed change on `region`, its change set, and the message to commit by.
    * `body` gets the Apply batch ready to send.
    */
  private def fencedRegionMerge[A](
      parent: ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
  )(
      body: (BranchChangeSet, String, () => Either[String, Unit], String, java.nio.file.Path) => A
  ): A =
    withBranchDb { (branchDb, branchDir) =>
      val fork = cloneBranch(branchDb, branchDir)
      duck(
        "UPDATE qod_branch.tpch1.region SET r_name = 'BRANCH' WHERE r_regionkey = 2;",
        Some(branchDb),
        Some(branchDir)
      )
      val br = branchReader(branchDb)
      try
        val cs      = BranchChanges.compute(br, parent, fork)
        val message = BranchMergeSql.commitMessage("feature", "mg-fence", "alice")
        val send    = () =>
          withNode(branchDb, branchDir)(node =>
            runMerge(node, cs, br, parent, message).map(_ => ())
          )
        body(cs, message, send, branchDb, branchDir)
      finally br.close()
    }

  private def ids(cs: BranchChangeSet) = cs.tables.flatMap(_.tableId).toSet

  /** A plain connection to the open catalog's Postgres database. */
  private def onCatalog[A](body: java.sql.Connection => A): A =
    val c = java.sql.DriverManager.getConnection(
      ai.starlake.quack.ondemand.state.testkit.TestPostgres.dbUrl(parentMeta("dbName")),
      ai.starlake.quack.ondemand.state.testkit.TestPostgres.pgUser,
      ai.starlake.quack.ondemand.state.testkit.TestPostgres.pgPass
    )
    try body(c)
    finally c.close()

  "the merge fence" should "let a commit on another table through" in
    withCatalog("fnother", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, send, _, _) =>
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot, ids(cs), None) shouldBe Right(
          ()
        )
        try
          duck("INSERT INTO lake.tpch1.nation VALUES (2, 'IT');")
          send() shouldBe Right(())
        finally PostgresMergeFence.disarm(parentMeta, message)
        duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe List("BRANCH")
        duck("SELECT n_name FROM lake.tpch1.nation ORDER BY 1;") shouldBe List("DE", "FR", "IT")
      }
    }

  "a fenced merge's open transaction" should "retry over another table's commit and land after it" in
    withCatalog("fnretried", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, _, branchDb, branchDir) =>
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot, ids(cs), None) shouldBe
          Right(())
        val br = branchReader(branchDb)
        try
          // At Apply's COMMIT its transaction has written, so it is pinned at main's head S and its
          // first try needs S + 1. This commit takes that id, so landing at all takes a retry.
          withNode(
            branchDb,
            branchDir,
            beforeCommit = () => duck("INSERT INTO lake.tpch1.nation VALUES (2, 'IT');"): Unit
          )(node => runMerge(node, cs, br, parent, message)) shouldBe a[Right[?, ?]]
        finally
          PostgresMergeFence.disarm(parentMeta, message)
          br.close()
        val other = cs.mainSnapshot + 1
        parent.snapshotByCommitMessage(message) shouldBe Some(other + 1)
        parent.snapshotChangesSince(cs.mainSnapshot).map(_._1) shouldBe List(other, other + 1)
        duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe List("BRANCH")
        duck("SELECT n_name FROM lake.tpch1.nation ORDER BY 1;") shouldBe List("DE", "FR", "IT")
      }
    }

  "the merge fence" should "refuse a merged table that moved at once, and the next attempt merges" in
    withCatalog("fnfast", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, send, branchDb, branchDir) =>
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot, ids(cs), None) shouldBe Right(
          ()
        )
        try
          duck("INSERT INTO lake.tpch1.region VALUES (7, 'MAIN', 'h');")
          val out = send()
          out.left.toOption.get should include("qod merge fence:")
          MergeDecision.mainMoved(out.left.toOption.get) shouldBe true
          // DuckLake's retries take about 4 s and end with this text. If it shows, the failed query's
          // text holds a retry word: lower ducklake_max_retry_count in the Apply batch.
          out.left.toOption.get should not include "Exceeded the maximum retry count"
        finally PostgresMergeFence.disarm(parentMeta, message)
        parent.snapshotByCommitMessage(message) shouldBe None

        // The next attempt reads main again and absorbs its commit: refused, never lost.
        val br = branchReader(branchDb)
        try
          val next = BranchChanges.compute(br, parent, cs.forkSnapshot)
          PostgresMergeFence.arm(
            parentMeta,
            message,
            next.mainSnapshot,
            ids(next),
            None
          ) shouldBe Right(())
          try
            withNode(branchDb, branchDir)(node =>
              runMerge(node, next, br, parent, message)
            ).isRight shouldBe true
          finally PostgresMergeFence.disarm(parentMeta, message)
        finally br.close()
        duck(
          "SELECT r_name FROM lake.tpch1.region WHERE r_regionkey IN (2, 7) ORDER BY r_regionkey;"
        ) shouldBe List("BRANCH", "MAIN")
      }
    }

  it should "refuse on a schema change anywhere" in
    withCatalog("fnddl", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, send, _, _) =>
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot, ids(cs), None) shouldBe Right(
          ()
        )
        try
          duck("CREATE TABLE lake.tpch1.brandnew (x INTEGER);")
          send().left.toOption.get should include("qod merge fence:")
        finally PostgresMergeFence.disarm(parentMeta, message)
      }
    }

  it should "refuse a snapshot with no row of changes: nothing shows it harmless" in
    withCatalog("fnnorow", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, send, _, _) =>
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot, ids(cs), None) shouldBe
          Right(())
        try
          duck("INSERT INTO lake.tpch1.nation VALUES (2, 'IT');")
          onCatalog(
            _.createStatement().execute(
              "DELETE FROM ducklake_snapshot_changes " +
                s"WHERE snapshot_id = ${parent.maxSnapshotId().get}"
            )
          )
          send().left.toOption.get should include("qod merge fence:")
        finally PostgresMergeFence.disarm(parentMeta, message)
      }
    }

  "arming the same message again" should "replace the row's tables and its hold" in
    withCatalog("fnrearm", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, send, _, _) =>
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot, Set(999L), Some(120)) shouldBe
          Right(())
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot, ids(cs), None) shouldBe
          Right(())
        try
          // No hold left, or this writer would wait and fail. The merged tables are the new ones, or
          // this commit would read as harmless and the merge would build on it.
          duck("INSERT INTO lake.tpch1.region VALUES (7, 'MAIN', 'h');")
          send().left.toOption.get should include("qod merge fence:")
        finally PostgresMergeFence.disarm(parentMeta, message)
      }
    }

  "the hold's rule" should "name whole table ids, in any entry of a commit" in
    withCatalog("hdrule", extra) { (parent, _) =>
      PostgresMergeFence.arm(
        parentMeta,
        "probe",
        parent.maxSnapshotId().get,
        Set(2L),
        Some(120)
      ) shouldBe
        Right(())
      // The trigger on its own: the row never lands.
      def held(changes: String): Boolean =
        onCatalog { c =>
          c.setAutoCommit(false)
          val ps = c.prepareStatement(
            "INSERT INTO ducklake_snapshot_changes (snapshot_id, changes_made) VALUES (999999, ?)"
          )
          ps.setString(1, changes)
          try
            ps.executeUpdate()
            false
          catch case e: java.sql.SQLException if e.getMessage.contains("qod merge hold:") => true
          finally c.rollback()
        }
      held("inlined_insert:2") shouldBe true
      held("inlined_insert:5,inlined_delete:2") shouldBe true
      held("dropped_table:2,created_table:\"s\".\"t\"") shouldBe true
      held("inlined_insert:23") shouldBe false
      held("inlined_insert:12") shouldBe false
      held("inlined_insert:5") shouldBe false
      PostgresMergeFence.disarm(parentMeta, "probe")
      held("inlined_insert:2") shouldBe false
    }

  "the fence's rule" should "admit only row data or maintenance on other tables" in
    withCatalog("fnrule", extra) { (parent, _) =>
      PostgresMergeFence.arm(
        parentMeta,
        "probe",
        parent.maxSnapshotId().get,
        Set(2L),
        None
      ) shouldBe Right(())
      def harmless(changes: String): Boolean =
        onCatalog { c =>
          val ps = c.prepareStatement("SELECT qod_merge_fence.harmless(?, ARRAY[2]::bigint[])")
          ps.setString(1, changes)
          val rs = ps.executeQuery()
          rs.next()
          rs.getBoolean(1)
        }
      harmless("inserted_into_table:3,rewrite_delete:3") shouldBe true
      harmless("inserted_into_table:2") shouldBe false
      harmless("merge_adjacent:2") shouldBe false
      harmless("""created_table:"s"."t"""") shouldBe false
      harmless("brand_new_verb:3") shouldBe false
      harmless("") shouldBe false
      harmless(null) shouldBe false
    }

  "a failed commit's text" should "hold no inlined value once it is cut for storing" in
    withCatalog("fnvalue", extra) { (parent, _) =>
      val sentinel = "SENTINEL-CELL-7731"
      withBranchDb { (branchDb, branchDir) =>
        val fork = cloneBranch(branchDb, branchDir)
        duck(
          s"UPDATE qod_branch.tpch1.region SET r_name = '$sentinel' WHERE r_regionkey = 2;",
          Some(branchDb),
          Some(branchDir)
        )
        val br = branchReader(branchDb)
        try
          val cs      = BranchChanges.compute(br, parent, fork)
          val message = BranchMergeSql.commitMessage("feature", "mg-value", "alice")
          PostgresMergeFence.arm(
            parentMeta,
            message,
            cs.mainSnapshot,
            ids(cs),
            None
          ) shouldBe Right(())
          try
            duck("INSERT INTO lake.tpch1.region VALUES (7, 'MAIN', 'h');")
            val raw = withNode(branchDb, branchDir)(node =>
              runMerge(node, cs, br, parent, message)
            ).left.toOption.get
            info(s"DuckLake's own text quotes the inlined value: ${raw.contains(sentinel)}")
            MergeDecision.failureText(raw) should not include sentinel
            // The cut keeps the line after DuckLake's wrapper: a pin bump that rewords the wrapper
            // would lose it. That line does not always hold the cause: a failure in the metadata
            // batch, as here, ends at `Failed to execute query`, before the quoted SQL.
            MergeDecision.failureText(raw) should not endWith "transaction."
          finally PostgresMergeFence.disarm(parentMeta, message)
        finally br.close()
      }
    }

  "the hold" should "make a writer wait, then commit" in
    withCatalog("hdwait", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, _, _, _) =>
        // Expiry stands in for a release: both end the hold the same way for the writer.
        PostgresMergeFence.arm(
          parentMeta,
          message,
          cs.mainSnapshot,
          ids(cs),
          Some(3)
        ) shouldBe Right(())
        try
          val started = System.nanoTime()
          duck("INSERT INTO lake.tpch1.region VALUES (7, 'WRITER', 'h');")
          ((System.nanoTime() - started) / 1e9) should be > 2.0
        finally PostgresMergeFence.disarm(parentMeta, message)
        duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 7;") shouldBe List("WRITER")
      }
    }

  it should "fail a writer past DuckLake's retries, saying the table is being merged" in
    withCatalog("hdfail", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, _, _, _) =>
        PostgresMergeFence.arm(
          parentMeta,
          message,
          cs.mainSnapshot,
          ids(cs),
          Some(120)
        ) shouldBe Right(())
        try
          val out = duckAttempt("INSERT INTO lake.tpch1.region VALUES (7, 'WRITER', 'h');")
          out.left.toOption.get should include("table is being merged")
        finally PostgresMergeFence.disarm(parentMeta, message)
      }
    }

  it should "leave other tables' writers alone" in
    withCatalog("hdother", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, _, _, _) =>
        PostgresMergeFence.arm(
          parentMeta,
          message,
          cs.mainSnapshot,
          ids(cs),
          Some(120)
        ) shouldBe Right(())
        // A held writer would run out of DuckLake's retries, and `duck` fails on its error.
        try duck("INSERT INTO lake.tpch1.nation VALUES (2, 'IT');")
        finally PostgresMergeFence.disarm(parentMeta, message)
      }
    }

  it should "let the merge itself through, and fail a writer already in flight once" in
    withCatalog("hdflight", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, send, _, _) =>
        given ExecutionContext = ExecutionContext.global
        PostgresMergeFence.arm(
          parentMeta,
          message,
          cs.mainSnapshot,
          ids(cs),
          Some(120)
        ) shouldBe Right(())
        try
          // The writer's transaction is open when the merge commits, and commits after it.
          val writer = Future(
            duckAttempt(
              """BEGIN;
                |INSERT INTO lake.tpch1.region VALUES (7, 'WRITER', 'h');
                |SELECT sleep_ms(15000);
                |COMMIT;""".stripMargin
            )
          )
          Thread.sleep(2000)
          send() shouldBe Right(())
          PostgresMergeFence.disarm(parentMeta, message)
          Await.result(writer, 60.seconds).left.toOption.get should include("Transaction conflict")
        finally PostgresMergeFence.disarm(parentMeta, message)
        // One that starts after the merge is unaffected.
        duck("INSERT INTO lake.tpch1.region VALUES (8, 'LATER', 'h');")
        duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe List("BRANCH")
        duck("SELECT count(*) FROM lake.tpch1.region WHERE r_regionkey = 7;") shouldBe List("0")
      }
    }

  it should "end by itself after a crash, with nobody disarming it" in
    withCatalog("hdcrash", extra) { (parent, _) =>
      fencedRegionMerge(parent) { (cs, message, _, _, _) =>
        PostgresMergeFence.arm(
          parentMeta,
          message,
          cs.mainSnapshot,
          ids(cs),
          Some(2)
        ) shouldBe Right(())
        Thread.sleep(2500)
        // A held writer would run out of DuckLake's retries, and `duck` fails on its error.
        duck("INSERT INTO lake.tpch1.region VALUES (7, 'WRITER', 'h');")
      }
    }

  /** A duckdb CLI committing single-row inserts on `region`, each with `r_name = 'W'`, one after
    * another, until the answer stops it or its statements run out, about 20 s at full speed. A
    * statement the hold fails does not end the script. Stopping twice is harmless.
    */
  private def hotWriter(): () => Unit =
    val m      = parentMeta
    val dir    = currentDataPath.get
    val script = new StringBuilder(
      s"""INSTALL ducklake;
         |LOAD ducklake;
         |INSTALL postgres;
         |LOAD postgres;
         |ATTACH 'ducklake:postgres:host=${m("pgHost")} port=${m("pgPort")} dbname=${m(
          "dbName"
        )} user=${m(
          "pgUser"
        )} password=${m("pgPassword")}' AS lake (DATA_PATH '$dir/');
         |""".stripMargin
    )
    (1000 until 7000).foreach(k =>
      script.append(s"INSERT INTO lake.tpch1.region VALUES ($k, 'W', 'h');\n")
    )
    val tmp = Files.createTempFile("hot-writer", ".sql")
    Files.writeString(tmp, script.toString)
    val writer = new java.lang.ProcessBuilder("duckdb", "-csv", "-noheader")
      .redirectInput(tmp.toFile)
      .redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
      .redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
      .start()
    () =>
      writer.destroyForcibly()
      writer.waitFor()
      Files.deleteIfExists(tmp): Unit

  /** Whether main's head reaches `snapshot` within 15 s. */
  private def headReaches(
      parent: ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader,
      snapshot: Long
  ): Boolean =
    val deadline = System.nanoTime() + 15.seconds.toNanos
    while parent.maxSnapshotId().exists(_ < snapshot) && System.nanoTime() < deadline do
      Thread.sleep(20)
    parent.maxSnapshotId().exists(_ >= snapshot)

  it should "land a merge on a hot table within two attempts, and lose no write" in
    withCatalog("hdhot", extra) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = cloneBranch(branchDb, branchDir)
        duck(
          "UPDATE qod_branch.tpch1.region SET r_name = 'BRANCH' WHERE r_regionkey = 2;",
          Some(branchDb),
          Some(branchDir)
        )
        val br    = branchReader(branchDb)
        val first = BranchChanges.compute(br, parent, fork)
        val stop  = hotWriter()
        try
          withClue("the writer never got going: ") {
            headReaches(parent, first.mainSnapshot + 50) shouldBe true
          }
          val message = BranchMergeSql.commitMessage("feature", "mg-hot", "alice")
          // The hold goes up before main's head is read, and what is already past it gets to land.
          PostgresMergeFence.arm(
            parentMeta,
            message,
            parent.maxSnapshotId().get,
            ids(first),
            Some(120)
          ) shouldBe Right(())
          Thread.sleep(200)
          var landed   = false
          var attempts = 0
          var failures = List.empty[String]
          try
            while !landed && attempts < 2 do
              attempts += 1
              val cs = BranchChanges.compute(br, parent, fork)
              PostgresMergeFence.arm(
                parentMeta,
                message,
                cs.mainSnapshot,
                ids(cs),
                Some(120)
              ) shouldBe Right(())
              withNode(branchDb, branchDir)(runMerge(_, cs, br, parent, message)) match
                case Right(_)  => landed = true
                case Left(err) => failures = failures :+ err
          finally PostgresMergeFence.disarm(parentMeta, message)
          withClue(s"failed attempts: $failures: ")(landed shouldBe true)

          // With the hold gone the writer goes back to it, and what it commits now counts too.
          val merged = parent.snapshotByCommitMessage(message).get
          withClue("the writer did not come back: ") {
            headReaches(parent, merged + 20) shouldBe true
          }
          stop()
          Thread.sleep(500) // what the kill left in flight lands or rolls back

          val commits = onCatalog { c =>
            val ps = c.prepareStatement(
              "SELECT count(*) FROM ducklake_snapshot_changes " +
                "WHERE snapshot_id > ? AND changes_made = ?"
            )
            ps.setLong(1, fork)
            ps.setString(2, s"inlined_insert:${ids(first).head}")
            val rs = ps.executeQuery()
            rs.next()
            rs.getLong(1)
          }
          commits should be > 0L
          // Every commit on record still has its row on main, and only one.
          duck(
            "SELECT count(*), count(DISTINCT r_regionkey) FROM lake.tpch1.region WHERE r_name = 'W';"
          ) shouldBe List(s"$commits,$commits")
          duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe
            List("BRANCH")
        finally
          stop()
          br.close()
      }
    }

  // ---- arming takes no lock that stalls the catalog's commits -----------------------------------

  /** `body` while an open transaction of another session holds what `lockSql` takes. */
  private def whileLocked[A](lockSql: String)(body: => A): A =
    onCatalog { holder =>
      holder.setAutoCommit(false)
      holder.createStatement().execute(lockSql)
      try body
      finally holder.rollback()
    }

  /** An arm on a thread of its own: one that waits on the lock fails the test here, and goes on
    * once the lock's holder lets go.
    */
  private def armAside(message: String, base: Long): Either[String, Unit] =
    given ExecutionContext = ExecutionContext.global
    Await.result(
      Future(PostgresMergeFence.arm(parentMeta, message, base, Set(2L), None)),
      20.seconds
    )

  // What a commit's trigger holds on the fence table. An ALTER cannot run beside it.
  private val FenceRead = "LOCK TABLE qod_merge_fence.fence IN ACCESS SHARE MODE"

  "arming a catalog whose fence table is current" should "run no ALTER, so no reader holds it up" in
    withCatalog("fnnoalter", extra) { (parent, _) =>
      val head = parent.maxSnapshotId().get
      PostgresMergeFence.arm(parentMeta, "first", head, Set(2L), None) shouldBe Right(())
      whileLocked(FenceRead)(armAside("second", head) shouldBe Right(()))
    }

  "arming a catalog missing a fence column" should "answer Left when a lock holds up the ALTER" in
    withCatalog("fnalter", extra) { (parent, _) =>
      val head = parent.maxSnapshotId().get
      PostgresMergeFence.arm(parentMeta, "first", head, Set(2L), None) shouldBe Right(())
      // The fence table as an earlier build left it.
      onCatalog(
        _.createStatement().execute("ALTER TABLE qod_merge_fence.fence DROP COLUMN hold_until")
      )
      whileLocked(FenceRead)(
        armAside("second", head).left.toOption.get should include("lock timeout")
      )
      // Once free, the next arm adds the column: it writes the hold there.
      PostgresMergeFence.arm(parentMeta, "third", head, Set(2L), Some(60)) shouldBe Right(())
    }

  "arming a catalog with no trigger yet" should "answer Left when a commit holds up creating it" in
    withCatalog("fntrigger", extra) { (parent, _) =>
      val head = parent.maxSnapshotId().get
      // What a commit holds on the table, until it ends. CREATE TRIGGER cannot run beside it.
      whileLocked("LOCK TABLE ducklake_snapshot_changes IN ROW EXCLUSIVE MODE")(
        armAside("first", head).left.toOption.get should include("lock timeout")
      )
      PostgresMergeFence.arm(parentMeta, "second", head, Set(2L), None) shouldBe Right(())
    }

  // ---- the catalog fixture --------------------------------------------------------------------

  "the catalog fixture" should "drop its database even when a session stays open on it" in {
    val pg              = ai.starlake.quack.ondemand.state.testkit.TestPostgres
    val (name, session) = withCatalog("fxforce") { (_, _) =>
      val db = currentDbName.get
      (db, java.sql.DriverManager.getConnection(pg.dbUrl(db), pg.pgUser, pg.pgPass))
    }
    try
      val admin = java.sql.DriverManager.getConnection(pg.adminUrl, pg.pgUser, pg.pgPass)
      try
        val ps = admin.prepareStatement("SELECT count(*) FROM pg_database WHERE datname = ?")
        ps.setString(1, name)
        val rs = ps.executeQuery()
        rs.next()
        rs.getInt(1) shouldBe 0
      finally admin.close()
    finally
      session.close()
      pg.dropDatabase(name)
  }

  // ---- TableCommitFence: a restore's replace held to the table state it validated ----

  private def restoreBatch(message: String, to: Long): String =
    List(
      "BEGIN",
      s"CALL ducklake_set_commit_message('lake', 'tenant:acme/user:bob', '$message')",
      s"CREATE OR REPLACE TABLE lake.tpch1.region AS SELECT * FROM lake.tpch1.region AT (VERSION => $to)",
      "COMMIT"
    ).mkString("", ";\n", ";")

  "TableCommitFence" should "refuse a replace when a row landed in its table after validation" in
    withCatalog("tfence1", extra) { (parent, _) =>
      val (tableId, _) = parent.currentTableInfo("tpch1", "region").get
      val to           = parent.maxSnapshotId().get
      duck("INSERT INTO lake.tpch1.region VALUES (5, 'BEFORE', 'x');")
      val base = parent.latestTableSnapshot("tpch1", "region").get
      // Validated at `base`; another client then commits a row before the replace commits.
      val message = "restore tpch1.region [live-1]"
      PostgresMergeFence.arm(parentMeta, message, base, tableId) shouldBe Right(())
      try
        duck("INSERT INTO lake.tpch1.region VALUES (6, 'CONCURRENT', 'y');")
        val out = duckAttempt(restoreBatch(message, to))
        out shouldBe a[Left[?, ?]]
        out.left.toOption.get should include("conflict")
      finally PostgresMergeFence.disarm(parentMeta, message)
      parent.snapshotByCommitMessage(message) shouldBe None
      duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 6;") shouldBe
        List("CONCURRENT")
    }

  it should "let the replace commit when only other tables moved" in
    withCatalog("tfence2", extra) { (parent, _) =>
      val (tableId, _) = parent.currentTableInfo("tpch1", "region").get
      val to           = parent.maxSnapshotId().get
      duck("INSERT INTO lake.tpch1.region VALUES (5, 'UNDONE', 'x');")
      val base    = parent.latestTableSnapshot("tpch1", "region").get
      val message = "restore tpch1.region [live-2]"
      PostgresMergeFence.arm(parentMeta, message, base, tableId) shouldBe Right(())
      try
        duck("INSERT INTO lake.tpch1.nation VALUES (2, 'IT');")
        duck(restoreBatch(message, to))
      finally PostgresMergeFence.disarm(parentMeta, message)
      parent.snapshotByCommitMessage(message).isDefined shouldBe true
      duck("SELECT count(*) FROM lake.tpch1.region WHERE r_regionkey = 5;") shouldBe List("0")
      duck("SELECT n_name FROM lake.tpch1.nation ORDER BY 1;") shouldBe List("DE", "FR", "IT")
    }
