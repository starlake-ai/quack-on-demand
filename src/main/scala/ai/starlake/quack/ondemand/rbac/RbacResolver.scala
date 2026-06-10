package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.ondemand.state._

import scala.collection.concurrent.TrieMap

/** In-memory mirror of the SCHEMA-bounded slice of the RBAC graph: roles, groups, role permissions,
  * group-role edges, and group-scoped pool permissions. User-bound state (users themselves,
  * user-role / user-group / user-pool-permission edges) is deliberately NOT cached here -- those
  * live in Postgres and are fetched per-handshake by
  * [[ai.starlake.quack.ondemand.PoolSupervisor.effectiveSetForUser]].
  *
  * Memory footprint is bounded by tenant + schema cardinality (roles per tenant, groups per tenant,
  * permissions per role) rather than org size, so the manager stays light even with millions of
  * users.
  *
  * Reader methods expose the closure operations the spec calls "rolesForGroup" and
  * "permissionsForRoles". The handshake path combines them with the per-user direct edges (read
  * straight from Postgres) to compute the spec's
  * `effective_roles / effective_pools / effective_perms` sets:
  *
  * {{{
  *   effective_roles(U)  = direct_roles(U) ∪ ⋃ roles(g)   for g ∈ groups(U)
  *   effective_pools(U)  = direct_pools(U) ∪ ⋃ pools(g)   for g ∈ groups(U)
  *                         (pool_id NULL matches every pool in tenant)
  *   effective_perms(U)  = ⋃ permissions(r)               for r ∈ effective_roles(U)
  * }}}
  *
  * Superusers (`U.tenant IS NULL`) bypass effective_pools + effective_perms upstream in the
  * handshake; this resolver only answers the tenant-scoped principal questions.
  */
