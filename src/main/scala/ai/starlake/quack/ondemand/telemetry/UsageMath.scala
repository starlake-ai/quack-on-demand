package ai.starlake.quack.ondemand.telemetry

import java.time.Instant

/** Pure shaping shared by PostgresTelemetryStore (rows from a SQL GROUP BY) and the in-memory test
  * store: folds day-level aggregate rows into per-group totals with ascending per-day sub-totals,
  * sorted by engineMs descending.
  */
object UsageMath:

  /** One day-level aggregate row. `pool` / `username` hold "" when they are not part of the
    * requested group key.
    */
  final case class DayRow(
      tenant: String,
      pool: String,
      username: String,
      day: Instant,
      statements: Long,
      errors: Long,
      denied: Long,
      engineMs: Long
  )

  def fold(groupBy: String, rows: List[DayRow]): List[UsageGroup] =
    rows
      .groupBy(r => (r.tenant, r.pool, r.username))
      .toList
      .map { case ((tenant, pool, username), rs) =>
        val days = rs
          .sortBy(_.day)
          .map(r => UsageDay(r.day, r.statements, r.errors, r.engineMs))
        UsageGroup(
          tenant = tenant,
          pool = if groupBy == "pool" then Some(pool) else None,
          username = if groupBy == "user" then Some(username) else None,
          statements = rs.map(_.statements).sum,
          errors = rs.map(_.errors).sum,
          denied = rs.map(_.denied).sum,
          engineMs = rs.map(_.engineMs).sum,
          days = days
        )
      }
      .sortBy(g => (-g.engineMs, g.tenant, g.pool.getOrElse(""), g.username.getOrElse("")))
