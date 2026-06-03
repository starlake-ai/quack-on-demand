package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.{AclGrant, AclGrantStore, TenantIdentityStore}
import com.typesafe.scalalogging.LazyLogging

/** Idempotent at-boot seeding so the bootstrap admin can connect over
  * FlightSQL and run any SQL out of the box. For each admin username
  * (from `quack-on-demand.admin.username`) and the bootstrap tenant id:
  *
  *   - upsert a `qodstate_tenant_identity` row mapping
  *     `(issuer="db", externalId=<adminName>)` to the tenant id, and
  *   - upsert an `slkstate_acl_grant` ALL grant for
  *     `principal=user:<adminName>` on every catalog/schema/table.
  *
  * Both writes are list-first / write-if-missing so reboots are no-ops. */
object BootstrapAccessSeeder extends LazyLogging:

  /** Seed identity + ALL grant for `adminNames` under `tenantId`.
    *
    * Returns the number of rows actually inserted (sum across both
    * tables), useful for tests asserting idempotency. */
  def seed(
      tenantId:      String,
      tenantLabel:   String,
      adminNames:    List[String],
      identityStore: TenantIdentityStore,
      grantStore:    AclGrantStore
  ): Int =
    if tenantId.isEmpty || adminNames.isEmpty then 0
    else
      val existingIdentities =
        identityStore.list(Some(tenantId)).map(i => (i.issuer, i.externalId)).toSet
      val existingAllGrants =
        grantStore.list(Some(tenantId))
          .filter(g =>
            g.permission.equalsIgnoreCase("ALL")
              && g.catalogName.isEmpty
              && g.schemaName.isEmpty
              && g.tableName.isEmpty
          )
          .map(_.principal)
          .toSet

      var inserts = 0
      adminNames.foreach { user =>
        if existingIdentities.contains(("db", user)) then
          logger.info(s"bootstrap-seed: identity db/$user -> $tenantLabel already present")
        else
          identityStore.create(tenantId, "db", user)
          inserts += 1
          logger.info(s"bootstrap-seed: seeded identity db/$user -> $tenantLabel")

        val principal = s"user:$user"
        if existingAllGrants.contains(principal) then
          logger.info(s"bootstrap-seed: ALL grant for $principal on $tenantLabel already present")
        else
          grantStore.insert(AclGrant(
            id          = None,
            tenantId    = tenantId,
            principal   = principal,
            catalogName = None,
            schemaName  = None,
            tableName   = None,
            permission  = "ALL",
            grantedAt   = None
          ))
          inserts += 1
          logger.info(s"bootstrap-seed: seeded ALL grant for $principal on $tenantLabel")
      }
      inserts
