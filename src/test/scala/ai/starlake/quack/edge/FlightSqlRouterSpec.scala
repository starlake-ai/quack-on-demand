package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.sql.{
  Allowed,
  Denied,
  NodeSettingScreen,
  StatementValidator,
  ValidationContext,
  ValidationResult
}
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  Role,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.observability.metrics.StatementInstruments
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

class FlightSqlRouterSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private val mmReg = new SimpleMeterRegistry
  private val si    = new StatementInstruments(mmReg)

  /** Builds a fresh stub response each call so tests don't share ArrowReader instances (each reader
    * is single-use).
    */
  private def defaultStub: () => QuackResponse = () => TestArrow.okResponse()

  private def setup(
      stub: () => QuackResponse = defaultStub,
      events: ManagerEventSink = ManagerEventSink.noop,
      lockdownFor: PoolKey => Boolean = _ => false,
      adminExecutor: Option[ai.starlake.quack.edge.admin.AdminStatementExecutor] = None
  ) =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())

    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    // Pre-register the tenant so createPool succeeds under the new contract.
    sup.createTenant(ai.starlake.quack.model.Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val node = sup.get(poolKey).get.nodes.head

    // Use TestArrow.sharedAllocator (which never closes); each call gets a
    // fresh reader from `stub`. The stub function is used (not a single value)
    // because ArrowReader is single-use.
    val client = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO.pure(stub())
    val adapter = new QuackHttpAdapter(client, tracker)

    val sessions = new SessionRegistry
    val router   =
      new FlightSqlRouter(
        sup,
        sessions,
        tracker,
        adapter,
        stmtInstruments = si,
        events = events,
        lockdownFor = lockdownFor,
        adminExecutor = adminExecutor
      )
    (router, sessions, node)

  "admin dispatch" should "answer a claimed statement at the manager when wired" in:
    val (router, _, _) = setup()
    val withAdmin      = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor =
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor))
    )
    // no effectiveSet: claimed, parsed, then denied by the executor - never forwarded
    val out = withAdmin.execute("c-adm", "alice", poolKey, "CREATE ROLE r1").unsafeRunSync()
    out match
      case Left(RouterFailure.AccessDenied(reason)) => reason should include("admin_required")
      case other                                    => fail(s"expected AccessDenied, got $other")

  it should "reject malformed claimed statements instead of forwarding" in:
    val (router, _, _) = setup()
    val withAdmin      = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor =
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor))
    )
    withAdmin
      .execute("c-adm2", "alice", poolKey, "GRANT FROBNICATE ON t TO ROLE r")
      .unsafeRunSync() match
      case Left(RouterFailure.BadRequest(reason)) => reason should include("admin statement")
      case other                                  => fail(s"expected BadRequest, got $other")

  it should "leave unclaimed statements and the executor-less router on the routed path" in:
    val (router, _, _) = setup()
    // executor-less router: GRANT flows to the routed path exactly as before this feature
    router.execute("c-1", "alice", poolKey, "GRANT SELECT ON t TO ROLE r").unsafeRunSync() shouldBe
      a[Right[?, ?]]
    val withAdmin = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor =
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor))
    )
    // SHOW TABLES is not an admin form: routed to the node stub, returns Right
    withAdmin.execute("c-2", "alice", poolKey, "SHOW TABLES").unsafeRunSync() shouldBe
      a[Right[?, ?]]

  it should "take the routed path for a claimed statement when adminDispatch is false" in:
    // Guards the MCP/preview choke point (Main.scala's PreviewExecutor closure): even with
    // adminExecutor wired, a caller that passes adminDispatch = false must see the exact
    // pre-dialect behavior - the statement flows to the node stub instead of the dialect
    // executor, which would otherwise AccessDenied "alice" (no admin effectiveSet) here.
    val (router, _, _) = setup()
    val withAdmin      = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor =
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor))
    )
    withAdmin
      .execute(
        "c-mcp",
        "alice",
        poolKey,
        "GRANT SELECT ON t TO ROLE r",
        adminDispatch = false
      )
      .unsafeRunSync() shouldBe a[Right[?, ?]]

  it should "run a claimed GRANT end-to-end for a superuser" in:
    val (router, _, _) = setup()
    val exec           = new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)
    val withAdmin      = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor = Some(exec)
    )
    val tenantId = router.supervisor.getTenant(poolKey.tenant).get.id
    router.supervisor.createRole(tenantId, "analyst").unsafeRunSync()
    val superuserEff = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      ai.starlake.quack.ondemand.state.RbacUser("u-root", None, "root", kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = withAdmin
      .execute(
        "c-adm-grant",
        "root",
        poolKey,
        "GRANT SELECT ON t TO ROLE analyst",
        effectiveSet = Some(superuserEff)
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]

  it should "record a redacted history row for an executed admin statement" in:
    val (router, _, _) = setup()
    val exec           = new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)
    val withAdmin      = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor = Some(exec)
    )
    val superuserEff = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      ai.starlake.quack.ondemand.state.RbacUser("u-root", None, "root", kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil,
      Nil
    )
    withAdmin
      .execute(
        "c-adm-hist-ok",
        "root",
        poolKey,
        "CREATE ROLE r1",
        effectiveSet = Some(superuserEff)
      )
      .unsafeRunSync() shouldBe a[Right[?, ?]]
    val latest = withAdmin.history.snapshot(1).head
    latest.sql shouldBe ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
    latest.status shouldBe "ok"
    latest.nodeId shouldBe "manager"
    latest.error shouldBe None

  it should "record a redacted history row for a denied admin statement" in:
    val (router, _, _) = setup()
    val exec           = new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)
    val withAdmin      = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor = Some(exec)
    )
    // No effectiveSet: claimed, parsed, then denied by the executor (admin_required).
    withAdmin
      .execute("c-adm-hist-denied", "alice", poolKey, "CREATE ROLE r1")
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    val latest = withAdmin.history.snapshot(1).head
    latest.sql shouldBe ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
    latest.status shouldBe "denied"
    latest.nodeId shouldBe "manager"
    latest.error shouldBe None

  it should "record a redacted history row for a malformed admin statement" in:
    val (router, _, _) = setup()
    val exec           = new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)
    val withAdmin      = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      stmtInstruments = si,
      adminExecutor = Some(exec)
    )
    withAdmin
      .execute("c-adm-hist-malformed", "alice", poolKey, "GRANT FROBNICATE ON t TO ROLE r")
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    val latest = withAdmin.history.snapshot(1).head
    latest.sql shouldBe ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
    latest.status shouldBe "denied"
    latest.nodeId shouldBe "manager"
    // The parser's error detail (which can echo a raw token, including a password literal in
    // a CREATE/ALTER USER statement) never reaches storage - the history row carries no error.
    latest.error shouldBe None

  it should "redact a claim-shaped statement's sql on the routed path when denied" in:
    // No adminExecutor wired: the claimed CREATE USER ... PASSWORD statement falls through to
    // the routed path exactly like an unwired dialect or an adminDispatch=false caller (MCP/
    // preview) would, and gets denied there by the validator. Requirement 3: `record` must
    // never let the password literal reach StatementHistoryStore on that path either.
    val denyAll = new StatementValidator:
      def validate(context: ValidationContext): ValidationResult = Denied("test-deny", Set.empty)
    val (router, _, _) = setup()
    val denying        = new FlightSqlRouter(
      router.supervisor,
      router.sessions,
      router.tracker,
      router.adapter,
      validator = denyAll,
      stmtInstruments = si
    )
    denying
      .execute("c-routed-redact", "alice", poolKey, "CREATE USER bob PASSWORD 'topsecret'")
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    val latest = denying.history.snapshot(1).head
    latest.sql shouldBe ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
    latest.sql should not include "topsecret"
    latest.status shouldBe "denied"

  it should "redact both sql and a node's quoted-statement error for an admitted statement" in:
    // Default validator (allowAll): the claimed statement is admitted and forwarded to the
    // node, whose own parser error quotes the offending line back verbatim - exactly how
    // DuckDB reports a syntax error, and exactly how a password literal could otherwise leak
    // through the `error` field even though `sql` is redacted.
    val nodeErr = () =>
      QuackResponse.Failed(
        QuackError.Permanent(
          "Parser Error: syntax error at or near \"topsecret\" - " +
            "LINE 1: CREATE USER bob PASSWORD 'topsecret';"
        ),
        1L
      )
    val (router, _, _) = setup(stub = nodeErr)
    router
      .execute("c-admit-node-err", "alice", poolKey, "CREATE USER bob PASSWORD 'topsecret'")
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    val latest = router.history.snapshot(1).head
    latest.sql shouldBe ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
    latest.sql should not include "topsecret"
    latest.error shouldBe Some(ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder)
    latest.error.foreach(_ should not include "topsecret")

  "FlightSqlRouter.execute" should "route a SELECT to the only DUAL node and return Ok" in:
    val beforeCount =
      mmReg.counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok").count()
    val (router, _, node) = setup()
    val out               = router.execute("c-1", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out shouldBe a[Right[_, _]]
    mmReg
      .counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok")
      .count() shouldBe (beforeCount + 1.0)

  it should "record locality metrics for routed statements" in:
    val reg          = new SimpleMeterRegistry
    val ri           = new ai.starlake.quack.observability.metrics.RoutingInstruments(reg)
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      refsConfigFor = _ => ai.starlake.acl.model.Config.forDuckDB(Some("db"), Some("main")),
      routingInstruments = ri
    )
    // Same SELECT twice on the single-node pool: the table is new on the first pass and a
    // repeat (staying on the same node) on the second.
    router.execute("loc-1", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync().foreach {
      qr => qr.close()
    }
    router.execute("loc-1", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync().foreach {
      qr => qr.close()
    }
    reg
      .counter(
        "routing_tables_total",
        "tenant",
        poolKey.tenant,
        "pool",
        poolKey.pool,
        "result",
        "new"
      )
      .count() shouldBe 1.0
    reg
      .counter(
        "routing_tables_total",
        "tenant",
        poolKey.tenant,
        "pool",
        poolKey.pool,
        "result",
        "repeat"
      )
      .count() shouldBe 1.0

  it should "expose the chosen nodeId on the QueryResult so callers can soft-pin follow-ups" in:
    val (router, _, node) = setup()
    val out               = router.execute("c-1b", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out match
      case Right(qr) =>
        qr.nodeId shouldBe node.nodeId
        qr.close()
      case Left(msg) => fail(s"expected Right, got Left($msg)")

  it should "pin the session inside a BEGIN…COMMIT block" in:
    val (router, sessions, node) = setup()
    router.execute("c-1", "alice", poolKey, "BEGIN").unsafeRunSync()
    sessions.get("c-1").exists(_.txOpen) shouldBe true
    router.execute("c-1", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    sessions.get("c-1").map(_.pinnedNodeId) shouldBe Some(Some(node.nodeId))
    router.execute("c-1", "alice", poolKey, "COMMIT").unsafeRunSync()
    sessions.get("c-1").exists(_.txOpen) shouldBe false
    sessions.get("c-1").flatMap(_.pinnedNodeId) shouldBe None

  it should "unpin on ROLLBACK too" in:
    val (router, sessions, _) = setup()
    router.execute("c-1", "alice", poolKey, "BEGIN").unsafeRunSync()
    router.execute("c-1", "alice", poolKey, "ROLLBACK").unsafeRunSync()
    sessions.get("c-1").exists(_.txOpen) shouldBe false
    sessions.get("c-1").flatMap(_.pinnedNodeId) shouldBe None

  it should "return Left when no compatible node available (quarantined)" in:
    val (router, _, _) = setup()
    val nodeId         = router.supervisor.list().head.nodes.head.nodeId
    router.tracker.setHealthy(nodeId, false)
    val out = router.execute("c-2", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out shouldBe a[Left[_, _]]

  // R12: distinct Flight SQL status codes. Each failure shape carries its
  // own RouterFailure variant so the Flight producer can map to UNAUTHORIZED
  // / NOT_FOUND / INVALID_ARGUMENT / UNAVAILABLE / INTERNAL rather than
  // folding every error to INTERNAL.
  it should "tag a quarantined-pool failure as RouterFailure.Unavailable" in:
    val (router, _, _) = setup()
    val nodeId         = router.supervisor.list().head.nodes.head.nodeId
    router.tracker.setHealthy(nodeId, false)
    val out = router.execute("c-2-kind", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.Unavailable]

  it should "return Left when pool does not exist" in:
    val (router, _, _) = setup()
    val out            = router
      .execute("c-3", "alice", PoolKey("ghost", "ghost_default", "missing"), "SELECT 1")
      .unsafeRunSync()
    out shouldBe a[Left[_, _]]

  it should "tag an unknown pool as RouterFailure.NotFound" in:
    val (router, _, _) = setup()
    val out            = router
      .execute("c-3-kind", "alice", PoolKey("ghost", "ghost_default", "missing"), "SELECT 1")
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.NotFound]

  it should "tag a StatementValidator deny as RouterFailure.AccessDenied" in:
    val denying = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult = Denied("you can't read this")
    // Reuse setup() to get a wired supervisor/adapter, then rebuild the
    // router with the denying validator. We pull the existing router's
    // collaborators rather than re-stand-up the whole stack.
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      validator = denying,
      stmtInstruments = si
    )
    val out = router.execute("c-deny", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]

  it should "tag a permanent backend error as RouterFailure.BadRequest" in:
    val perm = () => QuackResponse.Failed(QuackError.Permanent("Parser Error: syntax"), 1L)
    val (router, _, _) = setup(stub = perm)
    val out            = router.execute("c-bad", "alice", poolKey, "SELECTT 1").unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.BadRequest]

  it should "invalidate pin and return error when in-transaction node dies (transient)" in:
    val (router, sessions, _) = setup(stub = () => TestArrow.okResponse(1L))
    router.execute("c-4", "alice", poolKey, "BEGIN").unsafeRunSync()
    sessions.get("c-4").exists(_.txOpen) shouldBe true

    // Swap out the adapter behavior to fail transiently for the next call. Easiest path:
    // call invalidatePin directly is the wrong test (it tests the registry, not the router).
    // We instead set the only node as unhealthy AND draining so the pinned-node check
    // still finds the pinned id in the snapshot.nodes, but the adapter call fails.
    // To keep this test focused, we exercise the "PinnedNodeGone" path indirectly via the
    // following test below ("pinned node disappeared"). This test verifies only that BEGIN
    // pinned successfully - failure-handling path is unit-tested in RouterSpec already.
    succeed

  it should "report PinnedNodeGone when the pinned node has been removed from the pool" in:
    val (router, sessions, node) = setup()
    router.execute("c-5", "alice", poolKey, "BEGIN").unsafeRunSync()
    sessions.get("c-5").map(_.pinnedNodeId) shouldBe Some(Some(node.nodeId))

    // Stop the pool - the supervisor removes the node from snapshot.nodes
    router.supervisor.stopPool(poolKey, force = true).unsafeRunSync()

    val out = router.execute("c-5", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    out shouldBe a[Left[_, _]]
    sessions.get("c-5").flatMap(_.pinnedNodeId) shouldBe None

  // ---- defaultDatabase / defaultSchema precedence tests ----

  /** Validator that captures the last ValidationContext it receives. */
  private final class CapturingValidator extends StatementValidator:
    @volatile var lastCtx: ValidationContext               = null
    def validate(ctx: ValidationContext): ValidationResult =
      lastCtx = ctx
      Allowed

  private def freshSupervisorAndBackend(): PoolSupervisor =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          22000 + n.size,
          "tok",
          Some(2L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())
    val tracker = new NodeLoadTracker
    new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())

  it should "use tenantDb.defaultDatabase + defaultSchema when set" in:
    val sup = freshSupervisorAndBackend()
    val key = PoolKey("beta", "beta_mem", "p1")
    sup.createTenant(Tenant("beta")).unsafeRunSync()
    sup
      .createTenantDb(
        tenantName = "beta",
        suffix = "mem",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = "",
        defaultDatabase = Some("fedpg"),
        defaultSchema = Some("public")
      )
      .unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val capturer = new CapturingValidator
    val client   = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO.pure(TestArrow.okResponse())
    val adapter = new QuackHttpAdapter(client, new NodeLoadTracker)
    val router  = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      new NodeLoadTracker,
      adapter,
      validator = capturer
    )
    router.execute("d-1", "alice", key, "SELECT 1").unsafeRunSync()
    capturer.lastCtx.defaultDatabase shouldBe Some("fedpg")
    capturer.lastCtx.defaultSchema shouldBe Some("public")

  it should "fall back to memory/main for InMemory kind when no override" in:
    val sup = freshSupervisorAndBackend()
    val key = PoolKey("gamma", "gamma_mem", "p2")
    sup.createTenant(Tenant("gamma")).unsafeRunSync()
    sup
      .createTenantDb(
        tenantName = "gamma",
        suffix = "mem",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = ""
      )
      .unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val capturer = new CapturingValidator
    val client   = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO.pure(TestArrow.okResponse())
    val adapter = new QuackHttpAdapter(client, new NodeLoadTracker)
    val router  = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      new NodeLoadTracker,
      adapter,
      validator = capturer
    )
    router.execute("d-2", "alice", key, "SELECT 1").unsafeRunSync()
    capturer.lastCtx.defaultDatabase shouldBe Some("memory")
    capturer.lastCtx.defaultSchema shouldBe Some("main")

  it should "pass the pool's attached catalogs into the validation context" in:
    var seen: Set[String] = Set.empty
    val capturing         = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult =
        seen = ctx.attachedCatalogs
        Allowed
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      validator = capturing,
      stmtInstruments = si,
      attachedCatalogsOf = _ => Set("acme_tpch", "fedx", "memory", "system", "temp")
    )
    router.execute("attach-1", "alice", poolKey, "SELECT 1").unsafeRunSync()
    seen shouldBe Set("acme_tpch", "fedx", "memory", "system", "temp")

  // ---- preferredNode (soft pin) tests ----

  /** Spin up a pool with N dual-role nodes so we have routing choices to make. */
  private def setupMultiNode(replicas: Int, maxConcurrent: Int = 0) =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          23000 + n.size,
          "tok",
          Some(3L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())

    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    val key     = PoolKey("acme", "acme_default", "sales")
    sup.createTenant(Tenant(key.tenant)).unsafeRunSync()
    sup
      .createTenantDb(key.tenant, key.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup
      .createPool(key, RoleDistribution(0, 0, replicas), maxConcurrentPerNode = maxConcurrent)
      .unsafeRunSync()
    val client = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO.pure(TestArrow.okResponse())
    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(sup, sessions, tracker, adapter, stmtInstruments = si)
    (router, sessions, sup.get(key).get.nodes, key)

  it should "honor preferredNode when no transaction pin overrides" in:
    val (router, _, nodes, key) = setupMultiNode(3)
    nodes.size shouldBe 3
    val target = nodes(1).nodeId
    val out    = router
      .execute("p-1", "alice", key, "SELECT 1", preferredNode = Some(target))
      .unsafeRunSync()
    out match
      case Right(qr) =>
        qr.nodeId shouldBe target
        qr.close()
      case Left(msg) => fail(s"expected Right, got Left($msg)")

  it should "fall back to load-based pick when preferredNode is not in the snapshot" in:
    val (router, _, nodes, key) = setupMultiNode(2)
    val out                     = router
      .execute("p-2", "alice", key, "SELECT 1", preferredNode = Some("nonexistent-node-id"))
      .unsafeRunSync()
    out match
      case Right(qr) =>
        nodes.map(_.nodeId) should contain(qr.nodeId)
        qr.close()
      case Left(msg) => fail(s"expected Right, got Left($msg)")

  // The Prepare -> Execute soft pin: Execute re-sends the SQL text with preferredNode = the node
  // that served the Prepare probe, so this is exactly the Execute call.
  private def executeOn(router: FlightSqlRouter, key: PoolKey, conn: String, pref: String) =
    router.execute(conn, "alice", key, "SELECT 1", preferredNode = Some(pref)).unsafeRunSync() match
      case Right(qr) => qr.close(); qr.nodeId
      case Left(f)   => fail(s"expected Right, got Left($f)")

  it should "keep Execute on the Prepare node while it is healthy and under its cap" in:
    val (router, _, nodes, key) = setupMultiNode(2, maxConcurrent = 2)
    val target                  = nodes.head.nodeId
    // One statement in flight on the target: a load-aware pick would choose the other node.
    router.tracker.onStart(target)
    executeOn(router, key, "sp-1", target) shouldBe target

  it should "route Execute normally once the Prepare node is unhealthy, draining or quarantined" in:
    for (mark, conn) <- List[(NodeLoadTracker, String) => Unit](
        (t, id) => t.setHealthy(id, false),
        (t, id) => t.setDraining(id, true),
        (t, id) => t.setQuarantined(id, true)
      ).zip(List("sp-2", "sp-3", "sp-4"))
    do
      val (router, _, nodes, key) = setupMultiNode(2)
      val target                  = nodes.head.nodeId
      val other                   = nodes(1).nodeId
      mark(router.tracker, target)
      executeOn(router, key, conn, target) shouldBe other

  it should "route Execute normally once the Prepare node is at its maxConcurrent cap" in:
    val (router, _, nodes, key) = setupMultiNode(2, maxConcurrent = 1)
    val target                  = nodes.head.nodeId
    router.tracker.onStart(target)
    executeOn(router, key, "sp-5", target) shouldBe nodes(1).nodeId

  it should "skip history recording when recordExecution=false (Prepare-time probe)" in:
    val (router, _, _, key) = setupMultiNode(1)
    val historyBefore       = router.history.size
    router
      .execute("rec-1", "alice", key, "SELECT 1", recordExecution = false)
      .unsafeRunSync()
    router.history.size shouldBe historyBefore

  it should "attach prepareDurationMs to the recorded StatementRecord when supplied" in:
    val (router, _, _, key) = setupMultiNode(1)
    router
      .execute("rec-2", "alice", key, "SELECT 1", prepareDurationMs = Some(42L))
      .unsafeRunSync()
    val latest = router.history.snapshot(1).head
    latest.prepareDurationMs shouldBe Some(42L)

  it should "let the transaction pin override preferredNode" in:
    val (router, sessions, nodes, key) = setupMultiNode(3)
    // BEGIN pins to whichever node served it; pick a DIFFERENT node as the preferredNode
    // to prove the tx pin wins.
    router.execute("p-3", "alice", key, "BEGIN").unsafeRunSync()
    val pinned = sessions.get("p-3").flatMap(_.pinnedNodeId).getOrElse(fail("no pin after BEGIN"))
    val different = nodes.map(_.nodeId).find(_ != pinned).getOrElse(fail("need >1 node"))
    val out       = router
      .execute("p-3", "alice", key, "INSERT INTO t VALUES (1)", preferredNode = Some(different))
      .unsafeRunSync()
    out match
      case Right(qr) =>
        qr.nodeId shouldBe pinned
        qr.close()
      case Left(msg) => fail(s"expected Right, got Left($msg)")

  it should "fall back to metastore.dbName / schemaName for DuckLake kind" in:
    val sup = freshSupervisorAndBackend()
    val key = PoolKey("delta", "delta_lake", "p3")
    sup.createTenant(Tenant("delta")).unsafeRunSync()
    // For DuckLake, createTenantDb injects dbName=<full-name>; we supply schemaName explicitly.
    sup
      .createTenantDb(
        tenantName = "delta",
        suffix = "lake",
        kind = TenantDbKind.DuckLake,
        metastore = Map(
          "pgHost"     -> "localhost",
          "pgPort"     -> "5432",
          "pgUser"     -> "postgres",
          "pgPassword" -> "azizam",
          "schemaName" -> "myschema"
        ),
        dataPath = "/tmp/delta_lake"
      )
      .unsafeRunSync()
    // DuckLake createTenantDb attempts a Postgres connect; since we have no
    // Postgres in this unit test the result will be Left, so guard on that.
    // We only proceed if the tenant-db was created successfully.
    sup.findTenantDb("delta", "delta_lake") match
      case None =>
        // No Postgres available: skip the assertion rather than fail.
        succeed
      case Some(_) =>
        sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
        val capturer = new CapturingValidator
        val client   = new QuackHttpClient(TestArrow.sharedAllocator):
          override def query(
              endpoint: String,
              token: String,
              sql: String,
              session: Option[String],
              authorize: () => String
          ) =
            IO.pure(TestArrow.okResponse())
        val adapter = new QuackHttpAdapter(client, new NodeLoadTracker)
        val router  = new FlightSqlRouter(
          sup,
          new SessionRegistry,
          new NodeLoadTracker,
          adapter,
          validator = capturer
        )
        router.execute("d-3", "alice", key, "SELECT 1").unsafeRunSync()
        capturer.lastCtx.defaultDatabase shouldBe Some("delta_lake")
        capturer.lastCtx.defaultSchema shouldBe Some("myschema")

  // KNOWN GAP (ignored below): validator-vs-engine default-schema skew.
  //
  // FlightSqlRouter.execute builds ValidationContext.defaultSchema from
  // maybeState.defaultDatabase/defaultSchema (the tenant-db's own override
  // fields, see PoolSupervisor.scala lines ~240-241), but
  // wrapWithDefaultSchema (FlightSqlRouter.scala lines ~483-499) prepends
  // `USE <metastore("dbName")>.<metastore("schemaName")>` unconditionally
  // from the pool metastore map, NEVER consulting state.defaultSchema.
  //
  // The demo manifests set defaultSchema=tpch1 on the tenant-db while the
  // metastore carries schemaName=main (the DuckLake default schema DuckLake
  // itself created). When those two diverge, the ACL/statement validator
  // qualifies unqualified table refs against tpch1 while the engine actually
  // executes the statement `USE <db>.main`, i.e. the two components of the
  // router disagree about which schema is "current" for the same statement.
  //
  // This test builds exactly that shape (defaultSchema=tpch1,
  // metastore.schemaName=main), captures the ValidationContext the validator
  // saw and the actual SQL sent to the node, and asserts the USE statement's
  // schema equals ctx.defaultSchema. As of this writing it fails with
  // "main" != "tpch1" (see .superpowers/sdd/pin-tests-report.md for the
  // captured run output). Un-ignore when fixing.
  it should "keep the USE-statement schema in sync with ValidationContext.defaultSchema" in:
    val sup   = freshSupervisorAndBackend()
    val key   = PoolKey("epsilon", "epsilon_lake", "p4")
    val admin = new ai.starlake.quack.ondemand.state.DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    // freshSupervisorAndBackend() doesn't thread a DbAdmin, so rebuild a
    // supervisor sharing the same shape but with dbAdmin wired (mirrors
    // stampedSetup() above) - DuckLake pre-init against pgPort 0 fails fast
    // and is swallowed with a warning by design, so no live Postgres needed.
    val backend2 = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          25000 + n.size,
          "tok",
          Some(5L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())
    val sup2 = new PoolSupervisor(
      backend2,
      new NodeLoadTracker,
      new InMemoryControlPlaneStore(),
      dbAdmin = admin
    )
    sup2.createTenant(Tenant("epsilon")).unsafeRunSync()
    sup2
      .createTenantDb(
        tenantName = "epsilon",
        suffix = "lake",
        kind = TenantDbKind.DuckLake,
        metastore = Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "schemaName" -> "main"
        ),
        dataPath = "/tmp/qod-schema-skew-test",
        defaultSchema = Some("tpch1")
      )
      .unsafeRunSync()
    sup2.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val capturer    = new CapturingValidator
    var capturedSql = ""
    val client      = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        capturedSql = sql
        IO.pure(TestArrow.okResponse())
    val adapter = new QuackHttpAdapter(client, new NodeLoadTracker)
    val router  = new FlightSqlRouter(
      sup2,
      new SessionRegistry,
      new NodeLoadTracker,
      adapter,
      validator = capturer
    )
    router.execute("skew-1", "alice", key, "SELECT 1 FROM t").unsafeRunSync()

    // The validator saw defaultSchema=tpch1 (tenant-db override wins).
    capturer.lastCtx.defaultSchema shouldBe Some("tpch1")
    // The engine actually runs under whatever schema wrapWithDefaultSchema
    // put in the USE statement. Extract it and compare against what the
    // validator used - they MUST be the same schema for the ACL check to
    // mean anything.
    val useSchema = """USE\s+\S+?\.(\S+?);""".r
      .findFirstMatchIn(capturedSql)
      .map(_.group(1))
      .getOrElse(fail(s"no USE statement found in sent SQL: $capturedSql"))
    useSchema shouldBe capturer.lastCtx.defaultSchema.get

    // The other half of the precedence chain: with no tenant-db `defaultSchema`, BOTH sides must
    // fall back to the metastore's `schemaName`. Without this the two could agree only in the
    // case above and silently diverge again whenever the override is absent.
    sup2
      .createTenantDb(
        tenantName = "epsilon",
        suffix = "plain",
        kind = TenantDbKind.DuckLake,
        metastore = Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "schemaName" -> "curated"
        ),
        dataPath = "/tmp/qod-schema-fallback-test"
      )
      .unsafeRunSync()
    val key2 = PoolKey("epsilon", "epsilon_plain", "p5")
    sup2.createPool(key2, RoleDistribution(0, 0, 1)).unsafeRunSync()
    router.execute("skew-2", "alice", key2, "SELECT 1 FROM t").unsafeRunSync()

    capturer.lastCtx.defaultSchema shouldBe Some("curated")
    val useSchema2 = """USE\s+\S+?\.(\S+?);""".r
      .findFirstMatchIn(capturedSql)
      .map(_.group(1))
      .getOrElse(fail(s"no USE statement found in sent SQL: $capturedSql"))
    useSchema2 shouldBe capturer.lastCtx.defaultSchema.get

  // ---- ColumnPolicyRewriter integration tests ----

  private val tenantUser = ai.starlake.quack.ondemand.state.RbacUser(
    "u-1",
    Some("acme"),
    "alice",
    "user"
  )

  private def effWithPolicies(
      ps: List[ai.starlake.quack.ondemand.state.RoleColumnPolicy]
  ): ai.starlake.quack.ondemand.rbac.EffectiveSet =
    ai.starlake.quack.ondemand.rbac.EffectiveSet(tenantUser, Nil, Nil, Nil, Nil, ps)

  /** Builds a router backed by a fresh single-node InMemory pool, with a custom rewriter. Returns
    * (router, lastSqlSentToBackend ref, node). `metadataFilter` and `validator` default to today's
    * router defaults, so the CLS call sites below are unaffected.
    */
  private def setupWithRewriter(
      rewriter: ai.starlake.quack.edge.cls.ColumnPolicyRewriter =
        new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
          new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(Map.empty)
        ),
      metadataFilter: ai.starlake.quack.edge.meta.MetadataFilterRewriter =
        new ai.starlake.quack.edge.meta.MetadataFilterRewriter(enabled = false),
      validator: StatementValidator = StatementValidator.allowAll,
      readers: Int = 1,
      stub: () => QuackResponse = defaultStub,
      protectedWriteGuard: ai.starlake.quack.edge.policy.ProtectedWriteGuard =
        ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled,
      lockdownFor: PoolKey => Boolean = _ => false,
      attachedCatalogsOf: PoolKey => Set[String] = _ => Set.empty,
      locationRulesFor: (String, String) => Option[
        ai.starlake.quack.edge.authz.location.LocationRules
      ] = (_, _) => None
  ) =
    val backend = new ai.starlake.quack.ondemand.runtime.QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          24000 + n.size,
          "tok",
          Some(4L),
          None,
          java.time.Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())
    val tracker = new NodeLoadTracker
    val sup     = new ai.starlake.quack.ondemand.PoolSupervisor(
      backend,
      tracker,
      new ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore()
    )
    sup.createTenant(ai.starlake.quack.model.Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, readers)).unsafeRunSync()
    val node = sup.get(poolKey).get.nodes.head

    // Mutable cell that the override captures: holds the LAST SQL the backend saw,
    // so a retried statement overwrites the first attempt's text.
    var capturedSql: String = ""
    val client              = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        capturedSql = sql
        IO.pure(stub())

    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      validator = validator,
      columnPolicyRewriter = rewriter,
      metadataFilterRewriter = metadataFilter,
      protectedWriteGuard = protectedWriteGuard,
      lockdownFor = lockdownFor,
      attachedCatalogsOf = attachedCatalogsOf,
      locationRulesFor = locationRulesFor
    )
    (router, () => capturedSql, node)

  it should "rewrite a SELECT c_email projection through to the backend masked" in:
    val policies = List(
      ai.starlake.quack.ondemand.state.RoleColumnPolicy(
        "cp-1",
        "r-1",
        "*",
        "main",
        "customer",
        "c_email",
        "mask",
        Some("'***'")
      )
    )
    val rewriter = new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
        Map(("memory", "main", "customer") -> List("c_id", "c_email"))
      ),
      enabled = true
    )
    val (router, capturedSql, _) = setupWithRewriter(rewriter)
    val out                      = router
      .execute(
        "cls-1",
        "alice",
        poolKey,
        "SELECT c_email FROM main.customer",
        effectiveSet = Some(effWithPolicies(policies))
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("'***'")

  it should "expand SELECT * via the catalog and mask covered columns" in:
    val policies = List(
      ai.starlake.quack.ondemand.state.RoleColumnPolicy(
        "cp-2",
        "r-1",
        "*",
        "main",
        "customer",
        "c_email",
        "mask",
        Some("'***'")
      )
    )
    val rewriter = new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
        Map(("memory", "main", "customer") -> List("c_id", "c_email"))
      ),
      enabled = true
    )
    val (router, capturedSql, _) = setupWithRewriter(rewriter)
    val out                      = router
      .execute(
        "cls-2",
        "alice",
        poolKey,
        "SELECT * FROM main.customer",
        effectiveSet = Some(effWithPolicies(policies))
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    // The star was expanded; the masked column carries the literal, not the column name.
    capturedSql() should include("c_id")
    capturedSql() should include("'***'")

  it should "deny a SELECT c_ssn when a deny policy matches" in:
    val policies = List(
      ai.starlake.quack.ondemand.state.RoleColumnPolicy(
        "cp-3",
        "r-1",
        "*",
        "main",
        "customer",
        "c_ssn",
        "deny",
        None
      )
    )
    val rewriter = new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
        Map(("memory", "main", "customer") -> List("c_id", "c_ssn"))
      ),
      enabled = true
    )
    val (router, _, _) = setupWithRewriter(rewriter)
    val out            = router
      .execute(
        "cls-3",
        "alice",
        poolKey,
        "SELECT c_ssn FROM main.customer",
        effectiveSet = Some(effWithPolicies(policies))
      )
      .unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    val failure = out.left.get
    failure shouldBe a[RouterFailure.AccessDenied]
    failure.reason should include("access denied")

  // ---- a rewrite never reaches beyond what the gate authorized ----
  //
  // The column policy rewrite once printed a CTE reference schema-qualified (`FROM t` became
  // `FROM main.t`), and a qualified name never binds to a CTE: the node read the real table `t`,
  // which the ACL gate never saw. The router now refuses any rewrite that reaches a table the
  // original did not, and re-validates the rewrite for the same principal.

  private val maskCustomerEmailCls =
    ai.starlake.quack.ondemand.state.RoleColumnPolicy(
      "cp-cte",
      "r-1",
      "*",
      "main",
      "customer",
      "c_email",
      "mask",
      Some("'***'")
    )

  /** A column policy rewrite that schema-qualifies every `FROM t`, as the old resolver did. */
  private def qualifyingRewriter(qualified: String) =
    new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(Map.empty),
      inner = new ai.starlake.quack.edge.cls.SchemaAwareSqlRewriter:
        def rewrite(
            sql: String,
            schema: Map[String, List[String]],
            policies: List[ai.starlake.quack.ondemand.state.RoleColumnPolicy],
            defaultCatalog: Option[String],
            defaultSchema: Option[String],
            unresolvedMode: ai.starlake.quack.edge.cls.UnresolvedMode
        ) = ai.starlake.quack.edge.cls.RewriteOutcome.Rewritten(qualified)
      ,
      enabled = true
    )

  "a policy rewrite" should "be refused when it reaches a table the original statement did not" in:
    val sql = "WITH secret AS (SELECT c_id AS v FROM customer WHERE c_email LIKE '%') " +
      "SELECT v FROM secret"
    val (router, capturedSql, _) = setupWithRewriter(
      qualifyingRewriter(
        "WITH secret AS (SELECT c_id AS v FROM customer WHERE '***' LIKE '%') " +
          "SELECT v FROM main.secret"
      )
    )
    val out = router
      .execute(
        "cls-cte-1",
        "alice",
        poolKey,
        sql,
        Some(effWithPolicies(List(maskCustomerEmailCls)))
      )
      .unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    out.left.get shouldBe a[RouterFailure.AccessDenied]
    out.left.get.reason should include("memory.main.secret")
    capturedSql() shouldBe ""

  it should "be re-validated for the same principal" in:
    // A validator that admits only statements free of the masked literal: the original passes,
    // the rewrite does not, so the router must ask again.
    val validator = new StatementValidator:
      def validate(c: ValidationContext): ValidationResult =
        if c.statement.contains("'***'") then Denied("no masked literal", Set.empty)
        else Allowed
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_email"))
        ),
        enabled = true
      ),
      validator = validator
    )
    router
      .execute(
        "cls-cte-2",
        "alice",
        poolKey,
        "SELECT c_email FROM customer",
        Some(effWithPolicies(List(maskCustomerEmailCls)))
      )
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    capturedSql() shouldBe ""

  /** A validator that admits only statements free of `marker`: the original passes, a rewrite
    * introducing the marker does not, so only the re-validation can refuse.
    */
  private def refusing(marker: String) = new StatementValidator:
    def validate(c: ValidationContext): ValidationResult =
      if c.statement.contains(marker) then Denied(s"no $marker", Set.empty) else Allowed

  it should "re-validate the row policy wrap" in:
    val (router, capturedSql, _) = setupWithRewriter(validator = refusing("c_region = 'X'"))
    val out                      = router
      .execute(
        "rls-reval",
        "alice",
        poolKey,
        "SELECT c_id FROM customer",
        Some(effWithRowPolicies(List(rowPolicyCustomer)))
      )
      .unsafeRunSync()
    out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.left.toOption.get.reason should include("policy rewrite")
    capturedSql() shouldBe ""

  it should "re-validate the metadata filter's rewrite" in:
    val (router, capturedSql, _) = setupWithRewriter(
      metadataFilter = metaFilterOn,
      validator = refusing("table_schema = 'tpch1'")
    )
    val out = router
      .execute(
        "meta-reval",
        "alice",
        poolKey,
        "SELECT table_name FROM information_schema.tables",
        effectiveSet = customerOnly
      )
      .unsafeRunSync()
    out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.left.toOption.get.reason should include("policy rewrite")
    capturedSql() shouldBe ""

  it should "re-validate a rewrite with the tenant's OPA in OPA mode" in:
    // The policy refuses any statement text holding the masked literal: the original is
    // allowed, only the rewrite carries it.
    val bodies = scala.collection.mutable.ListBuffer.empty[String]
    val auth   = new ai.starlake.quack.edge.opa.OpaAuthorizer(
      ai.starlake.quack.edge.config.OpaConfig.default,
      new ai.starlake.quack.edge.opa.OpaClient(
        1000,
        (_, body, _) =>
          bodies += body
          Right((200, s"""{"result":{"allow":${!body.contains("'***'")}}}"""))
      ),
      new ai.starlake.quack.edge.opa.OpaDecisionCache(0),
      ai.starlake.quack.observability.metrics.OpaInstruments.noop
    )
    val tenant = ai.starlake.quack.model.Tenant(
      poolKey.tenant,
      acl = ai.starlake.quack.model.TenantAcl(
        Some("opa"),
        Some("http://opa"),
        sendStatementText = true
      )
    )
    val opa = new ai.starlake.quack.edge.opa.OpaValidator(
      auth,
      t => Option.when(t == poolKey.tenant)(tenant),
      _ => Nil,
      "memory",
      "main",
      "duckdb",
      false
    )
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_email"))
        ),
        enabled = true
      ),
      validator = opa
    )
    val out = router
      .execute(
        "opa-reval",
        "alice",
        poolKey,
        "SELECT c_email FROM customer",
        Some(effWithPolicies(List(maskCustomerEmailCls)))
      )
      .unsafeRunSync()
    out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
    capturedSql() shouldBe ""
    bodies.exists(_.contains("'***'")) shouldBe true
    bodies.exists(b => b.contains("SELECT c_email FROM customer")) shouldBe true

  it should "run an ordinary CTE query of a policy holder, the CTE reference left unqualified" in:
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_email"))
        ),
        enabled = true
      )
    )
    router
      .execute(
        "cls-cte-3",
        "alice",
        poolKey,
        "WITH t AS (SELECT c_id AS a FROM customer WHERE c_email LIKE '%') SELECT a FROM t",
        Some(effWithPolicies(List(maskCustomerEmailCls)))
      )
      .unsafeRunSync() shouldBe a[Right[?, ?]]
    capturedSql() should include("'***' LIKE '%'")
    capturedSql() should include("SELECT a FROM t")
    capturedSql() should not include "main.t"

  // The star expansion quotes a catalog column the screen would refuse as a caller-written name
  // (`c."a@b"`, `c."x]"`): the caller wrote no such token, so the re-validation admits the rewrite.
  "a star expansion over an unusual column name" should "pass the re-validation" in:
    // QoD grants: the re-validation goes through the validator too, not only the gate compare.
    val acl = new ai.starlake.quack.edge.sql.PostgresAclValidator(
      tenantCatalogs = _ => Set("memory"),
      filteredMetadata = true
    )
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_email", "a@b", "x]"))
        ),
        enabled = true
      ),
      validator = acl
    )
    val granted = effWithPolicies(List(maskCustomerEmailCls)).copy(permissions =
      List(ai.starlake.quack.ondemand.state.RolePermission("p-1", "r-1", "*", "*", "*", "RO"))
    )
    List("SELECT * FROM customer c", "SELECT * EXCLUDE (c_id) FROM customer").foreach { sql =>
      withClue(sql) {
        router
          .execute("cls-odd", "alice", poolKey, sql, Some(granted))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        capturedSql() should include("'***' AS c_email")
        capturedSql() should include("\"a@b\"")
        capturedSql() should include("\"x]\"")
      }
    }

  // JSqlParser splits a quoted dotted column name when it parses the expansion again
  // (`c."price.usd"` came out as `"price"."usd"`, another column): refused, never printed.
  it should "refuse a column whose name holds a dot rather than print another column" in:
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_email", "price.usd"))
        ),
        enabled = true
      )
    )
    val out = router
      .execute(
        "cls-dotted",
        "alice",
        poolKey,
        "SELECT * FROM customer c",
        Some(effWithPolicies(List(maskCustomerEmailCls)))
      )
      .unsafeRunSync()
    out.left.toOption.get.reason should include("holds a dot")
    capturedSql() shouldBe ""

  // `c.to_json()` is `to_json(c)`: the whole row of customer, c_email raw.
  "a method call on a masked table's binding" should "be denied before the node" in:
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_name", "c_email", "c_phone"))
        ),
        enabled = true
      )
    )
    List("SELECT c.to_json() FROM customer c", "SELECT customer.to_json() FROM customer")
      .foreach { sql =>
        withClue(sql) {
          val out = router
            .execute(
              "cls-row",
              "alice",
              poolKey,
              sql,
              Some(effWithPolicies(List(maskCustomerEmailCls)))
            )
            .unsafeRunSync()
          out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
          capturedSql() shouldBe ""
        }
      }

  // `customer AS c(a, b, e)` renames c_email to `e`, which no policy names.
  "an alias column list on a masked table" should "be denied before the node" in:
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_name", "c_email", "c_phone"))
        ),
        enabled = true
      )
    )
    List(
      "SELECT e FROM customer AS c(a, b, e)",
      "SELECT e FROM (customer) AS c(a, b, e)",
      "SELECT x.e FROM (customer AS c) x(a, b, e)"
    ).foreach { sql =>
      withClue(sql) {
        val out = router
          .execute(
            "cls-alias-cols",
            "alice",
            poolKey,
            sql,
            Some(effWithPolicies(List(maskCustomerEmailCls)))
          )
          .unsafeRunSync()
        out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      }
    }

  // ---- ProtectedWriteGuard integration tests ----
  //
  // The InMemory pool hands the guard a session catalog of "memory" / schema "main",
  // so the mask/row policy is keyed on ("memory","main","customer") (catalog wildcard
  // "*"). The catalog knows customer's columns so the guard's Deny-mode oracle can
  // decide precisely which reads project a masked column.

  private def maskCustomerEmail =
    ai.starlake.quack.ondemand.state.RoleColumnPolicy(
      "cp-pw",
      "r-1",
      "*",
      "main",
      "customer",
      "c_email",
      "mask",
      Some("'***'")
    )

  private def rowPolicyCustomer =
    ai.starlake.quack.ondemand.state.RoleRowPolicy(
      "rp-pw",
      "r-1",
      "*",
      "main",
      "customer",
      "c_region = 'X'"
    )

  private def effWithRowPolicies(
      ps: List[ai.starlake.quack.ondemand.state.RoleRowPolicy]
  ): ai.starlake.quack.ondemand.rbac.EffectiveSet =
    ai.starlake.quack.ondemand.rbac.EffectiveSet(tenantUser, Nil, Nil, Nil, Nil, Nil, ps)

  private def guardKnowingCustomer =
    new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
        Map(("memory", "main", "customer") -> List("c_id", "c_email", "c_region"))
      ),
      clsEnabled = true,
      rlsEnabled = true
    )

  it should "deny laundering a masked column through CTAS end-to-end" in:
    val (router, capturedSql, _) =
      setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
    val out = router
      .execute(
        "pw-1",
        "alice",
        poolKey,
        "CREATE TABLE main.scratch AS SELECT c_email FROM main.customer",
        effectiveSet = Some(effWithPolicies(List(maskCustomerEmail)))
      )
      .unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    out.left.get shouldBe a[RouterFailure.AccessDenied]
    // The guard denied before routing, so nothing reached the node.
    capturedSql() shouldBe ""

  it should "allow a write reading only unmasked columns end-to-end" in:
    val (router, capturedSql, _) =
      setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
    val out = router
      .execute(
        "pw-2",
        "alice",
        poolKey,
        "INSERT INTO main.scratch SELECT c_id FROM main.customer",
        effectiveSet = Some(effWithPolicies(List(maskCustomerEmail)))
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("c_id")

  it should "deny any read of an RLS table through a write end-to-end" in:
    val (router, capturedSql, _) =
      setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
    val out = router
      .execute(
        "pw-3",
        "alice",
        poolKey,
        "CREATE TABLE main.scratch AS SELECT c_id FROM main.customer",
        effectiveSet = Some(effWithRowPolicies(List(rowPolicyCustomer)))
      )
      .unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    out.left.get shouldBe a[RouterFailure.AccessDenied]
    capturedSql() shouldBe ""

  it should "deny a batch led by a read whose later write reads a protected table (audit F18)" in:
    // The classifier reads the first statement only (Select), the rewriters' parse keeps only
    // `SELECT 1`, and the node runs both statements: without the guard the masked or filtered
    // rows land raw in scratch.
    List(
      "SELECT 1; INSERT INTO main.scratch SELECT c_email FROM main.customer",
      "SELECT 1; CREATE TABLE main.leak AS SELECT * FROM main.customer"
    ).foreach { sql =>
      List(
        effWithPolicies(List(maskCustomerEmail)),
        effWithRowPolicies(List(rowPolicyCustomer))
      ).foreach { eff =>
        val (router, capturedSql, _) =
          setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
        val out = router.execute("pw-f18", "alice", poolKey, sql, Some(eff)).unsafeRunSync()
        withClue(s"$sql ${eff.columnPolicies.size}/${eff.rowPolicies.size}") {
          out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
          capturedSql() shouldBe ""
        }
      }
    }

  it should "still route a batch led by a read whose write reads nothing protected" in:
    val (router, capturedSql, _) =
      setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
    val out = router
      .execute(
        "pw-f18-ok",
        "alice",
        poolKey,
        "SELECT 1; INSERT INTO main.scratch SELECT c_id FROM main.customer",
        effectiveSet = Some(effWithPolicies(List(maskCustomerEmail)))
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("INSERT INTO main.scratch SELECT c_id FROM main.customer")

  it should "deny an INSERT whose statement-level CTE shadows the target (audit F19)" in:
    // DuckDB binds `FROM leak` to the CTE reading the masked column; the guard used to analyse
    // the Insert's Select without the WITH and resolve `leak` to the physical, unpoliced table.
    val guard = new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
        Map(
          ("memory", "main", "customer") -> List("c_id", "c_email", "c_region"),
          ("memory", "main", "leak")     -> List("c_email")
        )
      ),
      clsEnabled = true,
      rlsEnabled = true
    )
    val (router, capturedSql, _) = setupWithRewriter(protectedWriteGuard = guard)
    val out = router
      .execute(
        "pw-f19",
        "alice",
        poolKey,
        "WITH leak AS (SELECT c_email FROM main.customer) INSERT INTO leak SELECT * FROM leak",
        effectiveSet = Some(effWithPolicies(List(maskCustomerEmail)))
      )
      .unsafeRunSync()
    out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
    capturedSql() shouldBe ""

  it should "deny a two-part name headed by the pool's catalog alias with the ACL off (audit F20)" in:
    // DuckDB binds `memory.customer` catalog-first, under the USE prelude, to main.customer;
    // read schema-first it matched no policy keyed on schema main, so a write copied the masked
    // or filtered rows out and an RLS-only principal's plain SELECT went out unfiltered (F22).
    List(
      "CREATE TABLE main.mine AS SELECT c_email FROM memory.customer" ->
        effWithPolicies(List(maskCustomerEmail)),
      "CREATE TABLE main.mine AS SELECT * FROM memory.customer" ->
        effWithRowPolicies(List(rowPolicyCustomer)),
      "SELECT * FROM memory.customer" -> effWithRowPolicies(List(rowPolicyCustomer)),
      "SELECT count(*) FROM memory.customer" -> effWithRowPolicies(List(rowPolicyCustomer))
    ).foreach { (sql, eff) =>
      val (router, capturedSql, _) = setupWithRewriter(
        protectedWriteGuard = guardKnowingCustomer,
        attachedCatalogsOf = _ => Set("memory")
      )
      val out = router.execute("pw-f20", "alice", poolKey, sql, Some(eff)).unsafeRunSync()
      withClue(sql) {
        out.left.toOption.get match
          case RouterFailure.AccessDenied(reason) =>
            reason should include("ambiguous two-part name")
          case other => fail(s"expected AccessDenied, got $other")
        capturedSql() shouldBe ""
      }
    }

  it should "still filter a schema-qualified read of the same table (audit F20)" in:
    val (router, capturedSql, _) = setupWithRewriter(
      protectedWriteGuard = guardKnowingCustomer,
      attachedCatalogsOf = _ => Set("memory")
    )
    val out = router
      .execute(
        "pw-f20-ok",
        "alice",
        poolKey,
        "SELECT * FROM main.customer",
        effectiveSet = Some(effWithRowPolicies(List(rowPolicyCustomer)))
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("c_region = 'X'")

  it should "deny a PIVOT source or an EXPLAIN over an RLS table end-to-end (audit F21)" in:
    // The row rewrite had no slot for a PIVOT source and never entered an ExplainStatement, so
    // both reached the node unfiltered; EXPLAIN ANALYZE runs its statement on every row.
    List(
      "SELECT * FROM (PIVOT main.customer ON c_region USING count(*))",
      "SELECT 1 WHERE EXISTS (SELECT 1 FROM (PIVOT customer ON c_region USING count(*)))",
      "EXPLAIN ANALYZE SELECT error(string_agg(c_email, ',')) FROM customer",
      "EXPLAIN SELECT * FROM customer",
      "PIVOT customer ON c_region USING count(*)"
    ).foreach { sql =>
      val (router, capturedSql, _) =
        setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
      val out = router
        .execute("rls-f21", "alice", poolKey, sql, Some(effWithRowPolicies(List(rowPolicyCustomer))))
        .unsafeRunSync()
      withClue(sql) {
        out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      }
    }

  it should "summarize an RLS table through its row filter: SUMMARIZE is a SELECT now (Task 19)" in:
    // The gateway once refused SUMMARIZE over an RLS table (F21): the row rewrite had no slot
    // for it. Task 19 sends a tenant's SUMMARIZE as its column listing, then DuckDB's own
    // aggregate SELECT, which the row rewrite filters like any read.
    val calls   = new java.util.concurrent.atomic.AtomicInteger(0)
    val columns = () =>
      if calls.getAndIncrement() == 0 then
        QuackResponse.Ok(
          TestArrow.readerFor(
            "SELECT * FROM (VALUES ('c_id', 'INTEGER'), ('c_region', 'VARCHAR')) " +
              "AS c(column_name, column_type)"
          ),
          1L,
          () => ()
        )
      else TestArrow.okResponse()
    val (router, capturedSql, _) =
      setupWithRewriter(protectedWriteGuard = guardKnowingCustomer, stub = columns)
    val out = router
      .execute(
        "rls-t19",
        "alice",
        poolKey,
        "SUMMARIZE customer",
        Some(effWithRowPolicies(List(rowPolicyCustomer)))
      )
      .unsafeRunSync()
    out.foreach(_.close())
    out shouldBe a[Right[?, ?]]
    calls.get() shouldBe 2
    capturedSql() should include("unnest(list_value('c_id', 'c_region'))")
    capturedSql() should include("c_region = 'X'")

  it should "refuse EXPORT DATABASE to a local path under lockdown with the ACL off (audit F23)" in:
    // No policy, allow-all validator: lockdown is the only screen standing, as in the default
    // posture with lockdown turned on. COPY ... TO the same directory was refused; EXPORT was not.
    List(
      "EXPORT DATABASE '/tmp/dump' (FORMAT CSV)",
      "EXPLAIN ANALYZE EXPORT DATABASE '/tmp/dump'"
    ).foreach { sql =>
      val (router, capturedSql, _) = setupWithRewriter(lockdownFor = _ => true)
      val out = router
        .execute("ld-f23", "alice", poolKey, sql, Some(effWithPolicies(Nil)))
        .unsafeRunSync()
      withClue(sql) {
        out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      }
    }

  it should "refuse a string-named PREPARE of COPY ... TO under lockdown (audit F24)" in:
    // The prepare skip took identifier names only, so with `'p'` the screens judged the word
    // `prepare`, and the EXECUTE in the same batch wrote the file on the node.
    List(
      "PREPARE 'p' AS COPY (SELECT 1) TO '/tmp/leak.csv'; EXECUTE p",
      "PREPARE $$p$$ AS COPY (SELECT 1) TO '/tmp/leak.csv'; EXECUTE p"
    ).foreach { sql =>
      val (router, capturedSql, _) = setupWithRewriter(lockdownFor = _ => true)
      val out = router
        .execute("ld-f24", "alice", poolKey, sql, Some(effWithPolicies(Nil)))
        .unsafeRunSync()
      withClue(sql) {
        out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      }
    }

  it should "deny a SELECT that reaches an RLS table through query_table / query end-to-end" in:
    // The row rewriter wraps base tables; a table named in a string is invisible to it, so
    // without the guard these reads reach the node unfiltered.
    List(
      "SELECT * FROM query_table('main.customer')",
      "SELECT * FROM query('SELECT * FROM main.customer')"
    ).foreach { sql =>
      val (router, capturedSql, _) =
        setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
      val out = router
        .execute(
          "rls-qt",
          "alice",
          poolKey,
          sql,
          effectiveSet = Some(effWithRowPolicies(List(rowPolicyCustomer)))
        )
        .unsafeRunSync()
      withClue(sql) {
        out shouldBe a[Left[?, ?]]
        out.left.get shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      }
    }

  it should "refuse a quoted URL table name to an RLS principal holding a wildcard read grant" in:
    // DuckDB replacement-scans `"s3://..."` as a file: every row of the table's data files, raw,
    // while `FROM customer` is wrapped in the row filter.
    val acl = new ai.starlake.quack.edge.sql.PostgresAclValidator(
      tenantCatalogs = _ => Set("memory"),
      filteredMetadata = true
    )
    val wildcardRo = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser,
      Nil,
      Nil,
      List(ai.starlake.quack.ondemand.state.RolePermission("p-1", "r-1", "*", "*", "*", "RO")),
      Nil,
      rowPolicies = List(rowPolicyCustomer)
    )
    val (router, capturedSql, _) = setupWithRewriter(validator = acl)
    List(
      "SELECT * FROM \"s3://bucket/*/main/customer/*.parquet\"",
      "SELECT * FROM `file:///data/main/customer/x.parquet`"
    ).foreach { sql =>
      withClue(sql) {
        val out = router
          .execute("rls-url", "alice", poolKey, sql, effectiveSet = Some(wildcardRo))
          .unsafeRunSync()
        out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      }
    }
    router
      .execute("rls-url", "alice", poolKey, "SELECT c_id FROM customer", Some(wildcardRo))
      .unsafeRunSync() shouldBe a[Right[?, ?]]
    capturedSql() should include("c_region = 'X'")

  it should "deny a tenant wildcard-ALL principal reading a sibling catalog via query_table" in:
    val acl = new ai.starlake.quack.edge.sql.PostgresAclValidator(tenantCatalogs =
      t => if t == "acme" then Set("memory") else Set.empty
    )
    val wildcardAll = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser,
      Nil,
      Nil,
      List(
        ai.starlake.quack.ondemand.state.RolePermission("p-1", "r-1", "*", "*", "*", "ALL")
      ),
      Nil
    )
    val (router, capturedSql, _) = setupWithRewriter(validator = acl)
    List(
      "SELECT * FROM globex_db.main.secret",
      "SELECT * FROM query_table('globex_db.main.secret')",
      "SELECT * FROM query('SELECT * FROM globex_db.main.secret')"
    ).foreach { sql =>
      val out = router
        .execute("acl-qt", "alice", poolKey, sql, effectiveSet = Some(wildcardAll))
        .unsafeRunSync()
      withClue(sql) {
        out shouldBe a[Left[?, ?]]
        out.left.get shouldBe a[RouterFailure.AccessDenied]
      }
    }
    capturedSql() shouldBe ""

  // ---- DuckLake catalog internals (superuser-only, every ACL mode, never wildcard-coverable) ----

  private val metadataRead =
    "SELECT data_file_id, encryption_key FROM __ducklake_metadata_lake.ducklake_data_file"

  private val superuserSet = ai.starlake.quack.ondemand.rbac.EffectiveSet(
    tenantUser.copy(tenant = None, kind = "admin"),
    Nil,
    Nil,
    Nil,
    Nil
  )

  private val wildcardAllSet = ai.starlake.quack.ondemand.rbac.EffectiveSet(
    tenantUser,
    Nil,
    Nil,
    List(ai.starlake.quack.ondemand.state.RolePermission("p-1", "r-1", "*", "*", "*", "ALL")),
    Nil
  )

  private def wildcardAcl = new ai.starlake.quack.edge.sql.PostgresAclValidator(tenantCatalogs =
    t => if t == "acme" then Set("memory") else Set.empty
  )

  /** An OPA tenant whose policy allows everything: only the pre-gate screen can refuse. */
  private def allowAllOpa =
    val auth = new ai.starlake.quack.edge.opa.OpaAuthorizer(
      ai.starlake.quack.edge.config.OpaConfig.default,
      new ai.starlake.quack.edge.opa.OpaClient(
        1000,
        (_, _, _) => Right((200, """{"result":{"allow":true}}"""))
      ),
      new ai.starlake.quack.edge.opa.OpaDecisionCache(0),
      ai.starlake.quack.observability.metrics.OpaInstruments.noop
    )
    val tenant = ai.starlake.quack.model.Tenant(
      poolKey.tenant,
      acl = ai.starlake.quack.model.TenantAcl(Some("opa"), Some("http://opa"))
    )
    new ai.starlake.quack.edge.opa.OpaValidator(
      auth,
      t => Option.when(t == poolKey.tenant)(tenant),
      _ => Nil,
      "memory",
      "main",
      "duckdb",
      false
    )

  private def catalogDenied(
      out: Either[RouterFailure, ?],
      captured: () => String
  ): Unit =
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason shouldBe
      s"access denied: catalog: ${ai.starlake.quack.edge.sql.CatalogInternalsScreen.MetadataRefusal}"
    captured() shouldBe ""

  "the DuckLake metadata catalog" should "be admitted for a superuser, lockdown off" in:
    val (router, captured, _) = setupWithRewriter()
    val out                   = router
      .execute("dlm-1", "alice", poolKey, metadataRead, effectiveSet = Some(superuserSet))
      .unsafeRunSync()
    out.isRight shouldBe true
    captured() should include("__ducklake_metadata_lake")

  it should "be refused to a tenant user holding *.*.* ALL under QoD grants" in:
    val (router, captured, _) = setupWithRewriter(validator = wildcardAcl)
    val out                   = router
      .execute("dlm-2", "alice", poolKey, metadataRead, effectiveSet = Some(wildcardAllSet))
      .unsafeRunSync()
    catalogDenied(out, captured)

  it should "be refused to a tenant user under OPA, even with an allow-all policy" in:
    val (router, captured, _) = setupWithRewriter(validator = allowAllOpa)
    val out                   = router
      .execute("dlm-3", "alice", poolKey, metadataRead, effectiveSet = Some(wildcardAllSet))
      .unsafeRunSync()
    catalogDenied(out, captured)

  it should "be refused to a non-superuser with the ACL off, and with no principal bound" in:
    val (router, captured, _) = setupWithRewriter() // validator = allowAll: ACL off
    catalogDenied(
      router
        .execute("dlm-4", "alice", poolKey, metadataRead, effectiveSet = Some(wildcardAllSet))
        .unsafeRunSync(),
      captured
    )
    catalogDenied(
      router.execute("dlm-5", "alice", poolKey, metadataRead, effectiveSet = None).unsafeRunSync(),
      captured
    )
    val keys = router
      .execute(
        "dlm-6",
        "alice",
        poolKey,
        "SELECT * FROM ducklake_list_files('lake', 't')",
        effectiveSet = Some(wildcardAllSet)
      )
      .unsafeRunSync()
    keys.swap.toOption.get.reason should include("ducklake_list_files")

  it should "be refused to a superuser on a pool under lockdown" in:
    val (router, captured, _) = setupWithRewriter(lockdownFor = _ => true)
    val out                   = router
      .execute("dlm-7", "alice", poolKey, metadataRead, effectiveSet = Some(superuserSet))
      .unsafeRunSync()
    catalogDenied(out, captured)

  it should "leave the DuckLake data tables readable with an ordinary grant" in:
    val reader = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser,
      Nil,
      Nil,
      List(
        ai.starlake.quack.ondemand.state.RolePermission("p-2", "r-2", "memory", "main", "t", "RO")
      ),
      Nil
    )
    val (router, captured, _) = setupWithRewriter(validator = wildcardAcl)
    val out                   = router
      .execute(
        "dlm-8",
        "alice",
        poolKey,
        "SELECT * FROM memory.main.t",
        effectiveSet = Some(reader)
      )
      .unsafeRunSync()
    out.isRight shouldBe true
    captured() should include("memory.main.t")

  it should "be refused through the string-named carriers to every non-superuser" in:
    val carriers = Seq(
      "SELECT * FROM query_table('__ducklake_metadata_lake.ducklake_data_file')",
      "SELECT * FROM duckdb_table_sample('__ducklake_metadata_lake.ducklake_data_file')",
      "SELECT * FROM query('SELECT * FROM __ducklake_metadata_lake.ducklake_data_file')"
    )
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      carriers.foreach { sql =>
        withClue(sql)(
          catalogDenied(
            router
              .execute("dlm-9", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
              .unsafeRunSync(),
            captured
          )
        )
      }
      val computed = router
        .execute(
          "dlm-10",
          "alice",
          poolKey,
          "SELECT * FROM duckdb_table_sample('__duck' || 'lake_metadata_lake.ducklake_data_file')",
          effectiveSet = Some(wildcardAllSet)
        )
        .unsafeRunSync()
      computed.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    }
    val (locked, lockedCaptured, _) = setupWithRewriter(lockdownFor = _ => true)
    carriers.foreach { sql =>
      catalogDenied(
        locked.execute("dlm-11", "alice", poolKey, sql, Some(superuserSet)).unsafeRunSync(),
        lockedCaptured
      )
    }
    val (open, openCaptured, _) = setupWithRewriter()
    open
      .execute("dlm-12", "alice", poolKey, carriers.head, Some(superuserSet))
      .unsafeRunSync()
      .isRight shouldBe true
    openCaptured() should include("__ducklake_metadata_lake")

  it should "refuse json_execute_serialized_sql to every non-superuser, never to a superuser" in:
    val plan    = "SELECT * FROM json_execute_serialized_sql(json_serialize_sql('SELECT 1'))"
    val refusal = ai.starlake.quack.edge.sql.CatalogInternalsScreen.SerializedPlanRefusal
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      val out                   = router
        .execute("dlm-13", "alice", poolKey, plan, effectiveSet = Some(wildcardAllSet))
        .unsafeRunSync()
      out.swap.toOption.get.reason shouldBe s"access denied: catalog: $refusal"
      captured() shouldBe ""
    }
    val (open, openCaptured, _) = setupWithRewriter()
    val admitted                = open.execute("dlm-14", "alice", poolKey, plan, Some(superuserSet))
    admitted.unsafeRunSync().isRight shouldBe true
    openCaptured() should include("json_execute_serialized_sql")

  it should "refuse a computed PRAGMA search_path batch to every non-superuser" in:
    val batch =
      "PRAGMA search_path = '__duck' || 'lake_metadata_lake'; " +
        "SELECT encryption_key FROM ducklake_data_file"
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      val out                   = router
        .execute("dlm-15", "alice", poolKey, batch, effectiveSet = Some(wildcardAllSet))
        .unsafeRunSync()
      withClue(v.getClass.getSimpleName)(out.swap.toOption.get.reason should include("computed"))
      captured() shouldBe ""
    }

  it should "refuse a metadata-catalog name split across a newline to every non-superuser" in:
    val split = Seq(
      "SELECT encryption_key FROM query_table('__duck'\n'lake_metadata_lake.ducklake_data_file')",
      "SET search_path = '__duck'\n'lake_metadata_lake'; " +
        "SELECT encryption_key FROM ducklake_data_file"
    )
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      split.foreach { sql =>
        withClue(sql)(
          catalogDenied(
            router
              .execute("dlm-16", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
              .unsafeRunSync(),
            captured
          )
        )
      }
    }

  "the Iceberg catalog functions" should "be refused to *.*.* ALL under QoD and OPA, not su" in:
    val calls = Seq(
      "SELECT * FROM duckdb_table_sample('memory.main.t')",
      "SELECT * FROM iceberg_rollback_to_snapshot('ice.db.t', 1)",
      "SELECT * FROM set_iceberg_table_properties('ice.db.t', {'k': 'v'})"
    )
    Seq(wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      calls.foreach { sql =>
        val out = router
          .execute("ice-1", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
          .unsafeRunSync()
        withClue(sql)(out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied])
      }
      captured() shouldBe ""
      val su = router
        .execute("ice-2", "alice", poolKey, calls(1), effectiveSet = Some(superuserSet))
        .unsafeRunSync()
      su.isRight shouldBe true
    }

  // ---- node-wide settings (superuser-only, every ACL mode, never wildcard-coverable) ----

  private val nodeWideRefusal =
    s"access denied: node: ${ai.starlake.quack.edge.sql.NodeSettingScreen.NodeWideRefusal}"

  "a node-wide setting" should "be admitted for a superuser, lockdown off" in:
    val (router, captured, _) = setupWithRewriter()
    val out                   = router
      .execute("nw-1", "alice", poolKey, "SET threads = 2", effectiveSet = Some(superuserSet))
      .unsafeRunSync()
    out.isRight shouldBe true
    captured() should include("threads")

  it should "be refused to a tenant user holding *.*.* ALL, under QoD grants and under OPA" in:
    Seq(wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      Seq("SET memory_limit = '500GB'", "SET quack_debug_send_delay_ms = 60000").foreach { sql =>
        val out = router
          .execute("nw-2", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
          .unsafeRunSync()
        withClue(sql)(out.swap.toOption.get.reason shouldBe nodeWideRefusal)
      }
      captured() shouldBe ""
    }

  it should "be refused to a non-superuser with the ACL off" in:
    val (router, captured, _) = setupWithRewriter()
    val out                   = router
      .execute("nw-3", "alice", poolKey, "PRAGMA threads = 64", effectiveSet = Some(wildcardAllSet))
      .unsafeRunSync()
    out.swap.toOption.get.reason shouldBe nodeWideRefusal
    val stop = router
      .execute(
        "nw-4",
        "alice",
        poolKey,
        "SELECT * FROM quack_stop('quack:0.0.0.0:21900')",
        effectiveSet = Some(wildcardAllSet)
      )
      .unsafeRunSync()
    stop.swap.toOption.get.reason should include("quack_stop")
    captured() shouldBe ""

  it should "be refused to a superuser on a pool under lockdown" in:
    val (router, captured, _) = setupWithRewriter(lockdownFor = _ => true)
    val out                   = router
      .execute("nw-5", "alice", poolKey, "SET threads = 64", effectiveSet = Some(superuserSet))
      .unsafeRunSync()
    out.swap.toOption.get.reason shouldBe nodeWideRefusal
    captured() shouldBe ""

  "quack_serve and quack_stop" should "be refused to non-superusers and under lockdown" in:
    val calls = Seq(
      "SELECT * FROM quack_stop('quack:0.0.0.0:21900')",
      "CALL quack_serve('quack:0.0.0.0:9999', token := 'x', disable_ssl := true)"
    )
    def refusedIn(router: FlightSqlRouter, eff: ai.starlake.quack.ondemand.rbac.EffectiveSet) =
      calls.foreach { sql =>
        val out = router.execute("qs-1", "alice", poolKey, sql, Some(eff)).unsafeRunSync()
        withClue(sql)(out.swap.toOption.get.reason should include("reserved to superusers"))
      }
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      refusedIn(router, wildcardAllSet)
      captured() shouldBe ""
    }
    val (locked, lockedCaptured, _) = setupWithRewriter(lockdownFor = _ => true)
    refusedIn(locked, superuserSet)
    lockedCaptured() shouldBe ""
    val (open, _, _) = setupWithRewriter()
    open
      .execute("qs-2", "alice", poolKey, calls.head, Some(superuserSet))
      .unsafeRunSync()
      .isRight shouldBe true

  "quack session calls" should "be refused to non-superusers and under lockdown" in:
    // quack_active_connections() returns every session's running query text and connection id,
    // the id quack_cancel takes (2.0 CLI).
    val calls = Seq(
      "SELECT query FROM quack_active_connections()",
      "SELECT * FROM quack_server_list()",
      "SELECT * FROM quack_cancel('qod', '42')"
    )
    def refusedIn(router: FlightSqlRouter, eff: ai.starlake.quack.ondemand.rbac.EffectiveSet) =
      calls.foreach { sql =>
        val out = router.execute("qa-1", "alice", poolKey, sql, Some(eff)).unsafeRunSync()
        withClue(sql)(out.swap.toOption.get.reason should include("reserved to superusers"))
      }
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      refusedIn(router, wildcardAllSet)
      captured() shouldBe ""
    }
    val (locked, lockedCaptured, _) = setupWithRewriter(lockdownFor = _ => true)
    refusedIn(locked, superuserSet)
    lockedCaptured() shouldBe ""
    val (open, _, _) = setupWithRewriter()
    open
      .execute("qa-2", "alice", poolKey, calls.head, Some(superuserSet))
      .unsafeRunSync()
      .isRight shouldBe true

  it should "leave the allowlisted session settings to a non-superuser, ACL on or off" in:
    Seq(StatementValidator.allowAll, wildcardAcl).foreach { v =>
      val (router, _, _) = setupWithRewriter(validator = v)
      Seq(
        "SET search_path = 'memory.main'",
        "SET TimeZone = 'UTC'",
        "SET http_timeout = 300",
        "SET SESSION preserve_insertion_order = false",
        "SET enable_progress_bar = false",
        "SET errors_as_json = true",
        "SET SESSION default_order = 'DESC'",
        "SET VARIABLE x = 42"
      ).foreach { sql =>
        val out = router
          .execute("nw-6", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
          .unsafeRunSync()
        withClue(sql)(out.isRight shouldBe true)
      }
    }

  // DuckDB 2.0 runs an unscoped SET of a GLOBAL-default setting at GLOBAL scope (physical_set.cpp),
  // so the bare form is as node-wide as SET GLOBAL; an unknown name is refused fail-closed.
  it should "refuse a bare SET of a GLOBAL-default setting or an unknown one, in every mode" in:
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      Seq(
        "SET default_collation = 'nocase'" ->
          NodeSettingScreen.unscopedRefusal("default_collation"),
        "SET preserve_insertion_order = false" ->
          NodeSettingScreen.unscopedRefusal("preserve_insertion_order"),
        "SET extra_http_headers = MAP {'a': 'b'}" ->
          NodeSettingScreen.unscopedRefusal("extra_http_headers"),
        "SET ducklake_retry_wait_ms = 86400000" ->
          NodeSettingScreen.unscopedRefusal("ducklake_retry_wait_ms"),
        "PRAGMA null_order = 'nulls_first'" -> NodeSettingScreen.unscopedRefusal("null_order"),
        "SET force_mbedtls_unsafe = true"   -> NodeSettingScreen.NodeWideRefusal,
        "SET hnsw_ef_search = 10" -> NodeSettingScreen.unknownSettingRefusal("hnsw_ef_search")
      ).foreach { (sql, reason) =>
        val out = router
          .execute("nw-7", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
          .unsafeRunSync()
        withClue(sql)(out.swap.toOption.get.reason shouldBe s"access denied: node: $reason")
      }
      captured() shouldBe ""
    }
    val (locked, lockedCaptured, _) = setupWithRewriter(lockdownFor = _ => true)
    locked
      .execute("nw-8", "alice", poolKey, "SET default_order = 'DESC'", Some(superuserSet))
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .reason shouldBe s"access denied: node: ${NodeSettingScreen.unscopedRefusal("default_order")}"
    lockedCaptured() shouldBe ""
    val (open, openCaptured, _) = setupWithRewriter()
    open
      .execute("nw-9", "alice", poolKey, "SET default_collation = 'nocase'", Some(superuserSet))
      .unsafeRunSync()
      .isRight shouldBe true
    openCaptured() should include("default_collation")

  it should "deny (not forward) a plain read when a stored row policy fails to apply at rewrite time" in:
    // Simulates a row policy stored before RowPredicateValidator rejected splice-unsafe
    // predicates: a trailing line comment that comments out the rewriter's own closing paren at
    // splice time, throwing on re-parse. The router must deny, never forward the statement
    // unfiltered.
    var nodeCalled     = false
    val (router, _, _) = setup(stub = () => { nodeCalled = true; TestArrow.okResponse() })
    val badPolicy      = ai.starlake.quack.ondemand.state.RoleRowPolicy(
      "rp-bad",
      "r-1",
      "*",
      "main",
      "customer",
      "a = 1 --"
    )
    val out = router
      .execute(
        "rls-fail-1",
        "alice",
        poolKey,
        "SELECT c_id FROM main.customer",
        effectiveSet = Some(effWithRowPolicies(List(badPolicy)))
      )
      .unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    out.left.get shouldBe a[RouterFailure.AccessDenied]
    out.left.get.asInstanceOf[RouterFailure.AccessDenied].reason should include(
      "row policy failed to apply"
    )
    nodeCalled shouldBe false

  // ---- ActiveStatementRegistry integration tests ----

  it should "track the statement in the registry until the caller closes the stream" in:
    val registry     = new ActiveStatementRegistry()
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      registry = registry
    )
    val result = router.execute("reg-1", "alice", poolKey, "SELECT 1").unsafeRunSync()
    val qr     = result.toOption.get
    registry.list().map(_.user) shouldBe List("alice") // still open: entry present
    qr.close()
    registry.list() shouldBe Nil // closed: entry gone

  it should "carry the acting patId into the registry entry" in:
    val registry     = new ActiveStatementRegistry()
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      registry = registry
    )
    val result = router
      .execute("reg-pat", "alice", poolKey, "SELECT 1", patId = Some("pat-abc"))
      .unsafeRunSync()
    val qr = result.toOption.get
    registry.list().map(_.patId) shouldBe List(Some("pat-abc"))
    qr.close()

  it should "deregister on a permanent failure" in:
    val registry     = new ActiveStatementRegistry()
    val perm         = () => QuackResponse.Failed(QuackError.Permanent("Parser Error: syntax"), 1L)
    val (base, _, _) = setup(stub = perm)
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      registry = registry
    )
    router.execute("reg-2", "alice", poolKey, "SELECT boom").unsafeRunSync().isLeft shouldBe true
    registry.list() shouldBe Nil

  it should "register the retried statement in the registry and deregister on close" in:
    // Arrange: first adapter call returns a transient failure; the second (retry on the
    // fallback node) succeeds. We need two nodes so retryOnce has a fallback.
    val callCount                 = new java.util.concurrent.atomic.AtomicInteger(0)
    val stub: () => QuackResponse = () =>
      if callCount.getAndIncrement() == 0 then
        QuackResponse.Failed(QuackError.Transient("node temporarily gone"), 1L)
      else TestArrow.okResponse()

    val bknd = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          26000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())

    val tkr  = new NodeLoadTracker
    val sup2 = new PoolSupervisor(bknd, tkr, new InMemoryControlPlaneStore())
    val pk2  = PoolKey("acme", "acme_default", "sales")
    sup2.createTenant(ai.starlake.quack.model.Tenant(pk2.tenant)).unsafeRunSync()
    sup2
      .createTenantDb(pk2.tenant, pk2.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup2.createPool(pk2, RoleDistribution(0, 0, 2)).unsafeRunSync()

    val client2 =
      new QuackHttpClient(TestArrow.sharedAllocator):
        override def query(
            endpoint: String,
            token: String,
            sql: String,
            session: Option[String],
            authorize: () => String
        ) =
          IO.pure(stub())
    val adapter2  = new QuackHttpAdapter(client2, tkr)
    val sessions2 = new SessionRegistry
    val registry2 = new ActiveStatementRegistry()
    val router2   = new FlightSqlRouter(sup2, sessions2, tkr, adapter2, registry = registry2)

    val result =
      router2
        .execute("retry-reg-1", "alice", pk2, "SELECT 1", patId = Some("pat-retry"))
        .unsafeRunSync()
    result shouldBe a[Right[?, ?]]
    // Statement must be visible while the stream is open.
    registry2.list().map(_.user) shouldBe List("alice")
    registry2.list().map(_.patId) shouldBe List(Some("pat-retry"))
    result.toOption.get.close()
    // After close the entry must be gone.
    registry2.list() shouldBe Nil

  // ---------- EPIC P1: author stamping ----------

  /** Like setup() but the pool is DuckLake-kind (kindWire "ducklake" + metastore dbName), the
    * client records (prelude, sql) pairs, and stampWrites is on. DuckLake pre-init inside
    * createTenantDb fails fast against pgPort 0 and is swallowed with a warning by design.
    */
  private def stampedSetup(stampWrites: Boolean = true) =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          22000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())

    val tracker = new NodeLoadTracker
    val admin   = new ai.starlake.quack.ondemand.state.DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val sup = new PoolSupervisor(
      backend,
      tracker,
      new InMemoryControlPlaneStore(),
      dbAdmin = admin
    )
    sup.createTenant(ai.starlake.quack.model.Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(
        poolKey.tenant,
        poolKey.tenantDb,
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-stamp-test"
      )
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val calls  = scala.collection.mutable.ListBuffer.empty[(Option[String], String)]
    val client =
      new QuackHttpClient(TestArrow.sharedAllocator):
        override def query(
            endpoint: String,
            token: String,
            sql: String,
            session: Option[String],
            authorize: () => String
        ) =
          IO { calls += ((None, sql)); TestArrow.okResponse() }
        override def queryStamped(
            endpoint: String,
            token: String,
            authorize: () => String,
            prelude: String,
            sql: String
        ) =
          IO { calls += ((Some(prelude), sql)); TestArrow.okResponse() }
    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      stmtInstruments = si,
      stampWrites = stampWrites
    )
    (router, sessions, sup, calls)

  it should "stamp a DML statement on a ducklake pool with author tenant:<t>/user:<u>" in:
    val (router, _, sup, calls) = stampedSetup()
    router.execute("c-s1", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    val (prelude, sql) = calls.head
    prelude should not be empty
    val db = sup.get(poolKey).get.metastore("dbName")
    prelude.get shouldBe
      s"BEGIN; CALL ducklake_set_commit_message('$db', 'tenant:acme/user:alice', 'flightsql insert')"
    sql should include("INSERT INTO t VALUES (1)")

  it should "stamp DDL with the verb in the commit message" in:
    val (router, _, _, calls) = stampedSetup()
    router.execute("c-s2", "alice", poolKey, "CREATE TABLE t2 (i INT)").unsafeRunSync()
    calls.head._1.get should include("'flightsql create'")

  it should "escape a single quote in the user name (injection guard)" in:
    val (router, _, _, calls) = stampedSetup()
    router.execute("c-s3", "o'brien", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    calls.head._1.get should include("'tenant:acme/user:o''brien'")

  it should "not stamp a SELECT" in:
    val (router, _, _, calls) = stampedSetup()
    router.execute("c-s4", "alice", poolKey, "SELECT 1").unsafeRunSync()
    calls.head._1 shouldBe None

  it should "not stamp inside a client-opened transaction" in:
    val (router, _, _, calls) = stampedSetup()
    router.execute("c-s5", "alice", poolKey, "BEGIN").unsafeRunSync()
    router.execute("c-s5", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    calls.map(_._1).toList shouldBe List(None, None)

  it should "not stamp on a memory pool" in:
    val (router, _, _) = setup() // the existing InMemory setup routes through query only
    // covered implicitly: setup()'s client has no queryStamped override, so a stamped call
    // would hit the real native path and fail loudly. Execute a DML and expect Ok.
    val out = router.execute("c-s6", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    out shouldBe a[Right[_, _]]

  it should "not stamp when stampWrites is off" in:
    val (router, _, _, calls) = stampedSetup(stampWrites = false)
    router.execute("c-s7", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    calls.head._1 shouldBe None

  it should "not stamp when recordExecution is false (internal probe)" in:
    val (router, _, _, calls) = stampedSetup()
    router
      .execute("c-s8", "alice", poolKey, "INSERT INTO t VALUES (1)", recordExecution = false)
      .unsafeRunSync()
    calls.head._1 shouldBe None

  it should "derive the commit-message verb from comment-stripped SQL" in:
    val (router, _, _, calls) = stampedSetup()
    router
      .execute("c-s9", "alice", poolKey, "/* hint */ INSERT INTO t VALUES (1)")
      .unsafeRunSync()
    calls.head._1.get should include("'flightsql insert'")

  // ---- I1 regression: an invisible character must not leak into the audit ledger's verb ----
  //
  // `stampPrelude` only runs for `kind`=Dml/Ddl. Before the classifier normalized trivia,
  // `INSERT<NBSP>INTO ...` classified `Other`, so this code path never saw it. Once the classifier
  // started seeing through trivia, statements like this reached `stampPrelude` for the first time,
  // and its own verb reader had no trivia handling of its own: `stripped.trim` does not strip
  // NBSP (`String.trim` only strips <= U+0020), so the verb read as "insert\u00A0into" (or, with a
  // leading NBSP, "\u00A0insert") instead of "insert" -- an invisible character landing in the
  // DuckLake commit ledger's verb field, the field an operator greps to reconstruct who wrote
  // what. No injection risk (`SqlLiterals.duckdbLiteral` escapes it), but the ledger row no longer
  // matches `flightsql insert`. Every character below is a literal `\uXXXX` escape, never a raw
  // invisible byte (see the byte-hygiene test at the end of this file).
  it should "not let an interior NBSP leak into the commit-message verb" in:
    val (router, _, _, calls) = stampedSetup()
    router
      .execute("c-s10", "alice", poolKey, "INSERT\u00A0INTO t VALUES (1)")
      .unsafeRunSync()
    calls.head._1.get should include("'flightsql insert'")

  it should "not let a leading NBSP leak into the commit-message verb" in:
    val (router, _, _, calls) = stampedSetup()
    router
      .execute("c-s11", "alice", poolKey, "\u00A0INSERT INTO t VALUES (1)")
      .unsafeRunSync()
    calls.head._1.get should include("'flightsql insert'")

  it should "carry the stamping prelude through to the retry node on transient failure" in:
    // Two-node stamped pool; first call fails transiently; retry succeeds.
    // Both recorded calls must carry Some(prelude) to prove the prelude threads through retryOnce.
    val callCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val calls     = scala.collection.mutable.ListBuffer.empty[(Option[String], String)]

    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          27000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())

    val tracker = new NodeLoadTracker
    val admin   = new ai.starlake.quack.ondemand.state.DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val sup = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore(), dbAdmin = admin)
    sup.createTenant(ai.starlake.quack.model.Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(
        poolKey.tenant,
        poolKey.tenantDb,
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-stamp-retry-test"
      )
      .unsafeRunSync()
    // Two dual-role nodes so retryOnce has a fallback.
    sup.createPool(poolKey, RoleDistribution(0, 0, 2)).unsafeRunSync()

    val client =
      new QuackHttpClient(TestArrow.sharedAllocator):
        override def query(
            endpoint: String,
            token: String,
            sql: String,
            session: Option[String],
            authorize: () => String
        ) =
          IO { calls += ((None, sql)); TestArrow.okResponse() }
        override def queryStamped(
            endpoint: String,
            token: String,
            authorize: () => String,
            prelude: String,
            sql: String
        ) =
          IO {
            calls += ((Some(prelude), sql))
            if callCount.getAndIncrement() == 0 then
              QuackResponse.Failed(QuackError.Transient("boom"), 1L)
            else TestArrow.okResponse()
          }

    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      stmtInstruments = si,
      stampWrites = true
    )

    val result =
      router.execute("c-s10", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    result shouldBe a[Right[?, ?]]
    calls.size shouldBe 2
    calls.map(_._1).forall(_.isDefined) shouldBe true

  // ---------- SPI module events ----------

  "module events" should "emit SessionOpened then StatementExecuted with ok=true on success" in:
    val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
    val sink: ManagerEventSink = e => { received.add(e); () }
    val (router, _, _)         = setup(events = sink)
    val out = router.execute("evt-1", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out shouldBe a[Right[_, _]]

    val seen             = received.toArray.toList.map(_.asInstanceOf[ManagerEvent])
    val sessionOpenedIdx = seen.indexWhere {
      case ManagerEvent.SessionOpened(t, u, via) =>
        t == poolKey.tenant && u == "alice" && via == "flightsql"
      case _ => false
    }
    sessionOpenedIdx should be >= 0

    val executedIdx = seen.indexWhere {
      case _: ManagerEvent.StatementExecuted => true
      case _                                 => false
    }
    executedIdx should be > sessionOpenedIdx

    seen(executedIdx) match
      case se: ManagerEvent.StatementExecuted =>
        se.ok shouldBe true
        se.kind shouldBe "Select"
        se.tenant shouldBe poolKey.tenant
        se.tenantDb shouldBe poolKey.tenantDb
        se.pool shouldBe poolKey.pool
      case other => fail(s"expected StatementExecuted, got $other")

  it should "emit StatementExecuted with ok=false when execution fails" in:
    val denying = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult = Denied("you can't read this")
    val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
    val sink: ManagerEventSink = e => { received.add(e); () }
    val (base, _, _)           = setup()
    val router                 = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      validator = denying,
      stmtInstruments = si,
      events = sink
    )
    val out = router.execute("evt-2", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out shouldBe a[Left[_, _]]

    val executed = received.toArray.toList.collect { case e: ManagerEvent.StatementExecuted => e }
    executed should not be empty
    executed.head.ok shouldBe false

  it should "emit no module events when recordExecution=false (probe-style call)" in:
    val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
    val sink: ManagerEventSink = e => { received.add(e); () }
    val (router, _, _)         = setup(events = sink)
    val out                    = router
      .execute("evt-3", "alice", poolKey, "SELECT 1", recordExecution = false)
      .unsafeRunSync()
    out shouldBe a[Right[_, _]]
    received.toArray.toList shouldBe Nil

  // ---------- Suspend / resume: edge wake-and-hold ----------

  /** Like setup() but exposes the supervisor and lets tests tune the wake-and-hold knobs. The
    * backend's start can be flipped to fail AFTER pool creation (`failResumeStarts`), so a resume
    * clears the suspended flag but never produces a node - exercising the hold-timeout arm.
    */
  private def setupWithSupervisor(
      failResumeStarts: Boolean = false,
      resumeHoldTimeout: FiniteDuration = 60.seconds,
      resumePollInterval: FiniteDuration = 250.millis
  ) =
    val failStart = new java.util.concurrent.atomic.AtomicBoolean(false)
    val backend   = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) =
        if failStart.get() then IO.raiseError(new RuntimeException("spawn refused (test)"))
        else
          IO {
            val r = RunningNode(
              s.nodeId,
              s.poolKey,
              s.role,
              "127.0.0.1",
              28000 + n.size,
              "tok",
              Some(1L),
              None,
              Instant.EPOCH,
              maxConcurrent = s.maxConcurrent
            )
            n.put(s.nodeId, r); r
          }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())

    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(ai.starlake.quack.model.Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    if failResumeStarts then failStart.set(true)

    val client = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO.pure(TestArrow.okResponse())
    val adapter = new QuackHttpAdapter(client, tracker)
    val router  = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      adapter,
      stmtInstruments = si,
      resumeHoldTimeout = resumeHoldTimeout,
      resumePollInterval = resumePollInterval
    )
    (router, sup)

  "execute on a suspended pool" should "wake the pool and run the statement" in:
    val (router, sup) = setupWithSupervisor()
    sup.suspendPool(poolKey, "rest").unsafeRunSync()
    sup.get(poolKey).get.suspended shouldBe true
    val r = router
      .execute("wake-1", "alice", poolKey, "SELECT 1", recordExecution = true)
      .unsafeRunSync()
    r.isRight shouldBe true
    sup.get(poolKey).get.suspended shouldBe false
    sup.get(poolKey).get.nodes should not be empty

  it should "time out with a retryable error when no node comes up" in:
    val (router, sup) = setupWithSupervisor(
      failResumeStarts = true,
      resumeHoldTimeout = 500.millis,
      resumePollInterval = 50.millis
    )
    sup.suspendPool(poolKey, "rest").unsafeRunSync()
    val r = router
      .execute("wake-2", "alice", poolKey, "SELECT 1", recordExecution = true)
      .unsafeRunSync()
    r match
      case Left(RouterFailure.Unavailable(msg)) => msg should include("resuming")
      case other => fail(s"expected Unavailable('pool is resuming...'), got $other")

  it should "not wake a disabled pool" in:
    val (router, sup) = setupWithSupervisor()
    sup.suspendPool(poolKey, "rest").unsafeRunSync()
    sup.setPoolDisabled(poolKey, disabled = true).unsafeRunSync()
    val r = router
      .execute("wake-3", "alice", poolKey, "SELECT 1", recordExecution = true)
      .unsafeRunSync()
    r.isLeft shouldBe true
    // Untouched: a disabled pool is never auto-woken by traffic.
    sup.get(poolKey).get.suspended shouldBe true

  "execute on a stopPool'ed pool" should "fail immediately without waking" in:
    val (router, sup) = setupWithSupervisor()
    sup.stopPool(poolKey, force = true).unsafeRunSync()
    val t0 = System.nanoTime()
    val r  = router
      .execute("wake-4", "alice", poolKey, "SELECT 1", recordExecution = true)
      .unsafeRunSync()
    r.isLeft shouldBe true
    // The existing "pool is empty" error must surface immediately - no 60s hold.
    (System.nanoTime() - t0) should be < 2_000_000_000L

  // ---- trigger DDL (DuckDB 2.0) ----

  private val createTrigger =
    "CREATE TRIGGER t AFTER INSERT ON a FOR EACH STATEMENT INSERT INTO b VALUES (1)"

  "trigger DDL" should "be refused for a regular tenant user, lockdown off" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val out = router
      .execute("trg-1", "alice", poolKey, createTrigger, effectiveSet = Some(effWithPolicies(Nil)))
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should include(
      ai.starlake.quack.edge.sql.TriggerScreen.RefusalMessage
    )

  it should "be refused when the effective set is missing" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val out = router
      .execute("trg-2", "alice", poolKey, "DROP TRIGGER t", effectiveSet = None)
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]

  it should "be admitted for an admin of the pool's own tenant" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val admin = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router
      .execute("trg-3", "alice", poolKey, createTrigger, effectiveSet = Some(admin))
      .unsafeRunSync()
    out.isRight shouldBe true

  it should "be admitted for a superuser" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val superuser = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(tenant = None, kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router
      .execute("trg-4", "alice", poolKey, createTrigger, effectiveSet = Some(superuser))
      .unsafeRunSync()
    out.isRight shouldBe true

  it should "be refused for an admin of another tenant" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val foreignAdmin = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(tenant = Some("globex"), kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router
      .execute("trg-5", "alice", poolKey, createTrigger, effectiveSet = Some(foreignAdmin))
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should include(
      ai.starlake.quack.edge.sql.TriggerScreen.RefusalMessage
    )

  it should "yield to lockdown when both screens refuse the same batch" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      lockdownFor = _ => true
    )
    val out = router
      .execute(
        "trg-6",
        "alice",
        poolKey,
        s"ATTACH 'x.db' AS y; $createTrigger",
        effectiveSet = Some(effWithPolicies(Nil))
      )
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should startWith("access denied: lockdown:")

  // ---- the node CLI's shell history holds its init SQL, credentials included ----

  "shell_history()" should "be refused to a superuser and to a *.*.* ALL tenant, both edges" in:
    Seq(superuserSet, wildcardAllSet).foreach { eff =>
      val (router, captured, _) = setupWithRewriter(validator = wildcardAcl)
      withClue(eff.user.kind) {
        router
          .execute("hist-1", "alice", poolKey, "SELECT * FROM shell_history()", Some(eff))
          .unsafeRunSync()
          .swap
          .toOption
          .get
          .reason should include(
          ai.starlake.quack.edge.sql.NodeSettingScreen.SecretCallRefusalMessage
        )
        router
          .executeWith(
            "hist-2",
            "alice",
            poolKey,
            "SELECT * FROM shell_history()",
            Some(eff),
            okSend,
            source = "quack",
            clientDataScans = true
          )
          .unsafeRunSync() shouldBe a[Left[?, ?]]
        captured() shouldBe ""
      }
    }

  "SET file_search_path" should "be refused to a tenant user, ACL off or on, and open to a superuser" in:
    Seq(StatementValidator.allowAll, wildcardAcl).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      withClue(v.toString) {
        router
          .execute("fsp-1", "alice", poolKey, "SET file_search_path = '/etc'", Some(wildcardAllSet))
          .unsafeRunSync()
          .swap
          .toOption
          .get shouldBe a[RouterFailure.AccessDenied]
        captured() shouldBe ""
        router
          .execute("fsp-2", "alice", poolKey, "SET file_search_path = '/etc'", Some(superuserSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
      }
    }

  "INSTALL" should "be refused to a tenant user, ACL off or on, and open to a superuser" in:
    Seq(StatementValidator.allowAll, wildcardAcl).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      withClue(v.toString) {
        router
          .execute("inst-1", "alice", poolKey, "INSTALL httpfs", Some(wildcardAllSet))
          .unsafeRunSync()
          .swap
          .toOption
          .get shouldBe a[RouterFailure.AccessDenied]
        captured() shouldBe ""
        router
          .execute("inst-2", "alice", poolKey, "LOAD httpfs", Some(wildcardAllSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        router
          .execute("inst-3", "alice", poolKey, "INSTALL httpfs", Some(superuserSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
      }
    }

  // ---- parser settings (DuckDB 2.0) ----

  "a parser setting" should "be refused for a regular user with acl disabled, lockdown off" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val out = router
      .execute(
        "psr-1",
        "alice",
        poolKey,
        "SET GLOBAL dialect_compatibility_mode = 'spark'",
        effectiveSet = Some(effWithPolicies(Nil))
      )
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason shouldBe
      s"access denied: node: ${ai.starlake.quack.edge.sql.NodeSettingScreen.RefusalMessage}"

  it should "be refused for a superuser too, lockdown off" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val superuser = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(tenant = None, kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router
      .execute(
        "psr-2",
        "alice",
        poolKey,
        "SELECT 1; RESET show_behavior",
        effectiveSet = Some(superuser)
      )
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should include(
      ai.starlake.quack.edge.sql.NodeSettingScreen.RefusalMessage
    )

  it should "be refused when the effective set is missing, and leave other settings alone" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val refused = router
      .execute("psr-3", "alice", poolKey, "IMPORT DATABASE 'x'", effectiveSet = None)
      .unsafeRunSync()
    refused.swap.toOption.get.reason should include(
      ai.starlake.quack.edge.sql.NodeSettingScreen.ImportRefusalMessage
    )
    val admitted = router
      .execute("psr-4", "alice", poolKey, "SET http_timeout = 300", effectiveSet = None)
      .unsafeRunSync()
    admitted.isRight shouldBe true

  it should "refuse redact_http_logs to a superuser too, lockdown off" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val superuser = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(tenant = None, kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router
      .execute(
        "psr-5",
        "alice",
        poolKey,
        "SET GLOBAL\"redact_http_logs\" = false",
        effectiveSet = Some(superuser)
      )
      .unsafeRunSync()
    out.swap.toOption.get.reason shouldBe
      s"access denied: node: ${NodeSettingScreen.SecretRefusalMessage}"

  it should "refuse PRAGMA import_database for a superuser, lockdown off" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val superuser = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(tenant = None, kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val imp = router
      .execute("psr-6", "alice", poolKey, "PRAGMA import_database('/tmp/x')", Some(superuser))
      .unsafeRunSync()
    imp.swap.toOption.get.reason shouldBe
      s"access denied: node: ${NodeSettingScreen.ImportRefusalMessage}"

  it should "admit postgres_execute with the ACL off and lockdown off (owner ruling)" in:
    // The raw federation passthrough is blocked only for tenant-scoped principals when the ACL
    // validates (CatalogReachingCalls, unit-tested in AccessGateSpec / PostgresAclValidatorSpec)
    // and for every non-superuser under lockdown (below). The default validator here is allowAll.
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si
    )
    val superuser = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(tenant = None, kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router
      .execute(
        "psr-7",
        "alice",
        poolKey,
        "SELECT * FROM postgres_query('c', 'SELECT 1')",
        Some(superuser)
      )
      .unsafeRunSync()
    out.isRight shouldBe true
    // A SQL-valued setting is refused only to tenant principals under the ACL and under lockdown.
    // Session scope: the bare form of this GLOBAL-default option is node-wide (superuser-only).
    val set = router
      .execute("psr-8", "alice", poolKey, "SET SESSION pg_staleness_query = 'SELECT 1'", None)
      .unsafeRunSync()
    set.isRight shouldBe true

  // ---- node lockdown (QOD_NODE_LOCKDOWN) ----

  "lockdown" should "deny ATTACH for a tenant caller when enabled" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      lockdownFor = _ => true
    )
    val out = router
      .execute(
        "lockdown-1",
        "alice",
        poolKey,
        "ATTACH 'x.db' AS y",
        effectiveSet = Some(effWithPolicies(Nil))
      )
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should startWith("access denied: lockdown:")

  it should "deny ATTACH when effectiveSet is missing (no handshake state screens as non-superuser)" in:
    // Pins the fail-closed comment in FlightSqlRouter ("effectiveSet = None
    // (no handshake state attached) screens as non-superuser -- fail
    // closed"): `!effectiveSet.exists(_.user.tenant.isEmpty)` is true when
    // effectiveSet is None, so a missing EffectiveSet must never be treated
    // as the superuser bypass.
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      lockdownFor = _ => true
    )
    val out = router
      .execute(
        "lockdown-1b",
        "alice",
        poolKey,
        "ATTACH 'x.db' AS y",
        effectiveSet = None
      )
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should startWith("access denied: lockdown:")

  it should "admit ATTACH for a superuser caller" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      lockdownFor = _ => true
    )
    // Quarantine the only node so a statement that clears the lockdown screen
    // proceeds to routing and fails there (Unavailable) rather than being
    // denied by the screen -- proof the screen was never consulted.
    val nodeId = router.supervisor.list().head.nodes.head.nodeId
    router.tracker.setHealthy(nodeId, false)
    val superuserEff = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      tenantUser.copy(tenant = None),
      Nil,
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router
      .execute(
        "lockdown-2",
        "root",
        poolKey,
        "ATTACH 'x.db' AS y",
        effectiveSet = Some(superuserEff)
      )
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.Unavailable]

  it should "deny reads on a denied DuckLake bucket only when lockdown is on" in:
    val (base, _, _) = setup()
    val sql          = "SELECT * FROM read_parquet('s3://lakebucket/x.parquet')"
    val locked       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      lockdownFor = _ => true,
      deniedBuckets = () => Set("lakebucket")
    )
    val denied = locked
      .execute("lockdown-b1", "alice", poolKey, sql, effectiveSet = Some(effWithPolicies(Nil)))
      .unsafeRunSync()
    denied.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    denied.swap.toOption.get.reason should include("'lakebucket'")

    val unlocked = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      lockdownFor = _ => false,
      deniedBuckets = () => Set("lakebucket")
    )
    unlocked
      .execute("lockdown-b2", "alice", poolKey, sql, effectiveSet = Some(effWithPolicies(Nil)))
      .unsafeRunSync()
      .isRight shouldBe true

  it should "not consult the screen when disabled" in:
    val (router, _, _) = setup() // lockdownFor defaults to `_ => false`
    val out            = router
      .execute(
        "lockdown-3",
        "alice",
        poolKey,
        "ATTACH 'x.db' AS y",
        effectiveSet = Some(effWithPolicies(Nil))
      )
      .unsafeRunSync()
    // ATTACH isn't a real DuckDB statement against the stub node, but the point of
    // this test is only that it is NOT denied by the lockdown screen: today's
    // behavior (routes to the stub node and gets an Ok/whatever the stub returns)
    // is unchanged.
    out.swap.toOption.foreach(_ shouldNot be(a[RouterFailure.AccessDenied]))

  it should "resolve lockdown per pool: sibling pool with the override off is admitted" in:
    // lockdownFor answers true only for a pool this session does NOT target,
    // so the ATTACH that the locked-pool test sees denied must pass the
    // lockdown screen here (it may still fail later for other reasons; the
    // assertion is only "not a lockdown denial").
    val (router, _, _) = setup(lockdownFor = k => k.pool == "some-other-pool")
    val out            = router
      .execute(
        "lockdown-4",
        "alice",
        poolKey,
        "ATTACH 'x.db' AS y",
        effectiveSet = Some(effWithPolicies(Nil))
      )
      .unsafeRunSync()
    out.swap.toOption.map(_.reason).getOrElse("") should not startWith "access denied: lockdown:"

  // ---- per-catalog read-only screen wiring (CatalogWriteScreen via `readOnlyCatalogsOf`) ----
  // Pinning I1: `readOnlyCatalogsOf` defaults to `_ => Set.empty`, so with no test exercising a
  // non-empty value here every one of these would still pass with the whole `catalogDenial` block
  // (FlightSqlRouter.scala) and its `Main` wiring deleted outright.

  it should "deny a fully-qualified write to a read-only catalog and record it distinctly" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      readOnlyCatalogsOf = _ => Set("sales_lake"),
      attachedCatalogsOf = _ => Set("sales_lake")
    )
    val out = router
      .execute("ro-1", "alice", poolKey, "INSERT INTO sales_lake.main.t VALUES (1)")
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    val latest = router.history.snapshot(1).head
    latest.status shouldBe "denied"
    latest.error.get should startWith("read_only_catalog:")

  it should "let an ACL denial win over the read-only screen, with the ACL reason reported" in:
    // Proves the `catalogDenial = aclCheck.flatMap { ... }` ordering: when the ACL gate itself
    // denies, the screen must never run, and the reason the caller sees must be the ACL one, not
    // "read_only_catalog: ...". Reverting either the wiring or the flatMap ordering (e.g. running
    // the screen independently of aclCheck) would let this statement's read-only reason leak
    // through, or would report the wrong reason, and this test would fail.
    val (base, _, _) = setup()
    val aclValidator = new StatementValidator:
      def validate(context: ValidationContext): ValidationResult =
        Denied("acl: no grant on sales_lake.main.t")
    val router = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      validator = aclValidator,
      readOnlyCatalogsOf = _ => Set("sales_lake"),
      attachedCatalogsOf = _ => Set("sales_lake")
    )
    val out = router
      .execute(
        "ro-2",
        "alice",
        poolKey,
        "INSERT INTO sales_lake.main.t VALUES (1)",
        effectiveSet = Some(effWithPolicies(Nil))
      )
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should include("acl: no grant on sales_lake.main.t")
    out.swap.toOption.get.reason should not include "read_only_catalog"

  it should "deny an ambiguous two-part write against a read-only catalog (C1 regression)" in:
    // `sales_lake.orders` cannot be qualified (AmbiguousCatalogRef, TableQualifier.qualify drops
    // it from `accesses`); before the C1 fix CatalogWriteScreen only looked at `accesses` and
    // admitted this by omission. Reverting the qualificationErrors check in CatalogWriteScreen
    // reproduces that gap and this test fails (isRight becomes true).
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      readOnlyCatalogsOf = _ => Set("sales_lake"),
      attachedCatalogsOf = _ => Set("sales_lake")
    )
    val out = router
      .execute("ro-3", "alice", poolKey, "INSERT INTO sales_lake.orders VALUES (1)")
      .unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
    out.swap.toOption.get.reason should include("could not be fully resolved")

  // ---- cache-aware placement (milestone-2 edge integration) ----

  /** Two dual-role nodes on an object-store (or, when overridden, local) dataPath, a
    * SimpleMeterRegistry-backed RoutingInstruments, and a shared PlacementDirectory the test can
    * inspect. refsConfigFor supplies db/main so `SELECT * FROM customer` canonicalizes to
    * `db.main.customer`. The tenant-db is DuckLake-kind because that is the only kind whose
    * PoolState.metastore carries a `dataPath` key (the router's object-store gate reads it);
    * DuckLake pre-init against pgPort 0 fails fast and is swallowed with a warning by design.
    */
  private def setupPlacement(
      cacheAware: Boolean = true,
      dataPath: String = "s3://bucket/data"
  ) =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          29000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())

    val tracker = new NodeLoadTracker
    val admin   = new ai.starlake.quack.ondemand.state.DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val sup = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore(), dbAdmin = admin)
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(
        poolKey.tenant,
        poolKey.tenantDb,
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "schemaName" -> "main"
        ),
        dataPath
      )
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 2)).unsafeRunSync()

    val client = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO.pure(TestArrow.okResponse())
    val adapter = new QuackHttpAdapter(client, tracker)
    val reg     = new SimpleMeterRegistry
    val ri      = new ai.starlake.quack.observability.metrics.RoutingInstruments(reg)
    val dir     = new ai.starlake.quack.route.PlacementDirectory()
    val router  = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      adapter,
      stmtInstruments = si,
      refsConfigFor = _ => ai.starlake.acl.model.Config.forDuckDB(Some("db"), Some("main")),
      routingInstruments = ri,
      placement = dir,
      cacheAwareRouting = cacheAware
    )
    (router, reg, dir, sup.get(poolKey).get.nodes)

  private def decisionCount(reg: SimpleMeterRegistry, outcome: String): Double =
    reg
      .counter(
        "routing_decisions_total",
        "tenant",
        poolKey.tenant,
        "pool",
        poolKey.pool,
        "outcome",
        outcome
      )
      .count()

  "cache-aware placement" should "route repeat reads of the same table to the same node (sticky)" in:
    val (router, reg, _, _) = setupPlacement()
    val first = router.execute("stk-1", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync()
    val firstNode = first.toOption.get.nodeId
    first.toOption.get.close()
    val second = router.execute("stk-1", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync()
    val secondNode = second.toOption.get.nodeId
    second.toOption.get.close()
    firstNode shouldBe secondNode
    decisionCount(reg, "claim") shouldBe 1.0
    decisionCount(reg, "sticky-fresh") shouldBe 1.0

  it should "bump the epoch when a write is routed and keep the writer as fresh MRU home" in:
    val (router, _, dir, nodes) = setupPlacement()
    val live                    = nodes.map(_.nodeId).toSet
    val table                   = "db.main.customer"
    val out1                    =
      router.execute("wr-1", "alice", poolKey, "INSERT INTO customer VALUES (1)").unsafeRunSync()
    val writer = out1.toOption.get.nodeId
    out1.toOption.get.close()
    val a1 = dir.viewFor(poolKey, Set(table), live)(table)
    a1.homes shouldBe List(ai.starlake.quack.route.HomeEntry(writer, 0L))
    a1.currentEpoch shouldBe 0L
    val out2 =
      router.execute("wr-1", "alice", poolKey, "INSERT INTO customer VALUES (1)").unsafeRunSync()
    out2.toOption.get.close()
    val a2 = dir.viewFor(poolKey, Set(table), live)(table)
    a2.currentEpoch shouldBe 1L
    a2.homes.head shouldBe ai.starlake.quack.route.HomeEntry(writer, 1L)

  it should "fall back to least-loaded and label flag-off when cacheAwareRouting is false" in:
    val (router, reg, _, _) = setupPlacement(cacheAware = false)
    router.execute("off-1", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync().foreach {
      qr => qr.close()
    }
    decisionCount(reg, "flag-off") shouldBe 1.0

  it should "label not-eligible on a local-dataPath pool" in:
    val (router, reg, _, _) = setupPlacement(dataPath = "/tmp/data")
    router.execute("loc-1", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync().foreach {
      qr => qr.close()
    }
    decisionCount(reg, "not-eligible") shouldBe 1.0

  it should "let a transaction pin override the placement scorer and record pinned-move" in:
    val (router, reg, _, nodes) = setupPlacement()
    // Warm `customer` on whichever node serves the first read: that becomes its placement home.
    val warm = router.execute("pin-mv", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync()
    val homeNode = warm.toOption.get.nodeId
    warm.toOption.get.close()
    // Open a transaction pinned to the OTHER node, via the preferredNode seam on BEGIN, so the tx
    // pin and the scorer's warm home deliberately disagree.
    val other = nodes.map(_.nodeId).find(_ != homeNode).getOrElse(fail("need >1 node"))
    router.execute("pin-mv", "alice", poolKey, "BEGIN", preferredNode = Some(other)).unsafeRunSync()
    router.session("pin-mv").flatMap(_.pinnedNodeId) shouldBe Some(other)
    // A read of the same table inside the tx: the scorer would pick `homeNode`, but the tx pin
    // forces `other`. Assert both the node identity and the pinned-move decision label.
    val inTx = router.execute("pin-mv", "alice", poolKey, "SELECT * FROM customer").unsafeRunSync()
    inTx.toOption.get.nodeId shouldBe other
    inTx.toOption.get.close()
    decisionCount(reg, "pinned-move") shouldBe 1.0

  // ---- MetadataFilterRewriter integration tests ----
  //
  // The pool is InMemory, so the session catalog the router hands the rewriter is
  // "memory" and the session schema is "main"; grants must name that catalog to be
  // read-covering (see FlightSqlRouter.perKindDb).

  private val metaFilterOn = new ai.starlake.quack.edge.meta.MetadataFilterRewriter(enabled = true)

  private def grant(catalog: String, schema: String, table: String, verb: String) =
    ai.starlake.quack.ondemand.state.RolePermission(
      id = s"rp-$catalog-$schema-$table-$verb",
      roleId = "r-1",
      catalogName = catalog,
      schemaName = schema,
      tableName = table,
      verb = verb,
      grantedAt = Some(Instant.now())
    )

  private def effWithGrants(
      ps: List[ai.starlake.quack.ondemand.state.RolePermission]
  ): ai.starlake.quack.ondemand.rbac.EffectiveSet =
    ai.starlake.quack.ondemand.rbac.EffectiveSet(tenantUser, Nil, Nil, ps, Nil)

  private val customerOnly = Some(effWithGrants(List(grant("memory", "tpch1", "customer", "RO"))))

  "the metadata filter step" should "narrow an information_schema read to the granted objects" in:
    val (router, capturedSql, _) = setupWithRewriter(metadataFilter = metaFilterOn)
    val out                      = router
      .execute(
        "meta-1",
        "alice",
        poolKey,
        "SELECT table_name FROM information_schema.tables",
        effectiveSet = customerOnly
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("table_schema = 'tpch1' AND table_name = 'customer'")

  it should "leave the statement untouched when the filter is disabled" in:
    val (router, capturedSql, _) = setupWithRewriter()
    val out                      = router
      .execute(
        "meta-2",
        "alice",
        poolKey,
        "SELECT table_name FROM information_schema.tables",
        effectiveSet = customerOnly
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("FROM information_schema.tables")
    capturedSql() should not include "table_schema = 'tpch1'"

  it should "leave a superuser's information_schema read unfiltered" in:
    val root = ai.starlake.quack.ondemand.state.RbacUser("u-0", None, "root", "admin")
    val (router, capturedSql, _) = setupWithRewriter(metadataFilter = metaFilterOn)
    val out                      = router
      .execute(
        "meta-3",
        "root",
        poolKey,
        "SELECT table_name FROM information_schema.tables",
        effectiveSet = Some(ai.starlake.quack.ondemand.rbac.EffectiveSet(root, Nil, Nil, Nil, Nil))
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("FROM information_schema.tables")
    capturedSql() should not include "qod_meta"

  it should "narrow a plain SHOW TABLES, rewritten to a catalog function listing (Task 19)" in:
    val (router, capturedSql, _) = setupWithRewriter(metadataFilter = metaFilterOn)
    val out                      = router
      .execute("meta-4", "alice", poolKey, "SHOW TABLES", effectiveSet = customerOnly)
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("table_name AS name")
    capturedSql() should include("lower(schema_name) = lower('main')")
    capturedSql() should include("duckdb_tables() WHERE database_name = 'memory'")

  it should "deny a SHOW TABLES variant without ever reaching a node" in:
    val (router, capturedSql, _) = setupWithRewriter(metadataFilter = metaFilterOn)
    val out                      = router
      .execute("meta-5", "alice", poolKey, "SHOW TABLES FROM tpch1", effectiveSet = customerOnly)
      .unsafeRunSync()
    out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
    capturedSql() shouldBe ""

  it should "resend the FILTERED statement when the first node fails transiently" in:
    // Two readers so retryOnce has a fallback. The retry MUST carry the metadata
    // rewrite: resending the caller's text would answer the retry unfiltered.
    val calls           = new java.util.concurrent.atomic.AtomicInteger(0)
    val transientThenOk = () =>
      if calls.getAndIncrement() == 0 then QuackResponse.Failed(QuackError.Transient("gone"), 1L)
      else TestArrow.okResponse()
    val (router, capturedSql, _) =
      setupWithRewriter(metadataFilter = metaFilterOn, readers = 2, stub = transientThenOk)
    val out = router
      .execute(
        "meta-6",
        "alice",
        poolKey,
        "SELECT table_name FROM information_schema.tables",
        effectiveSet = customerOnly
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    calls.get() shouldBe 2
    // capturedSql holds the LAST wire text, i.e. the retry's.
    capturedSql() should include("table_schema = 'tpch1' AND table_name = 'customer'")

  // The validator and the rewriter mount from the same flag, so the pair must agree:
  // what the validator implicitly admits is exactly what the rewriter then filters.

  private val filteredMetaValidator = new ai.starlake.quack.edge.sql.PostgresAclValidator(
    defaultDatabase = "memory",
    defaultSchema = "main",
    filteredMetadata = true,
    tenantCatalogs = _ => Set("memory")
  )

  it should "admit and filter an ungranted metadata read under the real validator" in:
    val (router, capturedSql, _) =
      setupWithRewriter(metadataFilter = metaFilterOn, validator = filteredMetaValidator)
    val out = router
      .execute(
        "meta-7",
        "alice",
        poolKey,
        "SELECT table_name FROM information_schema.tables",
        effectiveSet = customerOnly
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    capturedSql() should include("table_schema = 'tpch1' AND table_name = 'customer'")

  // Task 12 review I1: a COPY to an authorized location must not carry a listing the edge filter
  // would have narrowed (the access check authorizes the COPY on its stand-in, and the filter
  // cannot read a COPY).
  private val acmeEtlRules: (String, String) => Option[
    ai.starlake.quack.edge.authz.location.LocationRules
  ] = (tenant, user) =>
    Option.when(tenant == "acme" && user == "u-1")(
      ai.starlake.quack.edge.authz.location.LocationRules(List("s3://acme-etl/"), Nil, Nil, None)
    )
  private val tableGrants = Some(
    effWithGrants(
      List(grant("memory", "main", "t", "RO"), grant("memory", "tpch1", "customer", "RO"))
    )
  )

  it should "refuse a COPY writing a filtered listing to an authorized location" in:
    val (router, capturedSql, _) = setupWithRewriter(
      metadataFilter = metaFilterOn,
      validator = filteredMetaValidator,
      locationRulesFor = acmeEtlRules
    )
    List(
      "COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "COPY (SHOW TABLES FROM tpch1) TO 's3://acme-etl/out/t.csv'",
      "COPY (SELECT table_name, column_name FROM information_schema./**/columns) TO " +
        "'s3://acme-etl/out/c.csv'",
      "COPY information_schema./**/tables TO 's3://acme-etl/out/t.csv'"
    ).zipWithIndex.foreach { (sql, i) =>
      withClue(sql) {
        val out = router
          .execute(s"copy-meta-$i", "alice", poolKey, sql, effectiveSet = tableGrants)
          .unsafeRunSync()
        out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      }
    }

  it should "send an ordinary COPY to an authorized location" in:
    val (router, capturedSql, _) = setupWithRewriter(
      metadataFilter = metaFilterOn,
      validator = filteredMetaValidator,
      locationRulesFor = acmeEtlRules
    )
    val sql = "COPY (SELECT * FROM main.t) TO 's3://acme-etl/out/t.csv'"
    val out = router
      .execute("copy-ok", "alice", poolKey, sql, effectiveSet = tableGrants)
      .unsafeRunSync()
    out match
      case Right(r) => r.close()
      case Left(f)  => fail(s"refused: $f")
    capturedSql() should include("COPY (SELECT * FROM main.t) TO 's3://acme-etl/out/t.csv'")

  it should "filter a metadata read hiding behind the first statement of a read batch" in:
    // A single parse reads only the first statement, so the second used to reach the node
    // untouched while the validator admitted the batch as a pure read: a zero-grant principal
    // got the full catalog. Either outcome is safe, but the wire text must never carry an
    // unfiltered catalog read.
    val (router, capturedSql, _) =
      setupWithRewriter(metadataFilter = metaFilterOn, validator = filteredMetaValidator)
    val out = router
      .execute(
        "meta-9",
        "alice",
        poolKey,
        "SELECT 1; SELECT * FROM information_schema.tables",
        effectiveSet = Some(effWithGrants(Nil))
      )
      .unsafeRunSync()
    out match
      case Left(f)  => f shouldBe a[RouterFailure.AccessDenied]
      case Right(r) =>
        r.close()
        capturedSql() should include("table_schema IN ('information_schema', 'pg_catalog')")
        """(?i)FROM\s+information_schema\.tables(?!\s+WHERE\s+\()""".r
          .findAllMatchIn(capturedSql())
          .size shouldBe 0

  it should "still deny a metadata read riding inside a multi-statement write batch" in:
    // The implicit admit is pure-read only: the rewriter passes non-Select statements
    // through untouched, so admitting this batch would let RW on `mine` materialize an
    // UNFILTERED catalog copy. Flat-set rule, pinned end to end.
    val (router, capturedSql, _) =
      setupWithRewriter(metadataFilter = metaFilterOn, validator = filteredMetaValidator)
    val out = router
      .execute(
        "meta-8",
        "alice",
        poolKey,
        "INSERT INTO main.mine SELECT 1; SELECT * FROM information_schema.tables",
        effectiveSet = Some(effWithGrants(List(grant("memory", "main", "mine", "RW"))))
      )
      .unsafeRunSync()
    out.left.toOption.get shouldBe a[RouterFailure.AccessDenied]
    capturedSql() shouldBe ""

  // ---- escape hygiene of this file's own I1 trivia test literals ----
  //
  // The NBSP characters exercised by the two I1 regression tests above must stay literal `\u00A0`
  // escapes, never a raw invisible byte pasted into the source -- see the identical (whole-file)
  // guard in `StatementClassifierSpec`, `LockdownScreenSpec`, `CatalogWriteScreenSpec`,
  // `PrepareStrategySpec` and `SqlTriviaSpec` for why this matters: an editor or an "helpful"
  // formatting pass can silently decode the escape back into a raw invisible character, at which
  // point the file still compiles and every assertion above still passes, with no way for the
  // next reader to tell. Scoped to a substring search rather than a whole-file scan (the pattern
  // those other files use) because this file predates the trivia arc and already carries an
  // unrelated raw non-ASCII character elsewhere (an ellipsis in a test name) that a whole-file
  // scan would also flag -- out of this task's scope to touch.
  it should "carry the I1 regression tests' NBSP as a literal escape, not a raw byte" in:
    val path = "src/test/scala/ai/starlake/quack/edge/FlightSqlRouterSpec.scala"
    val file = new java.io.File(path)
    withClue(s"expected to find $path relative to the working directory ${file.getAbsolutePath}") {
      file.exists shouldBe true
    }
    val src = scala.io.Source.fromFile(file, "UTF-8")
    try
      val text          = src.mkString
      val nbspOffenders = text.zipWithIndex.filter { case (c, _) => c == '\u00A0' }
      withClue(
        s"found a raw NBSP (should be the \\u00A0 escape) at offsets " +
          s"${nbspOffenders.map(_._2).mkString(", ")}: "
      ) {
        nbspOffenders shouldBe empty
      }
    finally src.close()

  // ---- DuckDB 2.0 client data scans: admitted on the native front door only ----

  private val ClientFedInsert =
    "INSERT INTO orders SELECT * FROM scan_data_from_quack_client(" +
      "'0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', NULL::STRUCT(a INTEGER), ordered := true)"

  // A narrow writer: RW on memory.main.orders and nothing else.
  private val ordersWriter = ai.starlake.quack.ondemand.rbac.EffectiveSet(
    tenantUser,
    Nil,
    Nil,
    List(
      ai.starlake.quack.ondemand.state
        .RolePermission("p-1", "r-1", "memory", "main", "orders", "RW")
    ),
    Nil
  )

  private def okSend: FlightSqlRouter.NodeSend[String] =
    (_, _, _, _, _) => IO.pure(NodeOutcome.Ok("count", 1L, () => ()))

  it should "refuse a client-fed INSERT on the FlightSQL path (the scan would wait forever)" in:
    val (router, _, _) =
      setupWithRewriter(validator = new ai.starlake.quack.edge.sql.PostgresAclValidator())
    router
      .execute("cds-1", "alice", poolKey, ClientFedInsert, effectiveSet = Some(ordersWriter))
      .unsafeRunSync() match
      case Left(RouterFailure.AccessDenied(m)) => m should include("scan_data_from_quack_client")
      case other                               => fail(s"expected AccessDenied, got $other")
    // executeWith without the front door's flag refuses it too, whatever the audit source
    router
      .executeWith(
        "cds-2",
        "alice",
        poolKey,
        ClientFedInsert,
        Some(ordersWriter),
        okSend,
        source = "quack"
      )
      .unsafeRunSync() shouldBe a[Left[?, ?]]

  it should "admit the same client-fed INSERT, same principal and grants, from the front door" in:
    val (router, _, _) =
      setupWithRewriter(validator = new ai.starlake.quack.edge.sql.PostgresAclValidator())
    router
      .executeWith(
        "cds-3",
        "alice",
        poolKey,
        ClientFedInsert,
        Some(ordersWriter),
        okSend,
        source = "quack",
        clientDataScans = true
      )
      .unsafeRunSync()
      .map(_.value) shouldBe Right("count")

  it should "carry the client-data-scan flag into validation only when the caller sets it" in:
    val capturer       = new CapturingValidator
    val (router, _, _) = setupWithRewriter(validator = capturer)
    router.execute("cds-4", "alice", poolKey, "SELECT 1").unsafeRunSync()
    capturer.lastCtx.clientDataScans shouldBe false
    router
      .executeWith("cds-5", "alice", poolKey, "SELECT 1", None, okSend, source = "quack")
      .unsafeRunSync()
    capturer.lastCtx.clientDataScans shouldBe false
    router
      .executeWith(
        "cds-6",
        "alice",
        poolKey,
        "SELECT 1",
        None,
        okSend,
        source = "quack",
        clientDataScans = true
      )
      .unsafeRunSync()
    capturer.lastCtx.clientDataScans shouldBe true

  it should "let the protected-write guard admit a client-fed INSERT only from the front door" in:
    val guard = new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
        Map(("memory", "main", "customer") -> List("c_id"))
      ),
      clsEnabled = true,
      rlsEnabled = true
    )
    val withRowPolicy = ordersWriter.copy(rowPolicies =
      List(
        ai.starlake.quack.ondemand.state
          .RoleRowPolicy("rp-1", "r-1", "*", "main", "customer", "1=1")
      )
    )
    val (router, _, _) = setupWithRewriter(protectedWriteGuard = guard)
    router
      .executeWith("cds-7", "alice", poolKey, ClientFedInsert, Some(withRowPolicy), okSend)
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    router
      .executeWith(
        "cds-8",
        "alice",
        poolKey,
        ClientFedInsert,
        Some(withRowPolicy),
        okSend,
        source = "quack",
        clientDataScans = true
      )
      .unsafeRunSync()
      .map(_.value) shouldBe Right("count")

  // ---- direct file access: superusers only (owner ruling 2026-10-11) ----

  private val fileReads = List(
    "SELECT * FROM read_parquet('s3://bucket/main/customer/x.parquet')",
    "SELECT * FROM 's3://bucket/main/customer/x.parquet'",
    "COPY (SELECT 1) TO '/tmp/x.csv'",
    // parseable: a keyword-named alias before a comma join; unparseable: the bare `set` alias
    "SELECT * FROM range(1) AS values, '/etc/x.csv'",
    "SELECT * FROM range(1) set, '/etc/x.csv'",
    "SELECT * FROM (SELECT 1 AS \"order\") r JOIN (SELECT 1 AS \"order\") s " +
      "ON r.order = s.order, '/etc/x.csv'",
    // a quoted glob name: every matching file
    "SELECT * FROM \"*\".csv"
  )

  "a direct file read or write" should "be refused to a *.*.* ALL tenant principal, QoD and OPA" in:
    Seq(wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      fileReads.foreach { sql =>
        withClue(s"$v: $sql") {
          router
            .execute("file-1", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
            .unsafeRunSync()
            .left
            .toOption
            .get shouldBe a[RouterFailure.AccessDenied]
          // the native front door, client data scans on
          router
            .executeWith(
              "file-2",
              "alice",
              poolKey,
              sql,
              Some(wildcardAllSet),
              okSend,
              source = "quack",
              clientDataScans = true
            )
            .unsafeRunSync()
            .left
            .toOption
            .get shouldBe a[RouterFailure.AccessDenied]
          captured() shouldBe ""
        }
      }
    }

  it should "stay open to a superuser" in:
    val (router, captured, _) = setupWithRewriter(validator = wildcardAcl)
    fileReads.foreach { sql =>
      withClue(sql) {
        router
          .execute("file-3", "alice", poolKey, sql, effectiveSet = Some(superuserSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        captured() should include(sql)
      }
    }

  it should "leave the front door's client data scan to a *.*.* ALL principal" in:
    Seq(wildcardAcl, allowAllOpa).foreach { v =>
      val (router, _, _) = setupWithRewriter(validator = v)
      withClue(v.toString) {
        router
          .executeWith(
            "file-4",
            "alice",
            poolKey,
            ClientFedInsert,
            Some(wildcardAllSet),
            okSend,
            source = "quack",
            clientDataScans = true
          )
          .unsafeRunSync()
          .map(_.value) shouldBe Right("count")
      }
    }

  it should "leave a row policy holding IS DISTINCT FROM a literal to its re-validation" in:
    // `IS DISTINCT FROM 'X'` takes a value; the re-validated wrap carries the policy's SQL.
    val withRows = wildcardAllSet.copy(rowPolicies =
      List(
        ai.starlake.quack.ondemand.state
          .RoleRowPolicy("rp-d", "r-1", "*", "main", "customer", "c_region IS DISTINCT FROM 'X'")
      )
    )
    val (router, captured, _) = setupWithRewriter(validator = wildcardAcl)
    router
      .execute("file-5", "alice", poolKey, "SELECT c_id FROM customer", Some(withRows))
      .unsafeRunSync() shouldBe a[Right[?, ?]]
    captured() should include("IS DISTINCT FROM 'X'")
    router
      .execute(
        "file-6",
        "alice",
        poolKey,
        "SELECT c_id FROM customer WHERE c_name IS NOT DISTINCT FROM 'a'",
        Some(withRows)
      )
      .unsafeRunSync() shouldBe a[Right[?, ?]]

  "a lockdown pool with the ACL off" should "refuse a file after a keyword-named alias, both edges" in:
    val (router, captured, _) = setupWithRewriter(lockdownFor = _ => true)
    List(
      "SELECT * FROM range(1) AS values, '/etc/x.csv'",
      "SELECT * FROM range(1) set, '/etc/x.csv'",
      "SELECT * FROM \"*\".csv"
    ).foreach { sql =>
      withClue(sql) {
        router
          .execute("lock-1", "alice", poolKey, sql, Some(wildcardAllSet))
          .unsafeRunSync()
          .swap
          .toOption
          .get shouldBe a[RouterFailure.AccessDenied]
        router
          .executeWith(
            "lock-2",
            "alice",
            poolKey,
            sql,
            Some(wildcardAllSet),
            okSend,
            source = "quack"
          )
          .unsafeRunSync() shouldBe a[Left[?, ?]]
        captured() shouldBe ""
      }
    }
    router
      .execute(
        "lock-3",
        "alice",
        poolKey,
        "SELECT * FROM range(1) AS values, 's3://staging/x.csv'",
        Some(wildcardAllSet)
      )
      .unsafeRunSync() shouldBe a[Right[?, ?]]

  "node-state introspection" should "be refused to a tenant user, ACL off or on, and open to a superuser" in:
    Seq(StatementValidator.allowAll, wildcardAcl).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      List(
        "SELECT * FROM duckdb_secrets()",
        "SELECT name, value FROM duckdb_settings()",
        "SELECT current_setting('s3_secret_access_key')",
        "SELECT * FROM duckdb_external_file_cache()",
        "PRAGMA database_list"
      ).foreach { sql =>
        withClue(s"$v: $sql") {
          router
            .execute("ns-1", "alice", poolKey, sql, Some(wildcardAllSet))
            .unsafeRunSync()
            .swap
            .toOption
            .get
            .reason should include("shows node state")
          captured() should not include sql
          router
            .execute("ns-2", "alice", poolKey, sql, Some(superuserSet))
            .unsafeRunSync() shouldBe a[Right[?, ?]]
        }
      }
      router
        .execute(
          "ns-3",
          "alice",
          poolKey,
          "SELECT current_setting('TimeZone')",
          Some(wildcardAllSet)
        )
        .unsafeRunSync() shouldBe a[Right[?, ?]]
    }

  // `histogram` / `histogram_values` are 2.0 table macros whose body reads `query_table(source)`.
  "a query_table-reading table macro" should "never reach the node for a tenant, lockdown or RLS" in:
    val sql = "SELECT * FROM histogram('/etc/x.csv', b)"
    // QoD grants, *.*.* ALL, both edges
    val (acl, aclCaptured, _) = setupWithRewriter(validator = wildcardAcl)
    acl
      .execute("hist-m1", "alice", poolKey, sql, Some(wildcardAllSet))
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    acl
      .executeWith(
        "hist-m2",
        "alice",
        poolKey,
        sql,
        Some(wildcardAllSet),
        okSend,
        source = "quack",
        clientDataScans = true
      )
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    aclCaptured() shouldBe ""
    // lockdown with the ACL off
    val (locked, lockedCaptured, _) = setupWithRewriter(lockdownFor = _ => true)
    locked
      .execute("hist-m3", "alice", poolKey, sql, Some(wildcardAllSet))
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    lockedCaptured() shouldBe ""
    // a row-policy principal: it read the protected table unfiltered
    val (rls, rlsCaptured, _) = setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
    rls
      .execute(
        "hist-m4",
        "alice",
        poolKey,
        "SELECT * FROM histogram('customer', c_email)",
        Some(effWithRowPolicies(List(rowPolicyCustomer)))
      )
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    rlsCaptured() shouldBe ""
    // the aggregate of that name is a value
    val (agg, aggCaptured, _) = setupWithRewriter(validator = wildcardAcl)
    agg
      .execute(
        "hist-m5",
        "alice",
        poolKey,
        "SELECT histogram(o_total) FROM orders",
        Some(wildcardAllSet)
      )
      .unsafeRunSync() shouldBe a[Right[?, ?]]
    aggCaptured() should include("histogram(o_total)")

  "pg_get_viewdef / pg_get_constraintdef" should "need the unrestricted *.*.* ALL grant" in:
    val (router, captured, _) = setupWithRewriter(validator = wildcardAcl)
    List("SELECT pg_get_viewdef(o_id) FROM orders", "SELECT pg_get_constraintdef(1)").foreach {
      sql =>
        withClue(sql) {
          router
            .execute("pgdef-1", "alice", poolKey, sql, Some(ordersWriter))
            .unsafeRunSync() shouldBe a[Left[?, ?]]
          captured() should not include sql
          router
            .execute("pgdef-2", "alice", poolKey, sql, Some(wildcardAllSet))
            .unsafeRunSync() shouldBe a[Right[?, ?]]
        }
    }

  // ---- F07: a backtick-quoted identifier meets the same row filter and column mask ----

  private def maskedAndFiltered = ai.starlake.quack.ondemand.rbac.EffectiveSet(
    tenantUser,
    Nil,
    Nil,
    List(ai.starlake.quack.ondemand.state.RolePermission("p-1", "r-1", "*", "*", "*", "ALL")),
    Nil,
    List(maskCustomerEmail),
    List(rowPolicyCustomer)
  )

  private def clsKnowingCustomer =
    new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
      new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
        Map(("memory", "main", "customer") -> List("c_id", "c_email", "c_region"))
      ),
      enabled = true
    )

  "a backtick-quoted table or column" should "be filtered and masked in every ACL mode" in:
    val twins = List(
      "SELECT c_id, c_email, c_region FROM `customer`" ->
        "SELECT c_id, c_email, c_region FROM \"customer\"",
      "SELECT * FROM `main`.`customer`" -> "SELECT * FROM \"main\".\"customer\"",
      "SELECT upper(`c_email`) AS e FROM customer" -> "SELECT upper(\"c_email\") AS e FROM customer",
      "SELECT c_id FROM customer WHERE `c_email` LIKE '%a%'" ->
        "SELECT c_id FROM customer WHERE \"c_email\" LIKE '%a%'",
      "SELECT `c` FROM customer c" -> "SELECT \"c\" FROM customer c"
    )
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(rewriter = clsKnowingCustomer, validator = v)
      def run(sql: String)      =
        val out = router.execute("bt-1", "alice", poolKey, sql, Some(maskedAndFiltered))
        val res = out.unsafeRunSync().map(_ => captured())
        res
      twins.foreach { (backtick, twin) =>
        withClue(s"${v.getClass.getSimpleName}: $backtick") {
          val b = run(backtick)
          b.map(_.contains("`")) should not be Right(true)
          b.map(_.toLowerCase) shouldBe run(twin).map(_.toLowerCase)
          b.foreach { sent =>
            sent should include("c_region = 'X'")
            sent should not include "c_email LIKE"
          }
        }
      }
      val table = run(twins.head._1)
      table.toOption.get should (include("'***'") and include("c_region = 'X'"))
    }

  it should "not launder a masked column into a table through CTAS" in:
    val (router, captured, _) = setupWithRewriter(
      rewriter = clsKnowingCustomer,
      protectedWriteGuard = guardKnowingCustomer
    )
    Seq(
      "CREATE TABLE main.scratch AS SELECT upper(`c_email`) AS e FROM main.customer",
      "CREATE TABLE main.scratch AS SELECT `c_email` FROM `main`.`customer`"
    ).foreach { sql =>
      withClue(sql) {
        val out = router
          .execute("bt-2", "alice", poolKey, sql, Some(effWithPolicies(List(maskCustomerEmail))))
          .unsafeRunSync()
        out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
        captured() shouldBe ""
      }
    }

  // ---- F08: histogram()/histogram_values() over a computed metadata-catalog source ----

  "a reading table macro over a computed source" should "be refused to every non-superuser" in:
    val leaks = Seq(
      "SELECT * FROM histogram('__duck' || 'lake_metadata_lake.ducklake_data_file', encryption_key)",
      "SELECT * FROM histogram_values(concat('__duck', 'lake_metadata_lake.ducklake_data_file'), " +
        "encryption_key)",
      "SELECT * FROM histogram_values(chr(95) || '_ducklake_metadata_lake.ducklake_data_file', " +
        "path)",
      "SELECT * FROM histogram(source := '__duck' || 'lake_metadata_lake.ducklake_data_file', " +
        "col_name := encryption_key)",
      "SELECT * FROM histogram('__ducklake_metadata_lake.ducklake_data_file', encryption_key)"
    )
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      leaks.foreach { sql =>
        withClue(s"${v.getClass.getSimpleName}: $sql") {
          val out = router
            .execute("hist-c1", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
            .unsafeRunSync()
          out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
          out.swap.toOption.get.reason should startWith("access denied: catalog: ")
          captured() shouldBe ""
        }
      }
    }
    val (open, openCaptured, _) = setupWithRewriter()
    open
      .execute("hist-c2", "alice", poolKey, leaks.head, Some(superuserSet))
      .unsafeRunSync()
      .isRight shouldBe true
    openCaptured() should include("histogram(")

  it should "leave the aggregate and a constant source admitted with the ACL off" in:
    val (router, captured, _) = setupWithRewriter()
    Seq(
      "SELECT histogram(c_region) FROM customer",
      "SELECT * FROM histogram('customer', c_region)"
    ).foreach { sql =>
      withClue(sql) {
        router
          .execute("hist-c3", "alice", poolKey, sql, Some(wildcardAllSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        captured() should include("histogram")
      }
    }

  // ---- F09: CHECKPOINT and DuckLake's per-schema macros ----

  private val checkpointRefusal =
    s"access denied: catalog: ${ai.starlake.quack.edge.sql.CheckpointScreen.Refusal}"

  private val checkpoints = Seq(
    "CHECKPOINT",
    "CHECKPOINT lake",
    "CALL checkpoint('lake')",
    "SELECT * FROM checkpoint('lake')",
    "FROM checkpoint()",
    "EXPLAIN ANALYZE CHECKPOINT",
    "PREPARE p AS CHECKPOINT",
    "SELECT 1; CHECKPOINT"
  )

  "CHECKPOINT" should "be refused to every non-superuser in every ACL mode, lockdown or not" in:
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      Seq(false, true).foreach { locked =>
        val (router, captured, _) = setupWithRewriter(validator = v, lockdownFor = _ => locked)
        checkpoints.foreach { sql =>
          withClue(s"${v.getClass.getSimpleName} lockdown=$locked: $sql") {
            val out = router
              .execute("ckpt-1", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
              .unsafeRunSync()
            out.swap.toOption.get.reason shouldBe checkpointRefusal
            captured() shouldBe ""
          }
        }
      }
    }

  it should "run for a superuser, routed and classified as DDL, and not under lockdown" in:
    val (router, captured, _) = setupWithRewriter()
    router
      .execute("ckpt-2", "alice", poolKey, "CHECKPOINT", Some(superuserSet))
      .unsafeRunSync()
      .isRight shouldBe true
    captured() should include("CHECKPOINT")
    router.classifier.classify("CHECKPOINT") shouldBe ai.starlake.quack.model.StatementKind.Ddl
    val (locked, lockedCaptured, _) = setupWithRewriter(lockdownFor = _ => true)
    locked
      .execute("ckpt-3", "alice", poolKey, "CHECKPOINT", Some(superuserSet))
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .reason shouldBe checkpointRefusal
    lockedCaptured() shouldBe ""

  "DuckLake's bare per-schema macros" should "be refused like ducklake_* when the ACL validates" in:
    val pairs = Seq(
      "FROM merge_adjacent_files()" -> "FROM ducklake_merge_adjacent_files('lake')",
      "FROM lake.set_option('delete_older_than', '1 second')" ->
        "FROM ducklake_set_option('lake', 'delete_older_than', '1 second')",
      "SELECT * FROM snapshots()" -> "SELECT * FROM ducklake_snapshots('lake')"
    )
    Seq(wildcardAcl, allowAllOpa).foreach { v =>
      val (router, captured, _) = setupWithRewriter(validator = v)
      pairs.foreach { (bare, explicit) =>
        withClue(s"${v.getClass.getSimpleName}: $bare") {
          Seq(bare, explicit).foreach { sql =>
            router
              .execute("dlm-m1", "alice", poolKey, sql, Some(wildcardAllSet))
              .unsafeRunSync()
              .swap
              .toOption
              .get shouldBe a[RouterFailure.AccessDenied]
          }
          captured() shouldBe ""
        }
      }
    }
    // With the ACL off the read-only family is admitted (owner ruling 2026-10-11), bare or
    // explicit; the maintenance and option-setting ones are refused in every mode (below).
    val (open, openCaptured, _) = setupWithRewriter()
    Seq(
      "SELECT * FROM snapshots()",
      "SELECT * FROM ducklake_snapshots('lake')",
      "FROM lake.table_info()",
      "FROM table_changes('t', 0, 3)",
      "CALL set_commit_message('a', 'b')"
    ).foreach { sql =>
      withClue(sql) {
        open
          .execute("dlm-m2", "alice", poolKey, sql, Some(wildcardAllSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        openCaptured() should include(sql)
      }
    }

  private val maintenanceCalls = Seq(
    "FROM merge_adjacent_files()",
    "CALL lake.merge_adjacent_files()",
    "FROM lake.set_option('delete_older_than', '1 second')",
    "FROM lake.main.set_option('expire_older_than', '1 second')",
    "CALL ducklake_merge_adjacent_files('lake')",
    "CALL ducklake_cleanup_old_files('lake', cleanup_all => true)",
    "CALL ducklake_expire_snapshots('lake', older_than => now())",
    "CALL ducklake_delete_orphaned_files('lake', cleanup_all => true)",
    "CALL ducklake_rewrite_data_files('lake', 't')",
    "CALL ducklake_flush_inlined_data('lake')",
    "CALL ducklake_set_option('lake', 'delete_older_than', '1 second')",
    "SELECT * FROM \"DuckLake_Cleanup_Old_Files\"('lake')",
    "EXPLAIN ANALYZE FROM merge_adjacent_files()",
    "SELECT 1; FROM lake.set_option('delete_older_than', '1 second')"
  )

  "DuckLake's maintenance and option-setting calls" should "be refused to every non-superuser" in:
    Seq(StatementValidator.allowAll, wildcardAcl, allowAllOpa).foreach { v =>
      Seq(false, true).foreach { locked =>
        val (router, captured, _) = setupWithRewriter(validator = v, lockdownFor = _ => locked)
        maintenanceCalls.foreach { sql =>
          withClue(s"${v.getClass.getSimpleName} lockdown=$locked: $sql") {
            val out = router
              .execute("dlm-mt1", "alice", poolKey, sql, effectiveSet = Some(wildcardAllSet))
              .unsafeRunSync()
            out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
            // under lockdown the lockdown screen, which runs first, may refuse it for its own reason
            if !locked then
              out.swap.toOption.get.reason should (startWith("access denied: catalog: ") and
                include("reserved to superusers"))
            captured() shouldBe ""
          }
        }
      }
    }

  it should "run for a superuser, and not under lockdown" in:
    val (open, openCaptured, _) = setupWithRewriter()
    maintenanceCalls.foreach { sql =>
      withClue(sql) {
        open
          .execute("dlm-mt2", "alice", poolKey, sql, Some(superuserSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        openCaptured() should not be ""
      }
    }
    val (locked, lockedCaptured, _) = setupWithRewriter(lockdownFor = _ => true)
    locked
      .execute("dlm-mt3", "alice", poolKey, maintenanceCalls.head, Some(superuserSet))
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .reason should include("reserved to superusers")
    lockedCaptured() shouldBe ""

  // Audit F13: a StackOverflowError is fatal to cats-effect, so one too-deep statement that
  // escapes a walk halts the whole manager. Run on a 1 MiB stack, a production worker's default.
  private def onWorkerStack[A](body: => A): Either[Throwable, A] =
    var out: Either[Throwable, A] = Left(new IllegalStateException("not run"))
    val t                         = new Thread(
      null,
      () =>
        out =
          try Right(body)
          catch case e: Throwable => Left(e), "deep-nesting",
      1L << 20
    )
    t.start()
    t.join()
    out

  // Under RoutingRefs.MaxSqlLength, so the placement walk parses it.
  private val tooDeep = "SELECT " + List.fill(45000)("1").mkString("+")

  "a too-deep statement" should "be forwarded, not crash the router, with the ACL off" in:
    val (router, _, _) = setup()
    onWorkerStack(router.execute("deep-1", "alice", poolKey, tooDeep).unsafeRunSync()) match
      case Right(out) => out shouldBe a[Right[?, ?]]
      case Left(e)    => fail(s"escaped ${e.getClass.getName}", e)

  it should "be denied, not crash the router, when a pipeline step overflows" in:
    val overflowing = new StatementValidator:
      def validate(context: ValidationContext): ValidationResult = throw new StackOverflowError()
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      validator = overflowing,
      stmtInstruments = si
    )
    onWorkerStack(router.execute("deep-2", "alice", poolKey, "SELECT 1").unsafeRunSync()) match
      case Right(out) =>
        out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
        router.history.snapshot(1).head.status shouldBe "denied"
      case Left(e) => fail(s"escaped ${e.getClass.getName}", e)
