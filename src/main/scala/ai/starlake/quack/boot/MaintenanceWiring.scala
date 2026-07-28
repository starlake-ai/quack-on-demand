package ai.starlake.quack.boot

import ai.starlake.quack.MaintenanceConfig
import ai.starlake.quack.edge.adapter.{QuackHttpAdapter, QuackResponse}
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, PinnedSetResolver}
import ai.starlake.quack.ondemand.ha.PoolLocker
import ai.starlake.quack.ondemand.maintenance.{MaintenanceRunner, MaintenanceScheduler, PolicyMath}
import ai.starlake.quack.ondemand.runtime.{NodeReadiness, QuackBackend}
import ai.starlake.quack.ondemand.state.PostgresControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging

/** Managed maintenance (EPIC Spec 09), extracted from Main.bootManager: leader-gated
  * cadence/threshold enqueue (scheduler) + the drain loop that claims queued runs and executes them
  * under a per-tenant-db advisory lock (runner). Both fibers are no-ops when the config flag is
  * off, so cancellation stays uniform in Main's fiber teardown.
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
      runTimeoutMinutes = maintenance.runTimeoutMin,
      staggerOf = PolicyMath.staggerMinutes,
      tickSeconds = maintenance.tickSec,
      isLeader = isLeader
    )

  private val pinnedSetResolver = new PinnedSetResolver(store, catalogReader)

  private val runner =
    new MaintenanceRunner(
      store = store,
      spawn = (t, td) =>
        sup.maintenanceNodeSpec(t, td) match
          case Some(spec) =>
            backend
              .start(spec)
              .flatMap { node =>
                // start() returns at fork time (local backend); the node only listens
                // once DuckDB finishes INSTALL/LOAD + ATTACH. Gate here or the chain's
                // first statement dies with ConnectException.
                NodeReadiness
                  .awaitReachable(
                    node.host,
                    node.port,
                    timeout = scala.concurrent.duration
                      .DurationInt(maintenance.nodeReadyTimeoutSec)
                      .seconds,
                    isAlive = () => backend.isAlive(node.nodeId)
                  )
                  .flatMap { ready =>
                    if ready then IO.pure(Some(node))
                    else
                      IO.delay(
                        logger.warn(
                          s"maintenance: node ${node.nodeId} for $t/$td did not " +
                            "accept connections within " +
                            s"${maintenance.nodeReadyTimeoutSec}s; stopping it"
                        )
                      ) *> backend
                        .stop(node.nodeId)
                        .handleErrorWith(_ => IO.unit)
                        .as(None)
                  }
              }
              .handleErrorWith { e =>
                IO.delay(
                  logger.warn(
                    s"maintenance: node spawn failed for $t/$td: ${e.getMessage}"
                  )
                ).as(None)
              }
          case None => IO.pure(None),
      stop = id => backend.stop(id).handleErrorWith(_ => IO.unit),
      exec = (node, sql) =>
        adapter.send(node, sql, session = None, recordLoad = false).map {
          case QuackResponse.Ok(r, _, close) =>
            close(); Right(())
          case QuackResponse.Failed(e, _) =>
            Left(e.toString)
        },
      snapshotsOlderThan = (t, td, cutoff) => catalogReader(t, td).snapshotsOlderThan(cutoff),
      pinnedSnapshotsOf = pinnedSetResolver.pinnedSnapshots,
      pinnedFilesOf = pinnedSetResolver.pinnedFiles,
      scheduledForDeletion = (t, td) => catalogReader(t, td).filesScheduledForDeletion(),
      totalBytesOf = (t, td) => catalogReader(t, td).totalDataFileBytes(),
      effectivePolicyOf = (t, td, s, tb) =>
        PolicyMath.effective(store.listMaintenancePolicies(t, td), s, tb),
      catalogAlias = (t, td) => sup.effectiveMetastoreFor(t, td).getOrElse("dbName", td),
      audit = audit,
      metrics = metrics
    )

  def schedulerFiber: IO[FiberIO[Unit]] =
    if maintenance.enabled then scheduler.start
    else IO.unit.start

  /** Drain loop: while leader and below maxConcurrent in-flight, claim queued runs and fork each
    * execution under the tenant-db's __maint advisory lock so two replicas (or a replica and a
    * stray retry) never run the same lake at once. The tick keeps claiming until the queue is empty
    * or capacity is reached, so tickSec only spaces empty polls.
    */
  def drainFiber: IO[FiberIO[Unit]] =
    val inFlight            = new java.util.concurrent.atomic.AtomicInteger(0)
    def drainTick: IO[Unit] =
      if !isLeader() then IO.unit
      else if inFlight.get() >= maintenance.maxConcurrent then IO.unit
      else
        IO.blocking(store.claimQueuedMaintenanceRun()).flatMap {
          case None      => IO.unit
          case Some(run) =>
            inFlight.incrementAndGet()
            poolLocks
              .withLock(PoolKey(run.tenant, run.tenantDb, "__maint")) {
                runner.executeRun(run)
              }
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
    if maintenance.enabled then
      (drainTick
        .handleErrorWith(e =>
          IO(logger.error(s"maintenance drain-loop tick failed: ${e.getMessage}"))
        )
        *> IO.sleep(
          scala.concurrent.duration.DurationInt(maintenance.tickSec).seconds
        )).foreverM.void.start
    else IO.unit.start
