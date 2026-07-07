package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditQuery, TelemetryStore}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handler for `GET /api/audit/list`.
  *
  * Scoping rules:
  *   - Superuser or unresolvable token (static key / open mode): `noTenant=true` selects exactly
  *     the null-tenant rows (wins over any `?tenant=` filter); a `?tenant=x` filter selects only
  *     that tenant's rows (null-tenant rows no longer mixed in); no filter returns everything
  *     including null-tenant rows.
  *   - Tenant admin (resolved, not superuser): `tenants` pinned to `manageableTenants`; a requested
  *     `?tenant=` narrows WITHIN them only (tenant not in manageable set falls back to the full
  *     manageable set, no error -- no existence leak); `noTenant=true` is ignored;
  *     `includeNullTenant` = false.
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
      noTenant: Option[Boolean],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[AuditListResponse] = IO.blocking {
    val scoped                 = apiKey.flatMap(scopeOf)
    val (tenants, includeNull) = scoped match
      case Some(s) if !s.superuser =>
        // Pin to manageable tenants; ?tenant= narrows within them; ?noTenant= is ignored
        // (tenant admins can never see null-tenant rows).
        val allowed =
          tenant.filter(s.manageableTenants.contains).map(Set(_)).getOrElse(s.manageableTenants)
        (Some(allowed), false)
      case _ =>
        // Superuser / static key. noTenant=true wins over any tenant filter and selects
        // exactly the null-tenant rows; a tenant filter selects that tenant only; no
        // filter returns everything including null-tenant rows.
        if noTenant.contains(true) then (Some(Set.empty[String]), true)
        else (tenant.map(Set(_)), tenant.isEmpty)

    for
      f <- QueryParams.instant(from, "from")
      t <- QueryParams.instant(to, "to")
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

  /** Static vocabulary; auth is enforced by the API-key perimeter, no tenant scoping needed. */
  def actions(apiKey: Option[String]): Out[AuditActionsResponse] =
    IO.pure(Right(AuditActionsResponse(AuditActions.all)))
