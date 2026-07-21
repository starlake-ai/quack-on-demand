package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor

/** Small helpers shared verbatim across several `api` handler classes. Kept as free functions
  * (rather than a trait mixed into each handler) so construction stays simple and the call sites
  * stay explicit about which supervisor/map they're resolving against.
  */
object HandlerResolvers:

  /** Resolve a `tenant` request field (display name OR surrogate id) to the canonical
    * `qodstate_tenant.id`, the same way `Role`/`Group`/`PoolPermission` handlers do. Distinct from
    * [[TenantDbGate.resolveTenantId]], which resolves through
    * `sup.getTenantById(...).orElse(sup.getTenant(...))` and has different fallback semantics; left
    * alone here.
    */
  def resolveTenantId(sup: PoolSupervisor, raw: String): Option[String] =
    val tenants = sup.listTenants()
    tenants
      .find(_.id == raw)
      .map(_.id)
      .orElse(tenants.find(_.displayName == raw.toLowerCase).map(_.id))

  /** Keys that must never round-trip in an API response: the metastore `pgPassword` plus the
    * object-store secret keys authored by `ObjectStoreSecret` (`s3_secret_access_key`,
    * `azure_account_key`, `gcs_hmac_secret`).
    */
  private val redactedKeys: Set[String] =
    Set("pgPassword", "s3_secret_access_key", "azure_account_key", "gcs_hmac_secret")

  /** Hide secret-like keys (pgPassword plus the object-store secret keys) from a
    * metastore/objectStore map before it goes out on the wire.
    */
  def redactPassword(m: Map[String, String]): Map[String, String] =
    m.filterNot { case (k, _) => redactedKeys.exists(_.equalsIgnoreCase(k)) }
