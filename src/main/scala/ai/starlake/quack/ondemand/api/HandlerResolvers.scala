package ai.starlake.quack.ondemand.api

import java.util.Locale
import ai.starlake.quack.model.TenantDb
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope

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
      .orElse(tenants.find(_.displayName == raw.toLowerCase(Locale.ROOT)).map(_.id))

  /** Hide secret-like keys ([[TenantDb.isSecretKey]]: pgPassword plus the object-store secret keys)
    * from a metastore/objectStore map before it goes out on the wire.
    */
  def redactPassword(m: Map[String, String]): Map[String, String] =
    m.filterNot { case (k, _) => TenantDb.isSecretKey(k) }

  /** `shown`, a metastore that `stored` resolved to, as `viewer` may read it: no secret for anyone,
    * and for a caller who is not a superuser nothing of QoD's own Postgres server
    * ([[TenantDb.metastoreShownToTenant]]).
    */
  def metastoreFor(
      viewer: SessionScope,
      shown: Map[String, String],
      stored: Map[String, String],
      defaults: Map[String, String]
  ): Map[String, String] =
    val plain = redactPassword(shown)
    if viewer.superuser then plain else TenantDb.metastoreShownToTenant(plain, stored, defaults)
