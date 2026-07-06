package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditQuery, TelemetryStore}
import cats.effect.IO
import sttp.model.StatusCode

import java.time.Instant
import scala.util.Try

/** REST handler for `GET /api/audit/list`.
  *
  * Scoping rules:
  *   - Superuser or unresolvable token (static key / open mode): `tenants` = requested tenant
  *     filter (or None), `includeNullTenant` = true.
  *   - Tenant admin (resolved, not superuser): `tenants` pinned to `manageableTenants`; a requested
  *     `?tenant=` narrows WITHIN them only (tenant not in manageable set falls back to the full
  *     manageable set, no error -- no existence leak); `includeNullTenant` = false.
  *
  * Pagination: `before` is an opaque cursor string (last row id from a prior response). An invalid
  * cursor is silently ignored (treated as absent). `limit` defaults to 50, clamped to [1, 500].
  */
final class AuditHandlers(store: TelemetryStore):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def list(
      family: Option[String],
      tenant: Option[String],
      actor: Option[String],
      action: Option[String],
      q: Option[String],
      from: Option[String],
      to: Option[String],
      limit: Option[Int],
      before: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[AuditListResponse] = IO.blocking {
    def instant(
        s: Option[String],
        label: String
    ): Either[(StatusCode, ErrorResponse), Option[Instant]] =
      s match
        case None    => Right(None)
        case Some(v) =>
          Try(Instant.parse(v)).toOption
            .map(i => Right(Some(i)))
            .getOrElse(
              Left(
                (
                  StatusCode.BadRequest,
                  ErrorResponse("invalid_time", s"$label must be ISO-8601 instant")
                )
              )
            )

    val scoped                 = apiKey.flatMap(scopeOf)
    val (tenants, includeNull) = scoped match
      case Some(s) if !s.superuser =>
        // Pin to manageable tenants; a requested ?tenant= narrows within them only.
        // If the requested tenant is not in manageableTenants, fall back to the full set.
        val allowed =
          tenant.filter(s.manageableTenants.contains).map(Set(_)).getOrElse(s.manageableTenants)
        (Some(allowed), false)
      case _ =>
        (tenant.map(Set(_)), true)

    for
      f <- instant(from, "from")
      t <- instant(to, "to")
    yield
      val rows = store.listAudit(
        AuditQuery(
          family = family,
          tenants = tenants,
          includeNullTenant = includeNull,
          actor = actor,
          action = action,
          q = q,
          from = f,
          to = t,
          limit = limit.getOrElse(50).max(1).min(500),
          beforeId = before.flatMap(_.toLongOption)
        )
      )
      AuditListResponse(
        events = rows.map(r =>
          AuditEventEntry(
            id = r.id.toString,
            ts = r.event.ts.toString,
            family = r.event.family,
            actor = r.event.actor,
            actorRealm = r.event.actorRealm,
            tenant = r.event.tenant,
            action = r.event.action,
            target = r.event.target,
            outcome = r.event.outcome,
            origin = r.event.origin,
            detail = r.event.detail
          )
        ),
        nextBefore = rows.lastOption.map(_.id.toString)
      )
  }
