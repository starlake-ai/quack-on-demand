package ai.starlake.quack.boot

import ai.starlake.quack.MaintenanceConfig
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackHttpAdapter, QuackHttpClient}
import ai.starlake.quack.model.{NodeSpec, PoolKey, RunningNode}
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.ha.PoolLocker
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  LiquibaseRunner,
  PostgresControlPlaneStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Pins the claim mode (HA follower at once, HA leader after the grace, single manager at once) and
  * that it is re-evaluated on every run: `drainFiber` binds `drainTick` to one IO value once (to
  * feed `.foreverM`), so a mode baked in at construction would latch a replica that is later
  * promoted or demoted (the bug fixed in [[AutoscaleWiringSpec]]'s "re-evaluate the leader gate on
  * every run" case).
  *
  * Needs a real Postgres (via [[TestPostgres]]) because [[MaintenanceWiring]] takes a concrete
  * [[PostgresControlPlaneStore]] directly (not the
  * [[ai.starlake.quack.ondemand.state.ControlPlaneStore]] trait) for `drainTick`'s
  * `claimQueuedMaintenanceRun` call. Every other collaborator (`PoolSupervisor`, `QuackBackend`,
  * `QuackHttpAdapter`, `PoolLocker`) is a minimal stub: with no pool registered for the run's
  * tenant/tenant-db, `PoolSupervisor.maintenanceNodeSpec` returns `None`, so
  * `MaintenanceRunner.executeRun` short-circuits to "node spawn failed" without ever touching the
  * backend/adapter/catalogReader stubs -- the test only needs the DB-level claim (queued ->
  * running), which happens synchronously inside `drainTick` before any of that runs.
  */
class MaintenanceWiringSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodmw")

  private val stubBackend: QuackBackend = new QuackBackend:
    def allowsLocalFiles = true

    def start(spec: NodeSpec): IO[RunningNode]       = IO.raiseError(new RuntimeException("unused"))
    def stop(key: PoolKey, nodeId: String): IO[Unit] = IO.unit
    def isAlive(nodeId: String): Boolean             = false
    def discoverExisting(): IO[List[RunningNode]]    = IO.pure(Nil)
    def cleanup(): IO[Unit]                          = IO.unit

  /** Fresh DB + migrated schema, a fully-stubbed [[MaintenanceWiring]] wired against it. Mirrors
    * [[ai.starlake.quack.ondemand.state.MaintenanceStoreSpec]]'s live-Postgres fixture.
    */
  private def withWiring(cfg: MaintenanceConfig, isLeaderFn: () => Boolean, haEnabled: Boolean)(
      test: (MaintenanceWiring, PostgresControlPlaneStore, String => Unit) => Unit
  ): Unit =
    if !TestPostgres.reachable then
      cancel(
        s"local Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort} " +
          "(SL_TEST_PG_* envs); skipping"
      )
    val dbName = s"qodmw_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val sup   = new PoolSupervisor(
        backend = stubBackend,
        tracker = new NodeLoadTracker(),
        store = new InMemoryControlPlaneStore()
      )
      val adapter = new QuackHttpAdapter(
        new QuackHttpClient(new RootAllocator()),
        new NodeLoadTracker(),
        ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
      )
      val wiring = new MaintenanceWiring(
        store = store,
        sup = sup,
        backend = stubBackend,
        adapter = adapter,
        poolLocks = PoolLocker.noop,
        catalogReader = (_, _) =>
          throw new NotImplementedError("not exercised: no node in this fixture"),
        maintenance = cfg,
        isLeader = isLeaderFn,
        haEnabled = haEnabled,
        audit = AuditRecorder.noop,
        metrics = MaintenanceMetrics.noop
      )
      try test(wiring, store, sql => TestPostgres.psql(dbName, sql))
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def statusOf(store: PostgresControlPlaneStore): String =
    store.listMaintenanceRuns("acme", "acme_db", 10, None).head.status

  "drainTick" should "re-evaluate the claim mode on every run, not just once when it is bound" in {
    var leader = true
    withWiring(MaintenanceConfig(), () => leader, haEnabled = true) { (w, store, _) =>
      store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)

      val tick = w.drainTick // bind ONCE, like AutoscaleWiringSpec's `val s = w.sweep()`

      tick.unsafeRunSync() // HA leader, fresh run: inside the grace, not claimed
      statusOf(store) shouldBe "queued"

      leader = false
      tick.unsafeRunSync() // SAME bound IO, now a follower: claims at once
      statusOf(store) should not be "queued"
    }
  }

  it should "let an HA follower claim a fresh run" in
    withWiring(MaintenanceConfig(), () => false, haEnabled = true) { (w, store, _) =>
      store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
      w.claimMinAgeSec shouldBe 0
      w.drainTick.unsafeRunSync()
      statusOf(store) should not be "queued"
    }

  it should "let the HA leader claim a run only once it waited past the grace" in
    withWiring(MaintenanceConfig(), () => true, haEnabled = true) { (w, store, sql) =>
      val r = store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
      w.claimMinAgeSec shouldBe 180 // three default ticks
      w.drainTick.unsafeRunSync()
      statusOf(store) shouldBe "queued"
      sql(
        s"UPDATE qodstate_maintenance_run SET queued_at = now() - interval '181 seconds' " +
          s"WHERE id = ${r.id}"
      )
      w.drainTick.unsafeRunSync()
      statusOf(store) should not be "queued"
    }

  it should "let a single manager claim a fresh run" in
    withWiring(MaintenanceConfig(), () => true, haEnabled = false) { (w, store, _) =>
      store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
      w.claimMinAgeSec shouldBe 0
      w.drainTick.unsafeRunSync()
      statusOf(store) should not be "queued"
    }

  "reapTick" should "reap a silent run on the leader only, re-evaluated on every run" in {
    var leader = false
    withWiring(MaintenanceConfig(), () => leader, haEnabled = true) { (w, store, sql) =>
      val r = store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)
      store.claimQueuedMaintenanceRun(minAgeSec = 0)
      sql(
        s"UPDATE qodstate_maintenance_run SET heartbeat_at = now() - interval '10 minutes' " +
          s"WHERE id = ${r.id}"
      )
      val tick = w.reapTick
      tick.unsafeRunSync() // follower: reaps nothing
      store.findMaintenanceRun(r.id).map(_.status) shouldBe Some("running")
      leader = true
      tick.unsafeRunSync()
      store.findMaintenanceRun(r.id).map(r => (r.status, r.error)) shouldBe
        Some(("failed", Some(ai.starlake.quack.model.MaintenanceRun.ReapedSilent)))
    }
  }
