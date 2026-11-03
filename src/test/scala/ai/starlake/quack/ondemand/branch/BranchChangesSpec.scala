package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.ondemand.catalog.TableVersion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pure classification over hand-built metadata (no Postgres). */
class BranchChangesSpec extends AnyFlatSpec with Matchers:

  private def col(
      name: String,
      typ: String = "INTEGER",
      nullable: Boolean = true,
      ordinal: Int = 0
  ) = CatalogColumnEntry(ordinal, name, typ, nullable, isPrimaryKey = false)

  /** A fake catalog: `head` snapshot, table versions, changes after the fork, and per-(table,
    * snapshot) column shapes (default: one column `x`, unchanged). `columns` also lists fields
    * nested in a STRUCT, LIST or MAP; `topLevel` is the table's own columns where they differ from
    * it (default: the same list). `dropped` is the schemas dropped after the fork.
    */
  private final class Fake(
      head: Long,
      versions: List[TableVersion],
      changes: List[(Long, String)],
      columns: Map[(Long, Long), List[CatalogColumnEntry]] = Map.empty,
      topLevel: Map[(Long, Long), List[CatalogColumnEntry]] = Map.empty,
      dropped: Set[String] = Set.empty
  ) extends ChangeSource:
    def maxSnapshotId(): Option[Long]                          = Some(head)
    def tableVersions(): List[TableVersion]                    = versions
    def snapshotChangesSince(fork: Long): List[(Long, String)] = changes.filter(_._1 > fork)
    def columnsOfTableAt(tableId: Long, n: Long): List[CatalogColumnEntry] =
      columns.getOrElse((tableId, n), List(col("x")))
    def topLevelColumnsOfTableAt(tableId: Long, n: Long): List[CatalogColumnEntry] =
      topLevel.getOrElse((tableId, n), columnsOfTableAt(tableId, n))
    def droppedSchemasSince(fork: Long): Set[String] = dropped

  private val fork   = 10L
  private val region = TableVersion(2L, "tpch1", "region", 3L, None)
  private val nation = TableVersion(3L, "tpch1", "nation", 3L, None)

  "parseEntries" should "read numeric ids, quoted names and schema payloads" in {
    val es = BranchChanges.parseEntries(
      List((11L, """created_table:"tpch1"."extra",inlined_insert:2,created_schema:"s2""""))
    )
    es.map(e => (e.verb, e.tableId, e.ref, e.schemaName)) shouldBe List(
      ("created_table", None, Some(BranchChanges.Ref("tpch1", "extra")), None),
      ("inlined_insert", Some(2L), None, None),
      ("created_schema", None, None, Some("s2"))
    )
  }

  "classify" should "mark data-touched tables as modified and created/dropped by existence" in {
    val extra   = TableVersion(4L, "tpch1", "extra", 12L, None)
    val dropped = nation.copy(end = Some(13L))
    val fake    = new Fake(
      head = 13L,
      versions = List(region, dropped, extra),
      changes = List(
        (11L, "inlined_insert:2,inlined_delete:2"),
        (12L, """created_table:"tpch1"."extra",inlined_insert:4"""),
        (13L, "dropped_table:3")
      )
    )
    val (tables, unsupported) = BranchChanges.classify(fake, fork, 13L)
    unsupported shouldBe Nil
    tables.map(t => (t.table, t.kind)) shouldBe List(
      ("extra", ChangeKind.Created),
      ("nation", ChangeKind.Dropped),
      ("region", ChangeKind.Modified)
    )
  }

  it should "mark a recreated table and an altered table, and drop a created-then-dropped one" in {
    val nationOld = nation.copy(end = Some(11L))
    val nationNew = TableVersion(9L, "tpch1", "nation", 12L, None)
    val ghost     = TableVersion(8L, "tpch1", "ghost", 11L, Some(12L))
    val fake      = new Fake(
      head = 13L,
      versions = List(region, nationOld, nationNew, ghost),
      changes = List(
        (11L, """dropped_table:3,created_table:"tpch1"."ghost""""),
        (12L, """created_table:"tpch1"."nation",dropped_table:8"""),
        (13L, "altered_table:2")
      ),
      columns = Map((2L, 13L) -> List(col("x"), col("y")))
    )
    val (tables, _) = BranchChanges.classify(fake, fork, 13L)
    // The merge fence is held to the table's id at the fork, so both carry it.
    tables.map(t => (t.table, t.kind, t.tableId)) shouldBe List(
      ("nation", ChangeKind.Recreated, Some(3L)),
      ("region", ChangeKind.Altered, Some(2L))
    )
    tables.find(_.table == "region").get.mergeable shouldBe false
  }

  it should "mark a modified table with a column named rowid, in any case, as not mergeable" in {
    val fake = new Fake(
      head = 11L,
      versions = List(region),
      changes = List((11L, "inlined_insert:2")),
      columns = Map(
        (2L, fork) -> List(col("RowId"), col("x")),
        (2L, 11L)  -> List(col("RowId"), col("x"))
      )
    )
    val (tables, _) = BranchChanges.classify(fake, fork, 11L)
    tables.map(t => (t.table, t.kind, t.reservedColumn, t.mergeable)) shouldBe
      List(("region", ChangeKind.Modified, Some("rowid"), false))
    tables.head.reason.get should include("column named rowid")
    // A table without one stays mergeable.
    val plain = new Fake(11L, List(region), List((11L, "inlined_insert:2")))
    BranchChanges.classify(plain, fork, 11L)._1.map(_.mergeable) shouldBe List(true)
  }

  it should "treat flush snapshots and compaction as no touch, and unknown table verbs as a touch" in {
    val fake = new Fake(
      head = 13L,
      versions = List(region, nation),
      changes = List(
        (11L, "deleted_from_table:2,inline_flush:2"), // flush artifact on region
        (12L, "merge_adjacent:3"),                    // compaction on nation
        (13L, "rewrote_table:3")                      // unknown verb: fail closed
      )
    )
    val (tables, unsupported) = BranchChanges.classify(fake, fork, 13L)
    unsupported shouldBe Nil
    tables.map(t => (t.table, t.kind)) shouldBe List(("nation", ChangeKind.Modified))
  }

  it should "report view and macro changes as unsupported" in {
    val fake = new Fake(
      head = 11L,
      versions = List(region),
      changes = List((11L, """created_view:"tpch1"."v1",inlined_insert:2"""))
    )
    val (tables, unsupported) = BranchChanges.classify(fake, fork, 11L)
    tables.map(_.table) shouldBe List("region")
    unsupported shouldBe List("""created_view:"tpch1"."v1"""")
  }

  "conflicts" should "leave rows changed on both sides to the row merge and flag a dropped schema" in {
    val branchTables = List(
      TableChange("tpch1", "region", ChangeKind.Modified),
      TableChange("tpch1", "nation", ChangeKind.Modified),
      TableChange("s2", "t", ChangeKind.Created)
    )
    val main = new Fake(
      head = 14L,
      versions = List(region, nation),
      changes = List(
        (11L, "inlined_insert:2"),                    // touches region: a row merge, no conflict
        (12L, "inline_flush:3,deleted_from_table:3"), // flush of nation: not a touch
        (13L, "dropped_schema:5"),                    // a schema id, as DuckDB commits it
        (14L, """created_schema:"s3"""")
      ),
      dropped = Set("s2")
    )
    BranchChanges
      .conflicts(branchTables, main, fork)
      .map(c => (c.schema, c.table, c.reason)) shouldBe
      List(("s2", "t", "schema 's2' was dropped on main"))
  }

  it should "flag a schema change, a drop, a recreate or a same-name create on main" in {
    val ghost    = TableVersion(4L, "tpch1", "ghost", 3L, Some(12L))
    val ghostNew = TableVersion(9L, "tpch1", "ghost", 12L, None)
    val created  = TableVersion(8L, "tpch1", "extra", 13L, None)
    val main     = new Fake(
      head = 14L,
      versions = List(region, nation.copy(end = Some(11L)), ghost, ghostNew, created),
      changes = List(
        (11L, "dropped_table:3"),
        (12L, """dropped_table:4,created_table:"tpch1"."ghost""""),
        (13L, """created_table:"tpch1"."extra""""),
        (14L, "altered_table:2")
      ),
      columns = Map((2L, 14L) -> List(col("x"), col("y")))
    )
    val branchTables = List(
      TableChange("tpch1", "extra", ChangeKind.Created),
      TableChange("tpch1", "ghost", ChangeKind.Modified),
      TableChange("tpch1", "nation", ChangeKind.Modified),
      TableChange("tpch1", "region", ChangeKind.Modified)
    )
    BranchChanges.conflicts(branchTables, main, fork).map(c => (c.table, c.reason)) shouldBe List(
      ("extra", "also created on main since the fork"),
      ("ghost", "recreated on main since the fork"),
      ("nation", "dropped on main since the fork"),
      ("region", "schema changed on main since the fork")
    )
  }

  it should "flag a branch drop, recreate or schema change against rows main changed" in {
    val ghost = TableVersion(4L, "tpch1", "ghost", 3L, None)
    val main  = new Fake(
      14L,
      List(region, nation, ghost),
      List((11L, "inlined_insert:2,inlined_insert:3,inlined_insert:4"))
    )
    val branchTables = List(
      TableChange("tpch1", "ghost", ChangeKind.Recreated),
      TableChange("tpch1", "nation", ChangeKind.Dropped),
      TableChange("tpch1", "region", ChangeKind.Altered)
    )
    BranchChanges.conflicts(branchTables, main, fork).map(c => (c.table, c.reason)) shouldBe List(
      ("ghost", "changed on main since the fork"),
      ("nation", "changed on main since the fork"),
      ("region", "changed on main since the fork")
    )
  }

  "compute" should "mark a table both sides changed as three-way, with no conflict" in {
    val branch = new Fake(12L, List(region), List((11L, "inlined_insert:2")))
    val main   = new Fake(15L, List(region), List((13L, "inlined_delete:2")))
    val cs     = BranchChanges.compute(branch, main, fork)
    cs.conflicts shouldBe Nil
    cs.tables.map(t => (t.table, t.kind, t.threeWay, t.tableId)) shouldBe
      List(("region", ChangeKind.Modified, true, Some(2L)))
    cs.mergeable shouldBe true
  }

  it should "report a table main renamed as a conflict, under the name the branch changed" in {
    val branch = new Fake(12L, List(region), List((11L, "inlined_insert:2")))
    val main   = new Fake(
      15L,
      List(
        TableVersion(2L, "tpch1", "region", 1L, Some(13L)),
        TableVersion(2L, "tpch1", "region2", 13L, None)
      ),
      // DuckDB commits a table rename as created_table under the new name.
      List((13L, """created_table:"tpch1"."region2""""))
    )
    val cs = BranchChanges.compute(branch, main, fork)
    cs.conflicts.map(c => (c.table, c.reason)) shouldBe
      List(("region", "schema changed on main since the fork"))
    cs.tables.map(_.threeWay) shouldBe List(false)
  }

  it should "leave a table only the branch changed as a plain row merge" in {
    val branch = new Fake(12L, List(region), List((11L, "inlined_insert:2")))
    val main   = new Fake(15L, List(region, nation), List((13L, "inlined_delete:3")))
    BranchChanges.compute(branch, main, fork).tables.map(_.threeWay) shouldBe List(false)
  }

  it should "give a created table no id and a dropped table its id at the fork" in {
    val extra   = TableVersion(4L, "tpch1", "extra", 12L, None)
    val dropped = nation.copy(end = Some(13L))
    val branch  = new Fake(
      13L,
      List(extra, dropped),
      List((12L, """created_table:"tpch1"."extra""""), (13L, "dropped_table:3"))
    )
    BranchChanges
      .compute(branch, new Fake(10L, List(nation), Nil), fork)
      .tables
      .map(t => (t.table, t.tableId)) shouldBe List(("extra", None), ("nation", Some(3L)))
  }

  it should "report a column main dropped and added again as a schema change" in {
    val branch = new Fake(12L, List(region), List((11L, "inlined_insert:2")))
    val main   = new Fake(
      head = 15L,
      versions = List(region),
      changes = List((13L, "altered_table:2"), (14L, "altered_table:2")),
      columns = Map(
        (2L, fork) -> List(col("a", ordinal = 1), col("c", ordinal = 2)),
        (2L, 15L)  -> List(col("a", ordinal = 1), col("c", ordinal = 3))
      )
    )
    val cs = BranchChanges.compute(branch, main, fork)
    cs.conflicts.map(c => (c.table, c.reason)) shouldBe
      List(("region", "schema changed on main since the fork"))
    cs.tables.map(_.threeWay) shouldBe List(false)
    cs.mergeable shouldBe false
  }

  /** Table 2 is `r`, renamed to `r2` at snapshot 12 and back at 14, with its rows written at 13:
    * DuckDB commits each rename as created_table under the new name, and the rows under the name
    * the table has at that moment.
    */
  private val viaR2 = new Fake(
    head = 14L,
    versions = List(
      TableVersion(2L, "tpch1", "r", 1L, Some(12L)),
      TableVersion(2L, "tpch1", "r2", 12L, Some(14L)),
      TableVersion(2L, "tpch1", "r", 14L, None)
    ),
    changes = List(
      (12L, """created_table:"tpch1"."r2""""),
      (13L, "inlined_insert:2"),
      (14L, """created_table:"tpch1"."r"""")
    )
  )

  it should "mark a table main wrote to under a temporary name as three-way" in {
    val branch = new Fake(
      12L,
      List(TableVersion(2L, "tpch1", "r", 1L, None)),
      List((11L, "inlined_insert:2"))
    )
    val cs = BranchChanges.compute(branch, viaR2, fork)
    cs.conflicts shouldBe Nil
    cs.tables.map(t => (t.table, t.kind, t.threeWay)) shouldBe
      List(("r", ChangeKind.Modified, true))
  }

  "classify" should "treat a rewrite as maintenance, never a touch" in {
    val fake = new Fake(11L, List(region), List((11L, "rewrite_delete:2")))
    BranchChanges.classify(fake, fork, 11L)._1 shouldBe Nil
  }

  it should "refuse a table with a column named snapshot_id, which the change functions rename" in {
    val fake = new Fake(
      head = 11L,
      versions = List(region),
      changes = List((11L, "inlined_insert:2")),
      columns = Map(
        (2L, fork) -> List(col("Snapshot_Id")),
        (2L, 11L)  -> List(col("Snapshot_Id"))
      )
    )
    val only = BranchChanges.classify(fake, fork, 11L)._1.head
    only.reservedColumn shouldBe Some("snapshot_id")
    only.mergeable shouldBe false
    only.reason.get should include("column named snapshot_id")
  }

  it should "look for reserved column names among a table's own columns, not its nested fields" in {
    // The full listing also holds the fields of a STRUCT column s: (rowid, snapshot_id).
    val listing = List(col("id"), col("s"), col("rowid"), col("snapshot_id"))
    val fake    = new Fake(
      head = 11L,
      versions = List(region),
      changes = List((11L, "inlined_insert:2")),
      columns = Map((2L, fork) -> listing, (2L, 11L) -> listing),
      topLevel = Map((2L, 11L) -> List(col("id"), col("s")))
    )
    BranchChanges
      .classify(fake, fork, 11L)
      ._1
      .map(t => (t.kind, t.reservedColumn, t.mergeable)) shouldBe
      List((ChangeKind.Modified, None, true))
  }

  it should "mark a table whose column was dropped and added again as altered" in {
    // Same name, type and nullability, but the new column takes the next order number.
    val before = List(col("a", ordinal = 1), col("c", ordinal = 2))
    val after  = List(col("a", ordinal = 1), col("c", ordinal = 3))
    val fake   = new Fake(
      head = 13L,
      versions = List(region),
      changes = List((11L, "inlined_insert:2"), (12L, "altered_table:2"), (13L, "altered_table:2")),
      columns = Map((2L, fork) -> before, (2L, 13L) -> after)
    )
    BranchChanges.classify(fake, fork, 13L)._1.map(t => (t.table, t.kind, t.tableId)) shouldBe
      List(("region", ChangeKind.Altered, Some(2L)))
  }

  it should "not take an altered table whose columns are as they were for a schema change" in {
    // A comment or a partitioning commits altered_table and leaves the columns alone.
    val rows =
      new Fake(12L, List(region), List((11L, "altered_table:2"), (12L, "inlined_insert:2")))
    BranchChanges.classify(rows, fork, 12L)._1.map(t => (t.table, t.kind)) shouldBe
      List(("region", ChangeKind.Modified))
    val bare = new Fake(11L, List(region), List((11L, "altered_table:2")))
    BranchChanges.classify(bare, fork, 11L)._1 shouldBe Nil
  }

  it should "not take a dropped schema for a table touch or an unsupported change" in {
    // dropped_schema carries a schema id, which names no table: unhandled, it would be unsupported.
    val fake = new Fake(11L, List(region, nation), List((11L, "dropped_schema:5")))
    BranchChanges.classify(fake, fork, 11L) shouldBe (Nil, Nil)
  }

  it should "count rows written under a temporary name of the table" in {
    BranchChanges.classify(viaR2, fork, 14L)._1.map(t => (t.table, t.kind, t.tableId)) shouldBe
      List(("r", ChangeKind.Modified, Some(2L)))
  }

  it should "mark a table renamed on the branch as altered, not as created" in {
    // The rename is a created_table under the new name, for the same table id.
    val fake = new Fake(
      head = 11L,
      versions = List(
        TableVersion(2L, "tpch1", "region", 3L, Some(11L)),
        TableVersion(2L, "tpch1", "region2", 11L, None)
      ),
      changes = List((11L, """created_table:"tpch1"."region2""""))
    )
    val renamed = BranchChanges.classify(fake, fork, 11L)._1
    renamed.map(t => (t.table, t.kind, t.tableId, t.mergeable)) shouldBe
      List(("region2", ChangeKind.Altered, Some(2L), false))
  }

  private def entries(changes: (Long, String)*) = BranchChanges.parseEntries(changes.toList)

  "readRanges" should "cover every snapshot after the fork except the table's own maintenance" in {
    val es = entries(
      (11L, "inserted_into_table:2"),
      (12L, "rewrite_delete:2"),
      (13L, "deleted_from_table:2,inline_flush:2"),
      (14L, "rewrite_delete:3"), // another table's maintenance: read through it
      (15L, "inlined_insert:2")
    )
    BranchChanges.readRanges(es, 2L, 10L, 15L) shouldBe
      Right(List(SnapshotRange(11L, 11L), SnapshotRange(14L, 15L)))
  }

  it should "be empty when the side has no commits since the fork" in {
    BranchChanges.readRanges(Nil, 2L, 10L, 10L) shouldBe Right(Nil)
  }

  it should "stop at head and ignore later snapshots" in {
    val es = entries((11L, "inserted_into_table:2"), (12L, "rewrite_delete:2"))
    BranchChanges.readRanges(es, 2L, 10L, 11L) shouldBe Right(List(SnapshotRange(11L, 11L)))
  }

  it should "refuse a snapshot mixing maintenance and data on the table" in {
    BranchChanges.readRanges(
      entries((11L, "rewrite_delete:2,inserted_into_table:2")),
      2L,
      10L,
      11L
    ) shouldBe
      Left(List(11L))
    BranchChanges.readRanges(
      entries((11L, "inline_flush:2,inlined_insert:2")),
      2L,
      10L,
      11L
    ) shouldBe
      Left(List(11L))
    // On another table it does not matter.
    BranchChanges.readRanges(
      entries((11L, "rewrite_delete:3,inserted_into_table:3")),
      2L,
      10L,
      11L
    ) shouldBe
      Right(List(SnapshotRange(11L, 11L)))
  }

  it should "refuse a snapshot holding a verb it does not know on the table" in {
    BranchChanges.readRanges(entries((11L, "brand_new_verb:2")), 2L, 10L, 11L) shouldBe
      Left(List(11L))
    BranchChanges.readRanges(
      entries((11L, "inserted_into_table:2"), (12L, "inlined_insert:2,brand_new_verb:2")),
      2L,
      10L,
      12L
    ) shouldBe Left(List(12L))
    // On another table it does not matter.
    BranchChanges.readRanges(entries((11L, "brand_new_verb:3")), 2L, 10L, 11L) shouldBe
      Right(List(SnapshotRange(11L, 11L)))
  }

  it should "read through an altered table whose columns did not change" in {
    BranchChanges.readRanges(
      entries((11L, "altered_table:2"), (12L, "inserted_into_table:2")),
      2L,
      10L,
      12L
    ) shouldBe Right(List(SnapshotRange(11L, 12L)))
  }

  "historyGap" should "find the first missing snapshot since the fork" in {
    BranchChanges.historyGap(List((11L, ""), (12L, ""), (13L, "")), 10L, 13L) shouldBe None
    BranchChanges.historyGap(List((11L, ""), (13L, "")), 10L, 13L) shouldBe Some(12L)
    BranchChanges.historyGap(List((12L, ""), (13L, "")), 10L, 13L) shouldBe Some(11L)
    BranchChanges.historyGap(List((11L, "")), 10L, 13L) shouldBe Some(12L)
    BranchChanges.historyGap(Nil, 10L, 10L) shouldBe None
    // A snapshot past head is not part of the question.
    BranchChanges.historyGap(List((11L, ""), (15L, "")), 10L, 11L) shouldBe None
  }

  "compute" should "carry fork, head and main snapshot ids" in {
    val branch = new Fake(12L, List(region), List((11L, "inlined_insert:2")))
    val main   = new Fake(15L, List(region), Nil)
    val cs     = BranchChanges.compute(branch, main, fork)
    (cs.forkSnapshot, cs.headSnapshot, cs.mainSnapshot) shouldBe (10L, 12L, 15L)
    cs.mergeable shouldBe true
  }

  "TableChange.counted" should "hold for a modified table whose count did not fail, and for no other" in {
    def of(kind: ChangeKind, unavailable: Boolean = false) =
      TableChange("tpch1", "region", kind, countsUnavailable = unavailable)
    ChangeKind.values.filter(k => of(k).counted).toList shouldBe List(ChangeKind.Modified)
    of(ChangeKind.Modified, unavailable = true).counted shouldBe false
  }
