package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model.TenantDbKind
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant

/** Leader-gated enqueue side of Spec 09 (spec section 4): cadence cron + small-file thresholds,
  * dedup against active runs, min-interval guard, stale-run sweep. Pure decisions live in tickOnce
  * so the spec drives it synchronously; start() wraps it in the duty-fiber loop.
  */
final class MaintenanceScheduler(
    store: ControlPlaneStore,
    smallFileCountsOf: (String, String, Long) => Map[(String, String), Int],
    minIntervalMinutes: Int,
    runTimeoutMinutes: Int,
    staggerOf: (String, String) => Int,
    tickSeconds: Int = 60,
    isLeader: () => Boolean = () => true
) extends LazyLogging:

  private val TargetBytesForSmall = 32L * 1024 * 1024 // "small" = under 32MB in v1

  /** A schema/table name unsafe to embed in a `table:<schema>.<table>` scope string: a quote or
    * semicolon could break out of the eventual SQL literal on the node session, a backslash is an
    * escape smuggling vector, and a dot would itself break the scope grammar (the scope parser
    * splits on the first dot only). Catalog-sourced names failing this check are dropped from this
    * tick rather than enqueued -- the next tick re-evaluates once/if the catalog is clean.
    */
  private def isUnsafeIdentifier(s: String): Boolean =
    s.contains('\'') || s.contains(';') || s.contains('\\') || s.contains('.')

  def tickOnce(now: Instant): Unit =
    store.sweepStaleMaintenanceRuns(now.minusSeconds(runTimeoutMinutes * 60L))
    store.listTenants().foreach { t =>
      store.listTenantDbs(t.id).filter(_.kind == TenantDbKind.DuckLake).foreach { td =>
        val rows = store.listMaintenancePolicies(t.id, td.name)
        val pol  = PolicyMath.effective(rows, None, None)
        // Computed once per tenant-db and reused below for both the min-interval gate and the
        // cron-due check (F3b): the two reads were previously separate store round trips that
        // could observe different results if a run enqueued between them.
        val lastRun = store.lastNonManualMaintenanceRunAt(t.id, td.name)
        if pol.enabled
          && !store.hasActiveMaintenanceRun(t.id, td.name)
          && lastRun.forall(_.isBefore(now.minusSeconds(minIntervalMinutes * 60L)))
        then
          if CronExpr.due(pol.cron, staggerOf(t.id, td.name), lastRun, now) then
            store.enqueueMaintenanceRun(t.id, td.name, "tenantdb", "cadence", None)
            ()
          else
            val hot = smallFileCountsOf(t.id, td.name, TargetBytesForSmall)
              .filter { case ((s, tb), n) =>
                val effective = PolicyMath.effective(rows, Some(s), Some(tb))
                // Per-table enabled=false overrides a lake-level enabled=true (F3a): a table
                // can be individually opted out of managed maintenance even while the rest of
                // the lake is on, so a threshold hit alone must not be enough to enqueue it.
                effective.enabled && n >= effective.smallFileMinCount
              }
              .filterNot { case ((s, tb), _) =>
                val unsafe = isUnsafeIdentifier(s) || isUnsafeIdentifier(tb)
                if unsafe then
                  logger.warn(
                    s"skipping threshold run for ${t.id}/${td.name}: unsafe identifier " +
                      s"in schema='$s' table='$tb'"
                  )
                unsafe
              }
            hot.keys.headOption.foreach { case (s, tb) =>
              // one table per tick per lake: the run itself compacts; the next tick
              // re-evaluates the rest
              store.enqueueMaintenanceRun(t.id, td.name, s"table:$s.$tb", "threshold", None)
            }
      }
    }

  def start: IO[cats.effect.FiberIO[Unit]] =
    (IO
      .blocking(if isLeader() then tickOnce(Instant.now()))
      .handleErrorWith(e => IO(logger.error(s"maintenance scheduler tick failed: ${e.getMessage}")))
      *> IO.sleep(scala.concurrent.duration.DurationInt(tickSeconds).seconds)).foreverM.void.start
