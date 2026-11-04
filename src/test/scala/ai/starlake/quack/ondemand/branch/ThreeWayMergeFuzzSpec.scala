package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.exceptions.TestCanceledException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.collection.mutable.ListBuffer
import scala.util.Random
import scala.util.control.NonFatal

/** Random histories on both sides of a merge, each checked against the full three-way scan
  * (`ScanOracle`). `ThreeWayMergeLiveSpec` lists the histories someone thought of; these mix
  * updates, deletes, inserts, transactions, flushes, rewrites and adjacent merges in any order, on
  * two tables, under any policy, with or without a key, with a second Decide after main moved, with
  * real multi-range updates, and with sibling branches merged in sequence. The conflict and key
  * counts are checked against a scan as well.
  *
  * The seeds are a fixed sequence, so every run repeats the same histories and a failure prints its
  * seed and its history. 20 histories keep this alive in the normal suite. `QOD_MERGE_FUZZ_RUNS`
  * raises the count, half for each test, which carries on along the same sequence.
  */
class ThreeWayMergeFuzzSpec extends AnyFlatSpec with Matchers with ThreeWayMergeFixture:

  private val Cols      = List("id", "status", "qty", "note")
  private val FirstSeed = 1000L

  /** Enough to keep this alive in the normal suite. The variable can raise it, never lower it. */
  private val DefaultRuns = 20
  private val Runs        =
    sys.env.get("QOD_MERGE_FUZZ_RUNS").map(_.toInt).fold(DefaultRuns)(_.max(DefaultRuns))

  /** Random writes on one side. `born` starts the ids of the rows it inserts. */
  private final class Writes(r: Random, side: String, born: Long):
    private var next               = born
    private def pick[A](xs: A*): A = xs(r.nextInt(xs.size))

    private def anyTable = if r.nextInt(4) == 0 then "fact2" else "fact"
    private def target   = s"$side.tpch1.$anyTable"

    /** Rows of the fork, overlapping the other side's often: half the domains are small. */
    private def ids(): String =
      val from = r.nextLong(pick(30L, 30L, 300L, 3000L))
      s"id BETWEEN $from AND ${from + pick(1L, 1L, 2L, 5L, 50L, 1000L) - 1L}"

    private def assignment(): (String, String) = r.nextInt(5) match
      case 0 => ("status", pick("'A'", "'B'", "'C'", "NULL"))
      case 1 => ("qty", pick("1", "2", "3", "NULL"))
      case 2 => ("note", pick("'x'", "'y'", "NULL", "'n' || id"))
      case 3 => ("status", "status") // no change
      case _ => ("qty", "qty + 1")

    private def update(): String =
      val (c1, v1) = assignment()
      val (c2, v2) = assignment()
      val sets     = if c1 == c2 || r.nextBoolean() then s"$c1 = $v1" else s"$c1 = $v1, $c2 = $v2"
      s"UPDATE $target SET $sets WHERE ${ids()};"

    private def delete(): String = s"DELETE FROM $target WHERE ${ids()};"

    /** Few distinct values, so that keys match across the sides, several on either side, and NULL.
      */
    private def insert(): String =
      val n    = pick(1L, 2L, 5L, 200L)
      val from = next
      next += n
      s"INSERT INTO $target SELECT i, ['A','B','C',NULL][(i % 4)::INTEGER + 1], (i % 3)::INTEGER, " +
        s"'born' || (i % 5) FROM range($from, ${from + n}) r(i);"

    private def touchBorn(): String =
      if next == born then insert()
      else
        val id = born + r.nextLong(next - born)
        r.nextInt(3) match
          case 0 => s"UPDATE $target SET note = 'bu' WHERE id = $id;"
          case 1 => s"UPDATE $target SET status = 'A' WHERE id = $id;"
          case _ => s"DELETE FROM $target WHERE id = $id;"

    private def data(): String = r.nextInt(8) match
      case 0 | 1 | 2 => update()
      case 3 | 4     => delete()
      case 5 | 6     => insert()
      case _         => touchBorn()

    private def op(): String = r.nextInt(14) match
      case 0 | 1 =>
        (1 to pick(2, 3)).map(_ => data()).mkString("BEGIN;\n", "\n", "\nCOMMIT;")
      case 2 => s"CALL ducklake_flush_inlined_data('$side');"
      case 3 =>
        s"CALL ducklake_rewrite_data_files('$side', '$anyTable', schema => 'tpch1', delete_threshold => 0.0);"
      case 4 => s"CALL ducklake_merge_adjacent_files('$side', '$anyTable', schema => 'tpch1');"
      case _ => data()

    def ops(n: Int): String = (1 to n).map(_ => op()).mkString("\n")

  /** Two tables, each in two files and three rows still inlined at the fork. */
  private def catalogSql(partitioned: Boolean): String =
    def table(name: String) =
      val part =
        if partitioned then s"ALTER TABLE lake.tpch1.$name SET PARTITIONED BY (status);\n" else ""
      def rows(from: Int, to: Int, note: String) =
        s"INSERT INTO lake.tpch1.$name SELECT i, ['A','B','C'][(i % 3)::INTEGER + 1], " +
          s"(i % 7)::INTEGER, $note FROM range($from, $to) r(i);\n"
      s"CREATE TABLE lake.tpch1.$name (id BIGINT, status VARCHAR, qty INTEGER, note VARCHAR);\n" +
        part + rows(0, 2000, "'n' || i") + rows(2000, 3000, "'n' || i") + rows(3000, 3003, "NULL")
    table("fact") + table("fact2")

  private def policyOf(r: Random): Option[ConflictPolicy] = r.nextInt(5) match
    case 0     => None
    case 1 | 2 => Some(ConflictPolicy.Main)
    case _     => Some(ConflictPolicy.Branch)

  /** How one merge goes. `mainMeanwhile` moves main between a first Decide and a second, on the
    * same node. `claimedUpdateRows` splits the update into real ranges (see `runMerge`).
    */
  private final case class How(
      policy: Option[ConflictPolicy],
      keys: Map[String, List[String]] = Map.empty,
      warm: Boolean = false,
      mainMeanwhile: Option[String] = None,
      claimedUpdateRows: Option[Long] = None
  )

  private final case class Outcome(cs: BranchChangeSet, counts: List[RowCounts], applied: Boolean):
    def conflicts: Long = counts.map(_.conflicts).sum

  /** Prepare and Decide, and Apply when the rules allow it: conflicts with no policy refuse. */
  private def merge(
      branchDb: String,
      branchDir: Path,
      br: DuckLakeCatalogReader,
      parent: DuckLakeCatalogReader,
      fork: Long,
      how: How,
      message: String
  ): Either[String, Outcome] =
    def nothing[A](a: A) = EitherT.rightT[IO, String](a)
    withNode(branchDb, branchDir) { node =>
      val runner = new RowMergeRunner("lake", BranchMergeSql.BranchAlias, fork, how.policy)
      val first  = planned(BranchChanges.compute(br, parent, fork), br, parent, how.keys)
      (for
        _     <- EitherT(runner.prepare(node, first))
        _     <- if how.warm then EitherT(runner.warmUp(node, first)) else nothing(())
        early <- EitherT(runner.decide(node, first))
        _      = how.mainMeanwhile.foreach(sql => duck(sql))
        cs     = BranchChanges.compute(br, parent, fork)
        tables = planned(cs, br, parent, how.keys)
        counts <-
          if how.mainMeanwhile.isDefined then EitherT(runner.decide(node, tables))
          else nothing(early)
        applied = how.policy.isDefined || counts.forall(_.conflicts == 0L)
        claimed = counts.map(c =>
          how.claimedUpdateRows.filter(_ => c.toUpdate > 0L).fold(c)(n => c.copy(toUpdate = n))
        )
        _ <-
          if applied then
            EitherT(
              node.exec(
                BranchMergeSql.applyBatch(
                  parentAlias = "lake",
                  branchAlias = BranchMergeSql.BranchAlias,
                  tableChanges = cs.tables,
                  rowMerges = tables.zip(claimed),
                  ranged = how.claimedUpdateRows.isDefined,
                  updateChunkRows = 1000000L,
                  fork = fork,
                  head = cs.headSnapshot,
                  author = BranchMergeSql.author("acme", "bob"),
                  message = message
                )
              )
            )
          else nothing(())
      yield Outcome(cs, counts, applied)).value.unsafeRunSync()
    }

  /** The conflicts of one table as a scan of base, main and branch finds them: `cell,upd,del`. */
  private def scannedConflicts(table: String, cs: BranchChangeSet, db: String, dir: Path) =
    val list             = Cols.mkString(", ")
    def rowOf(a: String) = Cols.map(c => s"$a.$c").mkString("(", ", ", ")")
    val anyCell          = Cols
      .map(c =>
        s"(th.$c IS DISTINCT FROM ba.$c AND ou.$c IS DISTINCT FROM ba.$c AND ou.$c IS DISTINCT FROM th.$c)"
      )
      .mkString(" OR ")
    val (oursRow, theirsRow, baseRow) = (rowOf("ou"), rowOf("th"), rowOf("ba"))
    duck(
      s"""CREATE TEMP TABLE ba AS SELECT rowid AS r, $list FROM lake.tpch1.$table AT (VERSION => ${cs.forkSnapshot});
         |CREATE TEMP TABLE ou AS SELECT rowid AS r, $list FROM lake.tpch1.$table AT (VERSION => ${cs.mainSnapshot});
         |CREATE TEMP TABLE th AS SELECT rowid AS r, $list FROM qod_branch.tpch1.$table AT (VERSION => ${cs.headSnapshot});
         |SELECT
         |  count(*) FILTER (ou.r IS NOT NULL AND th.r IS NOT NULL AND ($anyCell)),
         |  count(*) FILTER (ou.r IS NOT NULL AND th.r IS NULL AND $oursRow IS DISTINCT FROM $baseRow),
         |  count(*) FILTER (ou.r IS NULL AND th.r IS NOT NULL AND $theirsRow IS DISTINCT FROM $baseRow)
         |FROM ba LEFT JOIN ou ON ba.r = ou.r LEFT JOIN th ON ba.r = th.r;
         |""".stripMargin,
      Some(db),
      Some(dir)
    )

  /** The oracle's first check, then the rows born after the fork as a key leaves them: `main` drops
    * the branch's rows that match, `branch` main's. Lines `kept,<wrong>`,
    * `key,<branch's rows>,<main's rows>` and `post_fork,<wrong>`.
    */
  private def keyedScan(
      table: String,
      key: List[String],
      cs: BranchChangeSet,
      policy: Option[ConflictPolicy],
      db: String,
      dir: Path
  ): List[String] =
    val oracle = ScanOracle.statements(
      "tpch1",
      table,
      Cols,
      cs.forkSnapshot,
      cs.mainSnapshot,
      cs.headSnapshot,
      policy
    )
    val list     = Cols.mkString(", ")
    val matches  = key.map(k => s"th.$k = ou.$k").mkString(" AND ")
    val oursLeft =
      if policy.contains(ConflictPolicy.Branch) then " WHERE r NOT IN (SELECT mr FROM hits)" else ""
    val theirsLeft =
      if policy.contains(ConflictPolicy.Main) then " WHERE r NOT IN (SELECT tr FROM hits)" else ""
    val expected =
      s"(SELECT $list FROM born_ou$oursLeft UNION ALL SELECT $list FROM born_th$theirsLeft " +
        s"UNION ALL SELECT $list FROM scan_expected WHERE fate = 'returned')"
    val actual = s"(SELECT $list FROM scan_after WHERE r >= getvariable('n'))"
    duck(
      (oracle.dropRight(1) ++ List(
        "CREATE TEMP TABLE born_ou AS SELECT * FROM scan_ours WHERE r >= getvariable('n')",
        "CREATE TEMP TABLE born_th AS SELECT * FROM scan_theirs WHERE r >= getvariable('n')",
        s"CREATE TEMP TABLE hits AS SELECT th.r AS tr, ou.r AS mr FROM born_th th JOIN born_ou ou ON $matches",
        "SELECT 'key', count(DISTINCT tr), count(DISTINCT mr) FROM hits",
        s"SELECT 'post_fork', count(*) FROM (($actual EXCEPT ALL $expected) UNION ALL ($expected EXCEPT ALL $actual))"
      )).mkString("", ";\n", ";"),
      Some(db),
      Some(dir)
    )

  /** What a scan finds wrong with `outcome`, as text. */
  private def wrongIn(outcome: Outcome, how: How, db: String, dir: Path): List[String] =
    val cs = outcome.cs
    cs.tables.filter(_.kind == ChangeKind.Modified).zip(outcome.counts).flatMap { (t, c) =>
      val merged  = s"${c.cellConflicts},${c.updateDeleteConflicts},${c.deleteUpdateConflicts}"
      val scanned = scannedConflicts(t.table, cs, db, dir)
      val counted = Option.when(scanned != List(merged))(
        s"${t.table}: conflicts by scan $scanned, by the merge $merged"
      )
      val written =
        if !outcome.applied then None
        else
          how.keys.get(s"tpch1.${t.table}") match
            case None =>
              val found = scanCheck(
                "tpch1",
                t.table,
                Cols,
                cs.forkSnapshot,
                cs.mainSnapshot,
                cs.headSnapshot,
                how.policy,
                db,
                dir
              )
              Option.when(found != AllRight)(s"${t.table}: $found, counts $c")
            case Some(key) =>
              val found = keyedScan(t.table, key, cs, how.policy, db, dir)
              val want  = List("kept,0", s"key,${c.keyMatches},${c.mainRowsByKey}", "post_fork,0")
              Option.when(found != want)(s"${t.table} key $key: $found, not $want, counts $c")
      counted.toList ++ written.toList
    }

  /** Runs each history, keeping what went wrong with its seed and its history. */
  private final class Histories(seeds: Iterable[Long]):
    private val found = ListBuffer.empty[String]

    def each(history: Long => (String, () => List[String])): Unit =
      seeds.foreach { seed =>
        val (where, run) = history(seed)
        try run().foreach(wrong => found += s"$where\n-- what is wrong\n$wrong")
        catch
          case e: TestCanceledException => throw e
          case NonFatal(e)              => found += s"$where\n-- what is wrong\n${e.getMessage}"
      }
      if found.nonEmpty then
        fail(s"${found.size} of ${seeds.size} histories disagree:\n\n" + found.mkString("\n\n"))

  "a random history" should "merge as a full scan says" in {
    var applied   = 0
    var conflicts = 0L
    var keyed     = 0
    var matched   = 0L
    var retried   = 0
    var split     = 0
    new Histories(FirstSeed until FirstSeed + Runs - Runs / 2).each { seed =>
      val r           = new Random(seed)
      val policy      = policyOf(r)
      val main        = new Writes(r, "lake", 2000000L)
      val branch      = new Writes(r, "qod_branch", 3000000L)
      val mainSql     = main.ops(2 + r.nextInt(7))
      val branchSql   = branch.ops(2 + r.nextInt(7))
      val partitioned = r.nextInt(3) == 0
      val keys        =
        if r.nextBoolean() then
          Map("tpch1.fact" -> (if r.nextBoolean() then List("status") else List("status", "note")))
        else Map.empty[String, List[String]]
      val how = How(
        policy,
        keys,
        warm = r.nextBoolean(),
        mainMeanwhile = Option.when(r.nextBoolean())(main.ops(1 + r.nextInt(4))),
        claimedUpdateRows = Option.when(r.nextInt(3) == 0)(2000000L * (2L + r.nextInt(9)))
      )
      val where =
        s"seed=$seed policy=$policy partitioned=$partitioned keys=$keys warm=${how.warm} " +
          s"claimedUpdateRows=${how.claimedUpdateRows}\n-- main\n$mainSql\n-- branch\n$branchSql" +
          s"\n-- main, between the two Decides\n${how.mainMeanwhile.getOrElse("(no second Decide)")}"
      (
        where,
        () =>
          withCatalog(s"fz$seed", catalogSql(partitioned)) { (_, _) =>
            val parent = parentReader
            try
              withBranchDb { (branchDb, branchDir) =>
                val fork = cloneBranch(branchDb, branchDir)
                duck(mainSql)
                duck(branchSql, Some(branchDb), Some(branchDir))
                val br = branchReader(branchDb)
                try
                  if BranchChanges.compute(br, parent, fork).tables.isEmpty then Nil
                  else
                    merge(branchDb, branchDir, br, parent, fork, how, "merge [fuzz]") match
                      case Left(e)    => List(s"the merge failed: ${e.take(500)}")
                      case Right(out) =>
                        if out.applied then applied += 1
                        val onKey = out.counts.filter(c => keys.contains(s"tpch1.${c.table}"))
                        if out.applied && onKey.nonEmpty then keyed += 1
                        matched += onKey.map(_.keyMatches).sum
                        if how.mainMeanwhile.isDefined then retried += 1
                        if how.claimedUpdateRows.isDefined && out.applied &&
                          out.counts.exists(_.toUpdate > 0L)
                        then split += 1
                        conflicts += out.conflicts
                        wrongIn(out, how, branchDb, branchDir)
                finally br.close()
              }
            finally parent.close()
          }
      )
    }
    info(
      s"${Runs - Runs / 2} histories: $applied applied, $keyed of them with a key, " +
        s"$matched key matches, $retried decided twice, $split in ranges, $conflicts conflicts"
    )
    // The seeds are fixed, so these move only with the generator or the merge: a run that reached
    // none of them would prove little.
    List(applied, keyed, retried, split).foreach(_ should be > 0)
    List(matched, conflicts).foreach(_ should be > 0L)
  }

  "random sibling branches" should "merge in sequence as a full scan says, each seeing the last as main's" in {
    var applied = 0
    new Histories(FirstSeed until FirstSeed + Runs / 2).each { seed =>
      val r           = new Random(seed * 31L + 7L)
      val first       = How(policyOf(r).orElse(Some(ConflictPolicy.Main)))
      val second      = How(policyOf(r).orElse(Some(ConflictPolicy.Branch)))
      val main        = new Writes(r, "lake", 2000000L)
      val writes1     = new Writes(r, "qod_branch", 3000000L)
      val writes2     = new Writes(r, "qod_branch", 4000000L)
      val mainSql     = main.ops(1 + r.nextInt(4))
      val sql1        = writes1.ops(2 + r.nextInt(5))
      val sql2        = writes2.ops(2 + r.nextInt(5))
      val partitioned = r.nextInt(3) == 0
      val where       =
        s"sibling seed=$seed policies=${first.policy},${second.policy} partitioned=$partitioned" +
          s"\n-- main\n$mainSql\n-- first branch\n$sql1\n-- second branch\n$sql2"
      (
        where,
        () =>
          withCatalog(s"fs$seed", catalogSql(partitioned)) { (_, _) =>
            val parent = parentReader
            try
              withNamedBranchDb("__s1") { (db1, dir1) =>
                withNamedBranchDb("__s2") { (db2, dir2) =>
                  val fork = cloneBranch(db1, dir1)
                  cloneBranch(db2, dir2) shouldBe fork
                  duck(mainSql)
                  duck(sql1, Some(db1), Some(dir1))
                  duck(sql2, Some(db2), Some(dir2))
                  def land(db: String, dir: Path, how: How, message: String): List[String] =
                    val br = branchReader(db)
                    try
                      if BranchChanges.compute(br, parent, fork).tables.isEmpty then Nil
                      else
                        merge(db, dir, br, parent, fork, how, message) match
                          case Left(e)    => List(s"$message failed: ${e.take(500)}")
                          case Right(out) =>
                            if out.applied then applied += 1
                            wrongIn(out, how, db, dir).map(w => s"$message: $w")
                    finally br.close()
                  land(db1, dir1, first, "merge first") ++ land(db2, dir2, second, "merge second")
                }
              }
            finally parent.close()
          }
      )
    }
    info(s"${Runs / 2} sibling histories, $applied merges applied")
    applied should be > 0
  }
