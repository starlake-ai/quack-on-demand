package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.TenantIdentityStore
import com.typesafe.scalalogging.LazyLogging

/** Idempotent at-boot seeding so the bootstrap admin can connect over
  * FlightSQL out of the box. For each admin username (from
  * `quack-on-demand.admin.username`) and the bootstrap tenant id we
  * upsert a `qodstate_tenant_identity` row mapping
  * `(issuer="db", externalId=<adminName>)` to the tenant id.
  *
  * Phase C: the prior `slkstate_acl_grant` ALL-grant seeding is gone --
  * superuser admins bypass the per-statement ACL gate via
  * `qodstate_user.tenant IS NULL`, and a tenant-scoped admin would be
  * linked to that tenant's built-in `admin` role through the new RBAC
  * graph (PoolSupervisor.createTenant seeds the role + permission
  * transactionally). */
object BootstrapAccessSeeder extends LazyLogging:

  /** Seed identity rows for `adminNames` under `tenantId`. Returns the
    * number of rows actually inserted (useful for tests asserting
    * idempotency). */
  def seed(
      tenantId:      String,
      tenantLabel:   String,
      adminNames:    List[String],
      identityStore: TenantIdentityStore
  ): Int =
    if tenantId.isEmpty || adminNames.isEmpty then 0
    else
      val existing =
        identityStore.list(Some(tenantId)).map(i => (i.issuer, i.externalId)).toSet
      var inserts = 0
      adminNames.foreach { user =>
        if existing.contains(("db", user)) then
          logger.info(s"bootstrap-seed: identity db/$user -> $tenantLabel already present")
        else
          identityStore.create(tenantId, "db", user)
          inserts += 1
          logger.info(s"bootstrap-seed: seeded identity db/$user -> $tenantLabel")
      }
      inserts