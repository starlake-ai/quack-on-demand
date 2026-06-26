package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, PoolKey, Role, RoleDistribution, RunningNode, Tenant, TenantDb, TenantDbKind}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import java.time.Instant
import scala.sys.process._
import scala.util.Try

class PostgresControlPlaneStoreSpec extends AnyFlatSpec with Matchers:

  ai.starlake.quack.ondemand.state.testkit.TestPostgres.dropStrayTestDatabases("qodcp")

  private val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST",     "localhost")
  private val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT",     "5432").toInt
  private val pgUser = sys.env.getOrElse("SL_TEST_PG_USER",     "postgres")
  private val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  Class.forName("org.postgresql.Driver")

  private def adminUrl: String          = s"jdbc:postgresql://$pgHost:$pgPort/postgres"
  private def dbUrl(db: String): String = s"jdbc:postgresql://$pgHost:$pgPort/$db"

  private def pgReachable: Boolean =
    Try {
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try c.isValid(2) finally c.close()
    }.getOrElse(false)

  private def psql(targetDb: String, sql: String): Unit =
    val rc = Process(
      Seq("psql", "-h", pgHost, "-p", pgPort.toString, "-U", pgUser, "-d", targetDb, "-tAc", sql),
      None,
      "PGPASSWORD" -> pgPass
    ).!
    assert(rc == 0, s"psql ($sql) exit=$rc")

  /** Fresh DB + migrated schema, with the store wired against it. */
  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qodcp_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      new LiquibaseRunner(dbUrl(dbName), pgUser, pgPass).run()
      test(new PostgresControlPlaneStore(dbUrl(dbName), pgUser, pgPass))
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private val tenant = Tenant(
    id          = "tenant-1",
    displayName = "acme",
    disabled    = false
  )
  private val tenantDb = TenantDb(
    id        = "tdb-1",
    tenantId  = "tenant-1",
    name      = "acme_default",
    kind      = TenantDbKind.DuckLake,
    metastore = Map("pgHost" -> "h", "schemaName" -> "main"),
    dataPath  = "/data/acme"
  )
  private val pool = Pool(
    id           = "pool-1",
    tenantId     = "tenant-1",
    tenantDbId   = "tdb-1",
    name         = "sales",
    size         = 1,
    distribution = RoleDistribution(0, 0, 1)
  )
  private val node = RunningNode(
    nodeId    = "node-1",
    poolKey   = PoolKey("acme", "acme_default", "sales"),
    role      = Role.Dual,
    host      = "127.0.0.1",
    port      = 21900,
    token     = "tok",
    pid       = Some(12345L),
    podName   = None,
    startedAt = Instant.parse("2026-01-01T00:00:00Z")
  )

  "PostgresControlPlaneStore" should "round-trip a tenant" in withStore { store =>
    store.upsertTenant(tenant)
    store.listTenants() shouldBe List(tenant)
  }

  it should "be idempotent on repeated tenant upsert" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenant(tenant.copy(disabled = true))
    store.listTenants().head.disabled shouldBe true
  }

  it should "round-trip a tenant-db with JSONB params" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.listTenantDbs("tenant-1") shouldBe List(tenantDb)
  }

  it should "round-trip kind, defaultDatabase, defaultSchema for a memory tenant-db" in withStore { store =>
    val td = TenantDb(
      id              = "td-mem",
      tenantId        = "tenant-1",
      name            = "memorydb",
      kind            = TenantDbKind.InMemory,
      metastore       = Map.empty,
      dataPath        = "",
      defaultDatabase = Some("fedpg"),
      defaultSchema   = Some("public")
    )
    store.upsertTenant(Tenant(id = "tenant-1"))
    store.upsertTenantDb(td)
    val read = store.listTenantDbs("tenant-1").find(_.id == "td-mem").get
    read.kind            shouldBe TenantDbKind.InMemory
    read.defaultDatabase shouldBe Some("fedpg")
    read.defaultSchema   shouldBe Some("public")
  }

  it should "round-trip kind=DuckDbFile" in withStore { store =>
    val td = TenantDb(
      id        = "td-file",
      tenantId  = "tenant-1",
      name      = "filedb",
      kind      = TenantDbKind.DuckDbFile,
      metastore = Map("dbName" -> "mydata", "schemaName" -> "main"),
      dataPath  = "/tmp/file.duckdb"
    )
    store.upsertTenant(Tenant(id = "tenant-1"))
    store.upsertTenantDb(td)
    val read = store.listTenantDbs("tenant-1").find(_.id == "td-file").get
    read.kind shouldBe TenantDbKind.DuckDbFile
    read.metastore("dbName") shouldBe "mydata"
  }

  it should "round-trip a pool" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.listPools("tdb-1") shouldBe List(pool)
  }

  it should "preserve idleTimeoutSec as a populated Option" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    val withIdle = pool.copy(idleTimeoutSec = Some(60))
    store.upsertPool(withIdle)
    store.listPools("tdb-1") shouldBe List(withIdle)
  }

  it should "round-trip a node with pid set + lastSeen NULL" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    store.listNodes("pool-1") shouldBe List(node)
  }

  it should "round-trip a node with lastSeen + podName populated" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    val withFields = node.copy(
      pid      = None,
      podName  = Some("quack-pod-xyz"),
      lastSeen = Some(Instant.parse("2026-02-01T12:00:00Z"))
    )
    store.upsertNode(withFields, "pool-1")
    store.listNodes("pool-1") shouldBe List(withFields)
  }

  it should "reject delete of a tenant that still has tenant-dbs (FK RESTRICT)" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    intercept[java.sql.SQLException](store.deleteTenant("tenant-1"))
    store.listTenants() shouldBe List(tenant)
  }

  it should "reject delete of a pool that still has nodes (FK RESTRICT)" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    intercept[java.sql.SQLException](store.deletePool("pool-1"))
    store.listPools("tdb-1") shouldBe List(pool)
  }

  it should "allow ordered teardown: nodes, then pool, then tenant-db, then tenant" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    store.deleteNode("node-1")
    store.deletePool("pool-1")
    store.deleteTenantDb("tdb-1")
    store.deleteTenant("tenant-1")
    store.snapshot() shouldBe ControlPlaneSnapshot()
  }

  it should "load the full graph via snapshot()" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertNode(node, "pool-1")
    val s = store.snapshot()
    s.tenants   shouldBe List(tenant)
    s.tenantDbs shouldBe List(tenantDb)
    s.pools     shouldBe List(pool)
    s.nodes     shouldBe List(node)
    s.users           shouldBe Nil
    s.roles           shouldBe Nil
    s.rolePermissions shouldBe Nil
    s.groups          shouldBe Nil
    s.poolPermissions shouldBe Nil
  }

  // ---------- RBAC: roles + role permissions ----------

  private val role = RbacRole(
    id          = "r-1",
    tenantId    = "tenant-1",
    name        = "admin",
    description = Some("Built-in admin role for tenant")
  )

  "RBAC: roles" should "round-trip a role" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertRole(role)
    val out = store.listRoles("tenant-1")
    out.map(_.copy(createdAt = None)) shouldBe List(role)
    out.head.createdAt should not be empty
  }

  it should "find a role by (tenant, name)" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertRole(role)
    store.findRole("tenant-1", "admin").map(_.id) shouldBe Some("r-1")
    store.findRole("tenant-1", "missing")        shouldBe None
  }

  it should "cascade role permissions on role delete" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertRole(role)
    val perm = RolePermission(
      id = "rp-1", roleId = "r-1",
      catalogName = "*", schemaName = "*", tableName = "*", verb = "ALL"
    )
    val inserted = store.insertRolePermission(perm)
    inserted.grantedAt should not be empty
    store.listRolePermissions("r-1") should have size 1
    store.deleteRole("r-1")
    store.listRolePermissions("r-1") shouldBe empty
  }

  it should "bulk-fetch role permissions across multiple roles" in withStore { store =>
    store.upsertTenant(tenant)
    val r1 = role
    val r2 = role.copy(id = "r-2", name = "viewer")
    store.upsertRole(r1)
    store.upsertRole(r2)
    store.insertRolePermission(RolePermission("rp-1", "r-1", "*",    "*", "*",        "ALL"))
    store.insertRolePermission(RolePermission("rp-2", "r-2", "tpch", "*", "customer", "RO"))
    val out = store.listRolePermissionsForRoles(Set("r-1", "r-2")).map(_.id).toSet
    out shouldBe Set("rp-1", "rp-2")
    store.listRolePermissionsForRoles(Set.empty) shouldBe empty
  }

  // ---------- RBAC: groups + membership ----------

  private val group = RbacGroup(id = "g-1", tenantId = "tenant-1", name = "engineers")

  "RBAC: groups" should "round-trip a group" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertGroup(group)
    store.listGroups("tenant-1") shouldBe List(group)
    store.findGroup("tenant-1", "engineers").map(_.id) shouldBe Some("g-1")
  }

  it should "wire user-group + user-role + group-role memberships idempotently" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertRole(role)
    store.upsertGroup(group)
    val user = RbacUser(id = "u-1", tenant = Some("tenant-1"), username = "alice", role = "user")
    store.upsertUserIdentity(user)

    store.addUserGroup("u-1", "g-1")
    store.addUserGroup("u-1", "g-1")  // idempotent
    store.listGroupsForUser("u-1") shouldBe List("g-1")
    store.listUsersInGroup("g-1")  shouldBe List("u-1")

    store.addUserRole("u-1", "r-1")
    store.listDirectRolesForUser("u-1") shouldBe List("r-1")

    store.addGroupRole("g-1", "r-1")
    store.listRolesForGroup("g-1") shouldBe List("r-1")

    store.removeUserGroup("u-1", "g-1") shouldBe true
    store.removeUserGroup("u-1", "g-1") shouldBe false
    store.listGroupsForUser("u-1") shouldBe empty
  }

  // ---------- RBAC: users ----------

  "RBAC: users" should "round-trip a superuser (tenant = NULL)" in withStore { store =>
    val u = RbacUser(id = "u-root", tenant = None, username = "root", role = "admin")
    store.upsertUserIdentity(u)
    val got = store.getUserById("u-root")
    got.map(_.copy(createdAt = None, updatedAt = None)) shouldBe Some(u)
    store.listSuperusers().map(_.username) shouldBe List("root")
  }

  it should "find a tenant-scoped user by (tenant, username)" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertUserIdentity(RbacUser("u-a", Some("tenant-1"), "alice", "user"))
    store.findUser(Some("tenant-1"), "alice").map(_.id) shouldBe Some("u-a")
    store.findUser(Some("tenant-1"), "bob")             shouldBe None
    store.findUser(None,             "alice")           shouldBe None
  }

  // ---------- RBAC: pool permissions ----------

  "RBAC: pool permissions" should "grant a user access to a specific pool" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    val u = RbacUser("u-a", Some("tenant-1"), "alice", "user")
    store.upsertUserIdentity(u)
    val perm = store.insertPoolPermission(PoolPermission(
      id = "pp-1", tenantId = "tenant-1", poolId = Some("pool-1"), userId = Some("u-a")
    ))
    perm.grantedAt should not be empty
    store.listPoolPermissionsForUser("u-a") should have size 1
    store.listPoolPermissions(tenantId = Some("tenant-1")) should have size 1
  }

  it should "grant a group access to every pool in tenant (pool_id NULL)" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertGroup(group)
    val perm = PoolPermission("pp-2", "tenant-1", poolId = None, groupId = Some("g-1"))
    store.insertPoolPermission(perm)
    store.listPoolPermissionsForGroup("g-1").map(_.poolId) shouldBe List(None)
  }

  it should "reject a pool permission with both user_id and group_id set" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertGroup(group)
    val u = RbacUser("u-a", Some("tenant-1"), "alice", "user")
    store.upsertUserIdentity(u)
    val bad = PoolPermission("pp-bad", "tenant-1", None, Some("u-a"), Some("g-1"))
    intercept[java.sql.SQLException](store.insertPoolPermission(bad))
  }

  it should "cascade pool permissions when the principal user is deleted" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    val u = RbacUser("u-a", Some("tenant-1"), "alice", "user")
    store.upsertUserIdentity(u)
    store.insertPoolPermission(PoolPermission("pp-1", "tenant-1", Some("pool-1"), Some("u-a"), None))
    store.deleteUser("u-a")
    store.listPoolPermissions(tenantId = Some("tenant-1")) shouldBe empty
  }

  // ---------- RBAC: snapshot covers the full graph ----------

  "RBAC snapshot" should "round-trip every entity in one call" in withStore { store =>
    store.upsertTenant(tenant)
    store.upsertTenantDb(tenantDb)
    store.upsertPool(pool)
    store.upsertRole(role)
    store.upsertGroup(group)
    val u = RbacUser("u-a", Some("tenant-1"), "alice", "user")
    store.upsertUserIdentity(u)
    store.insertRolePermission(RolePermission("rp-1", "r-1", "*", "*", "*", "ALL"))
    store.addUserGroup("u-a", "g-1")
    store.addUserRole("u-a", "r-1")
    store.addGroupRole("g-1", "r-1")
    store.insertPoolPermission(PoolPermission("pp-1", "tenant-1", Some("pool-1"), Some("u-a"), None))

    val s = store.snapshot()
    s.users.map(_.username)             shouldBe List("alice")
    s.roles.map(_.name)                 shouldBe List("admin")
    s.groups.map(_.name)                shouldBe List("engineers")
    s.rolePermissions.map(_.verb)       shouldBe List("ALL")
    s.userGroups                        shouldBe List(UserGroupEdge("u-a", "g-1"))
    s.userRoles                         shouldBe List(UserRoleEdge ("u-a", "r-1"))
    s.groupRoles                        shouldBe List(GroupRoleEdge("g-1", "r-1"))
    s.poolPermissions.map(_.id)         shouldBe List("pp-1")
  }
