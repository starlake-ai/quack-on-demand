package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{MaintenancePolicy, MaintenanceRun, Names, RunCounters}
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

  /** Fresh DB + migrated schema, with the store wired against it and a way to run raw SQL on that
    * database (to move a run's timestamps, as only the database clock decides ages).
    */
  private def withStoreSql(test: (PostgresControlPlaneStore, String => Unit) => Unit): Unit =
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
      test(
        new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass),
        sql => TestPostgres.psql(dbName, sql)
      )
    finally Try(TestPostgres.dropDatabase(dbName))

  /** Fresh DB + migrated schema, with the store wired against it. */
  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    withStoreSql((s, _) => test(s))

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

  it should "round-trip a policy with all optional fields None" in withStore { s =>
    val policyAllNone = MaintenancePolicy(
      id = Names.newSurrogateId("mpol"),
      tenant = "acme",
      tenantDb = "acme_db",
      scopeKind = "tenantdb",
      scopeSchema = None,
      scopeTable = None,
      enabled = None,
      retentionDays = None,
      compactionEnabled = None,
      targetFileSize = None,
      smallFileMinCount = None,
      rewriteDeleteThreshold = None,
      cleanupGraceDays = None,
      orphanMinAgeDays = None,
      cron = None
    )
    val upserted  = s.upsertMaintenancePolicy(policyAllNone)
    val retrieved = s.findMaintenancePolicy(upserted.id).get
    retrieved.enabled shouldBe None
    retrieved.retentionDays shouldBe None
    retrieved.compactionEnabled shouldBe None
    retrieved.targetFileSize shouldBe None
    retrieved.smallFileMinCount shouldBe None
    retrieved.rewriteDeleteThreshold shouldBe None
    retrieved.cleanupGraceDays shouldBe None
    retrieved.orphanMinAgeDays shouldBe None
    retrieved.cron shouldBe None
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
      val claimed = s.claimQueuedMaintenanceRun(minAgeSec = 0)
      claimed.map(_.id) shouldBe Some(r.id)
      claimed.map(_.status) shouldBe Some("running")
      s.claimQueuedMaintenanceRun(minAgeSec = 0) shouldBe None // nothing else queued
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

  it should "refuse to overwrite a run already reaped (finish loses the race)" in
    withStoreSql { (s, sql) =>
      val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
      s.claimQueuedMaintenanceRun(minAgeSec = 0)
      backdate(sql, r.id, "heartbeat_at", "10 minutes")
      s.reapMaintenanceRuns(livenessTimeoutSec = 180, runTimeoutSec = 3600L).map(_.run.id) shouldBe
        List(r.id)

      // The runner, unaware it was reaped, tries to record its own (successful) finish.
      s.finishMaintenanceRun(r.id, "succeeded", RunCounters(filesMerged = 1), None) shouldBe false
      // The reaper's outcome is not clobbered by the late finish.
      val after = s.listMaintenanceRuns("acme", "acme_db", 10, None).head
      after.status shouldBe "failed"
      after.error shouldBe Some(MaintenanceRun.ReapedSilent)
    }

  it should "paginate runs by keyset" in withStore { s =>
    (1 to 3).foreach(_ => s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None))
    val page1 = s.listMaintenanceRuns("acme", "acme_db", limit = 2, before = None)
    page1.size shouldBe 2
    val page2 = s.listMaintenanceRuns("acme", "acme_db", limit = 2, before = Some(page1.last.id))
    page2.size shouldBe 1
    (page1 ++ page2).map(_.id).distinct.size shouldBe 3
  }

  it should "round-trip operations CSV with explicit operations list" in withStore { s =>
    val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", Some("flush,merge"))
    s.claimQueuedMaintenanceRun(minAgeSec = 0)
    val listed = s.listMaintenanceRuns("acme", "acme_db", limit = 10, before = None)
    listed.head.operations shouldBe Some("flush,merge")
  }

  it should "round-trip operations CSV with empty string" in withStore { s =>
    val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", Some(""))
    s.claimQueuedMaintenanceRun(minAgeSec = 0)
    val listed = s.listMaintenanceRuns("acme", "acme_db", limit = 10, before = None)
    listed.head.operations shouldBe Some("")
  }

  private def backdate(sql: String => Unit, id: Long, column: String, by: String): Unit =
    sql(
      s"UPDATE qodstate_maintenance_run SET $column = now() - interval '$by' WHERE id = $id"
    )

  "claimQueuedMaintenanceRun" should "claim a fresh run with a minimum age of zero" in
    withStore { s =>
      val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
      s.claimQueuedMaintenanceRun(minAgeSec = 0).map(_.id) shouldBe Some(r.id)
    }

  it should "skip a run younger than the minimum age and take it once older" in
    withStoreSql { (s, sql) =>
      val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
      s.claimQueuedMaintenanceRun(minAgeSec = 120) shouldBe None
      backdate(sql, r.id, "queued_at", "121 seconds")
      s.claimQueuedMaintenanceRun(minAgeSec = 120).map(_.id) shouldBe Some(r.id)
    }

  "findMaintenanceRun" should "answer None for an unknown id" in withStore { s =>
    s.findMaintenanceRun(424242L) shouldBe None
  }

  "touchMaintenanceRun and setMaintenanceRunNode" should
    "write a running row and refuse a row that is no longer running" in withStoreSql { (s, sql) =>
      val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
      s.claimQueuedMaintenanceRun(minAgeSec = 0)
      s.setMaintenanceRunNode(r.id, "maint-n1") shouldBe true
      s.findMaintenanceRun(r.id).flatMap(_.nodeId) shouldBe Some("maint-n1")

      backdate(sql, r.id, "heartbeat_at", "1 hour")
      s.touchMaintenanceRun(r.id) shouldBe true
      val beat = s.findMaintenanceRun(r.id).flatMap(_.heartbeatAt).get
      beat.isAfter(java.time.Instant.now().minusSeconds(60)) shouldBe true

      s.finishMaintenanceRun(r.id, "failed", RunCounters(), Some(MaintenanceRun.ReapedSilent))
      s.touchMaintenanceRun(r.id) shouldBe false
      s.setMaintenanceRunNode(r.id, "maint-n2") shouldBe false
      s.findMaintenanceRun(r.id).flatMap(_.nodeId) shouldBe Some("maint-n1")
    }

  "reapMaintenanceRuns" should "fail a silent run and an over-time run, each with its own error" in
    withStoreSql { (s, sql) =>
      val silent  = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
      val slow    = s.enqueueMaintenanceRun("acme", "other_db", "tenantdb", "cadence", None)
      val healthy = s.enqueueMaintenanceRun("acme", "third_db", "tenantdb", "cadence", None)
      val queued  = s.enqueueMaintenanceRun("acme", "fourth_db", "tenantdb", "cadence", None)
      // Claims go in id order: silent, slow, healthy. `queued` stays queued.
      (1 to 3).foreach(_ => s.claimQueuedMaintenanceRun(minAgeSec = 0))
      s.setMaintenanceRunNode(silent.id, "maint-silent")
      s.setMaintenanceRunNode(slow.id, "maint-slow")
      backdate(sql, silent.id, "heartbeat_at", "10 minutes")
      backdate(sql, slow.id, "started_at", "2 hours")

      val reaped = s.reapMaintenanceRuns(livenessTimeoutSec = 180, runTimeoutSec = 3600L)

      reaped.map(r => (r.run.id, r.silent, r.run.nodeId)).toSet shouldBe Set(
        (silent.id, true, Some("maint-silent")),
        (slow.id, false, Some("maint-slow"))
      )
      s.findMaintenanceRun(silent.id).map(r => (r.status, r.error)) shouldBe
        Some(("failed", Some(MaintenanceRun.ReapedSilent)))
      s.findMaintenanceRun(slow.id).map(r => (r.status, r.error)) shouldBe
        Some(("failed", Some(MaintenanceRun.ReapedTimeout)))
      s.findMaintenanceRun(healthy.id).map(_.status) shouldBe Some("running")
      s.findMaintenanceRun(queued.id).map(_.status) shouldBe Some("queued")
    }

  it should "report a run both silent and over time as silent" in withStoreSql { (s, sql) =>
    val r = s.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
    s.claimQueuedMaintenanceRun(minAgeSec = 0)
    backdate(sql, r.id, "heartbeat_at", "10 minutes")
    backdate(sql, r.id, "started_at", "2 hours")
    s.reapMaintenanceRuns(livenessTimeoutSec = 180, runTimeoutSec = 3600L)
      .map(x => (x.run.id, x.silent)) shouldBe List((r.id, true))
  }
