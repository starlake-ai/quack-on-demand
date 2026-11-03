package ai.starlake.quack.ondemand.branch

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BranchMergeSqlSpec extends AnyFlatSpec with Matchers:

  private def stmts(kind: ChangeKind, table: String = "t") =
    BranchMergeSql.tableStatements(
      "acme_tpch",
      "qod_branch",
      TableChange("tpch1", table, kind),
      10L,
      15L
    )

  "tableStatements" should "create a table at the validated head" in {
    stmts(ChangeKind.Created) shouldBe List(
      """CREATE SCHEMA IF NOT EXISTS "acme_tpch"."tpch1"""",
      """CREATE TABLE "acme_tpch"."tpch1"."t" AS SELECT * FROM "qod_branch"."tpch1"."t" AT (VERSION => 15)"""
    )
  }

  it should "drop first when a table was recreated" in {
    stmts(ChangeKind.Recreated).head shouldBe """DROP TABLE "acme_tpch"."tpch1"."t""""
    stmts(ChangeKind.Recreated)(1) should include("AT (VERSION => 15)")
  }

  it should "drop a dropped table" in {
    stmts(ChangeKind.Dropped) shouldBe List("""DROP TABLE "acme_tpch"."tpch1"."t"""")
  }

  it should "refuse an altered table and a table that merges by rows" in {
    an[IllegalArgumentException] should be thrownBy stmts(ChangeKind.Altered)
    an[IllegalArgumentException] should be thrownBy stmts(ChangeKind.Modified)
  }

  it should "quote identifiers" in {
    stmts(ChangeKind.Dropped, "o\"r") shouldBe List("""DROP TABLE "acme_tpch"."tpch1"."o""r"""")
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
      message = "it's [mg-1]"
    )
    val lines = sql.linesIterator.toList
    lines.head shouldBe "BEGIN;"
    lines(1) shouldBe
      "CALL ducklake_set_commit_message('acme_tpch', 'tenant:acme/user:bob', 'it''s [mg-1]');"
    lines(2) should include("branch moved past snapshot 15")
    lines(3) shouldBe "SET dynamic_or_filter_threshold = 10000;"
    lines.last shouldBe "COMMIT;"
    sql should include("""CREATE TABLE "acme_tpch"."tpch1"."extra" AS SELECT""")
    sql.indexOf("CREATE TABLE") should be < sql.indexOf("DELETE FROM")
    sql.indexOf("DELETE FROM") should be < sql.indexOf("UPDATE ")
    sql should include("SET ducklake_max_retry_count = 200;")
    sql should include("SET ducklake_retry_wait_ms = 10;")
    sql should include("SET ducklake_retry_backoff = 1;")
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

  "findSnapshotSql" should "look the merge snapshot up by its message" in {
    BranchMergeSql.findSnapshotSql("acme_tpch", "m [x]") shouldBe
      """SELECT max(snapshot_id) AS snapshot_id FROM "acme_tpch".snapshots() WHERE commit_message = 'm [x]'"""
  }
