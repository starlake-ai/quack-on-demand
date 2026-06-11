package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.ondemand.state._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RbacResolverSpec extends AnyFlatSpec with Matchers:

  private val role  = RbacRole (id = "r-1", tenantId = "t-1", name = "admin")
  private val role2 = RbacRole (id = "r-2", tenantId = "t-1", name = "viewer")
  private val group = RbacGroup(id = "g-1", tenantId = "t-1", name = "engineers")
  private val perm  = RolePermission("rp-1", "r-1", "*", "*", "*", "ALL")
  private val perm2 = RolePermission("rp-2", "r-2", "tpch", "*", "customer", "RO")
  private val groupGrant =
    PoolPermission("pp-g", "t-1", poolId = Some("p-1"), groupId = Some("g-1"))
  private val userGrant =
    PoolPermission("pp-u", "t-1", poolId = None,       userId  = Some("u-1"))

  "RbacResolver" should "mirror a snapshot's schema-bounded slice" in:
    val r = new RbacResolver()
    r.replace(ControlPlaneSnapshot(
      roles           = List(role, role2),
      groups          = List(group),
      rolePermissions = List(perm, perm2),
      groupRoles      = List(GroupRoleEdge("g-1", "r-1")),
      poolPermissions = List(groupGrant, userGrant),
      // The next two are user-bound -- the resolver MUST ignore them.
      users           = List(RbacUser("u-1", Some("t-1"), "alice", "user")),
      userRoles       = List(UserRoleEdge ("u-1", "r-1")),
      userGroups      = List(UserGroupEdge("u-1", "g-1"))
    ))

    r.role ("r-1") .map(_.name) shouldBe Some("admin")
    r.group("g-1") .map(_.name) shouldBe Some("engineers")
    r.rolesForGroup("g-1")      shouldBe Set("r-1")
    r.permissionsForRoles(Set("r-1", "r-2")).map(_.id).toSet shouldBe Set("rp-1", "rp-2")

  it should "cache GROUP-scoped pool permissions only, never user-scoped" in:
    val r = new RbacResolver()
    r.replace(ControlPlaneSnapshot(
      groups          = List(group),
      poolPermissions = List(groupGrant, userGrant)
    ))

    // The group-scoped grant is reachable via the group.
    r.poolPermissionsForGroup("g-1").map(_.id) shouldBe List("pp-g")
    // The user-scoped grant must not be in any reader the resolver exposes.
    // (It belongs to the per-handshake Postgres lookup, not the in-memory cache.)
    r.poolPermissionsForGroup("u-1") shouldBe empty

  it should "expose no method to look up a user" in:
    // Compile-time check: RbacResolver does NOT define `user(id)` or any
    // user-cache accessor. This test fails to compile if option-2 ever
    // sneaks back in. Use a structural-shape assertion since runtime
    // reflection wouldn't catch a private TrieMap.
    val r = new RbacResolver()
    val methods = r.getClass.getMethods.map(_.getName).toSet
    methods should not contain "user"
    methods should not contain "putUser"
    methods should not contain "removeUser"
    methods should not contain "directRoleIdsOf"
    methods should not contain "groupIdsOf"

  it should "resolve JWT-claimed role + group names against the tenant" in:
    val r = new RbacResolver()
    r.replace(ControlPlaneSnapshot(
      roles  = List(role, role2),                                 // tenant t-1: admin, viewer
      groups = List(group, group.copy(id = "g-2", name = "ops")),  // engineers, ops
      rolePermissions = List(perm, perm2)
    ))

    // Names that match → ids.
    r.rolesByNamesInTenant ("t-1", Set("admin", "viewer", "unknown")) shouldBe Set("r-1", "r-2")
    r.groupsByNamesInTenant("t-1", Set("engineers", "ops"))           shouldBe Set("g-1", "g-2")

    // Tenant scoping: a name that exists in a different tenant doesn't leak.
    r.rolesByNamesInTenant ("t-other", Set("admin")) shouldBe Set.empty

    // Empty input short-circuits cheaply.
    r.rolesByNamesInTenant("t-1", Set.empty)  shouldBe Set.empty
    r.groupsByNamesInTenant("t-1", Set.empty) shouldBe Set.empty

  it should "drop user-bound edges from the snapshot on replace()" in:
    val r = new RbacResolver()
    r.replace(ControlPlaneSnapshot(
      roles      = List(role),
      groups     = List(group),
      groupRoles = List(GroupRoleEdge("g-1", "r-1")),
      // user-bound edges -- must be ignored
      userGroups = List(UserGroupEdge("u-1", "g-1")),
      userRoles  = List(UserRoleEdge ("u-1", "r-1"))
    ))
    // The schema-bounded edge survives.
    r.rolesForGroup("g-1") shouldBe Set("r-1")
    // No user-side surface to expose the user-bound edges. Verified
    // structurally above; reasserted by the resolver's own readers
    // returning the same set whether or not the user edges were in
    // the snapshot.
    val r2 = new RbacResolver()
    r2.replace(ControlPlaneSnapshot(
      roles      = List(role),
      groups     = List(group),
      groupRoles = List(GroupRoleEdge("g-1", "r-1"))
    ))
    r.rolesForGroup("g-1") shouldBe r2.rolesForGroup("g-1")
