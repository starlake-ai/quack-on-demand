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

/** Regression coverage for the same construction-time leader-gate latching bug fixed in
  * [[AutoscaleWiringSpec]]'s "re-evaluate the leader gate on every run" case: `drainFiber` used to
  * build its forever loop from a `def drainTick` called exactly ONCE (to feed `.foreverM`), so the
  * `if !isLeader()` head was baked in at fiber construction instead of being re-checked every tick.
  * A replica that boots as follower and later wins leadership would never drain the maintenance
  * queue; one that boots as leader would keep draining after demotion.
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
    def start(spec: NodeSpec): IO[RunningNode]       = IO.raiseError(new RuntimeException("unused"))
    def stop(key: PoolKey, nodeId: String): IO[Unit] = IO.unit
    def isAlive(nodeId: String): Boolean             = false
    def discoverExisting(): IO[List[RunningNode]]    = IO.pure(Nil)
    def cleanup(): IO[Unit]                          = IO.unit

  /** Fresh DB + migrated schema, a fully-stubbed [[MaintenanceWiring]] wired against it. Mirrors
    * [[ai.starlake.quack.ondemand.state.MaintenanceStoreSpec]]'s live-Postgres fixture.
    */
  private def withWiring(cfg: MaintenanceConfig, isLeaderFn: () => Boolean)(
      test: (MaintenanceWiring, PostgresControlPlaneStore) => Unit
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
        new QuackHttpClient(new RootAllocator(), nativeClient = false, nodeDisableSsl = true),
        new NodeLoadTracker()
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
        audit = AuditRecorder.noop,
        metrics = MaintenanceMetrics.noop
      )
      try test(wiring, store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "drainTick" should "re-evaluate the leader gate on every run, not just once when it is bound" in {
    var leader = false
    withWiring(MaintenanceConfig(), () => leader) { (w, store) =>
      store.enqueueMaintenanceRun("acme", "acme_db", "tenantdb", "manual", None)

      val tick = w.drainTick // bind ONCE, like AutoscaleWiringSpec's `val s = w.sweep()`

      tick.unsafeRunSync() // not leader yet: must not claim
      store
        .listMaintenanceRuns("acme", "acme_db", 10, None)
        .head
        .status shouldBe "queued"

      leader = true
      tick.unsafeRunSync() // SAME bound IO, now leader: must claim fresh, not stay latched
      store
        .listMaintenanceRuns("acme", "acme_db", 10, None)
        .head
        .status should not be "queued"
    }
  }
