package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{MaintenancePolicy, Names, RunCounters}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Integration test for the `qodstate_maintenance_policy` / `qodstate_maintenance_run` persistence
  * added by Liquibase 0021. Mirrors [[PostgresControlPlaneStoreSpec]]'s live-Postgres fixture:
  * cancels cleanly when no local Postgres is reachable.
  */
class MaintenanceStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodmaint")

  /** Fresh DB + migrated schema, with the store wired against it. */
  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    if !TestPostgres.reachable then
      cancel(
        s"local Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort} " +
          "(SL_TEST_PG_* envs); skipping"
      )
    val dbName = s"qodmaint_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      test(new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass))
    finally Try(TestPostgres.dropDatabase(dbName))

  private def pol(
      scopeKind: String = "tenantdb",
      schema: Option[String] = None,
      table: Option[String] = None
  ) =
    MaintenancePolicy(
      id = Names.newSurrogateId("mpol"),
      tenant = "acme",
      tenantDb = "acme_db",
      scopeKind = scopeKind,
      scopeSchema = schema,
      scopeTable = table,
      enabled = Some(true),
      retentionDays = Some(14),
      compactionEnabled = None,
      targetFileSize = None,
      smallFileMinCount = None,
      rewriteDeleteThreshold = None,
      cleanupGraceDays = None,
      orphanMinAgeDays = None,
      cron = None
    )

  "maintenance policy store" should "upsert, list, find, delete" in withStore { s =>
    val p = s.upsertMaintenancePolicy(pol())
    s.findMaintenancePolicy(p.id).map(_.retentionDays) shouldBe Some(Some(14))
    // Upsert on the same scope tuple replaces, does not duplicate.
    s.upsertMaintenancePolicy(pol().copy(retentionDays = Some(3)))
    val rows = s.listMaintenancePolicies("acme", "acme_db")
    rows.size shouldBe 1
    rows.head.retentionDays shouldBe Some(3)
    s.deleteMaintenancePolicy(rows.head.id) shouldBe true
    s.listMaintenancePolicies("acme", "acme_db") shouldBe empty
  }

  it should "scope rows by (tenant, tenantDb)" in withStore { s =>
    s.upsertMaintenancePolicy(pol())
    s.listMaintenancePolicies("acme", "other_db") shouldBe empty
    s.listMaintenancePolicies("globex", "acme_db") shouldBe empty
  }

  "maintenance run store" should "enqueue, claim exactly once, heartbeat, finish, list" in
    withStore { s =>
      val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
      r.status shouldBe "queued"
      s.hasActiveMaintenanceRun("acme", "acme_db") shouldBe true
      val claimed = s.claimQueuedMaintenanceRun()
      claimed.map(_.id) shouldBe Some(r.id)
      claimed.map(_.status) shouldBe Some("running")
      s.claimQueuedMaintenanceRun() shouldBe None // nothing else queued
      s.heartbeatMaintenanceRun(r.id, RunCounters(filesMerged = 3)) shouldBe true
      s.finishMaintenanceRun(
        r.id,
        "succeeded",
        RunCounters(filesMerged = 3, bytesReclaimed = 42L),
        None
      ) shouldBe true
      s.hasActiveMaintenanceRun("acme", "acme_db") shouldBe false
      val listed = s.listMaintenanceRuns("acme", "acme_db", limit = 10, before = None)
      listed.map(_.status) shouldBe List("succeeded")
      listed.head.counters.bytesReclaimed shouldBe 42L
      s.lastNonManualMaintenanceRunAt("acme", "acme_db") shouldBe defined
    }

  it should "sweep stale running rows to failed" in withStore { s =>
    val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
    s.claimQueuedMaintenanceRun()
    // heartbeat is set by claim; sweeping with a future cutoff must catch it
    s.sweepStaleMaintenanceRuns(java.time.Instant.now().plusSeconds(3600)) shouldBe 1
    s.listMaintenanceRuns("acme", "acme_db", 10, None).head.status shouldBe "failed"
  }

  it should "refuse to overwrite a run already swept to failed (finish loses the race)" in
    withStore { s =>
      val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
      s.claimQueuedMaintenanceRun()
      s.sweepStaleMaintenanceRuns(java.time.Instant.now().plusSeconds(3600)) shouldBe 1
      s.listMaintenanceRuns("acme", "acme_db", 10, None).head.status shouldBe "failed"

      // The runner, unaware it was swept, tries to record its own (successful) finish.
      s.finishMaintenanceRun(
        r.id,
        "succeeded",
        RunCounters(filesMerged = 1),
        None
      ) shouldBe false
      // The sweep's outcome is not clobbered by the late finish.
      val after = s.listMaintenanceRuns("acme", "acme_db", 10, None).head
      after.status shouldBe "failed"
      after.error shouldBe Some("stale: heartbeat timeout")
    }

  it should "paginate runs by keyset" in withStore { s =>
    (1 to 3).foreach(_ => s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None))
    val page1 = s.listMaintenanceRuns("acme", "acme_db", limit = 2, before = None)
    page1.size shouldBe 2
    val page2 = s.listMaintenanceRuns("acme", "acme_db", limit = 2, before = Some(page1.last.id))
    page2.size shouldBe 1
    (page1 ++ page2).map(_.id).distinct.size shouldBe 3
  }
