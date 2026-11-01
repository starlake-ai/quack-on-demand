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
      totalBytes: List[Long] = List(100L, 100L), // sampled before/after the chain
      failOn: Option[String] = None,             // substring of a chain statement that should fail
      policyOf: (String, String, Option[String], Option[String]) => EffectivePolicy =
        (_, _, _, _) => EffectivePolicy.defaults.copy(enabled = true),
      // Rows the node returns for ducklake_cleanup_old_files: one per file it deleted.
      cleanupRows: Long = 0L,
      // Answers hasLiveBranchOf, call by call (the last answer repeats): a live branch reads the
      // parent's files by absolute path, so merge and cleanup must not run under it (audit F25).
      liveBranch: List[Boolean] = List(false),
      // Snapshots protected while the run goes on: pinned as soon as the flush step ran.
      pinnedDuringRun: Set[Long] = Set.empty
  ):
    val store    = new InMemoryControlPlaneStore()
    var executed = List.empty[String]
    var stopped  = List.empty[String]
    var started  = 0
    var mergeRan = false
    var pinsNow  = pinnedSnaps
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
      spawn = (_, _) => IO { started += 1; Some(node) },
      stop = node => IO { stopped = stopped :+ node.nodeId },
      exec = (_, sql) =>
        IO {
          executed = executed :+ sql
          if sql.contains("merge_adjacent_files") then mergeRan = true
          if sql.contains("flush_inlined_data") then pinsNow = pinsNow ++ pinnedDuringRun
          if failOn.exists(sql.contains) then Left("boom")
          else Right(if sql.contains("cleanup_old_files") then cleanupRows else 0L)
        },
      snapshotsOlderThan = (_, _, _) => List(1L, 2L, 3L, 4L), // 4 candidates
      pinnedSnapshotsOf = (_, _) => pinsNow,
      hasLiveBranchOf = {
        val answers = liveBranch.iterator
        var last    = false
        (_, _) =>
          if answers.hasNext then last = answers.next()
          last
      },
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

  it should "never expire a snapshot protected while the run was under way" in {
    val f      = new Fixture(pinnedDuringRun = Set(2L))
    val fin    = f.enqueueAndRun()
    val expire = f.executed.find(_.contains("expire_snapshots")).get
    expire should include("[1, 3, 4]")
    fin.counters.snapshotsSkippedPinned shouldBe 1
    fin.counters.snapshotsExpired shouldBe 3
  }

  it should "hold the snapshot-pin lock through the expire call, and only then" in {
    val f        = new Fixture()
    val lockedAt = scala.collection.mutable.ListBuffer.empty[String]
    // A probe store call made while the run executes each statement: can the lock be taken now?
    val probe = new MaintenanceRunner(
      store = f.store,
      spawn = (_, _) => IO.pure(Some(f.node)),
      stop = _ => IO.unit,
      exec = (_, sql) =>
        IO {
          val free = new java.util.concurrent.Semaphore(0)
          val t    = new Thread(() => {
            f.store.lockSnapshotPins("acme", "acme_db").close(); free.release()
          })
          t.setDaemon(true)
          t.start()
          // Held: the probe thread takes it once the run lets go, so it is not joined here.
          if free.tryAcquire(200, java.util.concurrent.TimeUnit.MILLISECONDS) then t.join()
          else lockedAt += sql
          Right(0L)
        },
      snapshotsOlderThan = (_, _, _) => List(1L, 2L),
      pinnedSnapshotsOf = (_, _) => Set.empty,
      hasLiveBranchOf = (_, _) => false,
      totalBytesOf = (_, _) => 0L,
      effectivePolicyOf = (_, _, _, _) => EffectivePolicy.defaults.copy(enabled = true),
      catalogAlias = (_, _) => "acme_db",
      audit = ai.starlake.quack.ondemand.telemetry.AuditRecorder.noop
    )
    val r = f.store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
    f.store.claimQueuedMaintenanceRun()
    probe.executeRun(r.copy(status = "running")).unsafeRunSync()
    lockedAt.toList match
      case List(sql) => sql should include("expire_snapshots")
      case other     => fail(s"the lock was held during: $other")
  }

  it should "start no node and run nothing for a branch database" in {
    val f = new Fixture()
    f.store.upsertTenant(Tenant(id = "acme"))
    f.store.upsertTenantDb(
      TenantDb(
        id = "td-br",
        tenantId = "acme",
        name = "acme_db",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = "/tmp/x__br_1/",
        branchOf = Some("td-parent")
      )
    )
    val fin = f.enqueueAndRun()
    f.started shouldBe 0
    f.executed shouldBe empty
    fin.status shouldBe "failed"
    fin.error.getOrElse("") should include("branch")
  }

  it should "skip expiry entirely when all candidates are pinned" in {
    val f = new Fixture(pinnedSnaps = Set(1L, 2L, 3L, 4L))
    f.enqueueAndRun()
    f.executed.exists(_.contains("expire_snapshots")) shouldBe false
  }

  it should "count the files cleanup reports deleting, not the files scheduled" in {
    // ducklake_cleanup_old_files returns one row per file it deleted; files still inside the
    // grace window stay scheduled and are not counted.
    new Fixture(cleanupRows = 2L).enqueueAndRun().counters.filesCleaned shouldBe 2
    new Fixture(cleanupRows = 0L).enqueueAndRun().counters.filesCleaned shouldBe 0
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

  it should "merge only the scoped table on a table-scoped run" in {
    val f = new Fixture()
    f.enqueueAndRun(scope = "table:tpch1.region")
    val merge = f.executed.find(_.contains("merge_adjacent_files")).get
    merge shouldBe MaintenanceChainSql.mergeAdjacentTable("acme_db", "tpch1", "region")
  }

  it should "merge the whole database on a tenantdb run" in {
    val f = new Fixture()
    f.enqueueAndRun()
    f.executed.find(_.contains("merge_adjacent_files")).get shouldBe
      MaintenanceChainSql.mergeAdjacent("acme_db")
  }

  it should "rewrite with the policy's delete threshold" in {
    val f = new Fixture(policyOf =
      (_, _, _, _) => EffectivePolicy.defaults.copy(enabled = true, rewriteDeleteThreshold = 0.05)
    )
    f.enqueueAndRun(scope = "table:tpch1.region")
    f.executed.find(_.contains("rewrite_data_files")).get should
      include("delete_threshold => 0.05")
  }

  it should "mark the run failed when the node cannot spawn" in {
    val store = new InMemoryControlPlaneStore()
    val r     = store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
    store.claimQueuedMaintenanceRun()
    val runner = new MaintenanceRunner(
      store = store,
      spawn = (_, _) => IO.pure(None),
      stop = _ => IO.unit,
      exec = (_, _) => IO.pure(Right(0L)),
      snapshotsOlderThan = (_, _, _) => Nil,
      pinnedSnapshotsOf = (_, _) => Set.empty,
      hasLiveBranchOf = (_, _) => false,
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
    * `heartbeatMaintenanceRun`: the first `passThrough` calls (the first one is the pre-chain
    * heartbeat in `executeRun`) pass through normally, but every call after that answers `false` --
    * as if a sweeper reaped the run as a zombie in between -- while independently flipping the
    * underlying row to `"failed"`, the status a real sweep would have left behind. This lets a test
    * prove the runner stops the chain and does not clobber that sweeper-set status (F2).
    */
  private class SweptAfterFirstHeartbeatStore(inner: InMemoryControlPlaneStore, passThrough: Int)
      extends ControlPlaneStore:
    export inner.{heartbeatMaintenanceRun as _, *}

    private var heartbeatCalls = 0

    override def heartbeatMaintenanceRun(id: Long, counters: RunCounters): Boolean =
      heartbeatCalls += 1
      if heartbeatCalls <= passThrough then inner.heartbeatMaintenanceRun(id, counters)
      else
        inner.finishMaintenanceRun(id, "failed", RunCounters(), Some("stale: heartbeat timeout"))
        false

  /** Runs a tenantdb run on a store swept after `passThrough` heartbeats; returns the statements
    * executed, the nodes stopped and the run's final row.
    */
  private def runSwept(passThrough: Int): (List[String], List[String], MaintenanceRun) =
    val inner    = new InMemoryControlPlaneStore()
    val store    = new SweptAfterFirstHeartbeatStore(inner, passThrough)
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
      stop = node => IO { stopped = stopped :+ node.nodeId },
      exec = (_, sql) => IO { executed = executed :+ sql; Right(0L) },
      snapshotsOlderThan = (_, _, _) => List(1L, 2L, 3L, 4L),
      pinnedSnapshotsOf = (_, _) => Set.empty,
      hasLiveBranchOf = (_, _) => false,
      totalBytesOf = (_, _) => 100L,
      effectivePolicyOf = (_, _, _, _) => EffectivePolicy.defaults.copy(enabled = true),
      catalogAlias = (_, _) => "acme_db",
      audit = ai.starlake.quack.ondemand.telemetry.AuditRecorder.noop
    )

    val r = inner.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
    inner.claimQueuedMaintenanceRun()
    runner.executeRun(r.copy(status = "running")).unsafeRunSync()
    (executed, stopped, inner.listMaintenanceRuns("acme", "acme_db", 10, None).head)

  "executeRun" should "stop the chain at the next heartbeat after a zombie sweep, without " +
    "clobbering the sweeper's status (F2)" in {
      val (executed, stopped, finalRun) = runSwept(passThrough = 1)

      // Only the flush step ran: the heartbeat gating step 2 (expire) already reported the run
      // swept, so the chain stopped there.
      executed.size shouldBe 1
      executed.head should include("ducklake_flush_inlined_data")

      // Node cleanup still happens regardless of how the chain ended.
      stopped shouldBe List("maint-n1")

      // The sweeper's status is left exactly as it set it: the runner must not call the
      // store's finish-with-success path or otherwise flip status back.
      finalRun.status shouldBe "failed"
      finalRun.error shouldBe Some("stale: heartbeat timeout")
    }

  it should "run no step when the run was swept before its first heartbeat" in {
    // The sweeper failed the run while the node was starting: no statement may run, cleanup
    // included, and the sweeper's status stays.
    val (executed, stopped, finalRun) = runSwept(passThrough = 0)
    executed shouldBe empty
    stopped shouldBe List("maint-n1")
    finalRun.status shouldBe "failed"
    finalRun.error shouldBe Some("stale: heartbeat timeout")
  }

  // Audit F25: a branch's catalog points at the parent's Parquet files by absolute path, and
  // merge_adjacent_files hard-deletes the merged rows, so no pin derived from the parent can see
  // what a later cleanup removes from under the branch.
  "a database with a live branch" should "skip merge and cleanup, and record partial" in {
    val f   = new Fixture(liveBranch = List(true))
    val fin = f.enqueueAndRun()
    f.executed.exists(_.contains("merge_adjacent_files")) shouldBe false
    f.executed.exists(_.contains("cleanup_old_files")) shouldBe false
    f.executed.head should include("ducklake_flush_inlined_data")
    f.executed.exists(_.contains("ducklake_expire_snapshots")) shouldBe true
    f.executed.last should include("ducklake_delete_orphaned_files")
    fin.status shouldBe "partial"
    fin.error.getOrElse("") should (include("merge skipped") and include("cleanup skipped"))
    fin.error.getOrElse("") should include("live branch")
  }

  it should "skip cleanup when the branch appears after the merge step" in {
    // Read at cleanup time, not in the prelude: a branch cloned while the merge ran forks over
    // files the merge just scheduled.
    val f   = new Fixture(liveBranch = List(false, true))
    val fin = f.enqueueAndRun()
    f.executed.exists(_.contains("merge_adjacent_files")) shouldBe true
    f.executed.exists(_.contains("cleanup_old_files")) shouldBe false
    fin.status shouldBe "partial"
  }

  it should "leave a database without a live branch on the full chain" in {
    val f   = new Fixture(liveBranch = List(false))
    val fin = f.enqueueAndRun()
    f.executed.exists(_.contains("merge_adjacent_files")) shouldBe true
    f.executed.exists(_.contains("cleanup_old_files")) shouldBe true
    fin.status shouldBe "succeeded"
  }
