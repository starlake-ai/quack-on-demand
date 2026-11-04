package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

/** Every merge here is checked against an independent full three-way scan (`ScanOracle`), on real
  * catalogs through every stage. These histories were all run by hand against the prototype: they
  * are the regressions of design section 3.
  */
class ThreeWayMergeLiveSpec extends AnyFlatSpec with Matchers with ThreeWayMergeFixture:

  private val Cols = List("id", "status", "qty", "note")

  private val seed =
    """CREATE TABLE lake.tpch1.fact (id BIGINT, status VARCHAR, qty INTEGER, note VARCHAR);
      |INSERT INTO lake.tpch1.fact SELECT i, 'NEW', (i % 100)::INTEGER, 'n' || i FROM range(100000) r(i);
      |""".stripMargin

  private def ins(catalog: String, note: String, from: Long, to: Long) =
    s"INSERT INTO $catalog.tpch1.fact SELECT i, 'NEW', 1, '$note' FROM range($from, $to) r(i);"

  /** Inlined rows, repeated updates, a mixed transaction, a no-op update, overlapping updates of
    * different columns: everything that merges cleanly, no conflict.
    */
  private val cleanMain = List(
    "UPDATE lake.tpch1.fact SET status = 'M1' WHERE id = 10;",
    "UPDATE lake.tpch1.fact SET status = 'M2' WHERE id = 10;",
    "UPDATE lake.tpch1.fact SET qty = qty + 1 WHERE id = 20;",
    "UPDATE lake.tpch1.fact SET note = 'same' WHERE id = 30;",
    "DELETE FROM lake.tpch1.fact WHERE id = 42;",
    ins("lake", "mi", 2000000L, 2000005L),
    ins("lake", "mi", 2000100L, 2000200L),
    "BEGIN;",
    "UPDATE lake.tpch1.fact SET status = 'MT' WHERE id = 50;",
    "DELETE FROM lake.tpch1.fact WHERE id = 51;",
    ins("lake", "mt", 2000300L, 2000301L),
    "COMMIT;",
    "UPDATE lake.tpch1.fact SET status = 'MB' WHERE id BETWEEN 1000 AND 1999;",
    "UPDATE lake.tpch1.fact SET status = status WHERE id = 70;"
  ).mkString("\n")

  private def cleanBranch(fork: Long) = List(
    "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 20;",
    "UPDATE qod_branch.tpch1.fact SET note = 'same' WHERE id = 30;",
    "DELETE FROM qod_branch.tpch1.fact WHERE id = 42;",
    ins("qod_branch", "bi", 3000000L, 3000005L),
    ins("qod_branch", "bi", 3000100L, 3000200L),
    "UPDATE qod_branch.tpch1.fact SET note = 'bu' WHERE id = 3000000;",
    "DELETE FROM qod_branch.tpch1.fact WHERE id = 3000001;",
    "BEGIN;",
    "UPDATE qod_branch.tpch1.fact SET note = 'bt1' WHERE id = 80;",
    "UPDATE qod_branch.tpch1.fact SET note = 'bt2' WHERE id = 80;",
    "DELETE FROM qod_branch.tpch1.fact WHERE id = 81;",
    ins("qod_branch", "bt", 3000300L, 3000301L),
    "COMMIT;",
    "UPDATE qod_branch.tpch1.fact SET qty = qty + 100 WHERE id BETWEEN 1500 AND 2499;",
    "UPDATE qod_branch.tpch1.fact SET note = 'b1' WHERE id = 82;",
    "UPDATE qod_branch.tpch1.fact SET note = 'b2' WHERE id = 82;",
    "UPDATE qod_branch.tpch1.fact SET note = 'b3' WHERE id = 83;",
    "DELETE FROM qod_branch.tpch1.fact WHERE id = 83;",
    "UPDATE qod_branch.tpch1.fact SET note = 'b2' WHERE id = 70;",
    "UPDATE qod_branch.tpch1.fact SET status = status WHERE id = 90;",
    "UPDATE qod_branch.tpch1.fact SET qty = -1 WHERE id = 91;",
    s"UPDATE qod_branch.tpch1.fact SET qty = (SELECT qty FROM qod_branch.tpch1.fact AT (VERSION => $fork) WHERE id = 91) WHERE id = 91;"
  ).mkString("\n")

  private val conflictMain = List(
    "UPDATE lake.tpch1.fact SET note = 'main' WHERE id = 31;",
    "UPDATE lake.tpch1.fact SET qty = -5 WHERE id = 32;",
    "DELETE FROM lake.tpch1.fact WHERE id = 40;",
    "UPDATE lake.tpch1.fact SET qty = 0 WHERE id = 41;",
    "UPDATE lake.tpch1.fact SET status = 'M6' WHERE id = 60;",
    "DELETE FROM lake.tpch1.fact WHERE id = 60;"
  ).mkString("\n")

  private val conflictBranch = List(
    "UPDATE qod_branch.tpch1.fact SET note = 'branch' WHERE id = 31;",
    "UPDATE qod_branch.tpch1.fact SET note = 'branch', status = 'BB' WHERE id = 32;",
    "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 40;",
    "DELETE FROM qod_branch.tpch1.fact WHERE id = 41;",
    "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 60;"
  ).mkString("\n")

  /** Clones, writes both sides, merges, and answers everything a test asserts on. `inspect` sees
    * the node right after the merge, while its working tables exist.
    */
  private def scenario[A](
      mainSql: String,
      branchSql: Long => String,
      policy: Option[ConflictPolicy] = None,
      ranged: Boolean = false,
      keys: Map[String, List[String]] = Map.empty,
      claimedUpdateRows: Option[Long] = None,
      inspect: MergeNode => Unit = _ => ()
  )(
      check: (Either[String, List[RowCounts]], BranchChangeSet, String, Path) => A
  ): A =
    val parent = parentReader
    try
      withBranchDb { (branchDb, branchDir) =>
        val fork = cloneBranch(branchDb, branchDir)
        if mainSql.nonEmpty then duck(mainSql)
        duck(branchSql(fork), Some(branchDb), Some(branchDir))
        val br = branchReader(branchDb)
        try
          val cs  = BranchChanges.compute(br, parent, fork)
          val out = withNode(branchDb, branchDir) { node =>
            val merged =
              runMerge(
                node,
                cs,
                br,
                parent,
                "merge [live]",
                policy,
                keys,
                ranged,
                claimedUpdateRows
              )
            inspect(node)
            merged
          }
          check(out, cs, branchDb, branchDir)
        finally br.close()
      }
    finally parent.close()

  /** How many rows a working table of the merge node holds. */
  private def workingRows(node: MergeNode, table: String): Long =
    node.rows(s"""SELECT count(*) FROM "memory"."qod_merge"."$table"""").unsafeRunSync() match
      case Right(List(List(n))) => n.asNumber.flatMap(_.toLong).getOrElse(fail(s"$table: $n"))
      case other                => fail(s"$table: $other")

  private def assertMatchesScan(
      cs: BranchChangeSet,
      table: String,
      columns: List[String],
      policy: Option[ConflictPolicy],
      branchDb: String,
      branchDir: Path
  ) =
    scanCheck(
      "tpch1",
      table,
      columns,
      cs.forkSnapshot,
      cs.mainSnapshot,
      cs.headSnapshot,
      policy,
      branchDb,
      branchDir
    ) shouldBe AllRight

  "a three-way merge" should "merge clean changes on both sides, as a full scan says" in
    withCatalog("twclean", seed) { (_, _) =>
      scenario(cleanMain, cleanBranch) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        c.conflicts shouldBe 0L
        c.toDelete shouldBe 2L   // 81 and 83; 42 was deleted on both sides
        c.toInsert shouldBe 105L // 106 inserted, 1 then deleted
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
        duck("SELECT status, qty, note FROM lake.tpch1.fact WHERE id = 1500;") shouldBe
          List("MB,100,n1500") // main's status and the branch's qty on one row
        duck("SELECT note FROM lake.tpch1.fact WHERE id = 80;") shouldBe List("bt2")
        duck("SELECT count(*) FROM lake.tpch1.fact WHERE id IN (42, 81, 83);") shouldBe List("0")
      }
    }

  it should "keep rowids of updated rows, so a sibling branch still matches them" in
    withCatalog("twrowid", seed) { (_, _) =>
      scenario(cleanMain, cleanBranch) { (out, cs, _, _) =>
        out.isRight shouldBe true
        val moved = duck(
          "SELECT count(*) FROM (SELECT a.id FROM lake.tpch1.fact a " +
            s"JOIN (SELECT rowid AS r, id FROM lake.tpch1.fact AT (VERSION => ${cs.forkSnapshot})) b " +
            "ON a.id = b.id WHERE a.rowid <> b.r);"
        )
        moved shouldBe List("0")
      }
    }

  it should "skip main's reads when main has no commits since the fork" in
    withCatalog("twbronly", seed) { (_, _) =>
      scenario("", cleanBranch) { (out, cs, branchDb, branchDir) =>
        cs.tables.map(_.threeWay) shouldBe List(false)
        out.fold(e => fail(e), _.head).conflicts shouldBe 0L
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
      }
    }

  it should "take the ranged form of an update, one range at this size, with the same result" in
    withCatalog("twranged", seed) { (_, _) =>
      scenario(cleanMain, cleanBranch, ranged = true) { (out, cs, branchDb, branchDir) =>
        out.isRight shouldBe true
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
      }
    }

  it should "apply several ranges of one update in one transaction" in
    withCatalog("twsplit", seed) { (_, _) =>
      val claimed = 10000000L
      scenario(
        List(
          "UPDATE lake.tpch1.fact SET status = 'm' WHERE id BETWEEN 100 AND 60000;",
          "DELETE FROM lake.tpch1.fact WHERE id BETWEEN 70000 AND 70100;"
        ).mkString("\n"),
        _ =>
          List(
            "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id BETWEEN 50 AND 69999;",
            "DELETE FROM qod_branch.tpch1.fact WHERE id BETWEEN 80000 AND 80100;",
            ins("qod_branch", "bi", 3000000L, 3000001L)
          ).mkString("\n"),
        ranged = true,
        claimedUpdateRows = Some(claimed)
      ) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        c.conflicts shouldBe 0L
        RowMergeSql.updateRanges(c.minUpdateRowid, c.maxUpdateRowid, claimed).size shouldBe 5
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
      }
    }

  it should "take a second attempt on the same node after main moved, fetching only what it lacks" in
    withCatalog("twretry", seed) { (_, _) =>
      val parent = parentReader
      try
        withBranchDb { (branchDb, branchDir) =>
          val fork = cloneBranch(branchDb, branchDir)
          duck("UPDATE lake.tpch1.fact SET status = 'm1' WHERE id BETWEEN 0 AND 9;")
          duck(
            List(
              "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id BETWEEN 5 AND 24;",
              "DELETE FROM qod_branch.tpch1.fact WHERE id = 30;",
              ins("qod_branch", "bi", 3000000L, 3000001L)
            ).mkString("\n"),
            Some(branchDb),
            Some(branchDir)
          )
          val br = branchReader(branchDb)
          try
            val policy = Some(ConflictPolicy.Main)
            val runner = new RowMergeRunner("lake", BranchMergeSql.BranchAlias, fork, policy)
            withNode(branchDb, branchDir) { node =>
              def run[A](step: IO[Either[String, A]]): A =
                step.unsafeRunSync().fold(e => fail(e), identity)
              val first = planned(BranchChanges.compute(br, parent, fork), br, parent)
              run(runner.prepare(node, first))
              run(runner.warmUp(node, first))
              workingRows(node, "t0_base") shouldBe 5L // 5 to 9, which both sides changed
              run(runner.decide(node, first)).head.conflicts shouldBe 0L
              // Main moves, as when the fence refuses a commit: rows the first attempt saw changed
              // only on the branch's side are now changed on both.
              duck(
                List(
                  "UPDATE lake.tpch1.fact SET status = 'm2' WHERE id BETWEEN 10 AND 19;",
                  "UPDATE lake.tpch1.fact SET note = 'mn' WHERE id = 20;",
                  "DELETE FROM lake.tpch1.fact WHERE id = 21;",
                  "UPDATE lake.tpch1.fact SET note = 'm30' WHERE id = 30;"
                ).mkString("\n")
              )
              val cs     = BranchChanges.compute(br, parent, fork)
              val second = planned(cs, br, parent)
              val c      = run(runner.decide(node, second)).head
              c.cellConflicts shouldBe 1L               // 20
              c.deleteUpdateConflicts shouldBe 1L       // 21
              c.updateDeleteConflicts shouldBe 1L       // 30
              workingRows(node, "t0_base") shouldBe 18L // 5 to 21, and 30: each once
              run(
                node.exec(
                  BranchMergeSql.applyBatch(
                    parentAlias = "lake",
                    branchAlias = BranchMergeSql.BranchAlias,
                    tableChanges = cs.tables,
                    rowMerges = second.zip(List(c)),
                    ranged = false,
                    updateChunkRows = 1000000L,
                    fork = fork,
                    head = cs.headSnapshot,
                    author = BranchMergeSql.author("acme", "bob"),
                    message = "merge [retry]"
                  )
                )
              )
              assertMatchesScan(cs, "fact", Cols, policy, branchDb, branchDir)
            }
          finally br.close()
        }
      finally parent.close()
    }

  it should "count conflicts, and give main the conflicting cells under `main`" in
    withCatalog("twmain", seed) { (_, _) =>
      val policy = Some(ConflictPolicy.Main)
      scenario(
        cleanMain + "\n" + conflictMain,
        f => cleanBranch(f) + "\n" + conflictBranch,
        policy
      ) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        c.cellConflicts shouldBe 1L         // id 31; id 32 changed different columns on each side
        c.updateDeleteConflicts shouldBe 1L // id 41
        c.deleteUpdateConflicts shouldBe 2L // ids 40 and 60
        c.columnConflicts shouldBe Map("note" -> 1L)
        c.samples("cell").size shouldBe 1
        assertMatchesScan(cs, "fact", Cols, policy, branchDb, branchDir)
        duck("SELECT note FROM lake.tpch1.fact WHERE id = 31;") shouldBe List("main")
        duck("SELECT status, qty, note FROM lake.tpch1.fact WHERE id = 32;") shouldBe
          List("BB,-5,branch") // clean cells merge
        duck("SELECT qty FROM lake.tpch1.fact WHERE id = 41;") shouldBe List("0")
        duck("SELECT count(*) FROM lake.tpch1.fact WHERE id IN (40, 60);") shouldBe List("0")
      }
    }

  it should "give the branch the conflicting cells under `branch`, returning deleted rows" in
    withCatalog("twbranch", seed) { (_, _) =>
      val policy = Some(ConflictPolicy.Branch)
      scenario(
        cleanMain + "\n" + conflictMain,
        f => cleanBranch(f) + "\n" + conflictBranch,
        policy
      ) { (_, cs, branchDb, branchDir) =>
        assertMatchesScan(cs, "fact", Cols, policy, branchDb, branchDir)
        duck("SELECT note FROM lake.tpch1.fact WHERE id = 31;") shouldBe List("branch")
        duck("SELECT count(*) FROM lake.tpch1.fact WHERE id = 41;") shouldBe List("0")
        // The row returns whole, with its unchanged cells, not NULLs.
        duck("SELECT status, note, rowid >= 100000 FROM lake.tpch1.fact WHERE id = 40;") shouldBe
          List("NEW,b,true")
      }
    }

  it should "merge a partitioned table, with rows moving between partitions" in
    withCatalog(
      "twpart",
      """CREATE TABLE lake.tpch1.fact (id BIGINT, status VARCHAR, qty INTEGER, note VARCHAR);
        |ALTER TABLE lake.tpch1.fact SET PARTITIONED BY (status);
        |INSERT INTO lake.tpch1.fact SELECT i, ['A','B','C'][(i % 3)::INTEGER + 1], (i % 100)::INTEGER, 'n' || i FROM range(100000) r(i);
        |""".stripMargin
    ) { (_, _) =>
      scenario(cleanMain, cleanBranch) { (out, cs, branchDb, branchDir) =>
        out.isRight shouldBe true
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
      }
    }

  it should "merge nested columns cell by cell" in
    withCatalog(
      "twnested",
      """CREATE TABLE lake.tpch1.nest (id BIGINT, s STRUCT(a INTEGER, b VARCHAR), l INTEGER[], m MAP(VARCHAR, INTEGER), j JSON, v VARIANT);
        |INSERT INTO lake.tpch1.nest SELECT i, {'a': i::INTEGER, 'b': 'x'}, [i::INTEGER], MAP {'k': i::INTEGER}, '{"a":1}', i::VARIANT FROM range(1000) r(i);
        |""".stripMargin
    ) { (_, _) =>
      scenario(
        """UPDATE lake.tpch1.nest SET s = {'a': -1, 'b': 'm'} WHERE id < 100;
          |UPDATE lake.tpch1.nest SET v = 'main'::VARIANT WHERE id < 10;""".stripMargin,
        _ =>
          """UPDATE qod_branch.tpch1.nest SET l = [9], m = MAP {'k': 9} WHERE id < 50;
            |UPDATE qod_branch.tpch1.nest SET j = '{"a":2}' WHERE id BETWEEN 200 AND 299;""".stripMargin
      ) { (out, cs, branchDb, branchDir) =>
        out.fold(e => fail(e), _.head).conflicts shouldBe 0L
        // VARIANT has no EXCEPT ALL: the scan compares the others, and v is asserted by hand.
        assertMatchesScan(cs, "nest", List("id", "s", "l", "m", "j"), None, branchDb, branchDir)
        duck(
          "SELECT v IS NOT DISTINCT FROM 'main'::VARIANT FROM lake.tpch1.nest WHERE id = 5;"
        ) shouldBe
          List("true")
      }
    }

  it should "treat a table compacted and flushed on main during the branch's life like any other" in
    withCatalog("twmaint", seed) { (_, _) =>
      scenario(
        List(
          "DELETE FROM lake.tpch1.fact WHERE id BETWEEN 50000 AND 79999;",
          "UPDATE lake.tpch1.fact SET status = 'u1' WHERE id BETWEEN 0 AND 99;",
          "UPDATE lake.tpch1.fact SET status = 'inl' WHERE id = 200;",
          "CALL ducklake_flush_inlined_data('lake');",
          "CALL ducklake_rewrite_data_files('lake', 'fact', schema => 'tpch1', delete_threshold => 0.0);",
          "UPDATE lake.tpch1.fact SET status = 'u2' WHERE id BETWEEN 1000 AND 1099;"
        ).mkString("\n"),
        _ => "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id BETWEEN 1050 AND 1149;",
        // Main's own 30,000 deletes and 201 updates, not the 70,000 rows the rewrite would report
        // if it were read. That read would change no result, so only this count can see it.
        inspect = workingRows(_, "t0_net_m") shouldBe 30201L
      ) { (out, cs, branchDb, branchDir) =>
        out.fold(e => fail(e), _.head).toUpdate shouldBe 100L
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
      }
    }

  it should "see main's changes made before a flush and a rewrite, on rows the branch also changed" in
    withCatalog("twflushed", seed) { (_, _) =>
      val policy = Some(ConflictPolicy.Main)
      scenario(
        List(
          "UPDATE lake.tpch1.fact SET status = 'x' WHERE id = 200;",
          "DELETE FROM lake.tpch1.fact WHERE id = 201;",
          "UPDATE lake.tpch1.fact SET status = 'y' WHERE id = 202;",
          "CALL ducklake_flush_inlined_data('lake');",
          "CALL ducklake_rewrite_data_files('lake', 'fact', schema => 'tpch1', delete_threshold => 0.0);"
        ).mkString("\n"),
        _ =>
          List(
            "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 200;",
            "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 201;",
            "DELETE FROM qod_branch.tpch1.fact WHERE id = 202;"
          ).mkString("\n"),
        policy
      ) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        c.cellConflicts shouldBe 0L
        c.deleteUpdateConflicts shouldBe 1L // 201
        c.updateDeleteConflicts shouldBe 1L // 202
        assertMatchesScan(cs, "fact", Cols, policy, branchDb, branchDir)
        duck("SELECT status, note FROM lake.tpch1.fact WHERE id = 200;") shouldBe List("x,b")
      }
    }

  it should "skip the branch's own maintenance, and never take a row it inserted and deleted for main's" in
    withCatalog("twbrmaint", seed) { (_, _) =>
      scenario(
        // These rows get the rowids the branch's inserts below get.
        ins("lake", "mi", 2000000L, 2000003L),
        _ =>
          List(
            "UPDATE qod_branch.tpch1.fact SET note = 'b1' WHERE id = 10;",
            ins("qod_branch", "bi", 3000000L, 3000003L),
            "DELETE FROM qod_branch.tpch1.fact WHERE id = 3000000;",
            "UPDATE qod_branch.tpch1.fact SET note = 'bu' WHERE id = 3000001;",
            "CALL ducklake_flush_inlined_data('qod_branch');",
            "CALL ducklake_rewrite_data_files('qod_branch', 'fact', schema => 'tpch1', delete_threshold => 0.0);",
            "UPDATE qod_branch.tpch1.fact SET qty = -1 WHERE id = 20;"
          ).mkString("\n")
      ) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        (c.toDelete, c.toUpdate, c.toInsert) shouldBe (0L, 2L, 2L)
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
      }
    }

  it should "leave main's change alone where the branch ended up with no net change" in
    withCatalog("twrevert", seed) { (_, _) =>
      scenario(
        List(
          "UPDATE lake.tpch1.fact SET status = 'm' WHERE id = 91;",
          "DELETE FROM lake.tpch1.fact WHERE id = 92;"
        ).mkString("\n"),
        _ =>
          List(
            "UPDATE qod_branch.tpch1.fact SET note = 'x' WHERE id IN (91, 92);",
            "UPDATE qod_branch.tpch1.fact SET note = 'n' || id WHERE id IN (91, 92);",
            "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 93;"
          ).mkString("\n")
      ) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        c.conflicts shouldBe 0L // not even for 92, which main deleted
        c.toUpdate shouldBe 1L  // 93 alone: 91 stays as main has it
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
      }
    }

  it should "compare cells holding NULL and NaN as equal to themselves" in
    withCatalog(
      "twnull",
      """CREATE TABLE lake.tpch1.cells (id BIGINT, d DECIMAL(18,3), x DOUBLE, note VARCHAR);
        |INSERT INTO lake.tpch1.cells SELECT i, NULL, CASE WHEN i % 2 = 0 THEN 'NaN'::DOUBLE ELSE 0.0 END, NULL FROM range(1000) r(i);
        |""".stripMargin
    ) { (_, _) =>
      val policy = Some(ConflictPolicy.Main)
      scenario(
        List(
          "UPDATE lake.tpch1.cells SET d = 1.5 WHERE id = 1;",
          "UPDATE lake.tpch1.cells SET x = 'NaN'::DOUBLE WHERE id = 2;", // no change
          "UPDATE lake.tpch1.cells SET x = 'NaN'::DOUBLE WHERE id = 3;",
          "UPDATE lake.tpch1.cells SET note = 'm' WHERE id = 4;",
          "UPDATE lake.tpch1.cells SET note = 'm' WHERE id = 5;"
        ).mkString("\n"),
        _ =>
          List(
            "UPDATE qod_branch.tpch1.cells SET x = 2.0 WHERE id = 1;",
            "UPDATE qod_branch.tpch1.cells SET x = 1.0 WHERE id = 2;",
            "UPDATE qod_branch.tpch1.cells SET x = 'NaN'::DOUBLE WHERE id = 3;", // the same as main
            "UPDATE qod_branch.tpch1.cells SET note = NULL, d = 2.5 WHERE id = 4;", // note: no change
            "UPDATE qod_branch.tpch1.cells SET note = 'b' WHERE id = 5;"
          ).mkString("\n"),
        policy
      ) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        c.cellConflicts shouldBe 1L // id 5 alone
        c.columnConflicts shouldBe Map("note" -> 1L)
        assertMatchesScan(cs, "cells", List("id", "d", "x", "note"), policy, branchDb, branchDir)
        duck("SELECT d, x FROM lake.tpch1.cells WHERE id = 1;") shouldBe List("1.500,2.0")
        duck("SELECT x FROM lake.tpch1.cells WHERE id = 2;") shouldBe List("1.0")
        duck("SELECT d, note FROM lake.tpch1.cells WHERE id = 4;") shouldBe List("2.500,m")
      }
    }

  it should "take columns and tables named like the working tables' own, or oddly" in {
    def q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val schema       = "s'x"
    val name         = "we\"ird; t"
    val table        = s"${q(schema)}.${q(name)}"
    val cols         = List(
      "v1",
      "v0",
      "action",
      "gone",
      "k0",
      "c0",
      "f",
      "n",
      "m",
      "b",
      "t",
      "u",
      "p",
      "ov",
      "A b",
      "select",
      "a.b",
      "q\"x"
    )
    val typed  = cols.map(c => s"${q(c)} INTEGER").mkString(", ")
    val values = cols.indices.map(i => s"(g.i + $i)::INTEGER").mkString(", ")
    withCatalog(
      "twodd",
      s"""CREATE SCHEMA lake.${q(schema)};
         |CREATE TABLE lake.$table ($typed);
         |INSERT INTO lake.$table SELECT $values FROM range(50000) g(i);
         |""".stripMargin
    ) { (_, _) =>
      val policy = Some(ConflictPolicy.Main)
      scenario(
        List(
          s"""UPDATE lake.$table SET "v0" = -1, "action" = -2 WHERE "v1" BETWEEN 0 AND 9;""",
          s"""UPDATE lake.$table SET "gone" = -3 WHERE "v1" = 20;""",
          s"""DELETE FROM lake.$table WHERE "v1" = 30;"""
        ).mkString("\n"),
        _ =>
          List(
            s"""UPDATE qod_branch.$table SET "v1" = -7, "q""x" = -8 WHERE "v1" BETWEEN 5 AND 14;""",
            s"""UPDATE qod_branch.$table SET "gone" = -9, "a.b" = -9 WHERE "v1" = 20;""",
            s"""UPDATE qod_branch.$table SET "select" = -9 WHERE "v1" = 30;"""
          ).mkString("\n"),
        policy
      ) { (out, cs, branchDb, branchDir) =>
        val c = out.fold(e => fail(e), _.head)
        c.columnConflicts shouldBe Map("gone" -> 1L)
        c.deleteUpdateConflicts shouldBe 1L
        scanCheck(
          schema,
          name,
          cols,
          cs.forkSnapshot,
          cs.mainSnapshot,
          cs.headSnapshot,
          policy,
          branchDb,
          branchDir
        ) shouldBe AllRight
      }
    }
  }

  it should "keep rows inlined at the fork matched after main flushes them" in
    withCatalog(
      "twinlined",
      """CREATE TABLE lake.tpch1.small (id BIGINT, note VARCHAR, qty INTEGER);
        |INSERT INTO lake.tpch1.small SELECT i, 'n', 0 FROM range(5) r(i);
        |""".stripMargin
    ) { (_, _) =>
      scenario(
        List(
          "CALL ducklake_flush_inlined_data('lake');",
          "UPDATE lake.tpch1.small SET qty = 7 WHERE id = 1;",
          "UPDATE lake.tpch1.small SET note = 'main' WHERE id = 4;"
        ).mkString("\n"),
        _ =>
          List(
            "UPDATE qod_branch.tpch1.small SET note = 'b' WHERE id = 1;",
            "UPDATE qod_branch.tpch1.small SET note = 'b' WHERE id = 2;",
            "DELETE FROM qod_branch.tpch1.small WHERE id = 3;"
          ).mkString("\n")
      ) { (out, cs, branchDb, branchDir) =>
        out.fold(e => fail(e), _.head).conflicts shouldBe 0L
        assertMatchesScan(cs, "small", List("id", "note", "qty"), None, branchDb, branchDir)
        duck("SELECT note, qty FROM lake.tpch1.small WHERE id = 1;") shouldBe List("b,7")
      }
    }

  it should "merge two tables in one transaction, each with deletes and updates" in
    withCatalog(
      "twtwo",
      seed + """CREATE TABLE lake.tpch1.other (id BIGINT, note VARCHAR);
               |INSERT INTO lake.tpch1.other SELECT i, 'o' FROM range(100000) r(i);
               |""".stripMargin
    ) { (_, _) =>
      val main = "UPDATE lake.tpch1.fact SET qty = 0 WHERE id < 100;\n" +
        "UPDATE lake.tpch1.other SET note = 'm' WHERE id < 100;"
      val branch = (_: Long) =>
        List(
          "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id BETWEEN 50 AND 149;",
          "DELETE FROM qod_branch.tpch1.fact WHERE id BETWEEN 500 AND 549;",
          "UPDATE qod_branch.tpch1.other SET id = id WHERE id BETWEEN 200 AND 249;",
          "DELETE FROM qod_branch.tpch1.other WHERE id BETWEEN 500 AND 549;"
        ).mkString("\n")
      scenario(main, branch) { (out, cs, branchDb, branchDir) =>
        out.fold(e => fail(e), _.size) shouldBe 2
        assertMatchesScan(cs, "fact", Cols, None, branchDb, branchDir)
        assertMatchesScan(cs, "other", List("id", "note"), None, branchDb, branchDir)
      }
    }

  private val keyedSeed =
    """CREATE TABLE lake.tpch1.k (id BIGINT, k VARCHAR, note VARCHAR);
      |INSERT INTO lake.tpch1.k SELECT i, 'old' || i, 'n' FROM range(1000) r(i);
      |""".stripMargin

  private val keyedMain =
    "INSERT INTO lake.tpch1.k VALUES (5000, 'a', 'main'), (5001, 'b', 'main'), (5002, NULL, 'main'), (5003, 'm', 'main'), (5004, 'a', 'main');"

  private val keyedBranch = (_: Long) =>
    "INSERT INTO qod_branch.tpch1.k VALUES (6000, 'a', 'branch'), (6001, 'c', 'branch'), (6002, NULL, 'branch'), (6003, 'm', 'branch'), (6004, 'm', 'branch');"

  private def keysOf(sql: String): List[String] =
    duck(
      s"SELECT coalesce(k, 'NULL') || ':' || note FROM lake.tpch1.k WHERE id >= 5000 $sql ORDER BY 1;"
    )

  it should "report rows both sides inserted under a key, and never match a NULL key" in
    withCatalog("twkey", keyedSeed) { (_, _) =>
      scenario(
        keyedMain,
        keyedBranch,
        Some(ConflictPolicy.Main),
        keys = Map("tpch1.k" -> List("k"))
      ) { (out, _, _, _) =>
        val c = out.fold(e => fail(e), _.head)
        c.keyMatches shouldBe 3L // 'a' and two of 'm'
        c.samples("key").size shouldBe 3
        keysOf("") shouldBe List(
          "NULL:branch",
          "NULL:main",
          "a:main",
          "a:main",
          "b:main",
          "c:branch",
          "m:main"
        )
      }
    }

  it should "replace main's matching rows under `branch`, however many match" in
    withCatalog("twkeyb", keyedSeed) { (_, _) =>
      scenario(
        keyedMain,
        keyedBranch,
        Some(ConflictPolicy.Branch),
        keys = Map("tpch1.k" -> List("k"))
      ) { (out, _, _, _) =>
        out.isRight shouldBe true
        keysOf("") shouldBe List(
          "NULL:branch",
          "NULL:main",
          "a:branch",
          "b:main",
          "c:branch",
          "m:branch",
          "m:branch"
        )
      }
    }

  it should "count main's rows a key matches apart from the branch's" in
    withCatalog("twkeyc", keyedSeed) { (_, _) =>
      val main =
        "INSERT INTO lake.tpch1.k VALUES (5000, 'a', 'main'), (5001, 'a', 'main'), (5002, 'a', 'main');"
      val branch = (_: Long) => "INSERT INTO qod_branch.tpch1.k VALUES (6000, 'a', 'branch');"
      scenario(main, branch, Some(ConflictPolicy.Branch), keys = Map("tpch1.k" -> List("k"))) {
        (out, _, _, _) =>
          val c = out.fold(e => fail(e), _.head)
          (c.keyMatches, c.mainRowsByKey, c.toDelete) shouldBe (1L, 3L, 3L)
      }
    }

  it should "match a key only against main's rows born after the fork and still there" in
    withCatalog("twkeyscope", keyedSeed) { (_, _) =>
      val main = List(
        "INSERT INTO lake.tpch1.k VALUES (5000, 'deleted', 'main'), (5001, 'moved', 'main'), (5002, 'late', 'main');",
        "DELETE FROM lake.tpch1.k WHERE id = 5000;",
        "UPDATE lake.tpch1.k SET k = 'elsewhere' WHERE id = 5001;",
        "UPDATE lake.tpch1.k SET k = 'forked' WHERE id = 1;" // a row of the fork, on the key below
      ).mkString("\n")
      val branch = (_: Long) =>
        List(
          "INSERT INTO qod_branch.tpch1.k VALUES (6000, 'deleted', 'branch'), (6001, 'moved', 'branch'), (6002, 'forked', 'branch');",
          "UPDATE qod_branch.tpch1.k SET k = 'late' WHERE id = 2;" // a row of the fork, not a new one
        ).mkString("\n")
      scenario(main, branch, Some(ConflictPolicy.Branch), keys = Map("tpch1.k" -> List("k"))) {
        (out, _, _, _) =>
          val c = out.fold(e => fail(e), _.head)
          (c.keyMatches, c.mainRowsByKey) shouldBe (0L, 0L)
          (c.toDelete, c.toUpdate, c.toInsert) shouldBe (0L, 1L, 3L)
      }
    }

  it should "read no key of main's when main has no commits since the fork" in
    ConflictPolicy.values.foreach { policy =>
      withCatalog(s"twkeyquiet${policy.wire}", keyedSeed) { (_, _) =>
        scenario("", keyedBranch, Some(policy), keys = Map("tpch1.k" -> List("k"))) {
          (out, cs, _, _) =>
            cs.tables.map(_.threeWay) shouldBe List(false)
            val c = out.fold(e => fail(e), _.head)
            (c.keyMatches, c.mainRowsByKey, c.toDelete, c.toInsert) shouldBe (0L, 0L, 0L, 5L)
            keysOf("").size shouldBe 5
        }
      }
    }

  private val pairKey = Map("tpch1.pairs" -> List("k", "k2"))

  private val pairSeed =
    """CREATE TABLE lake.tpch1.pairs (id BIGINT, k VARCHAR, k2 INTEGER, note VARCHAR);
      |INSERT INTO lake.tpch1.pairs SELECT i, 'old' || i, 0, 'n' FROM range(1000) r(i);
      |""".stripMargin

  /** Main's rows in a file and inlined, some changed since, all flushed and rewritten. */
  private val pairMain = List(
    "INSERT INTO lake.tpch1.pairs SELECT i, 'f' || (i - 5000), 1, 'main' FROM range(5000, 5100) r(i);",
    "INSERT INTO lake.tpch1.pairs VALUES (5100, 'inl', 1, 'main'), (5101, 'gone', 1, 'main'), (5102, 'was', 1, 'main'), (5103, NULL, 1, 'main'), (5104, 'c2', NULL, 'main'), (5105, 'c2', 2, 'main');",
    "DELETE FROM lake.tpch1.pairs WHERE id = 5101;",
    "UPDATE lake.tpch1.pairs SET k = 'now' WHERE id = 5102;",
    "CALL ducklake_flush_inlined_data('lake');",
    "DELETE FROM lake.tpch1.pairs WHERE id = 5050;",
    "CALL ducklake_rewrite_data_files('lake', 'pairs', schema => 'tpch1', delete_threshold => 0.0);",
    "UPDATE lake.tpch1.pairs SET note = 'main2' WHERE id = 5001;"
  ).mkString("\n")

  private val pairBranch = (_: Long) =>
    List(
      "INSERT INTO qod_branch.tpch1.pairs VALUES " +
        "(6000, 'f1', 1, 'branch'), " +    // matches main's row in a file, updated since
        "(6001, 'f50', 1, 'branch'), " +   // main deleted that key
        "(6002, 'inl', 1, 'branch'), " +   // matches main's inlined row, flushed since
        "(6003, 'gone', 1, 'branch'), " +  // main deleted that key
        "(6004, 'was', 1, 'branch'), " +   // main moved off that key
        "(6005, 'now', 1, 'branch'), " +   // main moved onto that key
        "(6006, NULL, 1, 'branch'), " +    // a NULL in the first part
        "(6007, 'c2', 2, 'branch'), " +    // both parts match
        "(6008, 'c2', NULL, 'branch'), " + // a NULL in the second part, against one on main too
        "(6009, 'c2', 3, 'branch'), " +    // the first part matches, not the second: moved below
        "(6010, 'dropped', 1, 'branch');",
      "UPDATE qod_branch.tpch1.pairs SET k = 'f2', k2 = 1 WHERE id = 6009;", // now on a key of main's
      "DELETE FROM qod_branch.tpch1.pairs WHERE id = 6010;",
      "UPDATE qod_branch.tpch1.pairs SET note = 'pre' WHERE id = 3;"
    ).mkString("\n")

  private def pairRows() =
    duck(
      "SELECT coalesce(k, 'NULL') || '/' || coalesce(k2::VARCHAR, 'NULL') || ':' || note " +
        "FROM lake.tpch1.pairs WHERE id >= 5000 " +
        "AND NOT (id BETWEEN 5003 AND 5099 AND id <> 5050) ORDER BY 1;" // 5050 stays deleted
    )

  it should "match every part of a composite key against main's latest values, under `main`" in
    withCatalog("twpairm", pairSeed) { (_, _) =>
      scenario(pairMain, pairBranch, Some(ConflictPolicy.Main), keys = pairKey) { (out, _, _, _) =>
        val c = out.fold(e => fail(e), _.head)
        // f1/1, inl/1, now/1, c2/2, and f2/1 by the row the branch moved onto it.
        (c.keyMatches, c.mainRowsByKey) shouldBe (5L, 5L)
        (c.toDelete, c.toUpdate, c.toInsert) shouldBe (0L, 1L, 5L)
        pairRows() shouldBe List(
          "NULL/1:branch",
          "NULL/1:main",
          "c2/2:main",
          "c2/NULL:branch",
          "c2/NULL:main",
          "f0/1:main",
          "f1/1:main2",
          "f2/1:main",
          "f50/1:branch",
          "gone/1:branch",
          "inl/1:main",
          "now/1:main",
          "was/1:branch"
        )
      }
    }

  it should "delete only main's rows that match every part of a composite key, under `branch`" in
    withCatalog("twpairb", pairSeed) { (_, _) =>
      scenario(pairMain, pairBranch, Some(ConflictPolicy.Branch), keys = pairKey) {
        (out, _, _, _) =>
          val c = out.fold(e => fail(e), _.head)
          (c.keyMatches, c.mainRowsByKey) shouldBe (5L, 5L)
          (c.toDelete, c.toUpdate, c.toInsert) shouldBe (5L, 1L, 10L)
          pairRows() shouldBe List(
            "NULL/1:branch",
            "NULL/1:main",
            "c2/2:branch",
            "c2/NULL:branch",
            "c2/NULL:main",
            "f0/1:main",
            "f1/1:branch",
            "f2/1:branch",
            "f50/1:branch",
            "gone/1:branch",
            "inl/1:branch",
            "now/1:branch",
            "was/1:branch"
          )
      }
    }

  it should "let both land without a key" in
    withCatalog("twnokey", keyedSeed) { (_, _) =>
      scenario(keyedMain, keyedBranch) { (out, _, _, _) =>
        out.fold(e => fail(e), _.head).keyMatches shouldBe 0L
        keysOf("").size shouldBe 10
      }
    }

  it should "refuse main's history with a hole, after a manual expiry" in
    withCatalog("twgap", seed) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = cloneBranch(branchDb, branchDir)
        duck("UPDATE lake.tpch1.fact SET note = 'm1' WHERE id = 1;")
        duck("UPDATE lake.tpch1.fact SET note = 'm2' WHERE id = 2;")
        duck(
          "UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 3;",
          Some(branchDb),
          Some(branchDir)
        )
        duck(s"CALL ducklake_expire_snapshots('lake', versions => [${fork + 1}]);")
        val br = branchReader(branchDb)
        try
          val cs = BranchChanges.compute(br, parent, fork)
          RowMergeTable.plan(cs, br, parent, Map.empty) shouldBe
            Left(RowMergeTable.Problem.HistoryGap("main", fork + 1))
        finally br.close()
      }
    }

  it should "refuse a hole in the branch's history, and the fork itself on either side, after a manual expiry" in
    withCatalog("twgapb", seed) { (parent, _) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork                  = cloneBranch(branchDb, branchDir)
        def onBranch(sql: String) = duck(sql, Some(branchDb), Some(branchDir))
        def withBranch[A](body: DuckLakeCatalogReader => A): A =
          val br = branchReader(branchDb)
          try body(br)
          finally br.close()
        // The branch creates a table (the only trace of it is that snapshot), then changes another.
        onBranch("CREATE TABLE qod_branch.tpch1.made (id BIGINT);")
        onBranch("UPDATE qod_branch.tpch1.fact SET note = 'b' WHERE id = 3;")
        duck("UPDATE lake.tpch1.fact SET note = 'm1' WHERE id = 4;")
        duck("UPDATE lake.tpch1.fact SET note = 'm2' WHERE id = 5;")
        def plan(): Either[RowMergeTable.Problem, List[RowMergeTable]] =
          withBranch(br =>
            RowMergeTable.plan(BranchChanges.compute(br, parent, fork), br, parent, Map.empty)
          )
        def expire(catalog: String, snapshot: Long): Unit =
          onBranch(s"CALL ducklake_expire_snapshots('$catalog', versions => [$snapshot]);"): Unit
        // Whole on both sides: the ids from the fork on are consecutive, the fork's own included.
        plan().isRight shouldBe true
        // The snapshot that created `made` goes, and with it the branch's only trace of the table.
        expire("qod_branch", fork + 1)
        withBranch(br => BranchChanges.compute(br, parent, fork).tables.map(_.table)) shouldBe
          List("fact")
        plan() shouldBe Left(RowMergeTable.Problem.HistoryGap("branch", fork + 1))
        // Then the fork itself: the lowest missing id is the one named.
        expire("qod_branch", fork)
        plan() shouldBe Left(RowMergeTable.Problem.HistoryGap("branch", fork))
        // Main is checked first, whatever the branch lost.
        expire("lake", fork + 1)
        plan() shouldBe Left(RowMergeTable.Problem.HistoryGap("main", fork + 1))
        expire("lake", fork)
        plan() shouldBe Left(RowMergeTable.Problem.HistoryGap("main", fork))
      }
    }

  it should "see a sibling's merged changes as main's, by rowid" in
    withCatalog("twsib", seed) { (parent, _) =>
      duck("UPDATE lake.tpch1.fact SET note = 'unrelated' WHERE id = 99999;")
      withNamedBranchDb("__s1") { (db1, dir1) =>
        withNamedBranchDb("__s2") { (db2, dir2) =>
          val fork1 = cloneBranch(db1, dir1)
          val fork2 = cloneBranch(db2, dir2)
          fork1 shouldBe fork2
          duck(
            List(
              "UPDATE qod_branch.tpch1.fact SET note = 's1' WHERE id BETWEEN 0 AND 999;",
              "DELETE FROM qod_branch.tpch1.fact WHERE id BETWEEN 2000 AND 2099;"
            ).mkString("\n"),
            Some(db1),
            Some(dir1)
          )
          duck(
            List(
              "UPDATE qod_branch.tpch1.fact SET note = 's2' WHERE id BETWEEN 500 AND 999;",
              "UPDATE qod_branch.tpch1.fact SET qty = -1 WHERE id BETWEEN 700 AND 1299;",
              "UPDATE qod_branch.tpch1.fact SET note = 's2d' WHERE id BETWEEN 2050 AND 2149;"
            ).mkString("\n"),
            Some(db2),
            Some(dir2)
          )
          // The first lands.
          val br1 = branchReader(db1)
          try
            val cs1 = BranchChanges.compute(br1, parent, fork1)
            withNode(db1, dir1)(n =>
              runMerge(n, cs1, br1, parent, "merge s1")
            ).isRight shouldBe true
          finally br1.close()
          // The second sees exactly the rows the first changed, by the rowids it kept.
          val br2 = branchReader(db2)
          try
            val cs2 = BranchChanges.compute(br2, parent, fork2)
            cs2.tables.map(_.threeWay) shouldBe List(true)
            val c = withNode(db2, dir2)(n =>
              runMerge(n, cs2, br2, parent, "merge s2", Some(ConflictPolicy.Main))
            ).fold(e => fail(e), _.head)
            c.cellConflicts shouldBe 500L
            c.deleteUpdateConflicts shouldBe 50L
            assertMatchesScan(cs2, "fact", Cols, Some(ConflictPolicy.Main), db2, dir2)
          finally br2.close()
        }
      }
    }

  "the change counts" should "match the full change feed, on a mixed history" in
    withCatalog("twcounts", seed) { (parent, _) =>
      val fork = parent.maxSnapshotId().get
      duck(cleanMain)
      val head  = parent.maxSnapshotId().get
      val cheap = duck(
        ai.starlake.quack.ondemand.api.DataDiffSql
          .summarySql("lake", "tpch1", "fact", fork, head) + ";"
      )
      val feed = duck(
        s"SELECT change_type, count(*) FROM ducklake_table_changes('lake', 'tpch1', 'fact', ${fork + 1}, $head) " +
          "WHERE change_type <> 'update_preimage' GROUP BY change_type;"
      )
      cheap.sorted shouldBe feed.sorted
    }
