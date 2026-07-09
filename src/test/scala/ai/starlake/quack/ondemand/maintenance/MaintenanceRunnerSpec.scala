package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model._
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, InMemoryControlPlaneStore}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class MaintenanceRunnerSpec extends AnyFlatSpec with Matchers:

  private class Fixture(
      pinnedSnaps: Set[Long] = Set.empty,
      pinnedFiles: Set[String] = Set.empty,
      scheduled: List[String] = Nil,
      totalBytes: List[Long] = List(100L, 100L), // sampled before/after the chain
      failOn: Option[String] = None,             // substring of a chain statement that should fail
      policyOf: (String, String, Option[String], Option[String]) => EffectivePolicy =
        (_, _, _, _) => EffectivePolicy.defaults.copy(enabled = true),
      // When true, `scheduled` is only visible to scheduledForDeletion once a chain step
      // (merge_adjacent_files) has actually executed -- simulates THIS run's own expire+merge
      // being what schedules the pinned file for deletion in the first place, so a test can
      // prove the guard's read happens after that step, not in the prelude (F2).
      scheduledVisibleAfterMerge: Boolean = false
  ):
    val store              = new InMemoryControlPlaneStore()
    var executed           = List.empty[String]
    var stopped            = List.empty[String]
    var started            = 0
    var scheduledCallCount = 0
    var mergeRan           = false
    val node               = RunningNode(
      "maint-n1",
      PoolKey("acme", "acme_db", "__maint"),
      Role.Dual,
      "127.0.0.1",
      21999,
      "tok",
      None,
      None,
      Instant.EPOCH,
      maxConcurrent = 1
    )

    val runner = new MaintenanceRunner(
      store = store,
      spawn = (_, _) => IO { started += 1; Some(node) },
      stop = id => IO { stopped = stopped :+ id },
      exec = (_, sql) =>
        IO {
          executed = executed :+ sql
          if sql.contains("merge_adjacent_files") then mergeRan = true
          if failOn.exists(sql.contains) then Left("boom") else Right(())
        },
      snapshotsOlderThan = (_, _, _) => List(1L, 2L, 3L, 4L), // 4 candidates
      pinnedSnapshotsOf = (_, _) => pinnedSnaps,
      pinnedFilesOf = (_, _) => pinnedFiles,
      scheduledForDeletion = (_, _) =>
        scheduledCallCount += 1
        if scheduledVisibleAfterMerge && !mergeRan then Nil else scheduled
      ,
      totalBytesOf = {
        val it = Iterator.from(0)
        (_, _) => totalBytes(math.min(it.next(), totalBytes.size - 1))
      },
      effectivePolicyOf = policyOf,
      catalogAlias = (_, _) => "acme_db",
      audit = ai.starlake.quack.ondemand.telemetry.AuditRecorder.noop
    )

    def enqueueAndRun(scope: String = "tenantdb", ops: Option[String] = None): MaintenanceRun =
      val r = store.enqueueMaintenanceRun("acme", "acme_db", scope, "manual", ops)
      store.claimQueuedMaintenanceRun()
      runner.executeRun(r.copy(status = "running")).unsafeRunSync()
      store.listMaintenanceRuns("acme", "acme_db", 10, None).head

  "executeRun" should "run the full chain in order and finish succeeded" in {
    val f   = new Fixture()
    val fin = f.enqueueAndRun()
    fin.status shouldBe "succeeded"
    f.started shouldBe 1
    f.stopped shouldBe List("maint-n1")
    // order: flush, expire, merge, cleanup, orphans (no rewrite: defaults have no
    // per-table rewrite targets in this stubbed fixture)
    f.executed.head should include("ducklake_flush_inlined_data")
    f.executed(1) should include("ducklake_expire_snapshots")
    f.executed.exists(_.contains("ducklake_merge_adjacent_files")) shouldBe true
    f.executed.last should include("ducklake_delete_orphaned_files")
  }

  it should "subtract pinned snapshots from expiry and count them" in {
    val f   = new Fixture(pinnedSnaps = Set(2L, 3L))
    val fin = f.enqueueAndRun()
    f.executed.find(_.contains("expire_snapshots")).get should (include("[1, 4]") or include(
      "[1,4]"
    ))
    fin.counters.snapshotsSkippedPinned shouldBe 2
  }

  it should "skip expiry entirely when all candidates are pinned" in {
    val f = new Fixture(pinnedSnaps = Set(1L, 2L, 3L, 4L))
    f.enqueueAndRun()
    f.executed.exists(_.contains("expire_snapshots")) shouldBe false
  }

  it should "fail safe on a pinned/scheduled file intersection: skip cleanup, mark partial" in {
    val f = new Fixture(
      pinnedFiles = Set("s3://lake/x.parquet"),
      scheduled = List("s3://lake/x.parquet")
    )
    val fin = f.enqueueAndRun()
    fin.status shouldBe "partial"
    f.executed.exists(_.contains("cleanup_old_files")) shouldBe false
    fin.error.getOrElse("") should include("pinned")
  }

  it should "catch a pinned/scheduled intersection created by THIS run's own expire+merge " +
    "(F2: guard reads scheduledForDeletion at cleanup time, not in the chain prelude)" in {
      // scheduledForDeletion only reveals the pinned-intersecting file once merge has actually
      // executed -- standing in for "this run's own expire+merge is what schedules the file for
      // deletion in the first place". If the guard were still computed in the chain prelude
      // (before flush/expire/merge run any exec calls), merge would not have run yet, the stub
      // would answer Nil, and cleanup would wrongly proceed. Reading it lazily inside cleanupIO
      // -- strictly after merge -- is what lets the guard see and catch the hit.
      val f = new Fixture(
        pinnedFiles = Set("s3://lake/x.parquet"),
        scheduled = List("s3://lake/x.parquet"),
        scheduledVisibleAfterMerge = true
      )
      val fin = f.enqueueAndRun()
      f.mergeRan shouldBe true
      fin.status shouldBe "partial"
      f.executed.exists(_.contains("cleanup_old_files")) shouldBe false
      fin.error.getOrElse("") should include("pinned")
      // Exactly one read of scheduledForDeletion per run: reused for both the guard and the
      // reclaimable count, as the finding requires.
      f.scheduledCallCount shouldBe 1
    }

  it should "abort the chain on a step failure, mark failed, still stop the node" in {
    val f   = new Fixture(failOn = Some("merge_adjacent_files"))
    val fin = f.enqueueAndRun()
    fin.status shouldBe "failed"
    fin.error.getOrElse("") should include("boom")
    f.executed.exists(_.contains("cleanup_old_files")) shouldBe false
    f.stopped shouldBe List("maint-n1")
  }

  it should "run only table-safe steps for a table-scoped run" in {
    val f = new Fixture()
    f.enqueueAndRun(scope = "table:tpch1.region")
    f.executed.exists(_.contains("expire_snapshots")) shouldBe false
    f.executed.exists(_.contains("cleanup_old_files")) shouldBe false
    f.executed.exists(_.contains("rewrite_data_files")) shouldBe true
    f.executed.exists(_.contains("merge_adjacent_files")) shouldBe true
  }

  it should "mark the run failed when the node cannot spawn" in {
    val store = new InMemoryControlPlaneStore()
    val r     = store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
    store.claimQueuedMaintenanceRun()
    val runner = new MaintenanceRunner(
      store = store,
      spawn = (_, _) => IO.pure(None),
      stop = _ => IO.unit,
      exec = (_, _) => IO.pure(Right(())),
      snapshotsOlderThan = (_, _, _) => Nil,
      pinnedSnapshotsOf = (_, _) => Set.empty,
      pinnedFilesOf = (_, _) => Set.empty,
      scheduledForDeletion = (_, _) => Nil,
      totalBytesOf = (_, _) => 0L,
      effectivePolicyOf = (_, _, _, _) => EffectivePolicy.defaults.copy(enabled = true),
      catalogAlias = (_, _) => "acme_db",
      audit = ai.starlake.quack.ondemand.telemetry.AuditRecorder.noop
    )
    runner.executeRun(r.copy(status = "running")).unsafeRunSync()
    store.listMaintenanceRuns("acme", "acme_db", 10, None).head.status shouldBe "failed"
  }

  it should "stop the node and mark the run failed when a prelude lookup throws" in {
    val f   = new Fixture(policyOf = (_, _, _, _) => sys.error("policy lookup boom"))
    val fin = f.enqueueAndRun()
    fin.status shouldBe "failed"
    fin.error.getOrElse("") should include("policy lookup boom")
    f.stopped shouldBe List("maint-n1")
  }

  /** Delegates every [[ControlPlaneStore]] call to a real in-memory store, except
    * `heartbeatMaintenanceRun`: the first call (the pre-chain heartbeat in `executeRun`) passes
    * through normally, but every call after that answers `false` -- as if a sweeper reaped the run
    * as a zombie in between -- while independently flipping the underlying row to `"failed"`, the
    * status a real sweep would have left behind. This lets a test prove the runner stops the chain
    * and does not clobber that sweeper-set status (F2).
    */
  private class SweptAfterFirstHeartbeatStore(inner: InMemoryControlPlaneStore)
      extends ControlPlaneStore:
    export inner.{heartbeatMaintenanceRun as _, *}

    private var heartbeatCalls = 0

    override def heartbeatMaintenanceRun(id: Long, counters: RunCounters): Boolean =
      heartbeatCalls += 1
      if heartbeatCalls <= 1 then inner.heartbeatMaintenanceRun(id, counters)
      else
        inner.finishMaintenanceRun(id, "failed", RunCounters(), Some("stale: heartbeat timeout"))
        false

  "executeRun" should "stop the chain at the next heartbeat after a zombie sweep, without " +
    "clobbering the sweeper's status (F2)" in {
      val inner    = new InMemoryControlPlaneStore()
      val store    = new SweptAfterFirstHeartbeatStore(inner)
      var executed = List.empty[String]
      var stopped  = List.empty[String]
      val node     = RunningNode(
        "maint-n1",
        PoolKey("acme", "acme_db", "__maint"),
        Role.Dual,
        "127.0.0.1",
        21999,
        "tok",
        None,
        None,
        Instant.EPOCH,
        maxConcurrent = 1
      )
      val runner = new MaintenanceRunner(
        store = store,
        spawn = (_, _) => IO.pure(Some(node)),
        stop = id => IO { stopped = stopped :+ id },
        exec = (_, sql) => IO { executed = executed :+ sql; Right(()) },
        snapshotsOlderThan = (_, _, _) => List(1L, 2L, 3L, 4L),
        pinnedSnapshotsOf = (_, _) => Set.empty,
        pinnedFilesOf = (_, _) => Set.empty,
        scheduledForDeletion = (_, _) => Nil,
        totalBytesOf = (_, _) => 100L,
        effectivePolicyOf = (_, _, _, _) => EffectivePolicy.defaults.copy(enabled = true),
        catalogAlias = (_, _) => "acme_db",
        audit = ai.starlake.quack.ondemand.telemetry.AuditRecorder.noop
      )

      val r = inner.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
      inner.claimQueuedMaintenanceRun()
      runner.executeRun(r.copy(status = "running")).unsafeRunSync()

      // Only the flush step ran: the heartbeat gating step 2 (expire) already reported the run
      // swept, so the chain stopped there.
      executed.size shouldBe 1
      executed.head should include("ducklake_flush_inlined_data")

      // Node cleanup still happens regardless of how the chain ended.
      stopped shouldBe List("maint-n1")

      // The sweeper's status is left exactly as it set it: the runner must not call the
      // store's finish-with-success path or otherwise flip status back.
      val finalRun = inner.listMaintenanceRuns("acme", "acme_db", 10, None).head
      finalRun.status shouldBe "failed"
      finalRun.error shouldBe Some("stale: heartbeat timeout")
    }
