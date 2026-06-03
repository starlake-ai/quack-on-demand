package ai.starlake.quack.model

/** Ownership umbrella. Storage configuration (metastore, data path, object
  * store) lives on the child [[TenantDb]] rows, not here.
  *
  *   - `id`          : app-generated surrogate, stable across renames.
  *   - `name`        : the natural addressing key used by [[PoolKey.tenant]].
  *                     Always lowercased; equal to `displayName` after
  *                     normalization.
  *   - `displayName` : human-facing label persisted on `qodstate_tenant`.
  *   - `disabled`    : soft-delete marker.
  *   - `metastore`   : legacy carry-over from the pre-refactor shape; kept
  *                     so older fixtures still compile. New code should
  *                     read storage off [[TenantDb]]. */
final case class Tenant(
    name:        String,
    metastore:   Map[String, String] = Map.empty,
    id:          String              = "",
    displayName: String              = "",
    disabled:    Boolean             = false
)
