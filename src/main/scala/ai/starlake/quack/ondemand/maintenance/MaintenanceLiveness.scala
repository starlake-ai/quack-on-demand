package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model.{MaintenanceRun, PoolKey}
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.FiniteDuration

/** The liveness heartbeat of one executing maintenance run (issue #210). While `work` runs, the
  * row's `heartbeatAt` is bumped every `every`, so the leader's [[MaintenanceReaper]] can tell a
  * live owner from a dead one within a few ticks, whatever a chain step takes.
  *
  * When a bump finds the row no longer running and the row carries one of the reaper's errors, the
  * run's node is stopped here, then the work is cancelled. The stop comes first because the work's
  * statement is an uninterruptible `IO.blocking` call: cancelling alone would wait for a long
  * compaction to finish, while a stopped node fails it at once. A row the owner finished itself
  * leaves the work to end on its own. A failed write or read is logged and retried at the next
  * tick, never a reason to cancel.
  */
object MaintenanceLiveness extends LazyLogging:

  def guard(
      store: ControlPlaneStore,
      run: MaintenanceRun,
      every: FiniteDuration,
      stopNode: (PoolKey, String) => IO[Unit]
  )(work: IO[Unit]): IO[Unit] =
    IO.race(work, watch(store, run, every, stopNode)).void

  private def label(run: MaintenanceRun): String =
    s"maintenance run ${run.id} (${run.tenant}/${run.tenantDb})"

  private def watch(
      store: ControlPlaneStore,
      run: MaintenanceRun,
      every: FiniteDuration,
      stopNode: (PoolKey, String) => IO[Unit]
  ): IO[Unit] =
    IO.sleep(every) *> IO.blocking(store.touchMaintenanceRun(run.id)).attempt.flatMap {
      case Right(true)  => watch(store, run, every, stopNode)
      case Right(false) => lost(store, run, every, stopNode)
      case Left(e)      =>
        IO.delay(
          logger.warn(s"${label(run)}: liveness heartbeat failed, retrying: ${e.getMessage}")
        ) *>
          watch(store, run, every, stopNode)
    }

  private def lost(
      store: ControlPlaneStore,
      run: MaintenanceRun,
      every: FiniteDuration,
      stopNode: (PoolKey, String) => IO[Unit]
  ): IO[Unit] =
    IO.blocking(store.findMaintenanceRun(run.id)).attempt.flatMap {
      case Right(Some(row)) if MaintenanceRun.isReaped(row.error) =>
        IO.delay(
          logger.warn(
            s"${label(run)} was reaped (${row.error.getOrElse("")}); stopping its node and " +
              "cancelling it"
          )
        ) *> row.nodeId.fold(IO.unit) { id =>
          stopNode(PoolKey(run.tenant, run.tenantDb, "__maint"), id).handleErrorWith(e =>
            IO.delay(logger.warn(s"${label(run)}: stopping node $id failed: ${e.getMessage}"))
          )
        }
      // Finished by its owner between a step and the race's end: the work ends the race.
      case Right(_) => IO.never
      case Left(e)  =>
        IO.delay(
          logger.warn(s"${label(run)}: reading the row failed, retrying: ${e.getMessage}")
        ) *>
          watch(store, run, every, stopNode)
    }
