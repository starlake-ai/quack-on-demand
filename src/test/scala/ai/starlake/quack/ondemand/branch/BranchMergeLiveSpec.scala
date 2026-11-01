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
          val sql     = BranchMergeSql.batch(
            "lake",
            BranchMergeSql.BranchAlias,
            cs.tables,
            fork,
            cs.headSnapshot,
            BranchMergeSql.author("acme", "bob"),
            message
          )
          val out = duckAttempt(sql, Some(branchDb), Some(branchDir))
          out shouldBe a[Left[?, ?]]
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
          // The batch's own statements past its head guard, run against the moved branch: the
          // pinned reads still see the validated row.
          val body = cs.tables.flatMap(
            BranchMergeSql.tableStatements(
              "lake",
              BranchMergeSql.BranchAlias,
              _,
              fork,
              cs.headSnapshot
            )
          )
          duck(body.mkString("BEGIN;\n", ";\n", ";\nCOMMIT;"), Some(branchDb), Some(branchDir))
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
          cs.tables.map(t => (t.table, t.kind, t.shadowsRowid)) shouldBe
            List(("keyed", ChangeKind.Modified, true))
          cs.mergeable shouldBe false
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
        val sql     = BranchMergeSql.batch(
          parentAlias = "lake",
          branchAlias = BranchMergeSql.BranchAlias,
          changes = cs.tables,
          fork = fork,
          head = cs.headSnapshot,
          author = BranchMergeSql.author("acme", "bob"),
          message = message
        )
        PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot) shouldBe Right(())
        val out =
          try duckAttempt(sql, Some(branchDb), Some(branchDir))
          finally PostgresMergeFence.disarm(parentMeta, message)
        out shouldBe a[Left[?, ?]]
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
          val sql     = BranchMergeSql.batch(
            "lake",
            BranchMergeSql.BranchAlias,
            cs.tables,
            fork,
            cs.headSnapshot,
            BranchMergeSql.author("acme", "bob"),
            message
          )
          PostgresMergeFence.arm(parentMeta, message, cs.mainSnapshot) shouldBe Right(())
          try duck(sql, Some(branchDb), Some(branchDir))
          finally PostgresMergeFence.disarm(parentMeta, message)
          parent.snapshotByCommitMessage(message) shouldBe Some(cs.mainSnapshot + 1)
          duck("SELECT r_name FROM lake.tpch1.region WHERE r_regionkey = 2;") shouldBe
            List("BRANCH")
          // A second arm on the same catalog reuses the installed trigger.
          PostgresMergeFence.arm(parentMeta, "other", cs.mainSnapshot + 1) shouldBe Right(())
          PostgresMergeFence.disarm(parentMeta, "other")
        finally br.close()
      }
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
