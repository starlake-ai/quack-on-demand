package ai.starlake.quack.edge.admin

import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.telemetry.{
  AuditActions,
  AuditEvent,
  AuditQuery,
  AuditRecorder,
  AuditRow,
  RollupBucket,
  RollupQuery,
  StatementEvent,
  StatementQuery,
  StatementRow,
  TelemetryStore,
  UsageQuery,
  UsageResult
}
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.vector.VarCharVector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class AdminStatementExecutorSpec extends AnyFlatSpec with Matchers:

  // Minimal TelemetryStore fake that only ever receives appendAudit calls in these tests -
  // mirrors AuditRecorderSpec's RecordingStore. `enabled = true` so AuditRecorder.restAs
  // actually writes instead of no-op'ing.
  private class RecordingAuditStore extends TelemetryStore:
    val enabled = true
    val events  = scala.collection.mutable.ListBuffer.empty[AuditEvent]
    def appendAudit(es: List[AuditEvent]): Unit                                      = events ++= es
    def listAudit(q: AuditQuery): List[AuditRow]                                     = Nil
    def purgeAudit(olderThan: Instant): Int                                          = 0
    def appendStatements(es: List[StatementEvent])                                   = ()
    def searchStatements(q: StatementQuery)                                          = Nil
    def purgeStatements(olderThan: Instant): Int                                     = 0
    def rollupWatermark(): Option[Instant]                                           = None
    def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit = ()
    def advanceRollupWatermark(to: Instant): Unit                                    = ()
    def queryRollups(q: RollupQuery): List[RollupBucket]                             = Nil
    def purgeRollups(granularity: String, olderThan: Instant): Int                   = 0
    def queryUsage(q: UsageQuery): UsageResult = UsageResult(Nil, None)

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

  // Audit-event tests only: wires createUserFn the same way setupWithUserFn does (CREATE USER
  // needs it) plus a RecordingAuditStore-backed AuditRecorder so the family-specific
  // AuditActions events fired from run()'s mutation arms can be asserted on.
  private def setupWithAudit(): (
      PoolSupervisor,
      InMemoryControlPlaneStore,
      AdminStatementExecutor,
      scala.collection.mutable.ListBuffer[AuditEvent]
  ) =
    val (sup, store, _)                                   = setup()
    val auditStore                                        = new RecordingAuditStore
    val audit                                             = new AuditRecorder(auditStore, _ => None)
    val createUserFn: AdminStatementExecutor.CreateUserFn = (tid, username, password, role) =>
      IO {
        store.findUser(Some(tid), username) match
          case Some(_) => Left(SupervisorError.AlreadyExists(s"user already exists: $username"))
          case None    =>
            val id = store.upsertUserWithHash(Some(tid), username, "x", role)
            Right(store.getUserById(id).get)
      }
    (
      sup,
      store,
      new AdminStatementExecutor(sup, createUserFn = createUserFn, audit = audit),
      auditStore.events
    )

  // CREATE/DROP USER tests only: builds an executor wired with a fake createUserFn that
  // writes through the store handle with a fixed hash (real hashing happens in the wired
  // PoolSupervisor.createUser, not exercised here) and implements the failIfExists
  // contract itself, since the fake stands in for supervisor.createUser(..., failIfExists
  // = true). `calls` records each invocation so the (tenantId, username, password, role)
  // arguments the executor passes can be pinned.
  private def setupWithUserFn(): (
      PoolSupervisor,
      InMemoryControlPlaneStore,
      AdminStatementExecutor,
      scala.collection.mutable.Buffer[(String, String, String, String)]
  ) =
    val (sup, store, _) = setup()
    val calls           = scala.collection.mutable.Buffer.empty[(String, String, String, String)]
    val fn: AdminStatementExecutor.CreateUserFn = (tid, username, password, role) =>
      IO {
        calls += ((tid, username, password, role))
        store.findUser(Some(tid), username) match
          case Some(_) => Left(SupervisorError.AlreadyExists(s"user already exists: $username"))
          case None    =>
            val id = store.upsertUserWithHash(Some(tid), username, "x", role)
            Right(store.getUserById(id).get)
      }
    (sup, store, new AdminStatementExecutor(sup, createUserFn = fn), calls)

  // ALTER USER PASSWORD tests only: builds an executor wired with a fake alterPasswordFn
  // that just records invocations (the real rotation path is PoolSupervisor.updateUserPassword,
  // not exercised here). The store is exposed so tests can seed a user via seedUser.
  private def setupWithAlterPasswordFn(): (
      PoolSupervisor,
      InMemoryControlPlaneStore,
      AdminStatementExecutor,
      scala.collection.mutable.Buffer[(String, String, String)]
  ) =
    val (sup, store, _) = setup()
    val calls           = scala.collection.mutable.Buffer.empty[(String, String, String)]
    val fn: AdminStatementExecutor.AlterPasswordFn = (tid, username, newPassword) =>
      IO {
        calls += ((tid, username, newPassword))
        Right(())
      }
    (sup, store, new AdminStatementExecutor(sup, alterPasswordFn = fn), calls)

  // ALTER USER REQUIRE PASSWORD CHANGE / ENABLE / DISABLE tests only: builds an executor wired
  // with fakes that record invocations AND actually flip the flag on the store row (mirroring
  // what the real Main-wired fns do against Postgres), so tests can assert the flip via a
  // store read-back, not just the recorded call. The store is exposed so tests can seed a user
  // via seedUser.
  private def setupWithAccountFlagFns(): (
      PoolSupervisor,
      InMemoryControlPlaneStore,
      AdminStatementExecutor,
      scala.collection.mutable.Buffer[(String, String)],
      scala.collection.mutable.Buffer[(String, String, Boolean)]
  ) =
    val (sup, store, _) = setup()
    val requireCalls    = scala.collection.mutable.Buffer.empty[(String, String)]
    val enabledCalls    = scala.collection.mutable.Buffer.empty[(String, String, Boolean)]
    val requireFn: AdminStatementExecutor.RequirePasswordChangeFn = (tid, username) =>
      IO {
        requireCalls += ((tid, username))
        store.findUser(Some(tid), username) match
          case None    => Left(SupervisorError.NotFound(s"user not found: $username"))
          case Some(u) =>
            store.upsertUserWithHash(
              u.tenant,
              u.username,
              "x",
              u.role,
              enabled = u.enabled,
              mustChangePassword = true,
              email = u.email
            )
            Right(())
      }
    val enabledFn: AdminStatementExecutor.SetUserEnabledFn = (tid, username, enabled) =>
      IO {
        enabledCalls += ((tid, username, enabled))
        store.findUser(Some(tid), username) match
          case None    => Left(SupervisorError.NotFound(s"user not found: $username"))
          case Some(u) =>
            store.upsertUserWithHash(
              u.tenant,
              u.username,
              "x",
              u.role,
              enabled = enabled,
              mustChangePassword = u.mustChangePassword,
              email = u.email
            )
            Right(())
      }
    (
      sup,
      store,
      new AdminStatementExecutor(
        sup,
        requirePasswordChangeFn = requireFn,
        setUserEnabledFn = enabledFn
      ),
      requireCalls,
      enabledCalls
    )

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
    // revoke with non-matching verb removes nothing; REVOKE ALL removes the row. The result
    // shape is (status, detail) - same as every other mutation arm - with the count folded
    // into detail (not a dedicated "revoked" column): the prepared FlightSQL path advertises
    // this exact shape at Prepare time without executing, so a mismatch here would break
    // ADBC/JDBC's strict prepare-time schema check.
    run(exec, sup, "REVOKE DDL ON tpch.main.orders FROM ROLE analyst") match
      case Right(qr) => readAll(qr) shouldBe List(List(Some("ok"), Some("revoked 0")))
      case other     => fail(s"expected rows, got $other")
    sup.listRolePermissions(role.id) should have size 1
    run(exec, sup, "REVOKE ALL ON tpch.main.orders FROM ROLE analyst") match
      case Right(qr) => readAll(qr) shouldBe List(List(Some("ok"), Some("revoked 1")))
      case other     => fail(s"expected rows, got $other")
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

  private def readAll(qr: QueryResult): List[List[Option[String]]] =
    try
      val reader = qr.rows
      val root   = reader.getVectorSchemaRoot
      val out    = List.newBuilder[List[Option[String]]]
      while reader.loadNextBatch() do
        (0 until root.getRowCount).foreach { r =>
          out += (0 until root.getSchema.getFields.size).toList.map { c =>
            val vec = root.getVector(c).asInstanceOf[VarCharVector]
            if vec.isNull(r) then None else Some(new String(vec.get(r), "UTF-8"))
          }
        }
      out.result()
    finally qr.close()

  "row policies" should "create, refuse duplicate, OR REPLACE, and drop" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(
      exec,
      sup,
      "CREATE ROW POLICY ON tpch.main.orders FOR ROLE analyst USING (region = ${tenantId})"
    ).isRight shouldBe true
    val role = sup.listRoles(tenantId(sup)).find(_.name == "analyst").get
    sup.listRowPoliciesByRole(role.id).unsafeRunSync().map(_.predicateSql) shouldBe
      List("region = ${tenantId}")
    run(
      exec,
      sup,
      "CREATE ROW POLICY ON tpch.main.orders FOR ROLE analyst USING (1=1)"
    ) match
      case Left(RouterFailure.AlreadyExists(_)) => succeed
      case other                                => fail(s"expected AlreadyExists, got $other")
    run(
      exec,
      sup,
      "CREATE OR REPLACE ROW POLICY ON tpch.main.orders FOR ROLE analyst USING (owner = ${user})"
    ).isRight shouldBe true
    sup.listRowPoliciesByRole(role.id).unsafeRunSync().map(_.predicateSql) shouldBe
      List("owner = ${user}")
    run(exec, sup, "DROP ROW POLICY ON tpch.main.orders FOR ROLE analyst").isRight shouldBe true
    sup.listRowPoliciesByRole(role.id).unsafeRunSync() shouldBe empty
    run(exec, sup, "DROP ROW POLICY ON tpch.main.orders FOR ROLE analyst") match
      case Left(RouterFailure.NotFound(_)) => succeed
      case other                           => fail(s"expected NotFound, got $other")
    run(
      exec,
      sup,
      "DROP ROW POLICY IF EXISTS ON tpch.main.orders FOR ROLE analyst"
    ).isRight shouldBe true

  it should "surface predicate-validator rejections as BadRequest" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(
      exec,
      sup,
      "CREATE ROW POLICY ON t FOR ROLE analyst USING (EXISTS (SELECT 1 FROM x))"
    ) match
      case Left(RouterFailure.BadRequest(_)) => succeed
      case other                             => fail(s"expected BadRequest, got $other")

  "column policies" should "create mask and deny, OR REPLACE, and drop" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(
      exec,
      sup,
      "CREATE COLUMN POLICY ON tpch.main.customers COLUMN email FOR ROLE analyst " +
        "MASK USING (SHA256(CAST(email AS VARCHAR)))"
    ).isRight shouldBe true
    run(
      exec,
      sup,
      "CREATE OR REPLACE COLUMN POLICY ON tpch.main.customers COLUMN email FOR ROLE analyst DENY"
    ).isRight shouldBe true
    val role = sup.listRoles(tenantId(sup)).find(_.name == "analyst").get
    val pols = sup.listColumnPoliciesByRole(role.id).unsafeRunSync()
    pols.map(p => (p.columnName, p.action, p.transformSql)) shouldBe
      List(("email", "deny", None))
    run(
      exec,
      sup,
      "DROP COLUMN POLICY ON tpch.main.customers COLUMN email FOR ROLE analyst"
    ).isRight shouldBe true
    sup.listColumnPoliciesByRole(role.id).unsafeRunSync() shouldBe empty

  it should "succeed on DROP COLUMN POLICY IF EXISTS for a missing tuple" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(
      exec,
      sup,
      "DROP COLUMN POLICY IF EXISTS ON tpch.main.customers COLUMN email FOR ROLE analyst"
    ).isRight shouldBe true

  "pool grants" should "resolve the pool by name and grant/revoke per principal" in:
    val (sup, store, exec) = setup()
    val tid                = tenantId(sup)
    val uid                = seedUser(store, tid, "alice")
    run(exec, sup, "GRANT CONNECT ON POOL sales TO USER alice").isRight shouldBe true
    sup.listPoolPermissions(Some(tid), Some(uid), None) should have size 1

    def rowsOf(sql: String): List[List[Option[String]]] =
      run(exec, sup, sql) match
        case Right(qr) => readAll(qr)
        case Left(f)   => fail(s"expected rows for '$sql', got $f")

    val granted = rowsOf("SHOW POOL GRANTS")
    granted should have size 1
    granted.head(2) shouldBe Some("alice")
    rowsOf("SHOW POOL GRANTS FOR USER alice") shouldBe granted

    run(exec, sup, "REVOKE CONNECT ON POOL sales FROM USER alice") match
      case Right(qr) => readAll(qr) shouldBe List(List(Some("ok"), Some("revoked 1")))
      case other     => fail(s"expected rows, got $other")
    sup.listPoolPermissions(Some(tid), Some(uid), None) shouldBe empty
    run(exec, sup, "GRANT CONNECT ON POOL ghost TO USER alice") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_pool")
      case other                                => fail(s"expected NotFound, got $other")

  // Test d from the polish request (ambiguous pool name -> BadRequest, qualified form succeeds)
  // is intentionally not added: PoolSupervisor.createPool refuses to create a second pool with
  // the same name under the same tenant even in a different tenant-db ("pool names must be
  // unique per tenant", PoolSupervisor.scala ~line 1524), so the two-pools-same-name fixture the
  // test needs cannot be constructed through the public API. The `_ :: _ :: Nil => ambiguous`
  // branch in resolvePoolId is defensive against a future relaxation of that invariant (or a
  // pre-existing DB row from before it was added) rather than something reachable today.

  "SHOW" should "return listings for roles, grants, and policies" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(exec, sup, "GRANT SELECT ON tpch.main.orders TO ROLE analyst").isRight shouldBe true
    run(
      exec,
      sup,
      "CREATE ROW POLICY ON tpch.main.orders FOR ROLE analyst USING (1=1)"
    ).isRight shouldBe true

    def rowsOf(sql: String): List[List[Option[String]]] =
      run(exec, sup, sql) match
        case Right(qr) => readAll(qr)
        case Left(f)   => fail(s"expected rows for '$sql', got $f")

    rowsOf("SHOW ROLES").map(_(1)) should contain(Some("analyst"))
    rowsOf("SHOW GRANTS FOR ROLE analyst").map(r => (r(1), r(4))) shouldBe
      List((Some("tpch"), Some("RO")))
    rowsOf("SHOW ROW POLICIES").map(_.head) shouldBe List(Some("analyst"))
    rowsOf("SHOW ROW POLICIES FOR ROLE analyst") should have size 1
    rowsOf("SHOW ROW POLICIES ON tpch.main.orders") should have size 1
    rowsOf("SHOW ROW POLICIES ON tpch.main.other") shouldBe empty
    rowsOf("SHOW COLUMN POLICIES") shouldBe empty

  it should "filter SHOW COLUMN POLICIES by ON table and FOR ROLE" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(
      exec,
      sup,
      "CREATE COLUMN POLICY ON tpch.main.customers COLUMN email FOR ROLE analyst DENY"
    ).isRight shouldBe true

    def rowsOf(sql: String): List[List[Option[String]]] =
      run(exec, sup, sql) match
        case Right(qr) => readAll(qr)
        case Left(f)   => fail(s"expected rows for '$sql', got $f")

    rowsOf("SHOW COLUMN POLICIES ON tpch.main.customers") should have size 1
    rowsOf("SHOW COLUMN POLICIES FOR ROLE analyst") should have size 1
    rowsOf("SHOW COLUMN POLICIES ON tpch.main.other") shouldBe empty

  "SHOW GRANTS FOR USER" should "flatten direct and role-via-group grants (6 columns)" in:
    val (sup, store, exec) = setup()
    val tid                = tenantId(sup)
    seedUser(store, tid, "alice")
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    run(exec, sup, "GRANT SELECT ON tpch.main.orders TO ROLE analyst").isRight shouldBe true
    run(exec, sup, "GRANT ROLE analyst TO USER alice").isRight shouldBe true
    run(exec, sup, "CREATE ROLE etl").isRight shouldBe true
    run(exec, sup, "GRANT INSERT ON tpch.main.staging TO ROLE etl").isRight shouldBe true
    sup.createGroup(tid, "finance").unsafeRunSync().isRight shouldBe true
    run(exec, sup, "GRANT ROLE etl TO GROUP finance").isRight shouldBe true
    run(exec, sup, "ALTER GROUP finance ADD USER alice").isRight shouldBe true

    run(exec, sup, "SHOW GRANTS FOR USER alice") match
      case Right(qr) =>
        val rows = readAll(qr)
        rows.foreach(_ should have size 6)
        rows.map(r => (r.head, r(2), r(5))) should contain allOf (
          (Some("analyst"), Some("tpch"), Some("RO")),
          (Some("etl"), Some("tpch"), Some("RW"))
        )
      case other => fail(s"expected rows, got $other")

  it should "be tenant-scoped: another tenant's user resolves as NotFound" in:
    val (sup, store, exec) = setup()
    sup.createTenant(Tenant("globex")).unsafeRunSync()
    val otherTid = sup.getTenant("globex").orElse(sup.getTenantById("globex")).get.id
    seedUser(store, otherTid, "eve")
    run(exec, sup, "SHOW GRANTS FOR USER eve") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_user")
      case other                                => fail(s"expected NotFound, got $other")

  it should "return NotFound for an unknown user" in:
    val (sup, _, exec) = setup()
    run(exec, sup, "SHOW GRANTS FOR USER ghost") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_user")
      case other                                => fail(s"expected NotFound, got $other")

  it should "list an empty grant set for a user with no role memberships" in:
    val (sup, store, exec) = setup()
    val tid                = tenantId(sup)
    seedUser(store, tid, "bob")
    run(exec, sup, "SHOW GRANTS FOR USER bob") match
      case Right(qr) => readAll(qr) shouldBe empty
      case other     => fail(s"expected rows, got $other")

  "CREATE/DROP USER" should "create a tenant user, refuse duplicates, and drop" in:
    val (sup, _, exec, calls) = setupWithUserFn()
    run(exec, sup, "CREATE USER alice PASSWORD 'secret'").isRight shouldBe true
    calls.last shouldBe ((tenantId(sup), "alice", "secret", "user"))
    sup.findUser(Some(tenantId(sup)), "alice") should not be empty
    run(exec, sup, "CREATE USER alice PASSWORD 'secret'") match
      case Left(RouterFailure.AlreadyExists(_)) => succeed
      case other                                => fail(s"expected AlreadyExists, got $other")
    run(exec, sup, "CREATE USER ops PASSWORD 'x' ADMIN").isRight shouldBe true
    calls.last shouldBe ((tenantId(sup), "ops", "x", "admin"))
    run(exec, sup, "DROP USER alice").isRight shouldBe true
    sup.findUser(Some(tenantId(sup)), "alice") shouldBe empty
    run(exec, sup, "DROP USER alice") match
      case Left(RouterFailure.NotFound(_)) => succeed
      case other                           => fail(s"expected NotFound, got $other")
    run(exec, sup, "DROP USER IF EXISTS alice").isRight shouldBe true

  it should "refuse dropping the session user and stay unwired-safe" in:
    val (sup, _, exec, _) = setupWithUserFn()
    // run() always executes as user "boss" - DROP USER boss is a self-drop.
    run(exec, sup, "DROP USER boss") match
      case Left(RouterFailure.BadRequest(msg)) =>
        msg should include("cannot drop the current session user")
      case other => fail(s"expected BadRequest, got $other")

    val (sup2, _, unwiredExec) = setup()
    run(unwiredExec, sup2, "CREATE USER zed PASSWORD 'x'") match
      case Left(RouterFailure.Internal(_)) => succeed
      case other                           => fail(s"expected Internal, got $other")

  "ALTER USER PASSWORD" should "rotate the password of an existing tenant user" in:
    val (sup, store, exec, calls) = setupWithAlterPasswordFn()
    val tid                       = tenantId(sup)
    seedUser(store, tid, "alice")
    run(exec, sup, "ALTER USER alice PASSWORD 'newsecret'").isRight shouldBe true
    calls.last shouldBe ((tid, "alice", "newsecret"))

  it should "return NotFound for an unknown user without ever invoking the fn" in:
    val (sup, _, _) = setup()
    val invoked     = new java.util.concurrent.atomic.AtomicBoolean(false)
    val guardFn: AdminStatementExecutor.AlterPasswordFn = (_, _, _) =>
      IO {
        invoked.set(true)
        Right(())
      }
    val exec2 = new AdminStatementExecutor(sup, alterPasswordFn = guardFn)
    run(exec2, sup, "ALTER USER ghost PASSWORD 'x'") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_user")
      case other                                => fail(s"expected NotFound, got $other")
    invoked.get() shouldBe false

  it should "allow rotating the session's own password (no self guard)" in:
    val (sup, store, exec, calls) = setupWithAlterPasswordFn()
    val tid                       = tenantId(sup)
    // run() always executes as user "boss".
    seedUser(store, tid, "boss")
    run(exec, sup, "ALTER USER boss PASSWORD 'newsecret'").isRight shouldBe true
    calls.last shouldBe ((tid, "boss", "newsecret"))

  it should "return Internal when the executor is unwired" in:
    val (sup, store, unwiredExec) = setup()
    seedUser(store, tenantId(sup), "alice")
    run(unwiredExec, sup, "ALTER USER alice PASSWORD 'x'") match
      case Left(RouterFailure.Internal(_)) => succeed
      case other                           => fail(s"expected Internal, got $other")

  "ALTER USER REQUIRE PASSWORD CHANGE" should "flip mustChangePassword on an existing user" in:
    val (sup, store, exec, requireCalls, _) = setupWithAccountFlagFns()
    val tid                                 = tenantId(sup)
    val uid                                 = seedUser(store, tid, "alice")
    store.getUserById(uid).get.mustChangePassword shouldBe false
    run(exec, sup, "ALTER USER alice REQUIRE PASSWORD CHANGE").isRight shouldBe true
    requireCalls.last shouldBe ((tid, "alice"))
    store.getUserById(uid).get.mustChangePassword shouldBe true

  it should "have no self-guard - the session user may require its own password change" in:
    val (sup, store, exec, requireCalls, _) = setupWithAccountFlagFns()
    val tid                                 = tenantId(sup)
    // run() always executes as user "boss".
    seedUser(store, tid, "boss")
    run(exec, sup, "ALTER USER boss REQUIRE PASSWORD CHANGE").isRight shouldBe true
    requireCalls.last shouldBe ((tid, "boss"))

  it should "return NotFound for an unknown user without invoking the fn" in:
    val (sup, _, _) = setup()
    val invoked     = new java.util.concurrent.atomic.AtomicBoolean(false)
    val guardFn: AdminStatementExecutor.RequirePasswordChangeFn = (_, _) =>
      IO {
        invoked.set(true)
        Right(())
      }
    val exec2 = new AdminStatementExecutor(sup, requirePasswordChangeFn = guardFn)
    run(exec2, sup, "ALTER USER ghost REQUIRE PASSWORD CHANGE") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_user")
      case other                                => fail(s"expected NotFound, got $other")
    invoked.get() shouldBe false

  it should "return Internal when the executor is unwired" in:
    val (sup, store, unwiredExec) = setup()
    seedUser(store, tenantId(sup), "alice")
    run(unwiredExec, sup, "ALTER USER alice REQUIRE PASSWORD CHANGE") match
      case Left(RouterFailure.Internal(_)) => succeed
      case other                           => fail(s"expected Internal, got $other")

  "ALTER USER ENABLE / DISABLE" should "flip the enabled flag on an existing user" in:
    val (sup, store, exec, _, enabledCalls) = setupWithAccountFlagFns()
    val tid                                 = tenantId(sup)
    val uid                                 = seedUser(store, tid, "alice")
    store.getUserById(uid).get.enabled shouldBe true
    run(exec, sup, "ALTER USER alice DISABLE").isRight shouldBe true
    enabledCalls.last shouldBe ((tid, "alice", false))
    store.getUserById(uid).get.enabled shouldBe false
    run(exec, sup, "ALTER USER alice ENABLE").isRight shouldBe true
    enabledCalls.last shouldBe ((tid, "alice", true))
    store.getUserById(uid).get.enabled shouldBe true

  it should "refuse DISABLE of the current session user" in:
    val (sup, store, exec, _, enabledCalls) = setupWithAccountFlagFns()
    val tid                                 = tenantId(sup)
    // run() always executes as user "boss".
    seedUser(store, tid, "boss")
    run(exec, sup, "ALTER USER boss DISABLE") match
      case Left(RouterFailure.BadRequest(msg)) =>
        msg should include("cannot disable the current session user")
      case other => fail(s"expected BadRequest, got $other")
    enabledCalls shouldBe empty

  it should "have no self-guard for ENABLE - the session user may re-enable itself" in:
    val (sup, store, exec, _, enabledCalls) = setupWithAccountFlagFns()
    val tid                                 = tenantId(sup)
    seedUser(store, tid, "boss")
    run(exec, sup, "ALTER USER boss ENABLE").isRight shouldBe true
    enabledCalls.last shouldBe ((tid, "boss", true))

  it should "return NotFound for an unknown user without invoking the fn" in:
    val (sup, _, _) = setup()
    val invoked     = new java.util.concurrent.atomic.AtomicBoolean(false)
    val guardFn: AdminStatementExecutor.SetUserEnabledFn = (_, _, _) =>
      IO {
        invoked.set(true)
        Right(())
      }
    val exec2 = new AdminStatementExecutor(sup, setUserEnabledFn = guardFn)
    run(exec2, sup, "ALTER USER ghost DISABLE") match
      case Left(RouterFailure.NotFound(reason)) => reason should include("unknown_user")
      case other                                => fail(s"expected NotFound, got $other")
    invoked.get() shouldBe false

  it should "return Internal when the executor is unwired" in:
    val (sup, store, unwiredExec) = setup()
    seedUser(store, tenantId(sup), "alice")
    run(unwiredExec, sup, "ALTER USER alice ENABLE") match
      case Left(RouterFailure.Internal(_)) => succeed
      case other                           => fail(s"expected Internal, got $other")

  "SHOW USERS" should "list the tenant's users with the 5-column shape and stay tenant-scoped" in:
    val (sup, store, exec) = setup()
    val tid                = tenantId(sup)
    seedUser(store, tid, "alice")
    seedUser(store, tid, "bob")
    // A user in a different tenant must never show up in the session tenant's listing.
    sup.createTenant(Tenant("globex")).unsafeRunSync()
    val otherTid = sup.getTenant("globex").orElse(sup.getTenantById("globex")).get.id
    seedUser(store, otherTid, "eve")

    run(exec, sup, "SHOW USERS") match
      case Right(qr) =>
        val rows = readAll(qr)
        rows.foreach(_ should have size 5)
        rows.map(_(1)) should contain allOf (Some("alice"), Some("bob"))
        rows.map(_(1)) should not contain Some("eve")
        val aliceRow = rows.find(_(1) == Some("alice")).get
        aliceRow(2) shouldBe Some("user") // role
        aliceRow(3) shouldBe Some("true") // enabled
        aliceRow(4) shouldBe None         // email (seedUser sets none)
      case other => fail(s"expected rows, got $other")

  "audit events" should "fire the family-specific action on a successful GRANT ROLE...TO USER" in:
    val (sup, store, exec, events) = setupWithAudit()
    val tid                        = tenantId(sup)
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    val uid = seedUser(store, tid, "alice")
    events.clear()
    run(exec, sup, "GRANT ROLE analyst TO USER alice").isRight shouldBe true
    events.map(_.action) should contain(AuditActions.MembershipUserRoleAdd)
    val e = events.find(_.action == AuditActions.MembershipUserRoleAdd).get
    e.outcome shouldBe "ok"
    e.actor shouldBe "boss"
    e.tenant shouldBe Some(tid)
    e.target shouldBe Some(uid)
    e.detail shouldBe Map("role" -> "analyst")

  it should "fire user.create with no password in the detail map" in:
    val (sup, _, exec, events) = setupWithAudit()
    run(exec, sup, "CREATE USER alice PASSWORD 'topsecret'").isRight shouldBe true
    val e = events.find(_.action == AuditActions.UserCreate).get
    e.outcome shouldBe "ok"
    e.detail.values should not contain "topsecret"
    e.detail should contain("role" -> "user")

  it should "fire the generic sql.admin.denied action on an authorization denial" in:
    val (sup, _, exec, events) = setupWithAudit()
    exec.execute("carol", poolKey, "CREATE ROLE r1", userEff(sup)).unsafeRunSync() match
      case Left(RouterFailure.AccessDenied(_)) => succeed
      case other                               => fail(s"expected AccessDenied, got $other")
    val e = events.find(_.action == AuditActions.SqlAdminDenied).get
    e.outcome shouldBe "denied"
    e.actor shouldBe "carol"
    e.detail shouldBe Map("cmd" -> "CreateRole")

  it should "not fire any audit event for a SHOW (read-only) statement" in:
    val (sup, _, exec, events) = setupWithAudit()
    run(exec, sup, "CREATE ROLE analyst").isRight shouldBe true
    events.clear()
    run(exec, sup, "SHOW ROLES").isRight shouldBe true
    events shouldBe empty

  it should "fire sql.admin.denied on a malformed statement with no parser text in the detail" in:
    val (sup, _, exec, events) = setupWithAudit()
    // GRANT ... TO ROLE with a password-shaped token to prove no raw token/literal leaks into
    // the audit row via a future parser-message change - the detail is pinned to the constant
    // "unparsed", never the parser's `err` string.
    run(exec, sup, "GRANT FROBNICATE ON t TO ROLE r") match
      case Left(RouterFailure.BadRequest(_)) => succeed
      case other                             => fail(s"expected BadRequest, got $other")
    val e = events.find(_.action == AuditActions.SqlAdminDenied).get
    e.outcome shouldBe "denied"
    e.actor shouldBe "boss"
    e.detail shouldBe Map("cmd" -> "unparsed")

  it should "fire user.update with field=mustChangePassword on REQUIRE PASSWORD CHANGE" in:
    val (sup, store, _) = setup()
    val auditStore      = new RecordingAuditStore
    val audit           = new AuditRecorder(auditStore, _ => None)
    val tid             = tenantId(sup)
    val uid             = seedUser(store, tid, "alice")
    val requireFn: AdminStatementExecutor.RequirePasswordChangeFn = (_, _) => IO.pure(Right(()))
    val exec = new AdminStatementExecutor(sup, requirePasswordChangeFn = requireFn, audit = audit)
    run(exec, sup, "ALTER USER alice REQUIRE PASSWORD CHANGE").isRight shouldBe true
    val e = auditStore.events.find(_.action == AuditActions.UserUpdate).get
    e.outcome shouldBe "ok"
    e.target shouldBe Some(uid)
    e.detail shouldBe Map("field" -> "mustChangePassword")

  it should "fire user.update with enabled=true/false on ENABLE/DISABLE" in:
    val (sup, store, _) = setup()
    val auditStore      = new RecordingAuditStore
    val audit           = new AuditRecorder(auditStore, _ => None)
    val tid             = tenantId(sup)
    val uid             = seedUser(store, tid, "alice")
    val enabledFn: AdminStatementExecutor.SetUserEnabledFn = (_, _, _) => IO.pure(Right(()))
    val exec = new AdminStatementExecutor(sup, setUserEnabledFn = enabledFn, audit = audit)
    run(exec, sup, "ALTER USER alice DISABLE").isRight shouldBe true
    val disabled = auditStore.events.find(_.action == AuditActions.UserUpdate).get
    disabled.outcome shouldBe "ok"
    disabled.target shouldBe Some(uid)
    disabled.detail shouldBe Map("enabled" -> "false")
    auditStore.events.clear()
    run(exec, sup, "ALTER USER alice ENABLE").isRight shouldBe true
    val enabled = auditStore.events.find(_.action == AuditActions.UserUpdate).get
    enabled.detail shouldBe Map("enabled" -> "true")
