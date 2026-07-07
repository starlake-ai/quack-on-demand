package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{RollupQuery, StatementQuery, TelemetryStore}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handler for `GET /api/history/trends` and `GET /api/history/statements`.
  *
  * Scoping rules (identical to AuditHandlers):
  *   - Superuser or unresolvable token (static key / open mode): `tenants` = requested tenant
  *     filter (or None).
  *   - Tenant admin (resolved, not superuser): `tenants` pinned to `manageableTenants`; a requested
  *     `?tenant=` narrows WITHIN them only (tenant not in manageable set falls back to the full
  *     manageable set, no error, no existence leak).
  *
  * Rollup rows always carry a tenant value so there is no `includeNullTenant` concern.
  *
  * Pagination for statements: `before` is an opaque cursor string (last row id from a prior
  * response). An invalid cursor is silently ignored. `limit` defaults to 50, clamped to [1, 500].
  */
final class HistoryHandlers(store: TelemetryStore):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def trends(
      granularity: Option[String],
      from: Option[String],
      to: Option[String],
      tenant: Option[String],
      pool: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[TrendsResponse] = IO.blocking {
    val gran = granularity.getOrElse("")
    if gran != "hour" && gran != "day" then
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("invalid_granularity", "granularity must be hour or day")
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
        val buckets = store.queryRollups(
          RollupQuery(
            granularity = gran,
            tenants = tenants,
            pool = pool,
            from = f,
            to = t
          )
        )
        TrendsResponse(
          buckets = buckets.map(b =>
            TrendBucketEntry(
              bucketStart = b.bucketStart.toString,
              tenant = b.tenant,
              pool = b.pool,
              username = b.username,
              stmtCount = b.stmtCount,
              errorCount = b.errorCount,
              deniedCount = b.deniedCount,
              engineMsSum = b.engineMsSum,
              p50Ms = b.p50Ms,
              p95Ms = b.p95Ms,
              p99Ms = b.p99Ms
            )
          )
        )
  }

  def statements(
      from: Option[String],
      to: Option[String],
      tenant: Option[String],
      pool: Option[String],
      user: Option[String],
      status: Option[String],
      q: Option[String],
      limit: Option[Int],
      before: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[StatementSearchResponse] = IO.blocking {
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
      val rows = store.searchStatements(
        StatementQuery(
          tenants = tenants,
          pool = pool,
          user = user,
          status = status,
          q = q,
          from = f,
          to = t,
          limit = limit.getOrElse(50).max(1).min(500),
          beforeId = before.flatMap(_.toLongOption)
        )
      )
      StatementSearchResponse(
        statements = rows.map(r =>
          StatementHistoryRowEntry(
            id = r.id.toString,
            ts = r.event.ts.toString,
            username = r.event.username,
            tenant = r.event.tenant,
            pool = r.event.pool,
            nodeId = r.event.nodeId,
            sql = r.event.sql,
            durationMs = r.event.durationMs,
            prepareMs = r.event.prepareMs,
            status = r.event.status,
            error = r.event.error
          )
        ),
        nextBefore = rows.lastOption.map(_.id.toString)
      )
  }
