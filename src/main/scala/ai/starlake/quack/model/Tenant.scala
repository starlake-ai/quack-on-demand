package ai.starlake.quack.model

/** Ownership umbrella. Storage configuration (metastore, data path, object store) lives on the
  * child [[TenantDb]] rows, not here.
  *
  * Auth provider lives at the tenant level. `authProvider` picks one of
  * `db / keycloak / google / azure / aws` (the column has a CHECK constraint to keep the set
  * honest); `authConfig` carries the provider-specific knobs (issuer URL, realm name, hd domain,
  * ...) as a free-form map serialized into the `auth_config` JSONB column.
  *
  *   - `id` : app-generated surrogate, stable across renames.
  *   - `name` : the natural addressing key used by [[PoolKey.tenant]]. Always lowercased; equal to
  *     `displayName` after normalization.
  *   - `displayName` : human-facing label persisted on `qodstate_tenant`.
  *   - `disabled` : soft-delete marker.
  *   - `authProvider` : one of {db, keycloak, google, azure, aws}. Defaults to `db` -- the username
  *     on `qodstate_user` IS the identity, no extra configuration needed.
  *   - `authConfig` : provider-specific config. Empty for `db`. For OIDC providers, the keys read
  *     by [[ai.starlake.quack.edge.auth.TenantOidcRegistry]] (FlightSQL bearer validation: public
  *     fields only, no secret) are: `keycloak` -> baseUrl + realm + clientId; `google` -> clientId;
  *     `azure` -> tenantId + clientId; `aws` -> region + userPoolId + clientId. The override is
  *     all-or-nothing per provider: missing required keys fall back to the manager-wide
  *     `quack-flightsql.auth.<provider>.*` config. The admin-console SSO
  *     ([[ai.starlake.quack.edge.auth.OidcEndpointResolver]]) reads issuerUrl + clientId + scopes
  *     (optional) and takes its client secret from `authClientSecret`, else from the
  *     `clientSecretRef` reference (`env:NAME`, which only a superuser may write).
  *   - `authClientSecret` : the tenant's own OIDC client secret for admin-console SSO
  *     (`qodstate_tenant.auth_client_secret`, Liquibase 0048). Write-only: responses expose only
  *     whether it is set, and `toString` redacts it. It is bound to the `issuerUrl` of `authConfig`
  *     it was saved with: the store writes the two in one statement, and a `tenant/setAuth` write
  *     that changes the issuer drops it.
  */
final case class Tenant(
    id: String,
    displayName: String = "",
    disabled: Boolean = false,
    authProvider: String = "db",
    authConfig: Map[String, String] = Map.empty,
    acl: TenantAcl = TenantAcl(),
    authClientSecret: Option[String] = None
):

  /** Redact `authClientSecret` so no `s"$tenant"` log line (or exception message built from one)
    * can print it; `acl` prints through [[TenantAcl.toString]], which redacts `opaToken`.
    * Case-class equality, `hashCode` and `copy` are untouched: Scala synthesizes them from the
    * constructor fields, never from `toString`.
    */
  override def toString: String =
    s"Tenant(id=$id,displayName=$displayName,disabled=$disabled,authProvider=$authProvider," +
      s"authConfig=$authConfig,acl=$acl,authClientSecret=${authClientSecret
          .map(_ => FederatedSecret.RedactedMarker)})"

object Tenant:
  val ValidAuthProviders: Set[String] =
    Set("db", "keycloak", "google", "azure", "aws")
