package ai.starlake.quack.boot

import ai.starlake.quack.MaintenanceConfig
import ai.starlake.quack.edge.adapter.{QuackHttpAdapter, QuackResponse}
import ai.starlake.quack.model.{NodeSpec, PoolKey, RunningNode, TenantDb}
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, PinnedSetResolver}
import ai.starlake.quack.ondemand.ha.PoolLocker
import ai.starlake.quack.ondemand.maintenance.{
  MaintenanceLiveness,
  MaintenanceReaper,
  MaintenanceRunner,
  MaintenanceScheduler,
  PolicyMath
}
import ai.starlake.quack.ondemand.runtime.{NodeReadiness, QuackBackend}
import ai.starlake.quack.ondemand.state.PostgresControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging

/** Managed maintenance (EPIC Spec 09, issue #210): leader-gated cadence/threshold enqueue
  * (scheduler), and the drain loop every replica runs, HA followers first, that claims queued runs
  * and executes them under a per-tenant-db advisory lock (runner). Both fibers are no-ops when the
  * config flag is off, so cancellation stays uniform in Main's fiber teardown.
  */
final class MaintenanceWiring(
    store: PostgresControlPlaneStore,
    sup: PoolSupervisor,
    backend: QuackBackend,
    adapter: QuackHttpAdapter,
    poolLocks: PoolLocker,
    catalogReader: (String, String) => DuckLakeCatalogReader,
    maintenance: MaintenanceConfig,
    isLeader: () => Boolean,
    haEnabled: Boolean,
    audit: AuditRecorder,
    metrics: MaintenanceMetrics
) extends LazyLogging:

  private val scheduler =
    new MaintenanceScheduler(
      store = store,
      smallFileCountsOf = (t, td, bytes) =>
        try catalogReader(t, td).smallFileCounts(bytes)
        catch
          case e: Exception =>
            logger.warn(
              s"maintenance: small-file count read failed for $t/$td, " +
                s"treating as no hot tables this tick: ${e.getMessage}"
            )
            Map.empty,
      minIntervalMinutes = maintenance.minIntervalMin,
      staggerOf = PolicyMath.staggerMinutes,
      tickSeconds = maintenance.tickSec,
      isLeader = isLeader
    )

  private val pinnedSetResolver = new PinnedSetResolver(store)

  /** Start a maintenance node and wait until it accepts connections. `start()` returns at fork time
    * on the local backend; the node only listens once DuckDB finishes INSTALL/LOAD + ATTACH, so the
    * chain's first statement would otherwise die with ConnectException.
    */
  private def startNode(spec: NodeSpec): IO[Option[RunningNode]] =
    val (t, td) = (spec.poolKey.tenant, spec.poolKey.tenantDb)
    backend
      .start(spec)
      .flatMap { node =>
        NodeReadiness
          .awaitReachable(
            node.host,
            node.port,
            timeout =
              scala.concurrent.duration.DurationInt(maintenance.nodeReadyTimeoutSec).seconds,
            isAlive = () => backend.isAlive(node.nodeId)
          )
          .flatMap { ready =>
            if ready then IO.pure(Some(node))
            else
              IO.delay(
                logger.warn(
                  s"maintenance: node ${node.nodeId} for $t/$td did not " +
                    s"accept connections within ${maintenance.nodeReadyTimeoutSec}s; stopping it"
                )
              ) *> backend
                .stop(node.poolKey, node.nodeId)
                .handleErrorWith(_ => IO.unit)
                .as(None)
          }
      }
      .handleErrorWith { e =>
        IO.delay(logger.warn(s"maintenance: node spawn failed for $t/$td: ${e.getMessage}"))
          .as(None)
      }

  private val runner =
    new MaintenanceRunner(
      store = store,
      specOf = (t, td) => sup.maintenanceNodeSpec(t, td),
      start = startNode,
      stop = node => backend.stop(node.poolKey, node.nodeId).handleErrorWith(_ => IO.unit),
      // Right(rows returned): ducklake_cleanup_old_files returns one row per file it deleted.
      exec = (node, sql) =>
        adapter
          .send(
            node,
            sql,
            ai.starlake.quack.edge.authz.NodePrincipal.System,
            session = None,
            recordLoad = false
          )
          .flatMap {
            case QuackResponse.Ok(r, _, close) =>
              IO.blocking {
                var rows = 0L
                while r.loadNextBatch() do rows += r.getVectorSchemaRoot.getRowCount
                rows
              }.guarantee(IO(close()))
                .attempt
                .map(_.left.map(e => s"reading the result: ${e.getMessage}"))
            case QuackResponse.Failed(e, _) =>
              IO.pure(Left(e.toString))
          },
      snapshotsOlderThan = (t, td, cutoff) => catalogReader(t, td).snapshotsOlderThan(cutoff),
      pinnedSnapshotsOf = pinnedSetResolver.pinnedSnapshots,
      expiryFloorOf = pinnedSetResolver.expiryFloor,
      hasLiveBranchOf = pinnedSetResolver.hasLiveBranch,
      totalBytesOf = (t, td) => catalogReader(t, td).totalDataFileBytes(),
      effectivePolicyOf = (t, td, s, tb) =>
        PolicyMath.effective(store.listMaintenancePolicies(t, td), s, tb),
      catalogAlias = (t, td) => TenantDb.catalogAlias(sup.effectiveMetastoreFor(t, td), td),
      audit = audit,
      metrics = metrics
    )

  def schedulerFiber: IO[FiberIO[Unit]] =
    if maintenance.enabled then scheduler.start
    else IO.unit.start

  private val inFlight = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Seconds a queued run must have waited before this replica may claim it (issue #210): none for
    * a single manager or an HA follower, `effectiveLeaderGraceSec` for the HA leader, so followers
    * take runs first and the leader takes only what none took in time.
    */
  private[boot] def claimMinAgeSec: Int =
    if haEnabled && isLeader() then maintenance.effectiveLeaderGraceSec else 0

  private val reaper =
    new MaintenanceReaper(
      store = store,
      livenessTimeoutSec = 3 * maintenance.tickSec,
      runTimeoutSec = maintenance.runTimeoutMin * 60L,
      stopNode = (key, nodeId) => backend.stop(key, nodeId),
      audit = audit,
      metrics = metrics
    )

  /** Leader only (a single manager always leads): fail runs whose owner went silent or that ran too
    * long, see [[MaintenanceReaper]]. IO.defer for the same reason as [[drainTick]]: the leader
    * check must run on every pass of the bound IO.
    */
  private[boot] def reapTick: IO[Unit] = IO.defer {
    if isLeader() then reaper.reapOnce else IO.unit
  }

  /** One drain pass: while below maxConcurrent in-flight, claim queued runs old enough for this
    * replica ([[claimMinAgeSec]]) and fork each execution under the tenant-db's __maint advisory
    * lock so two replicas never run the same lake at once. Keeps claiming until nothing claimable
    * is left or capacity is reached, so `tickSec` (in [[drainFiber]]) only spaces empty polls.
    *
    * IO.defer around the whole pass: `drainFiber` binds this to a single IO value once (see its
    * `foreverM`), so the claim mode (and in-flight) must be re-evaluated on every run rather than
    * baked in at construction time: a replica that boots as follower and is later promoted must
    * start waiting out the grace, and a demoted one must stop. Mirrors [[AutoscaleWiring.sweep]].
    */
  private[boot] def drainTick: IO[Unit] = IO.defer {
    if inFlight.get() >= maintenance.maxConcurrent then IO.unit
    else
      IO.blocking(store.claimQueuedMaintenanceRun(claimMinAgeSec)).flatMap {
        case None      => IO.unit
        case Some(run) =>
          inFlight.incrementAndGet()
          // The liveness heartbeat covers the lock wait too, so a run queued behind another
          // replica's run on the same lake is not reaped as silent.
          MaintenanceLiveness
            .guard(
              store,
              run,
              scala.concurrent.duration.DurationInt(maintenance.tickSec).seconds,
              (key, nodeId) => backend.stop(key, nodeId)
            )(
              poolLocks.withLock(PoolKey(run.tenant, run.tenantDb, "__maint")) {
                runner.executeRun(run)
              }
            )
            .guarantee(IO.delay(inFlight.decrementAndGet()).void)
            .handleErrorWith(t =>
              IO.delay(
                logger.error(
                  s"maintenance run ${run.id} (${run.tenant}/${run.tenantDb}) failed: ${t.getMessage}"
                )
              )
            )
            .start *> drainTick
      }
  }

  def drainFiber: IO[FiberIO[Unit]] =
    if maintenance.enabled then
      (reapTick.handleErrorWith(e =>
        IO(logger.error(s"maintenance reap tick failed: ${e.getMessage}"))
      ) *>
        drainTick.handleErrorWith(e =>
          IO(logger.error(s"maintenance drain-loop tick failed: ${e.getMessage}"))
        ) *>
        IO.sleep(
          scala.concurrent.duration.DurationInt(maintenance.tickSec).seconds
        )).foreverM.void.start
    else IO.unit.start
