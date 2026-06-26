package ai.starlake.quack.edge.auth

/** Scope of an admin-UI SSO login. `System` uses the manager-wide provider; `Tenant` uses the
  * tenant's per-tenant provider resolved from `qodstate_tenant.authConfig`.
  */
enum OidcScope:
  case System
  case Tenant(tenantId: String)

/** Resolved OIDC client + endpoint set for one scope. */
final case class OidcEndpoints(
    provider: String,
    authorizeUrl: String,
    tokenUrl: String,
    endSessionUrl: String,
    jwksUrl: String,
    issuer: String,
    clientId: String,
    clientSecret: String,
    scopes: String
)

/** Stable error codes surfaced to the UI as `/ui/?error=<code>` or as a 400 body. */
enum OidcSsoError(val code: String):
  case ScopeNotConfigured extends OidcSsoError("oidc_not_configured")
  case DiscoveryFailed    extends OidcSsoError("discovery_failed")
  case InvalidState       extends OidcSsoError("invalid_state")
  case IdpError           extends OidcSsoError("idp_error")
  case NotProvisioned     extends OidcSsoError("not_provisioned")
  case AdminRequired      extends OidcSsoError("admin_required")
