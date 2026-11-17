package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model.{MaintenanceRun, PoolKey, ReapedRun}
import ai.starlake.quack.observability.metrics.MaintenanceMetrics
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant

/** The HA leader's sweep of running maintenance runs (issue #210), a singleton duty. A run whose
  * liveness heartbeat ([[MaintenanceLiveness]]) is older than `livenessTimeoutSec` lost its
  * replica: it is failed and its `__maint` node stopped here, since nothing else will, retried on
  * every pass until a stop succeeds (the row's owed stop, set by the reap itself). A run started
  * more than `runTimeoutSec` ago is failed too, but its node is left to its live owner, whose
  * heartbeat sees the row change and stops it, so no replica kills a node under a live run. Neither
  * is requeued: the next cadence or threshold tick queues a fresh run. The drain fiber calls
  * [[tick]], whose quiet window (after becoming leader, and after a failed pass until
  * `livenessTimeoutSec` after the first probe that succeeds) keeps a control-plane outage from
  * getting live runs reaped.
  */
final class MaintenanceReaper(
    store: ControlPlaneStore,
    livenessTimeoutSec: Int,
    runTimeoutSec: Long,
    stopNode: (PoolKey, String) => IO[Unit],
    audit: AuditRecorder,
    metrics: MaintenanceMetrics
) extends LazyLogging:

  // Only the drain fiber calls `tick`, one pass at a time; volatile for the reads of other threads.
  @volatile private var wasLeader: Boolean  = false
  @volatile private var quietUntil: Instant = Instant.EPOCH
  @volatile private var recovering: Boolean = false

  /** A primary-key read of the run table: the cheapest call that proves the control plane answers
    * again after a failed pass. No run has id -1.
    */
  private def probe: IO[Unit] = IO.blocking(store.findMaintenanceRun(-1L)).void

  /** One reaper pass for this replica (issue #210). A non-leader reaps nothing. Right after this
    * replica becomes leader it reaps nothing for `livenessTimeoutSec`. After a pass that failed
    * (the control plane unreachable) it probes the store each tick instead of reaping, and once a
    * probe succeeds it waits `livenessTimeoutSec` more: owners whose heartbeats went stale during
    * the outage get the time to write one before any of their runs reads as silent.
    */
  def tick(leader: Boolean, now: Instant): IO[Unit] = IO.defer {
    def quietFor: Unit = quietUntil = now.plusSeconds(livenessTimeoutSec.toLong)
    if !leader then
      IO.delay {
        wasLeader = false
        recovering = false
      }
    else if !wasLeader then
      IO.delay {
        wasLeader = true
        quietFor
      }
    else if recovering then
      probe.attempt.flatMap {
        case Right(_) => IO.delay { recovering = false; quietFor }
        case Left(e)  => IO.delay(quietFor) *> IO.raiseError(e)
      }
    else if now.isBefore(quietUntil) then IO.unit
    else
      reapOnce.handleErrorWith { e =>
        IO.delay { recovering = true; quietFor } *> IO.raiseError(e)
      }
  }

  /** Reap, then stop every node a silent run left, this pass's and any earlier pass's whose stop
    * failed or never ran (a leader that died between the reap and the stop). A stop is recorded
    * done only when it succeeds, so a failed one is tried again on the next pass. Each maintenance
    * node id is unique to its run, so a late stop never reaches another run's node. Both lists are
    * handled concurrently: a stop can wait for its backend's stop timeout.
    */
  def reapOnce: IO[Unit] =
    IO.blocking(store.reapMaintenanceRuns(livenessTimeoutSec, runTimeoutSec))
      .flatMap(_.parTraverse_(settle)) *>
      IO.blocking(store.maintenanceRunsAwaitingNodeStop()).flatMap(_.parTraverse_(stopOwed))

  private def label(run: MaintenanceRun): String =
    s"maintenance run ${run.id} (${run.tenant}/${run.tenantDb})"

  private def settle(reaped: ReapedRun): IO[Unit] =
    val run      = reaped.run
    val duration =
      (for
        s <- run.startedAt
        f <- run.finishedAt
      yield math.max(0L, f.toEpochMilli - s.toEpochMilli) / 1000.0).getOrElse(0.0)
    IO.delay(logger.warn(s"${label(run)} reaped: ${run.error.getOrElse("")}")) *>
      MaintenanceRunRecord.write(
        audit,
        metrics,
        run,
        "failed",
        run.counters,
        duration,
        reason = Some(run.error.getOrElse(""))
      )

  private def stopOwed(run: MaintenanceRun): IO[Unit] =
    val cleared = IO.blocking(store.clearMaintenanceRunNodeStop(run.id))
    run.nodeId match
      case None     => cleared
      case Some(id) =>
        stopNode(PoolKey(run.tenant, run.tenantDb, "__maint"), id).attempt.flatMap {
          case Right(_) =>
            cleared.handleErrorWith(e =>
              IO.delay(
                logger.warn(
                  s"${label(run)}: node $id stopped, recording it failed: ${e.getMessage}"
                )
              )
            )
          case Left(e) =>
            IO.delay(
              logger.warn(
                s"${label(run)}: stopping its node $id failed, retrying next pass: ${e.getMessage}"
              )
            )
        }
