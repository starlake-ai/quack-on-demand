package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.ondemand.catalog.TableVersion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pure classification over hand-built metadata (no Postgres). */
class BranchChangesSpec extends AnyFlatSpec with Matchers:

  private def col(name: String, typ: String = "INTEGER", nullable: Boolean = true) =
    CatalogColumnEntry(0, name, typ, nullable, isPrimaryKey = false)

  /** A fake catalog: `head` snapshot, table versions, changes after the fork, and per-(table,
    * snapshot) column shapes (default: one column `x`, unchanged).
    */
  private final class Fake(
      head: Long,
      versions: List[TableVersion],
      changes: List[(Long, String)],
      columns: Map[(Long, Long), List[CatalogColumnEntry]] = Map.empty
  ) extends ChangeSource:
    def maxSnapshotId(): Option[Long]                          = Some(head)
    def tableVersions(): List[TableVersion]                    = versions
    def snapshotChangesSince(fork: Long): List[(Long, String)] = changes.filter(_._1 > fork)
    def columnsOfTableAt(tableId: Long, n: Long): List[CatalogColumnEntry] =
      columns.getOrElse((tableId, n), List(col("x")))

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
    tables.map(t => (t.table, t.kind)) shouldBe List(
      ("nation", ChangeKind.Recreated),
      ("region", ChangeKind.Altered)
    )
    tables.find(_.table == "region").get.mergeable shouldBe false
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

  "conflicts" should "intersect by name and flag a dropped schema" in {
    val branchTables = List(
      TableChange("tpch1", "region", ChangeKind.Modified),
      TableChange("tpch1", "nation", ChangeKind.Modified),
      TableChange("s2", "t", ChangeKind.Created)
    )
    val main = new Fake(
      head = 14L,
      versions = List(region, nation),
      changes = List(
        (11L, "inlined_insert:2"),                    // touches region
        (12L, "inline_flush:3,deleted_from_table:3"), // flush of nation: not a touch
        (13L, """dropped_schema:"s2""""),
        (14L, """created_schema:"s3"""")
      )
    )
    BranchChanges.conflicts(branchTables, main, fork).map(c => (c.schema, c.table)) shouldBe
      List(("tpch1", "region"), ("s2", "t"))
  }

  "compute" should "carry fork, head and main snapshot ids" in {
    val branch = new Fake(12L, List(region), List((11L, "inlined_insert:2")))
    val main   = new Fake(15L, List(region), Nil)
    val cs     = BranchChanges.compute(branch, main, fork)
    (cs.forkSnapshot, cs.headSnapshot, cs.mainSnapshot) shouldBe (10L, 12L, 15L)
    cs.mergeable shouldBe true
  }
