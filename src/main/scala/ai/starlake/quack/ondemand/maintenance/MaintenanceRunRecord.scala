package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model.{MaintenanceRun, RunCounters}
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO

/** The audit row and the metrics of a maintenance run that ended, written the same way whoever
  * ended it: its own replica ([[MaintenanceRunner]]) or the leader's [[MaintenanceReaper]]. Neither
  * failure is a reason to fail the caller: both are swallowed.
  */
object MaintenanceRunRecord:

  def write(
      audit: AuditRecorder,
      metrics: MaintenanceMetrics,
      run: MaintenanceRun,
      status: String,
      counters: RunCounters,
      durationSeconds: Double,
      reason: Option[String]
  ): IO[Unit] =
    val audited = IO
      .blocking(
        audit.restAs(
          "system",
          "system",
          "control-plane",
          AuditActions.MaintenanceRun,
          status,
          tenant = Some(run.tenant),
          target = Some(s"${run.tenantDb}/${run.scope}"),
          detail = Map(
            "trigger"                -> run.trigger,
            "snapshotsExpired"       -> counters.snapshotsExpired.toString,
            "snapshotsSkippedPinned" -> counters.snapshotsSkippedPinned.toString,
            "bytesReclaimed"         -> counters.bytesReclaimed.toString
          ) ++ reason.map("reason" -> _)
        )
      )
      .handleErrorWith(_ => IO.unit)
    val measured = IO
      .delay(
        metrics.recordRun(
          tenant = run.tenant,
          tenantDb = run.tenantDb,
          result = status,
          durationSeconds = durationSeconds,
          bytesReclaimed = counters.bytesReclaimed,
          filesCompacted = counters.filesMerged + counters.filesRewritten,
          snapshotsExpired = counters.snapshotsExpired
        )
      )
      .handleErrorWith(_ => IO.unit)
    audited *> measured
