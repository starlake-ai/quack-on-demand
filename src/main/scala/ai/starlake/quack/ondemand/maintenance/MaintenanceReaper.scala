package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model.{MaintenanceRun, PoolKey, ReapedRun}
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

/** The HA leader's sweep of running maintenance runs (issue #210), a singleton duty. A run whose
  * liveness heartbeat ([[MaintenanceLiveness]]) is older than `livenessTimeoutSec` lost its
  * replica: it is failed and its `__maint` node stopped here, since nothing else will. A run
  * started more than `runTimeoutSec` ago is failed too, but its node is left to its live owner,
  * whose heartbeat sees the row change and stops it, so no replica kills a node under a live run.
  * Neither is requeued: the next cadence or threshold tick queues a fresh run.
  */
final class MaintenanceReaper(
    store: ControlPlaneStore,
    livenessTimeoutSec: Int,
    runTimeoutSec: Long,
    stopNode: (PoolKey, String) => IO[Unit],
    audit: AuditRecorder,
    metrics: MaintenanceMetrics
) extends LazyLogging:

  def reapOnce: IO[Unit] =
    IO.blocking(store.reapMaintenanceRuns(livenessTimeoutSec, runTimeoutSec))
      .flatMap(_.traverse_(settle))

  private def settle(reaped: ReapedRun): IO[Unit] =
    val run   = reaped.run
    val label = s"maintenance run ${run.id} (${run.tenant}/${run.tenantDb})"
    val stop  = run.nodeId match
      case Some(id) if reaped.silent =>
        stopNode(PoolKey(run.tenant, run.tenantDb, "__maint"), id).handleErrorWith(e =>
          IO.delay(logger.warn(s"$label: stopping its node $id failed: ${e.getMessage}"))
        )
      case _ => IO.unit
    IO.delay(logger.warn(s"$label reaped: ${run.error.getOrElse("")}")) *> stop *> record(run)

  private def record(run: MaintenanceRun): IO[Unit] =
    val audited = IO
      .blocking(
        audit.restAs(
          "system",
          "system",
          "control-plane",
          AuditActions.MaintenanceRun,
          "failed",
          tenant = Some(run.tenant),
          target = Some(s"${run.tenantDb}/${run.scope}"),
          detail = Map("trigger" -> run.trigger, "reason" -> run.error.getOrElse(""))
        )
      )
      .handleErrorWith(_ => IO.unit)
    val duration =
      (for
        s <- run.startedAt
        f <- run.finishedAt
      yield math.max(0L, f.toEpochMilli - s.toEpochMilli) / 1000.0).getOrElse(0.0)
    val measured = IO
      .delay(
        metrics.recordRun(
          tenant = run.tenant,
          tenantDb = run.tenantDb,
          result = "failed",
          durationSeconds = duration,
          bytesReclaimed = run.counters.bytesReclaimed,
          filesCompacted = run.counters.filesMerged + run.counters.filesRewritten,
          snapshotsExpired = run.counters.snapshotsExpired
        )
      )
      .handleErrorWith(_ => IO.unit)
    audited *> measured
