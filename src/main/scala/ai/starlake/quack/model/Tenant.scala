package ai.starlake.quack.model

/** Ownership umbrella. Storage configuration (metastore, data path,
  * object store) lives on the child [[TenantDb]] rows, not here.
  *
  * Auth provider lives at the tenant level. `authProvider` picks one
  * of `db / keycloak / google / azure / aws` (the column has a CHECK
  * constraint to keep the set honest); `authConfig` carries the
  * provider-specific knobs (issuer URL, realm name, hd domain, ...)
  * as a free-form map serialized into the `auth_config` JSONB column.
  *
  *   - `id`           : app-generated surrogate, stable across renames.
  *   - `name`         : the natural addressing key used by
  *                      [[PoolKey.tenant]]. Always lowercased; equal to
  *                      `displayName` after normalization.
  *   - `displayName`  : human-facing label persisted on `qodstate_tenant`.
  *   - `disabled`     : soft-delete marker.
  *   - `metastore`    : legacy carry-over from the pre-refactor shape;
  *                      kept so older fixtures still compile.
  *   - `authProvider` : one of {db, keycloak, google, azure, aws}.
  *                      Defaults to `db` -- the username on
  *                      `qodstate_user` IS the identity, no extra
  *                      configuration needed.
  *   - `authConfig`   : provider-specific config. Empty for `db`. For
  *                      OIDC providers: `issuer` (full URL), plus one
  *                      of `realm` / `hd` / `tenantId` / `userPoolId`
  *                      depending on the provider. */
final case class Tenant(
    name:         String,
    metastore:    Map[String, String] = Map.empty,
    id:           String              = "",
    displayName:  String              = "",
    disabled:     Boolean             = false,
    authProvider: String              = "db",
    authConfig:   Map[String, String] = Map.empty
)

object Tenant:
  val ValidAuthProviders: Set[String] =
    Set("db", "keycloak", "google", "azure", "aws")