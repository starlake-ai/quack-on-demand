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

  /** Hide secret-like keys (today: just `pgPassword`) from a metastore/objectStore map before it
    * goes out on the wire.
    */
  def redactPassword(m: Map[String, String]): Map[String, String] =
    m.filterNot(_._1.equalsIgnoreCase("pgPassword"))
