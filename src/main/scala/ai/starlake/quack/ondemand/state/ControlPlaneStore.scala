package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RunningNode, Tenant, TenantDb}

/** Per-entity persistence for the normalized control plane
  * (`qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`,
  * `qodstate_node`) plus the RBAC graph
  * (`qodstate_user`, `qodstate_role`, `qodstate_role_permission`,
  * `qodstate_group`, the user_group/user_role/group_role membership
  * edges, and `qodstate_pool_permission`). Implementations are
  * responsible for ordered teardown -- deleting a parent fails when
  * children remain (matches the FK RESTRICT on the Postgres backend
  * for control-plane rows; the RBAC graph uses ON DELETE CASCADE so
  * dropping a tenant takes its roles/groups/permissions with it). */
trait ControlPlaneStore:

  def upsertTenant(t: Tenant): Unit
  def listTenants(): List[Tenant]
  def deleteTenant(id: String): Unit

  def upsertTenantDb(t: TenantDb): Unit
  def listTenantDbs(tenantId: String): List[TenantDb]
  def deleteTenantDb(id: String): Unit

  def upsertPool(p: Pool): Unit
  def listPools(tenantDbId: String): List[Pool]
  def deletePool(id: String): Unit

  /** Insert/update a running node under the given pool surrogate id.
    * `RunningNode.poolKey` is a natural key (tenant/pool) carried by the
    * runtime and does not always match a single Postgres row, so the FK
    * to `qodstate_pool.id` is supplied explicitly by the caller. */
  def upsertNode(n: RunningNode, poolId: String): Unit
  def listNodes(poolId: String): List[RunningNode]
  def deleteNode(nodeId: String): Unit

  // ---------------- RBAC: users ------------------------------------------
  // Password upserts still go through [[UserStore]] (bcrypt + ON CONFLICT
  // against the partial unique indexes). The methods here only manipulate
  // the identity row + the FK target for role/group/pool-permission edges.

  /** Upsert a `qodstate_user` row WITHOUT touching the password hash.
    * Use [[UserStore.upsertUser]] to create or rotate a password. */
  def upsertUserIdentity(u: RbacUser): Unit
  def getUserById(id: String): Option[RbacUser]
  def findUser(tenant: Option[String], username: String): Option[RbacUser]
  /** `None` lists every user, `Some(t)` returns the principals scoped to
    * tenant `t`. Pass `Some("")` to deliberately match nothing. */
  def listUsers(tenant: Option[String]): List[RbacUser]
  /** All `tenant IS NULL` rows -- the superuser set. */
  def listSuperusers(): List[RbacUser]
  def deleteUser(id: String): Unit

  // ---------------- RBAC: roles ------------------------------------------

  def upsertRole(r: RbacRole): Unit
  def listRoles(tenantId: String): List[RbacRole]
  def getRole(id: String): Option[RbacRole]
  def findRole(tenantId: String, name: String): Option[RbacRole]
  def deleteRole(id: String): Unit

  // ---------------- RBAC: role permissions -------------------------------

  /** Insert a permission row, returning the row with its `grantedAt`
    * populated by the database default. Caller is expected to set `id`
    * (use the same `${prefix}-<8 hex>` shape as the other surrogates). */
  def insertRolePermission(p: RolePermission): RolePermission
  def listRolePermissions(roleId: String): List[RolePermission]
  /** Bulk fetch -- used by the per-statement validator to expand all
    * effective roles' permissions in one round-trip. */
  def listRolePermissionsForRoles(roleIds: Set[String]): List[RolePermission]
  def deleteRolePermission(id: String): Boolean

  // ---------------- RBAC: groups -----------------------------------------

  def upsertGroup(g: RbacGroup): Unit
  def listGroups(tenantId: String): List[RbacGroup]
  def getGroup(id: String): Option[RbacGroup]
  def findGroup(tenantId: String, name: String): Option[RbacGroup]
  def deleteGroup(id: String): Unit

  // ---------------- RBAC: memberships ------------------------------------

  def addUserGroup(userId: String, groupId: String): Unit
  def removeUserGroup(userId: String, groupId: String): Boolean
  def listGroupsForUser(userId: String): List[String]
  def listUsersInGroup(groupId: String): List[String]

  def addUserRole(userId: String, roleId: String): Unit
  def removeUserRole(userId: String, roleId: String): Boolean
  def listDirectRolesForUser(userId: String): List[String]

  def addGroupRole(groupId: String, roleId: String): Unit
  def removeGroupRole(groupId: String, roleId: String): Boolean
  def listRolesForGroup(groupId: String): List[String]

  // ---------------- RBAC: pool permissions -------------------------------

  /** Insert a pool grant, returning the row with `grantedAt` populated.
    * Caller sets `id`. Exactly one of `userId` / `groupId` must be
    * `Some` -- the table CHECK enforces this. */
  def insertPoolPermission(p: PoolPermission): PoolPermission
  def deletePoolPermission(id: String): Boolean
  /** Filter by any subset of tenant / user / group. All-None lists every row. */
  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId:   Option[String] = None,
      groupId:  Option[String] = None
  ): List[PoolPermission]
  def listPoolPermissionsForUser(userId: String):   List[PoolPermission]
  def listPoolPermissionsForGroup(groupId: String): List[PoolPermission]

  /** Load the full topology in one round-trip. Used by the supervisor
    * at boot to seed its in-memory caches. The RBAC graph is included
    * so [[ai.starlake.quack.ondemand.rbac.RbacResolver]] can answer
    * effective_pools / effective_roles without per-request joins. */
  def snapshot(): ControlPlaneSnapshot