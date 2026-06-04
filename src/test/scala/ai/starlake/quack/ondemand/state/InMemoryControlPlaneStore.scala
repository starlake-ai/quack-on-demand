package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RunningNode, Tenant, TenantDb}

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Process-local fixture for unit tests that exercise the supervisor or
  * handlers without standing up a Postgres. Tracks the same per-entity
  * shape as [[PostgresControlPlaneStore]] and enforces FK RESTRICT on
  * control-plane parent deletes so callers can verify ordered teardown.
  *
  * The RBAC graph (users, roles, groups, memberships, permissions)
  * uses ON DELETE CASCADE in Postgres; the in-memory store mirrors
  * that by reaping the dependent edges/permissions on parent removal. */
final class InMemoryControlPlaneStore extends ControlPlaneStore:

  private val tenants   = TrieMap.empty[String, Tenant]
  private val tenantDbs = TrieMap.empty[String, TenantDb]
  private val pools     = TrieMap.empty[String, Pool]
  private val nodes     = TrieMap.empty[String, RunningNode]

  def upsertTenant(t: Tenant): Unit = tenants.put(t.id, t)

  def createTenantWithAdminRole(
      tenant:          Tenant,
      adminRole:       RbacRole,
      adminPermission: RolePermission
  ): Unit =
    if tenants.contains(tenant.id) then
      throw new java.sql.SQLException(s"tenant id ${tenant.id} already exists")
    tenants.put(tenant.id, tenant)
    upsertRole(adminRole)
    insertRolePermission(adminPermission)
  def listTenants(): List[Tenant]   = tenants.values.toList.sortBy(_.displayName)
  def deleteTenant(id: String): Unit =
    if tenantDbs.values.exists(_.tenantId == id) then
      throw new java.sql.SQLException(s"tenant $id has tenant-db children")
    tenants.remove(id)
    // CASCADE the RBAC subgraph.
    roles.values.filter(_.tenantId == id).foreach(r => deleteRole(r.id))
    groups.values.filter(_.tenantId == id).foreach(g => deleteGroup(g.id))
    poolPermissions.values.filter(_.tenantId == id).foreach(p => poolPermissions.remove(p.id))

  def upsertTenantDb(t: TenantDb): Unit = tenantDbs.put(t.id, t)
  def listTenantDbs(tenantId: String): List[TenantDb] =
    tenantDbs.values.filter(_.tenantId == tenantId).toList.sortBy(_.name)
  def deleteTenantDb(id: String): Unit =
    if pools.values.exists(_.tenantDbId == id) then
      throw new java.sql.SQLException(s"tenant-db $id has pool children")
    tenantDbs.remove(id)

  def upsertPool(p: Pool): Unit = pools.put(p.id, p)
  def listPools(tenantDbId: String): List[Pool] =
    pools.values.filter(_.tenantDbId == tenantDbId).toList.sortBy(_.name)
  def deletePool(id: String): Unit =
    if nodeIndex.values.exists(_ == id) then
      throw new java.sql.SQLException(s"pool $id has node children")
    pools.remove(id)
    poolPermissions.values.filter(_.poolId.contains(id)).foreach(p => poolPermissions.remove(p.id))

  // node_id -> pool_id index (matches the Postgres FK shape).
  private val nodeIndex = TrieMap.empty[String, String]

  def upsertNode(n: RunningNode, poolId: String): Unit =
    nodes.put(n.nodeId, n)
    nodeIndex.put(n.nodeId, poolId)
  def listNodes(poolId: String): List[RunningNode] =
    nodeIndex.collect { case (nid, pid) if pid == poolId => nodes(nid) }
      .toList.sortBy(_.nodeId)
  def deleteNode(nodeId: String): Unit =
    nodes.remove(nodeId)
    nodeIndex.remove(nodeId)

  // ---------------- RBAC: users ----------------
  private val users = TrieMap.empty[String, RbacUser]
  def upsertUserIdentity(u: RbacUser): Unit =
    val existing = users.get(u.id)
    val now = Instant.now()
    users.put(u.id, u.copy(
      createdAt = u.createdAt.orElse(existing.flatMap(_.createdAt)).orElse(Some(now)),
      updatedAt = Some(now)
    ))
  def getUserById(id: String): Option[RbacUser] = users.get(id)
  def findUser(tenant: Option[String], username: String): Option[RbacUser] =
    users.values.find(u => u.tenant == tenant && u.username == username)
  def listUsers(tenant: Option[String]): List[RbacUser] = tenant match
    case Some(t) => users.values.filter(_.tenant.contains(t)).toList.sortBy(_.username)
    case None    => users.values.toList.sortBy(u => (u.tenant.getOrElse(""), u.username))
  def listSuperusers(): List[RbacUser] =
    users.values.filter(_.tenant.isEmpty).toList.sortBy(_.username)
  def deleteUser(id: String): Unit =
    users.remove(id)
    userGroups.filterInPlace((u, _) => u != id)
    userRoles .filterInPlace((u, _) => u != id)
    poolPermissions.values.filter(_.userId.contains(id)).foreach(p => poolPermissions.remove(p.id))

  // ---------------- RBAC: roles ----------------
  private val roles = TrieMap.empty[String, RbacRole]
  def upsertRole(r: RbacRole): Unit =
    val existing = roles.get(r.id)
    roles.put(r.id, r.copy(
      createdAt = r.createdAt.orElse(existing.flatMap(_.createdAt)).orElse(Some(Instant.now()))
    ))
  def listRoles(tenantId: String): List[RbacRole] =
    roles.values.filter(_.tenantId == tenantId).toList.sortBy(_.name)
  def getRole(id: String): Option[RbacRole] = roles.get(id)
  def findRole(tenantId: String, name: String): Option[RbacRole] =
    roles.values.find(r => r.tenantId == tenantId && r.name == name)
  def deleteRole(id: String): Unit =
    roles.remove(id)
    rolePermissions.values.filter(_.roleId == id).foreach(p => rolePermissions.remove(p.id))
    userRoles .filterInPlace((_, r) => r != id)
    groupRoles.filterInPlace((_, r) => r != id)

  // ---------------- RBAC: role permissions ----------------
  private val rolePermissions = TrieMap.empty[String, RolePermission]
  def insertRolePermission(p: RolePermission): RolePermission =
    val populated = p.copy(grantedAt = p.grantedAt.orElse(Some(Instant.now())))
    rolePermissions.put(populated.id, populated)
    populated
  def listRolePermissions(roleId: String): List[RolePermission] =
    rolePermissions.values.filter(_.roleId == roleId).toList
      .sortBy(p => (p.catalogName, p.schemaName, p.tableName, p.verb))
  def listRolePermissionsForRoles(roleIds: Set[String]): List[RolePermission] =
    rolePermissions.values.filter(p => roleIds.contains(p.roleId)).toList
  def deleteRolePermission(id: String): Boolean = rolePermissions.remove(id).isDefined

  // ---------------- RBAC: groups ----------------
  private val groups = TrieMap.empty[String, RbacGroup]
  def upsertGroup(g: RbacGroup): Unit = groups.put(g.id, g)
  def listGroups(tenantId: String): List[RbacGroup] =
    groups.values.filter(_.tenantId == tenantId).toList.sortBy(_.name)
  def getGroup(id: String): Option[RbacGroup] = groups.get(id)
  def findGroup(tenantId: String, name: String): Option[RbacGroup] =
    groups.values.find(g => g.tenantId == tenantId && g.name == name)
  def deleteGroup(id: String): Unit =
    groups.remove(id)
    userGroups.filterInPlace((_, g) => g != id)
    groupRoles.filterInPlace((g, _) => g != id)
    poolPermissions.values.filter(_.groupId.contains(id)).foreach(p => poolPermissions.remove(p.id))

  // ---------------- RBAC: memberships ----------------
  private val userGroups = scala.collection.mutable.Set.empty[(String, String)]
  private val userRoles  = scala.collection.mutable.Set.empty[(String, String)]
  private val groupRoles = scala.collection.mutable.Set.empty[(String, String)]

  def addUserGroup(userId: String, groupId: String): Unit = userGroups += ((userId, groupId))
  def removeUserGroup(userId: String, groupId: String): Boolean = userGroups.remove((userId, groupId))
  def listGroupsForUser(userId: String): List[String] =
    userGroups.collect { case (u, g) if u == userId => g }.toList.sorted
  def listUsersInGroup(groupId: String): List[String] =
    userGroups.collect { case (u, g) if g == groupId => u }.toList.sorted

  def addUserRole(userId: String, roleId: String): Unit = userRoles += ((userId, roleId))
  def removeUserRole(userId: String, roleId: String): Boolean = userRoles.remove((userId, roleId))
  def listDirectRolesForUser(userId: String): List[String] =
    userRoles.collect { case (u, r) if u == userId => r }.toList.sorted

  def listDirectRolesByUsers(userIds: List[String]): Map[String, Set[String]] =
    val want = userIds.toSet
    userRoles.collect { case (u, r) if want(u) => (u, r) }
      .groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  def listGroupsByUsers(userIds: List[String]): Map[String, Set[String]] =
    val want = userIds.toSet
    userGroups.collect { case (u, g) if want(u) => (u, g) }
      .groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  def addGroupRole(groupId: String, roleId: String): Unit = groupRoles += ((groupId, roleId))
  def removeGroupRole(groupId: String, roleId: String): Boolean = groupRoles.remove((groupId, roleId))
  def listRolesForGroup(groupId: String): List[String] =
    groupRoles.collect { case (g, r) if g == groupId => r }.toList.sorted

  // ---------------- RBAC: pool permissions ----------------
  private val poolPermissions = TrieMap.empty[String, PoolPermission]
  def insertPoolPermission(p: PoolPermission): PoolPermission =
    require((p.userId.isDefined) ^ (p.groupId.isDefined),
            "exactly one of userId / groupId must be set")
    val populated = p.copy(grantedAt = p.grantedAt.orElse(Some(Instant.now())))
    poolPermissions.put(populated.id, populated)
    populated
  def deletePoolPermission(id: String): Boolean = poolPermissions.remove(id).isDefined
  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId:   Option[String] = None,
      groupId:  Option[String] = None
  ): List[PoolPermission] =
    poolPermissions.values.filter { p =>
      tenantId.forall(_ == p.tenantId) &&
      userId  .forall(u => p.userId.contains(u)) &&
      groupId .forall(g => p.groupId.contains(g))
    }.toList.sortBy(_.id)
  def listPoolPermissionsForUser(userId: String): List[PoolPermission] =
    listPoolPermissions(userId = Some(userId))
  def listPoolPermissionsForGroup(groupId: String): List[PoolPermission] =
    listPoolPermissions(groupId = Some(groupId))

  def listPoolPermissionsByUsers(userIds: List[String]): Map[String, List[PoolPermission]] =
    val want = userIds.toSet
    poolPermissions.values
      .filter(p => p.userId.exists(want.contains))
      .toList
      .groupBy(_.userId.get)

  def snapshot(): ControlPlaneSnapshot = ControlPlaneSnapshot(
    tenants         = listTenants(),
    tenantDbs       = tenantDbs.values.toList.sortBy(_.name),
    pools           = pools.values.toList.sortBy(_.name),
    nodes           = nodes.values.toList.sortBy(_.nodeId),
    users           = users.values.toList.sortBy(u => (u.tenant.getOrElse(""), u.username)),
    roles           = roles.values.toList.sortBy(r => (r.tenantId, r.name)),
    rolePermissions = rolePermissions.values.toList.sortBy(_.id),
    groups          = groups.values.toList.sortBy(g => (g.tenantId, g.name)),
    userGroups      = userGroups.toList.sorted.map((u, g) => UserGroupEdge(u, g)),
    userRoles       = userRoles .toList.sorted.map((u, r) => UserRoleEdge (u, r)),
    groupRoles      = groupRoles.toList.sorted.map((g, r) => GroupRoleEdge(g, r)),
    poolPermissions = poolPermissions.values.toList.sortBy(_.id)
  )
