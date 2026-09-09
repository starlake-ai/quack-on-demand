package ai.starlake.quack.edge.admin

import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AdminStatementExecutorSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  // Mirror the FlightSqlRouterSpec fake backend: nodes are never spawned in these tests.
  // The store is constructed before the supervisor and kept in the returned tuple so
  // seedUser can seed a credential row directly (createUser needs a UserStore we don't
  // have in this test; InMemoryControlPlaneStore already holds password hashes inline).
  private def setup(): (PoolSupervisor, InMemoryControlPlaneStore, AdminStatementExecutor) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(TestBackends.fake(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    (sup, store, new AdminStatementExecutor(sup))

  private def tenantId(sup: PoolSupervisor): String =
    sup.getTenant("acme").orElse(sup.getTenantById("acme")).get.id

  private def adminEff(sup: PoolSupervisor): Option[EffectiveSet] = Some(
    EffectiveSet(
      user = RbacUser("u-admin", Some(tenantId(sup)), "boss", role = "admin"),
      roles = Nil,
      groups = Nil,
      permissions = Nil,
      poolPerms = Nil
    )
  )

  private def superEff: Option[EffectiveSet] = Some(
    EffectiveSet(
      user = RbacUser("u-root", None, "root", role = "admin"),
      roles = Nil,
      groups = Nil,
      permissions = Nil,
      poolPerms = Nil
    )
  )

  private def userEff(sup: PoolSupervisor): Option[EffectiveSet] = Some(
    EffectiveSet(
      user = RbacUser("u-plain", Some(tenantId(sup)), "carol", role = "user"),
      roles = Nil,
      groups = Nil,
      permissions = Nil,
      poolPerms = Nil
    )
  )

  private def run(exec: AdminStatementExecutor, sup: PoolSupervisor, sql: String) =
    exec.execute("boss", poolKey, sql, adminEff(sup)).unsafeRunSync()

  "authorization" should "deny non-admin principals and missing context" in:
    val (sup, _, exec) = setup()
    exec.execute("carol", poolKey, "CREATE ROLE r1", userEff(sup)).unsafeRunSync() match
      case Left(RouterFailure.AccessDenied(reason)) => reason should include("admin_required")
      case other                                    => fail(s"expected AccessDenied, got $other")
    exec.execute("nobody", poolKey, "CREATE ROLE r1", None).unsafeRunSync() match
      case Left(RouterFailure.AccessDenied(_)) => succeed
      case other                               => fail(s"expected AccessDenied, got $other")

  it should "admit superusers and tenant admins of the session tenant" in:
    val (sup, _, exec) = setup()
    exec.execute("root", poolKey, "CREATE ROLE r1", superEff).unsafeRunSync().isRight shouldBe true
    run(exec, sup, "CREATE ROLE r2").isRight shouldBe true

  it should "deny a tenant admin whose tenant is not the session tenant" in:
    val (sup, _, exec) = setup()
    // role = "admin", but tenant is some OTHER tenant id, not the session's poolKey.tenant.
    val crossTenantAdmin = Some(
      EffectiveSet(
        user = RbacUser("u-other", Some("other-tenant-id"), "eve", role = "admin"),
        roles = Nil,
        groups = Nil,
        permissions = Nil,
        poolPerms = Nil
      )
    )
    exec.execute("eve", poolKey, "CREATE ROLE r1", crossTenantAdmin).unsafeRunSync() match
      case Left(RouterFailure.AccessDenied(reason)) => reason should include("admin_required")
      case other                                    => fail(s"expected AccessDenied, got $other")

  "CREATE/DROP ROLE" should "create, reject duplicates, and honor IF EXISTS" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    sup.listRoles(tenantId(sup)).map(_.name) should contain("analyst")
    run(exec, sup, "DROP ROLE analyst").isRight shouldBe true
    run(exec, sup, "DROP ROLE analyst") match
      case Left(RouterFailure.NotFound(_)) => succeed
      case other                           => fail(s"expected NotFound, got $other")
    run(exec, sup, "DROP ROLE IF EXISTS analyst").isRight shouldBe true

  it should "return AlreadyExists when CREATE ROLE targets an existing name" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(exec, sup, "CREATE ROLE analyst") match
      case Left(RouterFailure.AlreadyExists(_)) => succeed
      case other                                => fail(s"expected AlreadyExists, got $other")

  "GRANT/REVOKE table" should "store the mapped verb, dedupe, and count revokes" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(exec, sup, "GRANT SELECT ON tpch.main.orders TO ROLE analyst").isRight shouldBe true
    val role = sup.listRoles(tenantId(sup)).find(_.name == "analyst").get
    sup
      .listRolePermissions(role.id)
      .map(p => (p.catalogName, p.schemaName, p.tableName, p.verb)) shouldBe List(
      ("tpch", "main", "orders", "RO")
    )
    // duplicate grant is a no-op success
    run(exec, sup, "GRANT SELECT ON tpch.main.orders TO ROLE analyst").isRight shouldBe true
    sup.listRolePermissions(role.id) should have size 1
    // revoke with non-matching verb removes nothing; REVOKE ALL removes the row
    run(exec, sup, "REVOKE DDL ON tpch.main.orders FROM ROLE analyst").isRight shouldBe true
    sup.listRolePermissions(role.id) should have size 1
    run(exec, sup, "REVOKE ALL ON tpch.main.orders FROM ROLE analyst").isRight shouldBe true
    sup.listRolePermissions(role.id) shouldBe empty
    // grant to unknown role
    run(exec, sup, "GRANT SELECT ON t TO ROLE ghost") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_role")
      case other                                => fail(s"expected NotFound, got $other")

  "membership" should "wire user-role, group-role, and user-group edges" in:
    val (sup, store, exec) = setup()
    val tid                = tenantId(sup)
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    sup.createGroup(tid, "finance").unsafeRunSync().isRight shouldBe true
    val uid = seedUser(store, tid, "alice")
    uid should not be empty
    run(exec, sup, "GRANT ROLE analyst TO USER alice").isRight shouldBe true
    run(exec, sup, "ALTER GROUP finance ADD USER alice").isRight shouldBe true
    run(exec, sup, "GRANT ROLE analyst TO GROUP finance").isRight shouldBe true
    run(exec, sup, "REVOKE ROLE analyst FROM USER alice").isRight shouldBe true
    run(exec, sup, "ALTER GROUP finance DROP USER alice").isRight shouldBe true
    run(exec, sup, "GRANT ROLE analyst TO USER ghost") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_user")
      case other                                => fail(s"expected NotFound, got $other")

  // Seeds a tenant user credential row directly through the store handle (createUser on
  // PoolSupervisor requires a UserStore we don't wire in these tests), findable via
  // sup.findUser(Some(tid), name) since PoolSupervisor.findUser delegates to store.findUser.
  private def seedUser(store: InMemoryControlPlaneStore, tid: String, name: String): String =
    store.upsertUserWithHash(
      tenant = Some(tid),
      username = name,
      passwordHash = "x",
      role = "user"
    )
