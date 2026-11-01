package ai.starlake.quack.ondemand.state

import ai.starlake.quack.BranchingConfig
import ai.starlake.quack.model.{
  Branch,
  BranchMerge,
  BranchMergeStatus,
  BranchStatus,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** The three-way merge's columns on `qodstate_branch_merge` (Liquibase 0056) round trip through the
  * Postgres store, and rows written before them read back with safe defaults.
  */
class BranchMergeStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodbrmerge")

  private def withStore[A](test: (PostgresControlPlaneStore, String) => A): A =
    withStoreDb((pg, id, _) => test(pg, id))

  private def withStoreDb[A](test: (PostgresControlPlaneStore, String, String) => A): A =
    TestPostgres.ensureReachable()
    val dbName = s"qodbrmerge_test_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val pg = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        pg.upsertTenant(Tenant("acme"))
        pg.upsertTenantDb(
          TenantDb("tdb-1", "acme", "acme_db", TenantDbKind.DuckLake, Map.empty, "/d")
        )
        val b = Branch(
          "br-1",
          "acme",
          "tdb-1",
          "acme_db",
          "feature",
          "td-feature",
          "acme_db__br_feature",
          "__br_feature",
          "/d__br_feature/",
          5L,
          "alice",
          BranchStatus.Proposed,
          None
        )
        pg.createBranch(b).isRight shouldBe true
        test(pg, b.id, dbName)
      finally pg.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def merge(branchId: String) = BranchMerge(
    id = "mg-1",
    branchId = branchId,
    proposer = "alice",
    approver = None,
    status = BranchMergeStatus.Proposed,
    summaryJson = "{}",
    conflictsJson = "[]",
    mainSnapshotAtPropose = 7L
  )

  "createBranchMerge" should "store and return the proposal's options" in withStore { (pg, id) =>
    val stored = pg.createBranchMerge(
      merge(id).copy(
        onConflict = Some("branch"),
        mergeKeysJson = """{"s.t":["k"]}""",
        headSnapshotAtPropose = Some(12L),
        rowConflictsJson = """[{"schema":"s"}]"""
      )
    )
    stored.onConflict shouldBe Some("branch")
    stored.mergeKeysJson shouldBe """{"s.t":["k"]}"""
    stored.headSnapshotAtPropose shouldBe Some(12L)
    stored.rowConflictsJson shouldBe """[{"schema":"s"}]"""
    stored.holdWriters shouldBe false
    stored.attempts shouldBe 0
    pg.listBranchMerges(id).map(_.headSnapshotAtPropose) shouldBe List(Some(12L))
  }

  it should "default the new columns for a proposal that sets none" in withStore { (pg, id) =>
    val stored = pg.createBranchMerge(merge(id))
    stored.onConflict shouldBe None
    stored.mergeKeysJson shouldBe "{}"
    stored.headSnapshotAtPropose shouldBe None
    stored.rowConflictsJson shouldBe "[]"
  }

  it should "read a row written before these columns with safe defaults" in withStoreDb {
    (pg, id, dbName) =>
      // Only the columns Liquibase 0037 knows, so the database's own defaults fill the new ones.
      TestPostgres.psql(
        dbName,
        "INSERT INTO qodstate_branch_merge " +
          "(id, branch_id, proposer, status, summary_json, conflicts_json, main_snapshot_at_propose) " +
          s"VALUES ('mg-old', '$id', 'alice', 'proposed', '{}', '[]', 7)"
      )
      val old = pg.getBranchMerge("mg-old").get
      (old.onConflict, old.mergeKeysJson, old.headSnapshotAtPropose) shouldBe (None, "{}", None)
      (old.rowConflictsJson, old.holdWriters, old.attempts) shouldBe ("[]", false, 0)
  }

  "updateBranchMerge" should "record what the merge did" in withStore { (pg, id) =>
    val stored  = pg.createBranchMerge(merge(id))
    val updated = pg.updateBranchMerge(stored.copy(holdWriters = true, attempts = 3)).get
    updated.holdWriters shouldBe true
    updated.attempts shouldBe 3
    pg.getBranchMerge(stored.id).map(m => (m.holdWriters, m.attempts)) shouldBe Some((true, 3))
  }

  "BranchingConfig" should "default the merge settings and refuse nonsense" in {
    val cfg = BranchingConfig()
    cfg.mergeMaxAttempts shouldBe 5
    cfg.mergeUpdateChunkRows shouldBe 20000000L
    an[IllegalArgumentException] should be thrownBy BranchingConfig(mergeMaxAttempts = 0)
    an[IllegalArgumentException] should be thrownBy BranchingConfig(mergeUpdateChunkRows = 0L)
  }
