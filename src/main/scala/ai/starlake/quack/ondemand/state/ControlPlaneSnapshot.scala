package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RunningNode, Tenant, TenantDb}

/** Full graph returned by [[ControlPlaneStore.snapshot]]. Loaded at supervisor restore time in a
  * single round-trip; relationships are followed via the FK fields (`tenantDb.tenantId`,
  * `pool.tenantDbId`, `node.poolKey` joined back to the pool by the store on read).
  *
  * The RBAC slice (users / roles / role permissions / groups / membership edges / pool permissions)
  * is included so [[ai.starlake.quack.ondemand.rbac.RbacResolver]] can build its in-memory caches
  * in one trip. Empty by default so callers that only care about the control-plane rows keep
  * working.
  */
final case class ControlPlaneSnapshot(
    tenants: List[Tenant] = Nil,
    tenantDbs: List[TenantDb] = Nil,
    pools: List[Pool] = Nil,
    nodes: List[RunningNode] = Nil,
    // RBAC graph
    users: List[RbacUser] = Nil,
    roles: List[RbacRole] = Nil,
    rolePermissions: List[RolePermission] = Nil,
    groups: List[RbacGroup] = Nil,
    userGroups: List[UserGroupEdge] = Nil,
    userRoles: List[UserRoleEdge] = Nil,
    groupRoles: List[GroupRoleEdge] = Nil,
    poolPermissions: List[PoolPermission] = Nil,
    columnPolicies: List[RoleColumnPolicy] = Nil,
    rowPolicies: List[RoleRowPolicy] = Nil
)
