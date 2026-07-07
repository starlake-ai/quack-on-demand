package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{TelemetryStore, UsageQuery}
import cats.effect.IO
import sttp.model.StatusCode

import java.time.{Instant, ZoneOffset}

/** REST handler for `GET /api/usage`: per-tenant / per-pool / per-user metering over the daily
  * rollup ledger.
  *
  * Scoping rules (identical to HistoryHandlers):
  *   - Superuser or unresolvable token (static key / open mode): `tenants` = requested tenant
  *     filter (or None).
  *   - Tenant admin: `tenants` pinned to `manageableTenants`; a requested `?tenant=` narrows WITHIN
  *     them only (silent fallback, no existence leak).
  *
  * Defaults: `from`/`to` default to the current calendar month (UTC, half-open); `groupBy` defaults
  * to "tenant". `now` is injectable for tests.
  */
final class UsageHandlers(store: TelemetryStore, now: () => Instant = () => Instant.now()):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def usage(
      from: Option[String],
      to: Option[String],
      groupBy: Option[String],
      tenant: Option[String],
      pool: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[UsageResponse] = IO.blocking {
    val gb = groupBy.getOrElse("tenant")
    if gb != "tenant" && gb != "pool" && gb != "user" then
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("invalid_group_by", "groupBy must be tenant, pool or user")
        )
      )
    else
      val scoped  = apiKey.flatMap(scopeOf)
      val tenants = scoped match
        case Some(s) if !s.superuser =>
          val allowed =
            tenant
              .filter(s.manageableTenants.contains)
              .map(Set(_))
              .getOrElse(s.manageableTenants)
          Some(allowed)
        case _ =>
          tenant.map(Set(_))

      for
        f <- QueryParams.instant(from, "from")
        t <- QueryParams.instant(to, "to")
      yield
        val monthStart =
          now().atZone(ZoneOffset.UTC).toLocalDate.withDayOfMonth(1)
        val fromTs = f.getOrElse(monthStart.atStartOfDay(ZoneOffset.UTC).toInstant)
        val toTs   =
          t.getOrElse(monthStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant)
        val result = store.queryUsage(
          UsageQuery(groupBy = gb, tenants = tenants, pool = pool, from = fromTs, to = toTs)
        )
        UsageResponse(
          from = fromTs.toString,
          to = toTs.toString,
          groupBy = gb,
          dataStart = result.dataStart.map(_.toString),
          groups = result.groups.map(g =>
            UsageGroupEntry(
              tenant = g.tenant,
              pool = g.pool,
              username = g.username,
              statements = g.statements,
              errors = g.errors,
              denied = g.denied,
              engineMs = g.engineMs,
              days = g.days.map(d =>
                UsageDayEntry(
                  day = d.day.toString,
                  statements = d.statements,
                  errors = d.errors,
                  engineMs = d.engineMs
                )
              )
            )
          )
        )
  }