final class RbacResolver:

  // Schema-bounded entity caches.
  private val roles           = TrieMap.empty[String, RbacRole]
  private val groups          = TrieMap.empty[String, RbacGroup]
  private val rolePermissions = TrieMap.empty[String, RolePermission]
  // Only group-scoped pool permissions live here. User-scoped grants
  // are fetched from Postgres at handshake time alongside the user's
  // direct user-role / user-group edges.
  private val poolPermissions = TrieMap.empty[String, PoolPermission]

  // Group-side edges. Forward (group -> set of role ids) is the
  // primary read for handshake closure; reverse (role -> set of group
  // ids) is maintained so a role deletion can be reflected in O(degree)
  // without scanning the whole map.
  private val groupToRoles = TrieMap.empty[String, Set[String]]
  private val roleToGroups = TrieMap.empty[String, Set[String]]
  // groupId -> set of pool-permission ids granted to that group.
  private val poolGrantsByGroup = TrieMap.empty[String, Set[String]]

  /** Replace the whole graph from a fresh snapshot (boot / reload). Drops everything user-related
    * from the snapshot -- this resolver only mirrors the schema-bounded slice.
    */
  def replace(snap: ControlPlaneSnapshot): Unit = synchronized {
    roles.clear(); groups.clear(); rolePermissions.clear()
    poolPermissions.clear()
    groupToRoles.clear(); roleToGroups.clear(); poolGrantsByGroup.clear()

    snap.roles.foreach(r => roles.put(r.id, r))
    snap.groups.foreach(g => groups.put(g.id, g))
    snap.rolePermissions.foreach(p => rolePermissions.put(p.id, p))
    snap.poolPermissions
      .filter(_.groupId.isDefined)
      .foreach(addPoolPermissionLocal)
    snap.groupRoles.foreach(e => addEdge(groupToRoles, roleToGroups, e.groupId, e.roleId))
  }

  // ---------- mutators (called by the supervisor after a store write) -----

  def putRole(r: RbacRole): Unit   = roles.put(r.id, r)
  def putGroup(g: RbacGroup): Unit = groups.put(g.id, g)

  def removeRole(id: String): Unit =
    roles.remove(id)
    rolePermissions.filterInPlace((_, p) => p.roleId != id)
    roleToGroups.remove(id).foreach(_.foreach(g => removeFromIndex(groupToRoles, g, id)))

  def removeGroup(id: String): Unit =
    groups.remove(id)
    groupToRoles.remove(id).foreach(_.foreach(r => removeFromIndex(roleToGroups, r, id)))
    poolGrantsByGroup.remove(id).foreach(_.foreach(pid => poolPermissions.remove(pid)))

  def putRolePermission(p: RolePermission): Unit = rolePermissions.put(p.id, p)
  def removeRolePermission(id: String): Unit     = rolePermissions.remove(id)

  /** Cache the new grant ONLY if it's group-scoped. User-scoped grants are looked up from Postgres
    * on the handshake path.
    */
  def putPoolPermission(p: PoolPermission): Unit =
    if p.groupId.isDefined then addPoolPermissionLocal(p)

  def removePoolPermission(id: String): Unit =
    poolPermissions.remove(id).foreach { p =>
      p.groupId.foreach(g => removeFromIndex(poolGrantsByGroup, g, id))
    }

  def addGroupRoleEdge(groupId: String, roleId: String): Unit =
    if groups.contains(groupId) && roles.contains(roleId) then
      addEdge(groupToRoles, roleToGroups, groupId, roleId)
  def removeGroupRoleEdge(groupId: String, roleId: String): Unit =
    removeFromIndex(groupToRoles, groupId, roleId)
    removeFromIndex(roleToGroups, roleId, groupId)

  // ---------- readers --------------------------------------------------

  def role(id: String): Option[RbacRole]   = roles.get(id)
  def group(id: String): Option[RbacGroup] = groups.get(id)

  /** All known roles, sorted by name. Used by the REST list endpoints and the per-tenant breakdown
    * filters.
    */
  def allRoles: List[RbacRole]   = roles.values.toList.sortBy(_.name)
  def allGroups: List[RbacGroup] = groups.values.toList.sortBy(_.name)

  def rolesForGroup(groupId: String): Set[String] =
    groupToRoles.getOrElse(groupId, Set.empty)

  /** Resolve a tenant-scoped set of role NAMES (as a JWT `roles` claim would carry) to the
    * corresponding `qodstate_role.id` set. Names that don't match a known role in the tenant are
    * silently dropped -- callers union the result with the user's local direct roles.
    */
  def rolesByNamesInTenant(tenantId: String, names: Set[String]): Set[String] =
    if names.isEmpty then Set.empty
    else
      roles.values.iterator
        .filter(r => r.tenantId == tenantId && names.contains(r.name))
        .map(_.id)
        .toSet

  /** Same as [[rolesByNamesInTenant]] but for groups -- JWT `groups` claim → `qodstate_group.id`.
    */
  def groupsByNamesInTenant(tenantId: String, names: Set[String]): Set[String] =
    if names.isEmpty then Set.empty
    else
      groups.values.iterator
        .filter(g => g.tenantId == tenantId && names.contains(g.name))
        .map(_.id)
        .toSet

  def permissionsForRoles(roleIds: Set[String]): List[RolePermission] =
    if roleIds.isEmpty then Nil
    else
      rolePermissions.values
        .filter(p => roleIds.contains(p.roleId))
        .toList
        .sortBy(p => (p.catalogName, p.schemaName, p.tableName, p.verb))

  def poolPermissionsForGroup(groupId: String): List[PoolPermission] =
    poolGrantsByGroup
      .getOrElse(groupId, Set.empty)
      .flatMap(poolPermissions.get)
      .toList
      .sortBy(_.id)

  // ---------- helpers --------------------------------------------------

  private def addEdge(
      forward: TrieMap[String, Set[String]],
      reverse: TrieMap[String, Set[String]],
      from: String,
      to: String
  ): Unit =
    forward.updateWith(from)(prev => Some(prev.getOrElse(Set.empty) + to))
    reverse.updateWith(to)(prev => Some(prev.getOrElse(Set.empty) + from))

  private def removeFromIndex(
      index: TrieMap[String, Set[String]],
      key: String,
      v: String
  ): Unit =
    index.updateWith(key) {
      case Some(s) =>
        val next = s - v
        if next.isEmpty then None else Some(next)
      case None => None
    }

  private def addPoolPermissionLocal(p: PoolPermission): Unit =
    poolPermissions.put(p.id, p)
    p.groupId.foreach(g =>
      poolGrantsByGroup.updateWith(g)(prev => Some(prev.getOrElse(Set.empty) + p.id))
    )
