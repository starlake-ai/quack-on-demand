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
    val region     = RowMergeTable(0, "tpch1", "region", List("r"), Nil, Nil, Nil)
    val statements = BranchMergeSql.applyBatch(
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
    statements.head shouldBe "BEGIN"
    statements(1) shouldBe
      "CALL ducklake_set_commit_message('acme_tpch', 'tenant:acme/user:bob', 'it''s [mg-1]')"
    statements(2) should include("branch moved past snapshot 15")
    statements(3) shouldBe "SET dynamic_or_filter_threshold = 10000"
    statements.slice(4, 7) shouldBe List(
      "SET ducklake_max_retry_count = 200",
      "SET ducklake_retry_wait_ms = 10",
      "SET ducklake_retry_backoff = 1"
    )
    statements.last shouldBe "COMMIT"
    def at(start: String) = statements.indexWhere(_.startsWith(start))
    statements(at("CREATE TABLE")) should startWith(
      """CREATE TABLE "acme_tpch"."tpch1"."extra" AS SELECT"""
    )
    at("SET ducklake_retry_backoff") should be < at("CREATE TABLE")
    at("CREATE TABLE") should be < at("DELETE FROM")
    at("DELETE FROM") should be < at("UPDATE ")
    at("UPDATE ") should be < statements.size - 1
  }

  it should "keep DELETE, UPDATE, INSERT per table, the head guard first, and one statement per entry" in {
    def table(i: Int, name: String) =
      RowMergeTable(i, "s;t", name, List("a;b", "c'd"), Nil, Nil, Nil) ->
        RowCounts("s;t", name, toDelete = 1L, toUpdate = 5L, toInsert = 1L, maxUpdateRowid = 40L)
    val merges     = List(table(0, "o;r"), table(1, "plain"))
    val statements = BranchMergeSql.applyBatch(
      parentAlias = "la;ke",
      branchAlias = "b;r",
      tableChanges = List(
        TableChange("s;t", "o;r", ChangeKind.Modified),
        TableChange("s;t", "plain", ChangeKind.Modified),
        TableChange("s;t", "new;1", ChangeKind.Created),
        TableChange("s;t", "again;2", ChangeKind.Recreated),
        TableChange("s;t", "gone;3", ChangeKind.Dropped)
      ),
      rowMerges = merges,
      // Ranges: a large update's several statements in one transaction.
      ranged = true,
      updateChunkRows = 100L,
      fork = 10L,
      head = 15L,
      author = "it's; me",
      message = "merge; [mg-1]"
    )
    statements.foreach(s => withClue(s)(OneStatement.holds(s) shouldBe true))
    statements.indexWhere(_.contains("branch moved past snapshot")) shouldBe 2
    def kinds(name: String) =
      statements
        .filter(_.contains(s""""s;t"."$name""""))
        .map(_.takeWhile(_ != ' '))
        .filter(k => Set("DELETE", "UPDATE", "INSERT")(k))
        .distinct
    kinds("o;r") shouldBe List("DELETE", "UPDATE", "INSERT")
    kinds("plain") shouldBe List("DELETE", "UPDATE", "INSERT")
    statements.count(_ == "COMMIT") shouldBe 1
    statements.last shouldBe "COMMIT"
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
    ).mkString("\n") should include("""DELETE FROM "p"."s"."b"""")
    applied(List(TableChange("s", "x", ChangeKind.Created)), Nil).mkString("\n") should
      include("CREATE TABLE")
  }

  "findSnapshotSql" should "look the merge snapshot up by its message" in {
    BranchMergeSql.findSnapshotSql("acme_tpch", "m [x]") shouldBe
      """SELECT max(snapshot_id) AS snapshot_id FROM "acme_tpch".snapshots() WHERE commit_message = 'm [x]'"""
  }
