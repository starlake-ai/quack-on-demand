package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import sttp.model.StatusCode

/** Per-request body-tenant scope check. Mirrors the URL-path guard in `ManagerServer.apiKeyGuard`
  * but is invoked from handlers whose tenant id is in the JSON body. Returns
  * `Some(403, ErrorResponse("tenant_forbidden", ...))` to short-circuit, or `None` to admit.
  *
  * Admission rules:
  *   - No `X-API-Key` (static-key-only deploys with empty key, or open mode) => admit.
  *   - Static `QOD_API_KEY` token (no session row) => admit; trusted operator.
  *   - Session is `superuser` => admit any tenant.
  *   - Session is multi-tenant admin and `tenant in manageableTenants` => admit.
  *   - Anything else => reject `tenant_forbidden`.
  */
object TenantScopeCheck:

  def reject(apiKey: Option[String], tenant: String)(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    apiKey.flatMap(scopeOf) match
      case Some(s) if !s.superuser && !s.manageableTenants.contains(tenant) =>
        Some(
          StatusCode.Forbidden -> ErrorResponse(
            "tenant_forbidden",
            s"session has no admin grant on tenant '$tenant'"
          )
        )
      case _ => None

  /** Variant for id-only endpoints (e.g. POST /role/delete `{id: "r-xxx"}`). Resolves the resource
    * to its owning tenant via `lookupTenant`, then runs the standard scope check.
    *
    * Resolution outcomes:
    *   - `Some(tenantId)` and the session can manage it => admit (returns `None`).
    *   - `Some(tenantId)` out of scope => 403 `tenant_forbidden`.
    *   - `None` (resource not found) => admit; the handler returns its usual 404. We deliberately
    *     do NOT 403 on missing-id, to avoid leaking cross-tenant existence information.
    *
    * Superuser and static-key sessions always admit (same as [[reject]]).
    */
  def rejectForResource(apiKey: Option[String], lookupTenant: => Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    apiKey.flatMap(scopeOf) match
      case Some(s) if !s.superuser =>
        lookupTenant match
          case Some(t) if !s.manageableTenants.contains(t) =>
            Some(
              StatusCode.Forbidden -> ErrorResponse(
                "tenant_forbidden",
                s"session has no admin grant on tenant '$t'"
              )
            )
          case _ => None
      case _ => None

  /** Variant for endpoints addressing a `qodstate_user` row by id. `lookupUserTenant` returns
    * `Some(None)` for a superuser row, `Some(Some(tid))` for a tenant-scoped user, `None` if the id
    * is unknown.
    *
    *   - Superuser row (`Some(None)`) => only an existing superuser session may touch it.
    *   - Tenant-scoped user out of scope => 403.
    *   - Unknown id => admit (handler returns 404).
    */
  def rejectForUser(apiKey: Option[String], lookupUserTenant: => Option[Option[String]])(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    apiKey.flatMap(scopeOf) match
      case Some(s) if !s.superuser =>
        lookupUserTenant match
          case Some(None) =>
            Some(
              StatusCode.Forbidden -> ErrorResponse(
                "tenant_forbidden",
                "only a superuser session may modify a superuser account"
              )
            )
          case Some(Some(t)) if !s.manageableTenants.contains(t) =>
            Some(
              StatusCode.Forbidden -> ErrorResponse(
                "tenant_forbidden",
                s"session has no admin grant on tenant '$t'"
              )
            )
          case _ => None
      case _ => None
