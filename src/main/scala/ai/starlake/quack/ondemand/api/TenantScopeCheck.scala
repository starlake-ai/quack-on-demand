package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import sttp.model.StatusCode

/** Per-request body-tenant scope check. Mirrors the URL-path guard in `ManagerServer.apiKeyGuard`
  * but is invoked from handlers whose tenant id is in the JSON body. Returns
  * `Some(403, ErrorResponse("tenant_forbidden", ...))` to short-circuit, or `None` to admit.
  *
  * Admission rules, over the caller's resolved scope (`scopeOf(apiKey)`, total):
  *   - superuser => admit any tenant;
  *   - `tenant in manageableTenants` => admit;
  *   - anything else, a caller with no credential or an unresolvable one
  *     ([[SessionScope.NoAccess]]) included => reject `tenant_forbidden`.
  */
object TenantScopeCheck:

  /** The rule, on an already-resolved scope. The shared create paths call it directly with their
    * caller's scope (`SessionScope.Superuser` for boot provisioning); the gates below resolve a
    * REST or MCP credential first.
    */
  def forScope(scope: SessionScope, tenant: String): Option[(StatusCode, ErrorResponse)] =
    if scope.superuser || scope.manageableTenants.contains(tenant) then None
    else
      Some(
        StatusCode.Forbidden -> ErrorResponse(
          "tenant_forbidden",
          s"session has no admin grant on tenant '$tenant'"
        )
      )

  /** [[forScope]] on the caller's credential, resolved by the total `scopeOf`. */
  def reject(apiKey: Option[String], tenant: String)(
      scopeOf: SessionScope.Resolver
  ): Option[(StatusCode, ErrorResponse)] =
    forScope(scopeOf(apiKey), tenant)

  /** Variant for id-only endpoints (e.g. POST /role/delete `{id: "r-xxx"}`). Resolves the resource
    * to its owning tenant via `lookupTenant`, then runs the standard scope check.
    *
    * Resolution outcomes:
    *   - `Some(tenantId)` and the caller can manage it => admit (returns `None`).
    *   - `Some(tenantId)` out of scope => 403 `tenant_forbidden`.
    *   - `None` (resource not found) => admit; the handler returns its usual 404. We deliberately
    *     do NOT 403 on missing-id, to avoid leaking cross-tenant existence information.
    *
    * A superuser always admits, without the lookup (same as [[reject]]).
    */
  def rejectForResource(apiKey: Option[String], lookupTenant: => Option[String])(
      scopeOf: SessionScope.Resolver
  ): Option[(StatusCode, ErrorResponse)] =
    val s = scopeOf(apiKey)
    if s.superuser then None else lookupTenant.flatMap(forScope(s, _))

  /** Variant for endpoints addressing a `qodstate_user` row by id. `lookupUserTenant` returns
    * `Some(None)` for a superuser row, `Some(Some(tid))` for a tenant-scoped user, `None` if the id
    * is unknown.
    *
    *   - Superuser row (`Some(None)`) => only a superuser may touch it.
    *   - Tenant-scoped user out of scope => 403.
    *   - Unknown id => admit (handler returns 404).
    */
  def rejectForUser(apiKey: Option[String], lookupUserTenant: => Option[Option[String]])(
      scopeOf: SessionScope.Resolver
  ): Option[(StatusCode, ErrorResponse)] =
    val s = scopeOf(apiKey)
    if s.superuser then None
    else
      lookupUserTenant match
        case Some(None) =>
          Some(
            StatusCode.Forbidden -> ErrorResponse(
              "tenant_forbidden",
              "only a superuser session may modify a superuser account"
            )
          )
        case Some(Some(t)) => forScope(s, t)
        case None          => None
