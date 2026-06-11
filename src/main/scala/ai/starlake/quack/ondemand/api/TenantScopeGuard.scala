package ai.starlake.quack.ondemand.api

/** Extracts a tenant id from a management-plane request URL/query so the apiKeyGuard can reject
  * `tenant_forbidden` for non-superuser sessions.
  *
  * Only the URL-path / query forms are recognized here. Body-tenant endpoints (e.g.
  * POST /api/pool/create with `{ tenant: "..." }`) are NOT covered by this helper. Those handlers
  * enforce the scope themselves via [[TenantScopeCheck.reject]].
  */
object TenantScopeGuard:

  // Captures the segment right after the tenant marker. Path segments don't
  // contain '/', so the regex is sufficient.
  private val PoolStatus       = "^/api/pool/([^/]+)/[^/]+/[^/]+/status".r
  private val CatalogTenant    = "^/api/catalog/tenant/([^/]+)/".r
  private val FederatedTenants = "^/api/tenants/([^/]+)/tenant-dbs/".r

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
      .orElse(queryTenant.map(_.trim).filter(_.nonEmpty))