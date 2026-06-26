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
  *   - `metastore` : flat key/value map kept on the row so older fixtures and tests that wire
  *     storage at the tenant level still compile; production storage lives on the child
  *     [[TenantDb]] rows.
  *   - `authProvider` : one of {db, keycloak, google, azure, aws}. Defaults to `db` -- the username
  *     on `qodstate_user` IS the identity, no extra configuration needed.
  *   - `authConfig` : provider-specific config. Empty for `db`. For OIDC providers, the keys read
  *     by [[ai.starlake.quack.edge.auth.TenantOidcRegistry]] are: `keycloak` -> baseUrl + realm +
  *     clientId + clientSecretRef; `google` -> clientId + clientSecretRef; `azure` -> tenantId +
  *     clientId + clientSecretRef; `aws` -> region + userPoolId + clientId. The override is
  *     all-or-nothing per provider: missing required keys fall back to the manager-wide
  *     `quack-flightsql.auth.<provider>.*` config.
  */
final case class Tenant(
    id: String,
    displayName: String = "",
    metastore: Map[String, String] = Map.empty,
    disabled: Boolean = false,
    authProvider: String = "db",
    authConfig: Map[String, String] = Map.empty
)

object Tenant:
  val ValidAuthProviders: Set[String] =
    Set("db", "keycloak", "google", "azure", "aws")
