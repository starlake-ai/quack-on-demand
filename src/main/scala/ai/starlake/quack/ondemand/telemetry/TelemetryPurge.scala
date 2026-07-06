package ai.starlake.quack.ondemand.telemetry

import java.time.{Duration, Instant}

/** Helpers for the hourly audit-purge background duty. */
object TelemetryPurge:

  /** Compute the cutoff instant for audit-row deletion.
    *
    * Returns `None` when `retentionDays <= 0`, which means "keep forever" and suppresses the purge
    * entirely. Returns `Some(now minus retentionDays)` otherwise.
    */
  def cutoffFor(retentionDays: Int, now: Instant): Option[Instant] =
    if retentionDays <= 0 then None
    else Some(now.minus(Duration.ofDays(retentionDays.toLong)))
