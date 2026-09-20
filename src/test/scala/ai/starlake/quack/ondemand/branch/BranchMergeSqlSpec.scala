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
    lines.head shouldBe "BEGIN;"
    lines(1) shouldBe
      "CALL ducklake_set_commit_message('acme_tpch', 'tenant:acme/user:bob', 'merge branch f [mg-1] proposed by alice');"
    lines.last shouldBe "COMMIT;"
    sql should include(
      "ducklake_table_changes('qod_branch', 'tpch1', 'region', 11, 15) WHERE change_type IN ('delete', 'update_preimage')"
    )
    sql should include(
      """INSERT INTO "acme_tpch"."tpch1"."region" BY NAME SELECT * FROM "qod_branch"."tpch1"."region" WHERE rowid IN"""
    )
    sql should include("""CREATE SCHEMA IF NOT EXISTS "acme_tpch"."tpch1";""")
    sql should include(
      """CREATE TABLE "acme_tpch"."tpch1"."extra" AS SELECT * FROM "qod_branch"."tpch1"."extra";"""
    )
    sql should include("""DROP TABLE "acme_tpch"."tpch1"."nation";""")
    // Recreated: drop first, then create.
    val dropAgain = sql.indexOf("""DROP TABLE "acme_tpch"."tpch1"."again";""")
    val makeAgain = sql.indexOf("""CREATE TABLE "acme_tpch"."tpch1"."again" AS""")
    dropAgain should be > 0
    makeAgain should be > dropAgain
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

  "findSnapshotSql" should "look the merge snapshot up by its message" in {
    BranchMergeSql.findSnapshotSql("acme_tpch", "m [x]") shouldBe
      """SELECT max(snapshot_id) AS snapshot_id FROM "acme_tpch".snapshots() WHERE commit_message = 'm [x]'"""
  }
