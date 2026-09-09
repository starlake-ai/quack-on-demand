package ai.starlake.quack.edge.admin

import ai.starlake.quack.edge.{FlightSqlRouter, SessionRegistry}
import ai.starlake.quack.edge.adapter.{
  NodeLoadTracker,
  QuackHttpAdapter,
  QuackHttpClient,
  TestArrow
}
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.observability.metrics.StatementInstruments
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End-to-end wiring check: SQL admin statements driven through a real `FlightSqlRouter`
  * (adminExecutor wired, exactly as `Main` constructs it) must mutate the same `EffectiveSet` the
  * ACL validator and the RLS/CLS rewriters consume at query time - not merely the rows the
  * `AdminStatementExecutor` unit spec reads back from `PoolSupervisor`'s list methods.
  */
class AdminSqlEndToEndSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  // Mirrors FlightSqlRouterSpec.setup (stubbed QuackHttpClient, no real node spawn) plus
  // AdminStatementExecutorSpec's store handle, wired with adminExecutor = Some(...) so
  // execute() dispatches admin SQL to the manager instead of the node stub.
  private def setup(): (PoolSupervisor, InMemoryControlPlaneStore, FlightSqlRouter) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(TestBackends.fake(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val client = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        IO.pure(TestArrow.okResponse())
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)

    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      adapter,
      stmtInstruments = new StatementInstruments(new SimpleMeterRegistry),
      adminExecutor = Some(new AdminStatementExecutor(sup))
    )
    (sup, store, router)

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

  // Mirrors AdminStatementExecutorSpec's seedUser: createUser needs a UserStore we don't wire
  // in these tests, so the credential row is seeded directly through the store handle.
  private def seedUser(store: InMemoryControlPlaneStore, tid: String, name: String): String =
    store.upsertUserWithHash(
      tenant = Some(tid),
      username = name,
      passwordHash = "x",
      role = "user"
    )

  "SQL admin over the router" should "mutate what the enforcement pipeline consumes" in:
    val (sup, store, router) = setup()
    val tid                  = tenantId(sup)
    val daveId               = seedUser(store, tid, "dave")

    // Before: dave's effective set holds no permissions and no policies.
    sup.effectiveSetForUser(daveId).map(_.permissions) shouldBe Some(Nil)

    def admin(sql: String) =
      router.execute("c-adm", "boss", poolKey, sql, adminEff(sup)).unsafeRunSync()

    admin("CREATE ROLE analyst").isRight shouldBe true
    admin("GRANT SELECT ON tpch.main.orders TO ROLE analyst").isRight shouldBe true
    admin(
      "CREATE ROW POLICY ON tpch.main.orders FOR ROLE analyst USING (owner = ${user})"
    ).isRight shouldBe true
    admin(
      "CREATE COLUMN POLICY ON tpch.main.orders COLUMN email FOR ROLE analyst " +
        "MASK USING (SHA256(CAST(email AS VARCHAR)))"
    ).isRight shouldBe true
    admin("GRANT ROLE analyst TO USER dave").isRight shouldBe true

    // After: the effective set - the exact object the ACL validator and RLS/CLS rewriters
    // consume at query time - reflects every SQL-admin mutation.
    val eff = sup.effectiveSetForUser(daveId).get
    eff.permissions.map(p => (p.tableName, p.verb)) shouldBe List(("orders", "RO"))
    eff.rowPolicies.map(_.predicateSql) shouldBe List("owner = ${user}")
    eff.columnPolicies.map(p => (p.columnName, p.action)) shouldBe List(("email", "mask"))

    // Teardown via SQL and verify it is gone (cache invalidation path).
    admin("REVOKE ALL ON tpch.main.orders FROM ROLE analyst").isRight shouldBe true
    admin("DROP ROW POLICY ON tpch.main.orders FOR ROLE analyst").isRight shouldBe true
    admin(
      "DROP COLUMN POLICY ON tpch.main.orders COLUMN email FOR ROLE analyst"
    ).isRight shouldBe true
    val after = sup.effectiveSetForUser(daveId).get
    after.permissions shouldBe Nil
    after.rowPolicies shouldBe Nil
    after.columnPolicies shouldBe Nil
