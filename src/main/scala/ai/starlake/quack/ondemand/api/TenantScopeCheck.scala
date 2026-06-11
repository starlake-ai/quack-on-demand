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