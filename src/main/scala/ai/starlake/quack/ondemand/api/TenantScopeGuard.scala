package ai.starlake.quack.ondemand.api

/** Extracts a tenant id from a management-plane request URL/query so the apiKeyGuard can reject
  * `tenant_forbidden` for non-superuser sessions.
  *
  * Only the URL-path / query forms are recognized here. Body-tenant endpoints (e.g. POST
  * /api/pool/create with `{ tenant: "..." }`) are NOT covered by this helper. Those handlers
  * enforce the scope themselves via [[TenantScopeCheck.reject]].
  *
  * CONVENTION: a route that carries the tenant in its URL PATH MUST name the capture
  * `path[String]("tenant")`. TenantScopeCompletenessSpec enumerates every endpoint and fails the
  * build if a `{tenant}` path route is not covered by `extractTenant` here, or if a tenant-ish
  * capture is named anything other than `tenant`. Query-tenant routes (`?tenant=`) are covered
  * generically by the `queryTenant` fallback below and need no per-route arm.
  */
object TenantScopeGuard:

  // Captures the segment right after the tenant marker. Path segments don't
  // contain '/', so the regex is sufficient.
  private val PoolStatus       = "^/api/pool/([^/]+)/[^/]+/[^/]+/status".r
  private val CatalogTenant    = "^/api/catalog/tenant/([^/]+)/".r
  private val FederatedTenants = "^/api/tenants/([^/]+)/tenant-dbs/".r

  // Paths where ?tenant= is a filter hint handled by the endpoint's own scoping
  // logic, not an authz scope that the perimeter guard should enforce. Adding a
  // path here means the guard will NOT fire 403 for a cross-tenant ?tenant= value
  // on these routes; instead the handler silently falls back to the caller's
  // manageable-tenant set.
  private val QueryTenantExempt = Set(
    "/api/audit/list",
    "/api/history/trends",
    "/api/history/statements"
  )

  /** Returns the request's tenant id (path or query), or `None` if no tenant is encoded in the URL
    * form. Public-API URLs (`/api/auth/login`, `/api/config/client`) always return `None` here
    * because the guard never runs on them.
    */
  def extractTenant(path: String, queryTenant: Option[String]): Option[String] =
    PoolStatus
      .findFirstMatchIn(path)
      .map(_.group(1))
      .orElse(CatalogTenant.findFirstMatchIn(path).map(_.group(1)))
      .orElse(FederatedTenants.findFirstMatchIn(path).map(_.group(1)))
      .orElse(
        if QueryTenantExempt.contains(path) then None
        else queryTenant.map(_.trim).filter(_.nonEmpty)
      )
