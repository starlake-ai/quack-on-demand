package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RunningNode, Tenant, TenantDb}

/** Per-entity persistence for the normalized control plane (`qodstate_tenant`,
  * `qodstate_tenant_db`, `qodstate_pool`, `qodstate_node`) plus the RBAC graph (`qodstate_user`,
  * `qodstate_role`, `qodstate_role_permission`, `qodstate_group`, the
  * user_group/user_role/group_role membership edges, and `qodstate_pool_permission`).
  * Implementations are responsible for ordered teardown -- deleting a parent fails when children
  * remain (matches the FK RESTRICT on the Postgres backend for control-plane rows; the RBAC graph
  * uses ON DELETE CASCADE so dropping a tenant takes its roles/groups/permissions with it).
  */
trait ControlPlaneStore:

  def upsertTenant(t: Tenant): Unit
  def listTenants(): List[Tenant]
  def deleteTenant(id: String): Unit

  /** Bootstrap a new tenant atomically: insert the tenant row, its built-in `admin` role, and the
    * `*.*.* ALL` permission attached to that role -- all three in a single transaction so a partial
    * failure leaves no orphan role / permission rows. Caller picks the role / permission ids.
    * Throws on uniqueness violation.
    */
  def createTenantWithAdminRole(
      tenant: Tenant,
      adminRole: RbacRole,
      adminPermission: RolePermission
  ): Unit

  def upsertTenantDb(t: TenantDb): Unit
  def listTenantDbs(tenantId: String): List[TenantDb]
  def deleteTenantDb(id: String): Unit

  def upsertPool(p: Pool): Unit
  def listPools(tenantDbId: String): List[Pool]
  def deletePool(id: String): Unit

  /** Insert/update a running node under the given pool surrogate id. `RunningNode.poolKey` is a
    * natural key (tenant/pool) carried by the runtime and does not always match a single Postgres
    * row, so the FK to `qodstate_pool.id` is supplied explicitly by the caller.
    */
  def upsertNode(n: RunningNode, poolId: String): Unit
  def listNodes(poolId: String): List[RunningNode]
  def deleteNode(nodeId: String): Unit

  // ---------------- RBAC: users ------------------------------------------
  // Password upserts still go through [[UserStore]] (bcrypt + ON CONFLICT
  // against the partial unique indexes). The methods here only manipulate
  // the identity row + the FK target for role/group/pool-permission edges.

  /** Upsert a `qodstate_user` row WITHOUT touching the password hash. Use [[UserStore.upsertUser]]
    * to create or rotate a password.
    */
  def upsertUserIdentity(u: RbacUser): Unit

  /** Read the bcrypt password hash for a user identified by `(tenant, username)`. Returns `None`
    * when the user does not exist. Used by [[ai.starlake.quack.ondemand.manifest.ManifestImporter]]
    * to snapshot existing credentials before a per-user replace so that users with no `password:`
    * field in the YAML can be carried forward without rotating the hash.
    */
  def getPasswordHash(tenant: Option[String], username: String): Option[String]

  /** Upsert a user row carrying its bcrypt hash verbatim. Returns the user id (newly generated for
    * inserts, existing for updates). Unlike [[upsertUserIdentity]] this writes the `password_hash`
    * column. The hash is stored as-is -- the caller is responsible for bcrypt-ing the plaintext
    * (see [[ai.starlake.quack.ondemand.manifest.BcryptUtils.toHash]]).
    */
  def upsertUserWithHash(
      tenant: Option[String],
      username: String,
      passwordHash: String,
      role: String
  ): String

  def getUserById(id: String): Option[RbacUser]
  def findUser(tenant: Option[String], username: String): Option[RbacUser]

  /** `None` lists every user, `Some(t)` returns the principals scoped to tenant `t`. Pass
    * `Some("")` to deliberately match nothing.
    */
  def listUsers(tenant: Option[String]): List[RbacUser]

  /** All `tenant IS NULL` rows -- the superuser set. */
  def listSuperusers(): List[RbacUser]

  /** Login-style lookup: prefer the tenant-scoped row when one exists for `(tenantId, username)`,
    * fall back to the superuser row with `tenant IS NULL AND username = ?` if the tenant-scoped one
    * is absent. Mirrors the [[ai.starlake.quack.edge.auth.DatabaseAuthenticator]] query that the
    * FlightSQL handshake uses to map a Basic credential onto a [[RbacUser]] before the authorize
    * gate runs.
    */
  def findUserForLogin(tenantId: String, username: String): Option[RbacUser]
  def deleteUser(id: String): Unit

  // ---------------- RBAC: roles ------------------------------------------

  def upsertRole(r: RbacRole): Unit
  def listRoles(tenantId: String): List[RbacRole]
  def getRole(id: String): Option[RbacRole]
  def findRole(tenantId: String, name: String): Option[RbacRole]
  def deleteRole(id: String): Unit

  // ---------------- RBAC: role permissions -------------------------------

  /** Insert a permission row, returning the row with its `grantedAt` populated by the database
    * default. Caller is expected to set `id` (use the same `${prefix}-<8 hex>` shape as the other
    * surrogates).
    */
  def insertRolePermission(p: RolePermission): RolePermission
  def listRolePermissions(roleId: String): List[RolePermission]

  /** Look up a single role permission by id. Used by the tenant-scope check to resolve a permission
    * id back to its owning tenant (via the parent role).
    */
  def getRolePermission(id: String): Option[RolePermission]

  /** Bulk fetch -- used by the per-statement validator to expand all effective roles' permissions
    * in one round-trip.
    */
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

  /** Bulk-fetch group ids for a batch of users. Same semantics as [[listDirectRolesByUsers]].
    */
  def listGroupsByUsers(userIds: List[String]): Map[String, Set[String]]

  def addUserRole(userId: String, roleId: String): Unit
  def removeUserRole(userId: String, roleId: String): Boolean
  def listDirectRolesForUser(userId: String): List[String]

  /** Bulk-fetch direct role ids for a batch of users. Empty input returns `Map.empty`; users with
    * no roles are omitted from the result so callers can `getOrElse(uid, Set.empty)`.
    */
  def listDirectRolesByUsers(userIds: List[String]): Map[String, Set[String]]

  def addGroupRole(groupId: String, roleId: String): Unit
  def removeGroupRole(groupId: String, roleId: String): Boolean
  def listRolesForGroup(groupId: String): List[String]

  // ---------------- RBAC: pool permissions -------------------------------

  /** Insert a pool grant, returning the row with `grantedAt` populated. Caller sets `id`. Exactly
    * one of `userId` / `groupId` must be `Some` -- the table CHECK enforces this.
    */
  def insertPoolPermission(p: PoolPermission): PoolPermission
  def deletePoolPermission(id: String): Boolean

  /** Look up a single pool permission by id. Used by the tenant-scope check to resolve a grant id
    * back to its owning tenant.
    */
  def getPoolPermission(id: String): Option[PoolPermission]

  /** Filter by any subset of tenant / user / group. All-None lists every row. */
  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId: Option[String] = None,
      groupId: Option[String] = None
  ): List[PoolPermission]
  def listPoolPermissionsForUser(userId: String): List[PoolPermission]
  def listPoolPermissionsForGroup(groupId: String): List[PoolPermission]

  /** Bulk-fetch user-scoped pool grants. Map keys are user ids with at least one grant; users with
    * none are omitted.
    */
  def listPoolPermissionsByUsers(userIds: List[String]): Map[String, List[PoolPermission]]

  // ----- Column policies -----
  def insertColumnPolicy(p: RoleColumnPolicy): RoleColumnPolicy
  def updateColumnPolicy(id: String, action: String, transformSql: Option[String]): Boolean
  def deleteColumnPolicy(id: String): Boolean
  def getColumnPolicy(id: String): Option[RoleColumnPolicy]
  def listColumnPolicies(roleId: String): List[RoleColumnPolicy]
  def listAllColumnPolicies(): List[RoleColumnPolicy]

  // ----- Row policies -----
  def insertRowPolicy(p: RoleRowPolicy): RoleRowPolicy
  def updateRowPolicy(id: String, predicateSql: String): Boolean
  def deleteRowPolicy(id: String): Boolean
  def getRowPolicy(id: String): Option[RoleRowPolicy]
  def listRowPolicies(roleId: String): List[RoleRowPolicy]

  /** Load the full topology in one round-trip. Used by the supervisor at boot to seed its in-memory
    * caches. The RBAC graph is included so [[ai.starlake.quack.ondemand.rbac.RbacResolver]] can
    * answer effective_pools / effective_roles without per-request joins.
    */
  def snapshot(): ControlPlaneSnapshot

  /** Release any pooled connections / heap resources. Default impl is a no-op (in-memory stores
    * hold no I/O resources); [[PostgresControlPlaneStore.close]] drains the Hikari pool. Called
    * from Main's shutdown hook so JVM exit returns connections cleanly to the broker.
    */
  def close(): Unit = ()

  /** HA: JWT revocation rows shared across manager replicas. Defaults are no-ops so in-memory
    * stores and test doubles stay revocation-free.
    */
  def insertRevokedJti(jti: String, expiresAt: java.time.Instant): Unit = ()
  def listRevokedJti(): List[(String, java.time.Instant)]               = Nil
  def purgeExpiredRevokedJti(now: java.time.Instant): Unit              = ()

  /** HA: broadcast a change notification to peer replicas. No-op by default. */
  def notifyListeners(channel: String, payload: String): Unit = ()

  /** Cheap liveness probe for readiness checks. */
  def ping(): Boolean = true
