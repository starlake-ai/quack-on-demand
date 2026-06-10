package ai.starlake.quack.ondemand.state

import java.time.Instant

/** A row in `qodstate_pool_permission`. Grants a user OR a group access to a `(tenant, pool)` pair.
  * `poolId = None` means "every pool in the tenant". Exactly one of `userId` / `groupId` is set --
  * enforced both at the application layer and by the table CHECK constraint.
  */
final case class PoolPermission(
    id: String,
    tenantId: String,
    poolId: Option[String] = None,
    userId: Option[String] = None,
    groupId: Option[String] = None,
    grantedAt: Option[Instant] = None
)
