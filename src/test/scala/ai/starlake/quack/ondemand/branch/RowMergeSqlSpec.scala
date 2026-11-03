package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.ondemand.catalog.TableVersion
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pure rendering. What the SQL computes is proved by `ThreeWayMergeLiveSpec` against a full scan;
  * this pins the properties that are invisible in results and expensive to lose: what is read, and
  * in which order things are written.
  */
class RowMergeSqlSpec extends AnyFlatSpec with Matchers:

  private val t = RowMergeTable(
    index = 0,
    schema = "s",
    table = "t",
    columns = List("id", "note"),
    keyColumns = Nil,
    branchRanges = List(SnapshotRange(11L, 12L)),
    mainRanges = List(SnapshotRange(11L, 11L), SnapshotRange(13L, 14L))
  )

  private def statementFor(stmts: List[String], name: String): String =
    stmts.find(_.contains(s"t0_$name")).getOrElse(fail(s"no statement for $name in $stmts"))

  "branchSide" should "read only rowids and snapshots for the net changes" in {
    val net = statementFor(RowMergeSql.branchSide("qod_branch", "lake", t, 10L), "net_b")
    net should include(
      "SELECT snapshot_id, rowid FROM ducklake_table_deletions('qod_branch', 's', 't', 11, 12)"
    )
    net should include(
      "SELECT snapshot_id, rowid FROM ducklake_table_insertions('qod_branch', 's', 't', 11, 12)"
    )
    net should not include "\"note\""
    // Without coalesce a delete-only row reads NULL here and drops out of WHERE NOT born_after_fork.
    net should include("coalesce(i.first_ins < d.first_del, false)")
    net should include("coalesce(d.last_del > i.last_ins, false)")
  }

  it should "name values by position, so no user column collides with an internal one" in {
    val post = statementFor(RowMergeSql.branchSide("qod_branch", "lake", t, 10L), "post_b")
    post should include("""p."id" AS v0, p."note" AS v1""")
    post should include("p.snapshot_id = n.last_ins")
  }

  it should "create the base table without reading it" in {
    val base = statementFor(RowMergeSql.branchSide("qod_branch", "lake", t, 10L), "base")
    base should include("CREATE TABLE IF NOT EXISTS")
    base should include("AT (VERSION => 10)")
    base should include("WHERE false")
  }

  it should "render empty typed tables when a side has no snapshots to read" in {
    val none = t.copy(branchRanges = Nil)
    val all  = RowMergeSql.branchSide("qod_branch", "lake", none, 10L).mkString("\n")
    all should not include "ducklake_table_"
    all should include("(rowid BIGINT, born_after_fork BOOLEAN, gone BOOLEAN, last_ins BIGINT)")
  }

  "mainSide" should "union the ranges that skip maintenance" in {
    val net = statementFor(RowMergeSql.mainSide("lake", t), "net_m")
    net should include("ducklake_table_deletions('lake', 's', 't', 11, 11)")
    net should include("ducklake_table_deletions('lake', 's', 't', 13, 14)")
    net should include(" UNION ALL ")
    RowMergeSql.mainSide("lake", t.copy(mainRanges = Nil)).mkString("\n") should
      not include "ducklake_table_"
  }

  it should "keep only rows that existed at the fork in the overlap" in {
    val ov = statementFor(RowMergeSql.mainSide("lake", t), "overlap")
    ov should include("NOT m.born_after_fork AND NOT b.born_after_fork")
  }

  it should "list only the original values not fetched yet" in {
    val need = RowMergeSql.mainSide("lake", t).last
    need should include("t0_need")
    need should include("EXCEPT")
    need should include("t0_base")
  }

  "topUp" should "read the fork by rowid from a plain table, the shape whose pruning was measured" in {
    val sql = RowMergeSql.topUp("lake", t, 10L)
    sql should startWith("INSERT INTO")
    sql should include(
      """FROM "lake"."s"."t" AT (VERSION => 10) WHERE rowid IN (SELECT rowid FROM"""
    )
    sql should include("t0_need")
  }

  "values" should "not read main's insertions when there is no overlap" in {
    val post = statementFor(RowMergeSql.values("lake", t, 10L, overlap = 0L, None), "post_m")
    post should not include "ducklake_table_insertions"
    post should include("WHERE false")
    val read = statementFor(RowMergeSql.values("lake", t, 10L, overlap = 5L, None), "post_m")
    read should include("ducklake_table_insertions('lake', 's', 't', 11, 11)")
  }

  it should "give a conflicting cell to the side the policy names" in {
    def merged(p: Option[ConflictPolicy]) =
      statementFor(RowMergeSql.values("lake", t, 10L, 5L, p), "merged")
    merged(Some(ConflictPolicy.Branch)) should include("ELSE b.v1 END AS v1")
    merged(Some(ConflictPolicy.Main)) should include("ELSE m.v1 END AS v1")
  }

  it should "compare cells with IS DISTINCT FROM, so NULLs compare equal" in {
    val sql = statementFor(RowMergeSql.values("lake", t, 10L, 5L, None), "merged")
    sql should include("b.v0 IS NOT DISTINCT FROM f.v0")
    sql should include("m.v0 IS DISTINCT FROM f.v0")
    sql should include("'conflict_cell'")
    sql should include("'conflict_update_delete'")
    sql should include("'conflict_delete_update'")
  }

  it should "update conflicting rows only under a policy" in {
    val none = statementFor(RowMergeSql.values("lake", t, 10L, 5L, None), "upd")
    none should not include "conflict_cell"
    val some =
      statementFor(RowMergeSql.values("lake", t, 10L, 5L, Some(ConflictPolicy.Main)), "upd")
    some should include("conflict_cell")
  }

  it should "delete a row main updated only when the branch wins" in {
    val main =
      statementFor(RowMergeSql.values("lake", t, 10L, 5L, Some(ConflictPolicy.Main)), "del")
    main should not include "conflict_update_delete"
    val br =
      statementFor(RowMergeSql.values("lake", t, 10L, 5L, Some(ConflictPolicy.Branch)), "del")
    br should include("conflict_update_delete")
  }

  it should "bring a row main deleted back from the branch's own row, when the branch wins" in {
    val ins =
      statementFor(RowMergeSql.values("lake", t, 10L, 5L, Some(ConflictPolicy.Branch)), "ins")
    // From post_b, the branch's own row. merged's first cell case gives the same values there.
    ins should include("conflict_delete_update")
    ins should include("t0_post_b")
    val main =
      statementFor(RowMergeSql.values("lake", t, 10L, 5L, Some(ConflictPolicy.Main)), "ins")
    main should not include "conflict_delete_update"
  }

  private val keyed = t.copy(keyColumns = List("note"))

  it should "render key tables only for a table with a key" in {
    RowMergeSql.values("lake", t, 10L, 5L, None).mkString("\n") should not include "key_hits"
    val sql = statementFor(RowMergeSql.values("lake", keyed, 10L, 5L, None), "key_hits")
    sql should include("b.v1 = m.k0") // plain =, so a NULL key never matches
    sql should include("born_after_fork")
  }

  it should "resolve key matches by the policy" in {
    val main = RowMergeSql.values("lake", keyed, 10L, 5L, Some(ConflictPolicy.Main))
    statementFor(main, "ins") should include("NOT IN (SELECT b_rowid FROM")
    statementFor(main, "del") should not include "m_rowid"
    val br = RowMergeSql.values("lake", keyed, 10L, 5L, Some(ConflictPolicy.Branch))
    statementFor(br, "del") should include("m_rowid")
    statementFor(br, "ins") should not include "b_rowid"
  }

  private val counts =
    RowCounts(
      "s",
      "t",
      toUpdate = 10L,
      toDelete = 2L,
      toInsert = 3L,
      minUpdateRowid = 5L,
      maxUpdateRowid = 99L
    )

  "applyStatements" should "delete, then update, then insert" in {
    val stmts =
      RowMergeSql.applyStatements("lake", t, counts, ranged = false, updateChunkRows = 100L)
    stmts.map(_.takeWhile(_ != ' ')) shouldBe List("DELETE", "UPDATE", "INSERT")
  }

  it should "set every column, so the update reads no column" in {
    val update = RowMergeSql
      .applyStatements("lake", t, counts, false, 100L)
      .find(_.startsWith("UPDATE"))
      .get
    update should include(""""id" = u.v0, "note" = u.v1""")
    update should include("t.rowid = u.rowid")
  }

  it should "not render a statement with an empty set: it would read the whole table" in {
    RowMergeSql
      .applyStatements("lake", t, counts.copy(toDelete = 0L), false, 100L)
      .map(_.takeWhile(_ != ' ')) shouldBe List("UPDATE", "INSERT")
    RowMergeSql
      .applyStatements("lake", t, counts.copy(toUpdate = 0L), false, 100L)
      .map(_.takeWhile(_ != ' ')) shouldBe List("DELETE", "INSERT")
    RowMergeSql.applyStatements("lake", t, RowCounts("s", "t"), false, 100L) shouldBe Nil
  }

  it should "split the update into rowid ranges above the chunk size, or when asked" in {
    def updates(c: RowCounts, ranged: Boolean, chunk: Long) =
      RowMergeSql.applyStatements("lake", t, c, ranged, chunk).filter(_.startsWith("UPDATE"))
    updates(counts, ranged = false, chunk = 100L).size shouldBe 1
    updates(counts, ranged = false, chunk = 9L).size shouldBe 1 // one range covers these rowids
    val big  = counts.copy(toUpdate = 5000000L, maxUpdateRowid = 32000004L)
    val many = updates(big, ranged = false, chunk = 100L)
    many.size should be > 1
    // The predicate on t.rowid bounds the scan side the planner may build on after the first UPDATE.
    many.foreach { u =>
      u should include("WHERE t.rowid = u.rowid AND t.rowid >= ")
      u should include("AND t.rowid < ")
      // And the source side: a statement holding the whole set would need the memory ranges save.
      u should include("WHERE rowid >= ")
    }
    updates(big, ranged = true, chunk = 1000000000L).size shouldBe many.size
  }

  "updateRanges" should "cover every rowid once, within the width and row targets" in {
    val ranges = RowMergeSql.updateRanges(100L, 64000099L, 3000000L)
    ranges.head._1 shouldBe 100L
    ranges.last._2 shouldBe 64000100L
    ranges.zip(ranges.tail).foreach((a, b) => a._2 shouldBe b._1)
    ranges.size shouldBe 4 // 64M rowids at 16M wide; 3M rows would need only 2
    ranges.foreach((lo, hi) => (hi - lo) should be <= RowMergeSql.MaxRangeWidth)
    RowMergeSql.updateRanges(5L, 5L, 1L) shouldBe List((5L, 6L))
    RowMergeSql.updateRanges(0L, 999L, 10000000L).size shouldBe 5 // rows decide: 10M at 2M each
  }

  it should "keep its edges right where span * n passes a Long" in {
    val lo     = 30000000000000L
    val span   = 20000000000000L
    val ranges = RowMergeSql.updateRanges(lo, lo + span - 1L, 1L)
    ranges.size shouldBe 1250000 // 2e13 rowids at 16M wide
    ranges.head._1 shouldBe lo
    ranges.last._2 shouldBe lo + span
    ranges.zip(ranges.tail).forall((a, b) => a._2 == b._1) shouldBe true
    ranges.forall((a, b) => b > a && b - a <= RowMergeSql.MaxRangeWidth) shouldBe true
  }

  "parseSizes" should "read the two sizes, and refuse an answer it cannot read" in {
    RowMergeSql.parseSizes(List(List(Json.fromLong(3L), Json.fromLong(1L)))) shouldBe
      Right(RowMergeSql.OverlapSizes(3L, 1L))
    RowMergeSql.parseSizes(Nil).isLeft shouldBe true
    RowMergeSql.parseSizes(List(List(Json.Null, Json.fromLong(1L)))).isLeft shouldBe true
  }

  "parseCounts" should "read the counts row and the samples" in {
    val row = (1L to 10L).toList.map(Json.fromLong) ++ List(Json.fromLong(0L), Json.fromLong(4L))
    val c   = RowMergeSql
      .parseCounts(
        t,
        List(row),
        List(
          List(Json.fromString("cell"), Json.fromLong(70L)),
          List(Json.fromString("cell"), Json.fromLong(71L)),
          List(Json.fromString("key"), Json.fromLong(8L))
        )
      )
      .fold(e => fail(e), identity)
    (c.toUpdate, c.toDelete, c.toInsert) shouldBe (1L, 2L, 3L)
    (c.cellConflicts, c.updateDeleteConflicts, c.deleteUpdateConflicts) shouldBe (4L, 5L, 6L)
    c.keyMatches shouldBe 7L
    (c.minUpdateRowid, c.maxUpdateRowid) shouldBe (8L, 9L)
    c.mainRowsByKey shouldBe 10L
    c.columnConflicts shouldBe Map("note" -> 4L)
    c.samples shouldBe Map("cell" -> List(70L, 71L), "key" -> List(8L))
  }

  it should "read NULL bounds of an empty update set as zero" in {
    val row = List.fill(7)(Json.fromLong(0L)) ++ List(Json.Null, Json.Null) ++ List.fill(3)(
      Json.fromLong(0L)
    )
    RowMergeSql.parseCounts(t, List(row), Nil).map(_.minUpdateRowid) shouldBe Right(0L)
  }

  it should "refuse an update with rows whose bounds cannot be read: its ranges would match nothing" in {
    def counted(toUpdate: Long, lo: Json, hi: Json) =
      val rest = List.fill(3)(Json.fromLong(0L))
      val row  = Json.fromLong(toUpdate) :: List.fill(6)(Json.fromLong(0L)) ++ List(lo, hi) ++ rest
      RowMergeSql.parseCounts(t, List(row), Nil)
    val five = Json.fromLong(5L)
    counted(1L, Json.Null, Json.Null).isLeft shouldBe true
    counted(1L, five, Json.Null).isLeft shouldBe true
    counted(1L, Json.Null, five).isLeft shouldBe true
    counted(1L, Json.fromString("x"), five).isLeft shouldBe true
    counted(1L, five, five).map(c => (c.minUpdateRowid, c.maxUpdateRowid)) shouldBe Right((5L, 5L))
    counted(0L, Json.Null, Json.Null).map(c => (c.toUpdate, c.minUpdateRowid)) shouldBe Right(
      (0L, 0L)
    )
  }

  it should "refuse counts it cannot read: zeros would hide main's changes" in {
    RowMergeSql.parseCounts(t, Nil, Nil).isLeft shouldBe true
    RowMergeSql.parseCounts(t, List(List.fill(8)(Json.fromLong(0L))), Nil).isLeft shouldBe true
    val unreadable = Json.Null :: List.fill(11)(Json.fromLong(0L))
    RowMergeSql.parseCounts(t, List(unreadable), Nil).isLeft shouldBe true
    val noMainCount =
      List.fill(9)(Json.fromLong(0L)) ++ (Json.Null :: List.fill(2)(Json.fromLong(0L)))
    RowMergeSql.parseCounts(t, List(noMainCount), Nil).isLeft shouldBe true
  }

  "counts" should "read the branch's and main's key rows apart, in the positions parseCounts reads" in {
    val sql = RowMergeSql.counts(keyed)
    sql.indexOf("count(DISTINCT b_rowid)") should be >= 0
    sql.indexOf("count(DISTINCT m_rowid)") should be > sql.indexOf("min(rowid)")
    RowMergeSql.counts(t) should not include "key_hits"
  }

  "the merge's statements" should "hold no cast: MergeDecision.failureText relies on that" in {
    val all = RowMergeSql.branchSide("qod_branch", "lake", keyed, 10L) ++
      RowMergeSql.mainSide("lake", keyed) ++
      List(RowMergeSql.topUp("lake", keyed, 10L), RowMergeSql.overlapSizes(keyed)) ++
      RowMergeSql.values("lake", keyed, 10L, 5L, Some(ConflictPolicy.Branch)) ++
      List(RowMergeSql.counts(keyed), RowMergeSql.samples(keyed)) ++
      RowMergeSql.applyStatements("lake", keyed, counts, false, 100L)
    all.foreach { s =>
      s should not include "CAST("
      s should not include "TRY_CAST"
      s should not include "::"
    }
  }

  "identifiers and literals" should "be quoted and escaped" in {
    val odd = t.copy(schema = "s'x", table = "t\"y", columns = List("c\"1"))
    val all = RowMergeSql.branchSide("qod_branch", "p\"q", odd, 10L)
    all.mkString("\n") should include("'s''x', 't\"y'")
    all.mkString("\n") should include(""""c""1" AS v0""")
    RowMergeSql.topUp("p\"q", odd, 10L) should include(""""p""q"."s'x"."t""y"""")
  }

  private final class Fake(changes: List[(Long, String)], cols: List[String] = List("id", "note"))
      extends ChangeSource:
    def maxSnapshotId(): Option[Long]                                 = changes.lastOption.map(_._1)
    def tableVersions(): List[TableVersion]                           = Nil
    def snapshotChangesSince(fork: Long): List[(Long, String)]        = changes.filter(_._1 > fork)
    def columnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      cols.zipWithIndex.map((c, i) => CatalogColumnEntry(i, c, "VARCHAR", true, false))
    def topLevelColumnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      columnsOfTableAt(id, n)
    def droppedSchemasSince(fork: Long): Set[String] = Set.empty

  private def changeSet(tables: TableChange*) =
    BranchChangeSet(10L, 12L, 13L, tables.toList, Nil, Nil)

  /** A catalog's snapshot rows from the fork (10) to `head`, each empty unless `verbs` gives it
    * some: both real catalogs hold the fork's own row.
    */
  private def rows(head: Long, verbs: (Long, String)*): List[(Long, String)] =
    val byId = verbs.toMap
    (10L to head).toList.map(id => id -> byId.getOrElse(id, ""))

  /** `catalog` after a manual expiry of the snapshots `ids`. */
  private def expired(catalog: List[(Long, String)], ids: Long*): List[(Long, String)] =
    catalog.filterNot(r => ids.contains(r._1))

  "RowMergeTable.plan" should "take row-merged tables only, with their ranges, columns and keys" in {
    // The fork's own row carries a data touch: it is the fork, not a change since it.
    val branch = new Fake(
      rows(
        12L,
        10L -> "inserted_into_table:2",
        11L -> "inserted_into_table:2",
        12L -> "deleted_from_table:2"
      )
    )
    val main = new Fake(
      rows(
        13L,
        10L -> "inserted_into_table:2",
        11L -> "rewrite_delete:2",
        12L -> "inlined_insert:2",
        13L -> "inlined_delete:2"
      )
    )
    val cs = changeSet(
      TableChange("s", "made", ChangeKind.Created),
      TableChange("s", "t", ChangeKind.Modified, tableId = Some(2L))
    )
    RowMergeTable.plan(cs, branch, main, Map("s.t" -> List("note"))) shouldBe Right(
      List(
        RowMergeTable(
          0,
          "s",
          "t",
          List("id", "note"),
          List("note"),
          List(SnapshotRange(11L, 12L)),
          List(SnapshotRange(12L, 13L))
        )
      )
    )
  }

  it should "refuse a snapshot gone from main, the fork itself included, naming main" in {
    val branch = new Fake(rows(12L, 11L -> "inserted_into_table:2"))
    val cs     = changeSet(TableChange("s", "t", ChangeKind.Modified, tableId = Some(2L)))
    def plan(main: List[(Long, String)]) = RowMergeTable.plan(cs, branch, new Fake(main), Map.empty)
    plan(rows(13L)).isRight shouldBe true
    plan(expired(rows(13L), 12L)) shouldBe Left(RowMergeTable.Problem.HistoryGap("main", 12L))
    // Prepare reads the table AT the fork, so its row is part of the history. The lowest missing
    // id is the one named.
    plan(expired(rows(13L), 10L)) shouldBe Left(RowMergeTable.Problem.HistoryGap("main", 10L))
    plan(expired(rows(13L), 10L, 12L)) shouldBe Left(RowMergeTable.Problem.HistoryGap("main", 10L))
  }

  it should "refuse a snapshot gone from the branch, the fork itself included, naming the branch" in {
    val main = new Fake(rows(13L))
    val cs   = changeSet(TableChange("s", "t", ChangeKind.Modified, tableId = Some(2L)))
    def plan(branch: List[(Long, String)]) =
      RowMergeTable.plan(cs, new Fake(branch), main, Map.empty)
    plan(rows(12L, 11L -> "inserted_into_table:2")).isRight shouldBe true
    // The snapshot that touched `t` is the one that is gone: nothing left says that it changed.
    plan(expired(rows(12L, 11L -> "inserted_into_table:2"), 11L)) shouldBe
      Left(RowMergeTable.Problem.HistoryGap("branch", 11L))
    plan(expired(rows(12L), 10L)) shouldBe Left(RowMergeTable.Problem.HistoryGap("branch", 10L))
    plan(expired(rows(12L), 10L, 11L)) shouldBe
      Left(RowMergeTable.Problem.HistoryGap("branch", 10L))
  }

  it should "check main first, and check the history when no table merges by rows" in {
    // The branch created a table, and its only other touch was in a snapshot since expired there:
    // the change set holds nothing to read by rows, and would merge without that touch.
    val cs     = changeSet(TableChange("s", "made", ChangeKind.Created))
    val branch = rows(12L, 12L -> """created_table:"s"."made"""")
    def plan(b: List[(Long, String)], m: List[(Long, String)]) =
      RowMergeTable.plan(cs, new Fake(b), new Fake(m), Map.empty)
    plan(branch, rows(13L)) shouldBe Right(Nil)
    plan(expired(branch, 11L), rows(13L)) shouldBe
      Left(RowMergeTable.Problem.HistoryGap("branch", 11L))
    plan(expired(branch, 11L), expired(rows(13L), 12L)) shouldBe
      Left(RowMergeTable.Problem.HistoryGap("main", 12L))
  }

  it should "refuse a snapshot mixing maintenance and data" in {
    val branch = new Fake(rows(12L, 11L -> "inserted_into_table:2"))
    val cs     = changeSet(TableChange("s", "t", ChangeKind.Modified, tableId = Some(2L)))
    RowMergeTable.plan(
      cs,
      branch,
      new Fake(rows(13L, 12L -> "rewrite_delete:2,inserted_into_table:2")),
      Map.empty
    ) shouldBe Left(RowMergeTable.Problem.MixedSnapshots("s", "t", List(12L)))
  }

  it should "fail loudly on a modified table with no id, which only a bug can produce" in {
    val cs = changeSet(TableChange("s", "t", ChangeKind.Modified))
    an[IllegalArgumentException] should be thrownBy
      RowMergeTable.plan(cs, new Fake(rows(12L)), new Fake(rows(13L)), Map.empty)
  }

  it should "fail loudly on a table with a reserved column, which its callers must refuse first" in {
    // Such a column hides the row identifier from the UPDATE and the DELETE, not from the feed, so
    // the merge would write other rows than the ones it read.
    val cs = changeSet(
      TableChange("s", "t", ChangeKind.Modified, tableId = Some(2L), reservedColumn = Some("rowid"))
    )
    val thrown = the[IllegalArgumentException] thrownBy
      RowMergeTable.plan(cs, new Fake(rows(12L)), new Fake(rows(13L)), Map.empty)
    thrown.getMessage should include("s.t")
    thrown.getMessage should include("rowid")
  }
