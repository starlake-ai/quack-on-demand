package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model._
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class MaintenanceSchedulerSpec extends AnyFlatSpec with Matchers:

  private def fixture(
      smallFiles: Map[(String, String), Int] = Map.empty,
      policyRows: List[MaintenancePolicy] = Nil
  ) =
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db"))
    store.upsertTenantDb(
      TenantDb(
        id = "td1",
        tenantId = "acme",
        name = "acme_db",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = ""
      )
    )
    policyRows.foreach(store.upsertMaintenancePolicy)
    val sched = new MaintenanceScheduler(
      store = store,
      smallFileCountsOf = (_, _, _) => smallFiles,
      minIntervalMinutes = 30,
      runTimeoutMinutes = 60,
      staggerOf = (_, _) => 0
    )
    (store, sched)

  private def enabledPolicy(cron: String = "* * * * *") =
    MaintenancePolicy(
      Names.newSurrogateId("mpol"),
      "acme",
      "acme_db",
      "tenantdb",
      None,
      None,
      enabled = Some(true),
      retentionDays = None,
      compactionEnabled = None,
      targetFileSize = None,
      smallFileMinCount = Some(2),
      rewriteDeleteThreshold = None,
      cleanupGraceDays = None,
      orphanMinAgeDays = None,
      cron = Some(cron)
    )

  "tickOnce" should "do nothing when no policy enables the lake" in {
    val (store, sched) = fixture()
    sched.tickOnce(Instant.now())
    store.listMaintenanceRuns("acme", "acme_db", 10, None) shouldBe empty
  }

  it should "enqueue a cadence run when the cron is due" in {
    val (store, sched) = fixture(policyRows = List(enabledPolicy()))
    sched.tickOnce(Instant.now())
    val runs = store.listMaintenanceRuns("acme", "acme_db", 10, None)
    runs.map(r => (r.scope, r.trigger)) shouldBe List(("tenantdb", "cadence"))
  }

  it should "enqueue a table-scoped threshold run when small files exceed the minimum" in {
    // cron far in the future so only the threshold can fire
    val (store, sched) = fixture(
      smallFiles = Map(("tpch1", "region") -> 5),
      policyRows = List(enabledPolicy(cron = "0 0 1 1 *"))
    )
    sched.tickOnce(Instant.now())
    val runs = store.listMaintenanceRuns("acme", "acme_db", 10, None)
    runs.map(r => (r.scope, r.trigger)) shouldBe List(("table:tpch1.region", "threshold"))
  }

  it should "not enqueue while a run is queued or running (dedup)" in {
    val (store, sched) = fixture(policyRows = List(enabledPolicy()))
    sched.tickOnce(Instant.now())
    sched.tickOnce(Instant.now())
    store.listMaintenanceRuns("acme", "acme_db", 10, None).size shouldBe 1
  }

  it should "honor the min interval after a finished run" in {
    val (store, sched) = fixture(policyRows = List(enabledPolicy()))
    val r              = store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
    store.claimQueuedMaintenanceRun()
    store.finishMaintenanceRun(r.id, "failed", RunCounters(), Some("x"))
    sched.tickOnce(Instant.now()) // last run just now -> min interval blocks
    store.listMaintenanceRuns("acme", "acme_db", 10, None).size shouldBe 1
  }

  it should "sweep stale running rows" in {
    val (store, sched) = fixture(policyRows = List(enabledPolicy(cron = "0 0 1 1 *")))
    store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "cadence", None)
    store.claimQueuedMaintenanceRun()
    // pretend the heartbeat is ancient by sweeping with runTimeoutMinutes = 60 and a tick
    // 2 hours in the future
    sched.tickOnce(Instant.now().plusSeconds(7200))
    store.listMaintenanceRuns("acme", "acme_db", 10, None).head.status shouldBe "failed"
  }
