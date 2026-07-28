package ai.starlake.quack.boot

import ai.starlake.quack.TelemetryConfig
import ai.starlake.quack.ondemand.telemetry.{TelemetryPurge, TelemetryStore}
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging

/** Telemetry background duties, extracted from Main.bootManager. Both loops are leader-gated under
  * HA (isLeader() is constantly true in single-manager mode) and no-op fibers when telemetry is
  * disabled, so cancellation stays uniform in Main's fiber teardown.
  */
object TelemetryFibers extends LazyLogging:

  def auditPurge(
      telemetryStore: TelemetryStore,
      telemetry: TelemetryConfig,
      isLeader: () => Boolean
  ): IO[FiberIO[Unit]] =
    if telemetryStore.enabled then
      (IO
        .blocking {
          if isLeader() then
            val now = java.time.Instant.now()
            TelemetryPurge.cutoffFor(telemetry.auditRetentionDays, now) match
              case Some(cutoff) =>
                val n = telemetryStore.purgeAudit(cutoff)
                if n > 0 then logger.info(s"audit purge: deleted $n events older than $cutoff")
              case None => ()
            // raw retention must cover the rollup watermark's full day
            // (recompute rebuilds it); keep >= 2 days
            TelemetryPurge
              .cutoffFor(telemetry.stmtHistoryRetentionDays, now)
              .foreach { c =>
                val n = telemetryStore.purgeStatements(c)
                if n > 0 then logger.info(s"stmt-history purge: deleted $n rows older than $c")
              }
            TelemetryPurge
              .cutoffFor(telemetry.hourlyRollupRetentionDays, now)
              .foreach { c =>
                val n = telemetryStore.purgeRollups("hour", c)
                if n > 0 then logger.info(s"hourly-rollup purge: deleted $n buckets older than $c")
              }
            TelemetryPurge
              .cutoffFor(telemetry.usageRetentionDays, now)
              .foreach { c =>
                val n = telemetryStore.purgeRollups("day", c)
                if n > 0 then logger.info(s"daily-rollup purge: deleted $n buckets older than $c")
              }
        }
        .handleErrorWith(e => IO(logger.error(s"audit purge failed: ${e.getMessage}")))
        *> IO.sleep(
          scala.concurrent.duration.DurationInt(1).hours
        )).foreverM.void.start
    else IO.unit.start

  def rollup(
      telemetryStore: TelemetryStore,
      telemetry: TelemetryConfig,
      isLeader: () => Boolean
  ): IO[FiberIO[Unit]] =
    if telemetryStore.enabled then
      (IO
        .blocking {
          if isLeader() then
            val to = java.time.Instant.now().minusSeconds(60)
            telemetryStore.recomputeRollups(telemetryStore.rollupWatermark(), to)
            telemetryStore.advanceRollupWatermark(to)
        }
        .handleErrorWith(e => IO(logger.error(s"rollup pass failed: ${e.getMessage}")))
        *> IO.sleep(
          scala.concurrent.duration.DurationInt(telemetry.rollupIntervalSec).seconds
        )).foreverM.void.start
    else IO.unit.start
