package ai.starlake.quack.ondemand.branch

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BranchMergeSqlSpec extends AnyFlatSpec with Matchers:

  "BranchMergeSql.batch" should "render one transaction with the stamp first and COMMIT last" in {
    val sql = BranchMergeSql.batch(
      parentAlias = "acme_tpch",
      branchAlias = "qod_branch",
      changes = List(
        TableChange("tpch1", "region", ChangeKind.Modified),
        TableChange("tpch1", "extra", ChangeKind.Created),
        TableChange("tpch1", "nation", ChangeKind.Dropped),
        TableChange("tpch1", "again", ChangeKind.Recreated)
      ),
      fork = 10L,
      head = 15L,
      author = "tenant:acme/user:bob",
      message = "merge branch f [mg-1] proposed by alice"
    )
    val lines = sql.linesIterator.toList
    lines.head shouldBe "SET ducklake_max_retry_count = 0;"
    lines(1) shouldBe "BEGIN;"
    lines(2) shouldBe
      "CALL ducklake_set_commit_message('acme_tpch', 'tenant:acme/user:bob', 'merge branch f [mg-1] proposed by alice');"
    // Inside the transaction and before any write: the branch must still be at the validated head.
    lines(3) shouldBe
      """SELECT CASE WHEN (SELECT max(snapshot_id) FROM "qod_branch".snapshots()) <> 15 THEN error('branch moved past snapshot 15 after validation; propose it again') END AS head_guard;"""
    lines.last shouldBe "COMMIT;"
    sql should include(
      "ducklake_table_changes('qod_branch', 'tpch1', 'region', 11, 15) WHERE change_type IN ('delete', 'update_preimage')"
    )
    sql should include(
      """INSERT INTO "acme_tpch"."tpch1"."region" BY NAME SELECT * FROM "qod_branch"."tpch1"."region" AT (VERSION => 15) WHERE rowid IN"""
    )
    sql should include("""CREATE SCHEMA IF NOT EXISTS "acme_tpch"."tpch1";""")
    sql should include(
      """CREATE TABLE "acme_tpch"."tpch1"."extra" AS SELECT * FROM "qod_branch"."tpch1"."extra" AT (VERSION => 15);"""
    )
    sql should include("""DROP TABLE "acme_tpch"."tpch1"."nation";""")
    // Recreated: drop first, then create.
    val dropAgain = sql.indexOf("""DROP TABLE "acme_tpch"."tpch1"."again";""")
    val makeAgain = sql.indexOf(
      """CREATE TABLE "acme_tpch"."tpch1"."again" AS SELECT * FROM "qod_branch"."tpch1"."again" AT (VERSION => 15);"""
    )
    dropAgain should be > 0
    makeAgain should be > dropAgain
    // No branch read at its current state: every one is pinned to the validated head.
    sql.linesIterator.filter(_.contains("FROM \"qod_branch\".\"tpch1\"")).foreach { l =>
      l should include("AT (VERSION => 15)")
    }
  }

  it should "quote identifiers and escape literals" in {
    val sql = BranchMergeSql.batch(
      "p\"q",
      "qod_branch",
      List(TableChange("s", "o'reilly", ChangeKind.Modified)),
      1L,
      2L,
      "a",
      "it's"
    )
    sql should include("""DELETE FROM "p""q"."s"."o'reilly" WHERE rowid IN""")
    sql should include("ducklake_table_changes('qod_branch', 's', 'o''reilly', 2, 2)")
    sql should include("'it''s'")
  }

  it should "refuse an altered table" in {
    an[IllegalArgumentException] should be thrownBy BranchMergeSql.batch(
      "p",
      "b",
      List(TableChange("s", "t", ChangeKind.Altered)),
      1L,
      2L,
      "a",
      "m"
    )
  }

  it should "refuse a table whose own rowid column hides the row identifier" in {
    an[IllegalArgumentException] should be thrownBy BranchMergeSql.batch(
      "p",
      "b",
      List(TableChange("s", "t", ChangeKind.Modified, reservedColumn = Some("rowid"))),
      1L,
      2L,
      "a",
      "m"
    )
  }

  "findSnapshotSql" should "look the merge snapshot up by its message" in {
    BranchMergeSql.findSnapshotSql("acme_tpch", "m [x]") shouldBe
      """SELECT max(snapshot_id) AS snapshot_id FROM "acme_tpch".snapshots() WHERE commit_message = 'm [x]'"""
  }

  "BranchMergeSql.applyBatch" should "stamp first, guard the head, write other tables, then rows" in {
    val region = RowMergeTable(0, "tpch1", "region", List("r"), Nil, Nil, Nil)
    val sql    = BranchMergeSql.applyBatch(
      parentAlias = "acme_tpch",
      branchAlias = "qod_branch",
      tableChanges = List(
        TableChange("tpch1", "region", ChangeKind.Modified),
        TableChange("tpch1", "extra", ChangeKind.Created)
      ),
      rowMerges = List(region -> RowCounts("tpch1", "region", toDelete = 1L, toUpdate = 1L)),
      ranged = false,
      updateChunkRows = 100L,
      fork = 10L,
      head = 15L,
      author = "tenant:acme/user:bob",
      message = "merge branch f [mg-1] proposed by alice"
    )
    val lines = sql.linesIterator.toList
    lines.head shouldBe "BEGIN;"
    lines(1) should startWith("CALL ducklake_set_commit_message('acme_tpch',")
    lines(2) should include("branch moved past snapshot 15")
    lines(3) shouldBe "SET dynamic_or_filter_threshold = 10000;"
    lines.last shouldBe "COMMIT;"
    sql should include("""CREATE TABLE "acme_tpch"."tpch1"."extra" AS SELECT""")
    sql.indexOf("CREATE TABLE") should be < sql.indexOf("DELETE FROM")
    sql.indexOf("DELETE FROM") should be < sql.indexOf("UPDATE ")
  }

  private def rowMerge(schema: String, table: String) =
    RowMergeTable(0, schema, table, List("r"), Nil, Nil, Nil) ->
      RowCounts(schema, table, toDelete = 1L)

  private def modified(schema: String, table: String) =
    TableChange(schema, table, ChangeKind.Modified)

  private def applied(changes: List[TableChange], merges: List[(RowMergeTable, RowCounts)]) =
    BranchMergeSql.applyBatch(
      parentAlias = "p",
      branchAlias = "b",
      tableChanges = changes,
      rowMerges = merges,
      ranged = false,
      updateChunkRows = 100L,
      fork = 10L,
      head = 15L,
      author = "a",
      message = "m"
    )

  it should "refuse a modified table with no row merge: it would be left out just before the commit" in {
    val thrown = the[IllegalArgumentException] thrownBy
      applied(List(modified("s", "a"), modified("s", "b")), List(rowMerge("s", "a")))
    thrown.getMessage should include("s.b")
    thrown.getMessage should not include "s.a"
    an[IllegalArgumentException] should be thrownBy applied(List(modified("s", "a")), Nil)
  }

  it should "refuse a row merge of a table the change set does not hold as modified" in {
    val thrown = the[IllegalArgumentException] thrownBy
      applied(List(modified("s", "a")), List(rowMerge("s", "a"), rowMerge("s", "b")))
    thrown.getMessage should include("s.b")
    thrown.getMessage should not include "s.a"
    an[IllegalArgumentException] should be thrownBy
      applied(List(TableChange("s", "a", ChangeKind.Created)), List(rowMerge("s", "a")))
    an[IllegalArgumentException] should be thrownBy
      applied(List(modified("s1", "a")), List(rowMerge("s2", "a")))
  }

  it should "take the same tables in any order, and no row merge when no table is modified" in {
    applied(
      List(modified("s", "b"), modified("s", "a")),
      List(rowMerge("s", "a"), rowMerge("s", "b"))
    ) should include("""DELETE FROM "p"."s"."b"""")
    applied(List(TableChange("s", "x", ChangeKind.Created)), Nil) should include("CREATE TABLE")
  }
