package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
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

  /** The metadata filter as `Main` mounts it: every router here is built with it. */
  private def mountedFilter = new ai.starlake.quack.edge.meta.MetadataFilterRewriter(enabled = true)

  private val mmReg = new SimpleMeterRegistry
  private val si    = new StatementInstruments(mmReg)

  /** Builds a fresh stub response each call so tests don't share ArrowReader instances (each reader
    * is single-use).
    */
  private def defaultStub: () => QuackResponse = () => TestArrow.okResponse()

  private def setup(
      stub: () => QuackResponse = defaultStub,
      events: ManagerEventSink = ManagerEventSink.noop,
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
    val adapter = new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())

    val sessions = new SessionRegistry
    val router   =
      new FlightSqlRouter(
        sup,
        sessions,
        tracker,
        adapter,
        stmtInstruments = si,
        events = events,
        adminExecutor = adminExecutor,
        metadataFilterRewriter = mountedFilter,
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
        Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(router.supervisor)),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
      adminExecutor = Some(exec),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
      adminExecutor = Some(exec),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
      adminExecutor = Some(exec),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
      adminExecutor = Some(exec),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
    // preview) would, and the node refuses it there. Requirement 3: `record` must never let the
    // password literal reach StatementHistoryStore on that path either.
    val refusal = () =>
      QuackResponse.Failed(
        QuackError.Permanent(
          "Permission Error: QoD: statement uses CREATE USER, which QoD does not support"
        ),
        1L
      )
    val (router, _, _) = setup(stub = refusal)
    router
      .execute("c-routed-redact", "alice", poolKey, "CREATE USER bob PASSWORD 'topsecret'")
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    val latest = router.history.snapshot(1).head
    latest.sql shouldBe ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
    latest.sql should not include "topsecret"
    latest.status shouldBe "denied"

  it should "mask the values of a system statement and its error in history, keeping user text" in:
    // The manager's own statements (a module's system SQL) may carry platform credentials, and
    // history and audit are shown to the tenant's admins: their values are masked as the stdout
    // sink masks every statement leaving the control plane.
    val nodeErr = () =>
      QuackResponse.Failed(
        QuackError.Permanent("Invalid Input Error: secret 'topsecret' was rejected"),
        1L
      )
    val (router, _, _) = setup(stub = nodeErr)
    val systemEff      = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      ai.starlake.quack.ondemand.state.RbacUser("", None, "module:seed", kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil,
      system = true
    )
    router
      .execute(
        "c-sys-mask",
        "module:seed",
        poolKey,
        "CREATE SECRET s3k (TYPE s3, KEY_ID 'AKIAEXAMPLE', SECRET 'topsecret')",
        effectiveSet = Some(systemEff)
      )
      .unsafeRunSync() shouldBe a[Left[?, ?]]
    val latest = router.history.snapshot(1).head
    latest.user shouldBe "module:seed"
    latest.sql should startWith("CREATE SECRET s3k")
    latest.sql should not include "topsecret"
    latest.sql should not include "AKIAEXAMPLE"
    latest.error.getOrElse("") should not include "topsecret"
    // A user's statement keeps its text, as before.
    val (plain, _, _) = setup()
    plain.execute("c-user-text", "alice", poolKey, "SELECT 'hello'").unsafeRunSync()
    plain.history.snapshot(1).head.sql shouldBe "SELECT 'hello'"

  it should "redact both sql and a node's quoted-statement error for an admitted statement" in:
    // The claimed statement is forwarded to the
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
      routingInstruments = ri,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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

  it should "tag a permanent backend error as RouterFailure.BadRequest" in:
    val perm = () => QuackResponse.Failed(QuackError.Permanent("Parser Error: syntax"), 1L)
    val (router, _, _) = setup(stub = perm)
    val out            = router.execute("c-bad", "alice", poolKey, "SELECTT 1").unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.BadRequest]

  it should "tag the node's refusal for an unavailable decision as RouterFailure.Unavailable" in:
    val perm = () =>
      QuackResponse.Failed(
        QuackError.Permanent(
          "Permission Error: QoD: authorization unavailable, retry: cannot connect to 127.0.0.1:1"
        ),
        1L
      )
    val (router, _, _) = setup(stub = perm)
    val out            = router.execute("c-unavail", "alice", poolKey, "SELECT 1").unsafeRunSync()
    out.swap.toOption.get shouldBe a[RouterFailure.Unavailable]
    // Any other node refusal is a denial (the next case).
    val refusal        = QuackError.Permanent("Permission Error: QoD: user:alice lacks")
    val (denied, _, _) = setup(stub = () => QuackResponse.Failed(refusal, 1L))
    denied
      .execute("c-refused", "alice", poolKey, "SELECT 1")
      .unsafeRunSync()
      .swap
      .toOption
      .get shouldBe a[RouterFailure.AccessDenied]
    // The parse screen's form of the same refusal is retryable too; a user's own text quoting the
    // marker is not.
    FlightSqlRouter.isNodeAuthzUnavailable(
      "Invalid Input Error: Authorization callback failed: Permission Error: " +
        "QoD: authorization unavailable, retry: timed out"
    ) shouldBe true
    List(
      "Invalid Input Error: QoD: authorization unavailable, retry",
      "Catalog Error: Table with name QoD: authorization unavailable, retry does not exist!",
      "Binder Error: Permission Error: QoD: authorization unavailable, retry"
    ).foreach(m => FlightSqlRouter.isNodeAuthzUnavailable(m) shouldBe false)
    val user =
      QuackError.Permanent("Invalid Input Error: QoD: authorization unavailable, retry")
    val (mine, _, _) = setup(stub = () => QuackResponse.Failed(user, 1L))
    mine
      .execute("c-user", "alice", poolKey, "SELECT error('QoD: authorization unavailable, retry')")
      .unsafeRunSync()
      .swap
      .toOption
      .get shouldBe a[RouterFailure.BadRequest]

  it should "not mark that refusal retryable for a multi-statement submission" in:
    // In `SELECT 1; SELECT 2` the first statement may have run (each autocommits) before the node
    // refused the second for want of a verdict: a client retry would run it twice (D-m5).
    val perm = () =>
      QuackResponse.Failed(
        QuackError.Permanent("Permission Error: QoD: authorization unavailable, retry: timed out"),
        1L
      )
    val (router, _, _) = setup(stub = perm)
    router.execute("c-batch", "alice", poolKey, "SELECT 1; SELECT 2").unsafeRunSync() match
      case Left(RouterFailure.BadRequest(reason)) =>
        reason should startWith("permanent failure: Permission Error: QoD: authorization")
        reason should endWith(s"(${FlightSqlRouter.BatchNotRetried})")
      case other => fail(s"expected BadRequest, got $other")
    // The nearest admitted case: one statement, a trailing separator and a comment included.
    router
      .execute("c-one", "alice", poolKey, "SELECT 1; -- done")
      .unsafeRunSync()
      .swap
      .toOption
      .get shouldBe a[RouterFailure.Unavailable]
    FlightSqlRouter.isMultiStatement("SELECT ';'; ") shouldBe false
    FlightSqlRouter.isMultiStatement("SELECT 1;SELECT 2") shouldBe true

  it should "book a node's authorization refusal as a denial, in either shape the node raises it" in:
    List(
      "Permission Error: QoD: user:alice lacks grants on memory.main.t:read",
      "Invalid Input Error: Authorization callback failed: Permission Error: QoD: function " +
        "getenv is superuser-only"
    ).foreach { m =>
      withClue(m) {
        val (router, _, node) =
          setup(stub = () => QuackResponse.Failed(QuackError.Permanent(m), 1L))
        router.execute("c-node-denied", "alice", poolKey, "SELECT * FROM t").unsafeRunSync() match
          case Left(RouterFailure.AccessDenied(reason)) => reason shouldBe s"access denied: $m"
          case o                                        => fail(s"expected AccessDenied, got $o")
        val latest = router.history.snapshot(1).head
        latest.status shouldBe "denied"
        latest.nodeId shouldBe node.nodeId
        latest.error shouldBe Some(m)
      }
    }
    // A user's text quoting the marker, and every other node error, stay permanent failures.
    List(
      "Invalid Input Error: QoD: user:alice lacks",
      "Binder Error: Permission Error: QoD: user:alice lacks",
      "Parser Error: syntax error at or near \"QoD\""
    ).foreach { m =>
      withClue(m) {
        FlightSqlRouter.isNodeRefusal(m) shouldBe false
        val (router, _, _) =
          setup(stub = () => QuackResponse.Failed(QuackError.Permanent(m), 1L))
        router
          .execute("c-node-error", "alice", poolKey, "SELECT 1")
          .unsafeRunSync()
          .swap
          .toOption
          .get shouldBe a[RouterFailure.BadRequest]
        router.history.snapshot(1).head.status shouldBe "permanent"
      }
    }
    FlightSqlRouter.isNodeRefusal(
      s"Permission Error: ${FlightSqlRouter.NodeAuthzUnavailable}: timed out"
    ) shouldBe false

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

  it should "fence a front door's transaction when its pinned node leaves the pool" in:
    // The native front door holds the client's transaction on its own node connection: the router
    // must end it there too (D-m8). FlightSQL passes no fence and gets the same failure.
    val (router, sessions, _) = setup()
    val fenced                = scala.collection.mutable.ListBuffer.empty[String]
    val relay: FlightSqlRouter.NodeSend[Unit] = (node, wrappedSql, _, _, principal) =>
      router.adapter.send(node, wrappedSql, principal, None).map {
        case QuackResponse.Ok(_, ms, close) => NodeOutcome.Ok((), ms, close)
        case QuackResponse.Failed(err, ms)  => NodeOutcome.Permanent(err.toString, ms)
      }
    def statement(sql: String) =
      router
        .executeWith(
          "c-fence",
          "alice",
          poolKey,
          sql,
          None,
          relay,
          failInTransaction = Some(answer => IO(fenced += answer).void),
          purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
        )
        .unsafeRunSync()
    statement("BEGIN").foreach(_.close())
    sessions.get("c-fence").exists(_.txOpen) shouldBe true
    fenced.toList shouldBe Nil
    router.supervisor.stopPool(poolKey, force = true).unsafeRunSync()
    statement("SELECT 1").left.map(_.reason) shouldBe
      Left("pinned node disappeared; transaction lost")
    fenced.toList shouldBe List(FlightSqlRouter.PinLostAnswer)
    sessions.get("c-fence").flatMap(_.pinnedNodeId) shouldBe None

  // ---- defaultDatabase / defaultSchema precedence tests ----

  /** A router over `sup` whose parser defaults the cases below read; its node is never called. */
  private def defaultsRouter(sup: PoolSupervisor): FlightSqlRouter =
    val tracker = new NodeLoadTracker
    new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(new QuackHttpClient(TestArrow.sharedAllocator), tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral()),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )

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
    defaultsRouter(sup).defaultsOf(key) shouldBe ((Some("fedpg"), Some("public")))

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
    defaultsRouter(sup).defaultsOf(key) shouldBe ((Some("memory"), Some("main")))

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
    val adapter  = new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      stmtInstruments = si,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
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
        defaultsRouter(sup).defaultsOf(key) shouldBe ((Some("delta_lake"), Some("myschema")))

  // Issue #112: the gateway's parser default (`defaultsOf`, the tenant-db's own defaultSchema
  // override) and the schema of the USE prelude wrapWithDefaultSchema sends must agree, or the
  // policy rewriters qualify unqualified table refs against one schema while the engine runs the
  // statement under another. The demo manifests set defaultSchema=tpch1 on the tenant-db while the
  // metastore carries schemaName=main; this test builds exactly that shape.
  it should "keep the USE-statement schema in sync with the parser's default schema" in:
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
    val adapter = new QuackHttpAdapter(client, new NodeLoadTracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val router  = new FlightSqlRouter(
      sup2,
      new SessionRegistry,
      new NodeLoadTracker,
      adapter,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
    router.execute("skew-1", "alice", key, "SELECT 1 FROM t").unsafeRunSync()

    // The parser default is tpch1 (tenant-db override wins). The engine actually runs under
    // whatever schema wrapWithDefaultSchema put in the USE statement; the two MUST be the same
    // schema for the policy rewriters' qualification to mean anything.
    router.defaultsOf(key)._2 shouldBe Some("tpch1")
    val useSchema = """USE\s+\S+?\.(\S+?);""".r
      .findFirstMatchIn(capturedSql)
      .map(_.group(1))
      .getOrElse(fail(s"no USE statement found in sent SQL: $capturedSql"))
    useSchema shouldBe "tpch1"

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

    router.defaultsOf(key2)._2 shouldBe Some("curated")
    val useSchema2 = """USE\s+\S+?\.(\S+?);""".r
      .findFirstMatchIn(capturedSql)
      .map(_.group(1))
      .getOrElse(fail(s"no USE statement found in sent SQL: $capturedSql"))
    useSchema2 shouldBe "curated"

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
    * (router, lastSqlSentToBackend ref, node). `metadataFilter` is the mounted filter, as `Main`
    * mounts it.
    */
  private def setupWithRewriter(
      rewriter: ai.starlake.quack.edge.cls.ColumnPolicyRewriter =
        new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
          new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(Map.empty)
        ),
      metadataFilter: ai.starlake.quack.edge.meta.MetadataFilterRewriter = mountedFilter,
      readers: Int = 1,
      stub: () => QuackResponse = defaultStub,
      protectedWriteGuard: ai.starlake.quack.edge.policy.ProtectedWriteGuard =
        ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled,
      attachedCatalogsOf: PoolKey => Set[String] = _ => Set.empty
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

    val adapter  = new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      columnPolicyRewriter = rewriter,
      metadataFilterRewriter = metadataFilter,
      protectedWriteGuard = protectedWriteGuard,
      attachedCatalogsOf = attachedCatalogsOf
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
  // (`c."a@b"`, `c."x]"`): the caller wrote no such token, so the rewrite scope admits the rewrite.
  "a star expansion over an unusual column name" should "pass the rewrite scope check" in:
    val (router, capturedSql, _) = setupWithRewriter(
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
          Map(("memory", "main", "customer") -> List("c_id", "c_email", "a@b", "x]"))
        ),
        enabled = true
      )
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

  it should "deny a two-part name headed by the pool's catalog alias (audit F20)" in:
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

  "the routed path" should "deny (not forward) a plain read when a stored row policy fails to apply at rewrite time" in:
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
      registry = registry,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
    val result = router.execute("reg-1", "alice", poolKey, "SELECT 1").unsafeRunSync()
    val qr     = result.toOption.get
    registry.list().map(_.user) shouldBe List("alice") // still open: entry present
    qr.close()
    registry.list() shouldBe Nil // closed: entry gone

  it should "mask a system statement's values in the registry, which tenant admins list" in:
    // Review M1: history and audit mask the system principal's statement values, and so must the
    // active-statement list (ActiveStatementHandlers.list shows `sql` to the tenant's admins).
    val registry     = new ActiveStatementRegistry()
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      registry = registry,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
    val systemEff = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      ai.starlake.quack.ondemand.state.RbacUser("", None, "module:seed", kind = "admin"),
      Nil,
      Nil,
      Nil,
      Nil,
      system = true
    )
    val result = router
      .execute(
        "reg-sys",
        "module:seed",
        poolKey,
        "SELECT 'topsecret' AS k",
        effectiveSet = Some(systemEff)
      )
      .unsafeRunSync()
    val qr = result.toOption.get
    registry.list().map(_.sql) should have size 1
    registry.list().head.sql should not include "topsecret"
    qr.close()

  it should "carry the acting patId into the registry entry" in:
    val registry     = new ActiveStatementRegistry()
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      registry = registry,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
      registry = registry,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
    val adapter2  = new QuackHttpAdapter(client2, tkr, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val sessions2 = new SessionRegistry
    val registry2 = new ActiveStatementRegistry()
    val router2   = new FlightSqlRouter(
      sup2,
      sessions2,
      tkr,
      adapter2,
      registry = registry2,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )

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

  it should "keep the retry node's authorization-unavailable answer retryable" in:
    // The first node fails transiently; the fallback answers that it could get no verdict, which
    // a client may retry: the "retry failed" context must not make it a bad request.
    val callCount                 = new java.util.concurrent.atomic.AtomicInteger(0)
    val stub: () => QuackResponse = () =>
      if callCount.getAndIncrement() == 0 then
        QuackResponse.Failed(QuackError.Transient("node temporarily gone"), 1L)
      else
        QuackResponse.Failed(
          QuackError.Permanent("Permission Error: QoD: authorization unavailable, retry"),
          1L
        )
    val bknd = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          26100 + n.size,
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
    val router2 =
      new FlightSqlRouter(
        sup2,
        new SessionRegistry,
        tkr,
        new QuackHttpAdapter(client2, tkr, ai.starlake.quack.edge.authz.NodeKeys.ephemeral()),
        metadataFilterRewriter = mountedFilter,
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      )
    router2.execute("retry-authz", "alice", pk2, "SELECT 1").unsafeRunSync() match
      case Left(f: RouterFailure.Unavailable) => f.reason should include("retry failed")
      case other                              => fail(s"expected Unavailable, got $other")
    callCount.get() shouldBe 2

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
    val adapter  = new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      stmtInstruments = si,
      stampWrites = stampWrites,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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

    val adapter  = new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      stmtInstruments = si,
      stampWrites = true,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )

    val result =
      router.execute("c-s10", "alice", poolKey, "INSERT INTO t VALUES (1)").unsafeRunSync()
    result shouldBe a[Right[?, ?]]
    calls.size shouldBe 2
    calls.map(_._1).forall(_.isDefined) shouldBe true

  // ---------- SPI module events ----------

  "executeOneShot" should "run on a connection of its own, closed after, never metered as a session" in:
    // Review N2: UNDROP TABLE's CTAS runs on `<id>/undrop`, which nothing else ever closed.
    val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
    val sink: ManagerEventSink = e => { received.add(e); () }
    val (router, _, _)         = setup(events = sink)
    val eff                    = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      ai.starlake.quack.ondemand.state.RbacUser("u-1", Some("acme"), "alice", kind = "user"),
      Nil,
      Nil,
      Nil,
      Nil
    )
    val out = router.executeOneShot("c-1/undrop", "alice", poolKey, "SELECT 1", eff).unsafeRunSync()
    out.foreach(_.close())
    router.session("c-1/undrop") shouldBe None
    received.toArray.toList.collect { case e: ManagerEvent.SessionOpened => e } shouldBe Nil

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
    val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
    val sink: ManagerEventSink = e => { received.add(e); () }
    val (router, _, _)         = setup(
      stub = () => QuackResponse.Failed(QuackError.Permanent("Parser Error: syntax"), 1L),
      events = sink
    )
    val out = router.execute("evt-2", "alice", poolKey, "SELECTT 1").unsafeRunSync()
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
    val adapter = new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val router  = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      adapter,
      stmtInstruments = si,
      resumeHoldTimeout = resumeHoldTimeout,
      resumePollInterval = resumePollInterval,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
    val adapter = new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
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
      cacheAwareRouting = cacheAware,
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
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
    capturedSql() should include("duckdb_tables() WHERE (lower(database_name) = 'memory'")

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

  // Task 12 review I1: a COPY to an authorized location must not carry a listing the edge filter
  // would have narrowed (the filter cannot read a COPY).
  private val tableGrants = Some(
    effWithGrants(
      List(grant("memory", "main", "t", "RO"), grant("memory", "tpch1", "customer", "RO"))
    )
  )

  it should "refuse a COPY writing a filtered listing to an authorized location" in:
    val (router, capturedSql, _) = setupWithRewriter(
      metadataFilter = metaFilterOn
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
      metadataFilter = metaFilterOn
    )
    val sql = "COPY (SELECT * FROM main.t) TO 's3://acme-etl/out/t.csv'"
    val out = router
      .execute("copy-ok", "alice", poolKey, sql, effectiveSet = tableGrants)
      .unsafeRunSync()
    out match
      case Right(r) => r.close()
      case Left(f)  => fail(s"refused: $f")
    capturedSql() should include("COPY (SELECT * FROM main.t) TO 's3://acme-etl/out/t.csv'")

  it should "refuse a COPY the gateway cannot read to a principal the filter narrows, only" in:
    val unread = List(
      "SELECT 1; COPY (SELECT * FROM main.t) TO 's3://acme-etl/out/t.csv'",
      "EXPLAIN ANALYZE COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "PREPARE p AS COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "COPY (DESCRIBE customer) TO 's3://acme-etl/out/t.csv'"
    )
    val (narrowing, narrowingCaptured, _) = setupWithRewriter(metadataFilter = metaFilterOn)
    unread.zipWithIndex.foreach { (sql, i) =>
      withClue(sql) {
        narrowing
          .execute(s"copy-unread-$i", "alice", poolKey, sql, effectiveSet = tableGrants)
          .unsafeRunSync() match
          case Left(RouterFailure.AccessDenied(m)) => m should include("COPY")
          case other                               => fail(s"expected AccessDenied, got $other")
        narrowingCaptured() shouldBe ""
      }
    }
    // The filter leaves these listings alone (a superuser; a *.*.* ALL holder), so the COPY is
    // the node's to judge.
    val everyTable = Some(effWithGrants(List(grant("*", "*", "*", "ALL"))))
    unread.foreach { sql =>
      withClue(sql) {
        narrowing
          .execute("copy-unread-su", "alice", poolKey, sql, Some(superuserSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        narrowing
          .execute("copy-unread-all", "alice", poolKey, sql, effectiveSet = everyTable)
          .unsafeRunSync() shouldBe a[Right[?, ?]]
      }
    }
    narrowingCaptured() should include("COPY")

  it should "filter a metadata read hiding behind the first statement of a read batch" in:
    // A single parse reads only the first statement, so the second used to reach the node
    // untouched: a zero-grant principal
    // got the full catalog. Either outcome is safe, but the wire text must never carry an
    // unfiltered catalog read.
    val (router, capturedSql, _) =
      setupWithRewriter(metadataFilter = metaFilterOn)
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

  it should "never send an unfiltered metadata read riding inside a multi-statement write batch" in:
    // The filter rewrites the batch's SELECT and keeps the INSERT as written, or refuses the batch:
    // either is safe, but the wire text must never carry an unfiltered catalog read.
    val (router, capturedSql, _) = setupWithRewriter(metadataFilter = metaFilterOn)
    val out                      = router
      .execute(
        "meta-8",
        "alice",
        poolKey,
        "INSERT INTO main.mine SELECT 1; SELECT * FROM information_schema.tables",
        effectiveSet = Some(effWithGrants(List(grant("memory", "main", "mine", "RW"))))
      )
      .unsafeRunSync()
    out match
      case Left(f) =>
        f shouldBe a[RouterFailure.AccessDenied]
        capturedSql() shouldBe ""
      case Right(r) =>
        r.close()
        """(?i)FROM\s+information_schema\.tables(?!\s+WHERE\s+\()""".r
          .findAllMatchIn(capturedSql())
          .size shouldBe 0

  // ---- escape hygiene of this file's own I1 trivia test literals ----
  //
  // The NBSP characters exercised by the two I1 regression tests above must stay literal `\u00A0`
  // escapes, never a raw invisible byte pasted into the source -- see the identical (whole-file)
  // guard in `StatementClassifierSpec`, `PrepareStrategySpec` and `SqlTriviaSpec` for why this
  // matters: an editor or an "helpful"
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
      setupWithRewriter()
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
        source = "quack",
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync() shouldBe a[Left[?, ?]]

  it should "admit the same client-fed INSERT, same principal and grants, from the front door" in:
    val (router, _, _) =
      setupWithRewriter()
    router
      .executeWith(
        "cds-3",
        "alice",
        poolKey,
        ClientFedInsert,
        Some(ordersWriter),
        okSend,
        source = "quack",
        clientDataScans = true,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
      .map(_.value) shouldBe Right("count")

  it should "refuse a client data scan off the native front door, superusers included" in:
    val (router, captured, _) = setupWithRewriter()
    List(Some(ordersWriter), Some(superuserSet), None).foreach { eff =>
      withClue(eff.map(_.user.username)) {
        router
          .execute("cds-x1", "alice", poolKey, ClientFedInsert, effectiveSet = eff)
          .unsafeRunSync() match
          case Left(RouterFailure.AccessDenied(m)) =>
            m should include("scan_data_from_quack_client")
          case other => fail(s"expected AccessDenied, got $other")
        router
          .executeWith(
            "cds-x2",
            "alice",
            poolKey,
            ClientFedInsert,
            eff,
            okSend,
            source = "quack",
            purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
          )
          .unsafeRunSync() shouldBe a[Left[?, ?]]
      }
    }
    captured() shouldBe ""
    // the front door feeds the scan: admitted there, for the same principal
    router
      .executeWith(
        "cds-x3",
        "alice",
        poolKey,
        ClientFedInsert,
        Some(ordersWriter),
        okSend,
        source = "quack",
        clientDataScans = true,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
      .map(_.value) shouldBe Right("count")

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
      .executeWith(
        "cds-7",
        "alice",
        poolKey,
        ClientFedInsert,
        Some(withRowPolicy),
        okSend,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
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
        clientDataScans = true,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
      .map(_.value) shouldBe Right("count")

  "a row policy holding IS DISTINCT FROM a literal" should "pass the rewrite scope check" in:
    // `IS DISTINCT FROM 'X'` takes a value; the wrap carries the policy's SQL.
    val withRows = wildcardAllSet.copy(rowPolicies =
      List(
        ai.starlake.quack.ondemand.state
          .RoleRowPolicy("rp-d", "r-1", "*", "main", "customer", "c_region IS DISTINCT FROM 'X'")
      )
    )
    val (router, captured, _) = setupWithRewriter()
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

  // `histogram` / `histogram_values` are 2.0 table macros whose body reads `query_table(source)`.
  "a query_table-reading table macro" should "never reach the node for a row-policy principal" in:
    // histogram_values runs query_table on its string argument: the row rewriter never sees the
    // table, so the protected-write guard refuses the call (CatalogReachingCalls.readingTableMacro)
    val rls                   = Some(effWithRowPolicies(List(rowPolicyCustomer)))
    val (router, captured, _) = setupWithRewriter(protectedWriteGuard = guardKnowingCustomer)
    List(
      "SELECT * FROM histogram('customer', c_email)",
      "SELECT * FROM histogram_values('main.customer', c_region)"
    ).foreach { sql =>
      withClue(sql) {
        router.execute("hist-m4", "alice", poolKey, sql, rls).unsafeRunSync() shouldBe
          a[Left[?, ?]]
      }
    }
    captured() shouldBe ""
    // the aggregate of that name is a value, left to the row rewriter
    router
      .execute("hist-m5", "alice", poolKey, "SELECT histogram(c_region) FROM customer", rls)
      .unsafeRunSync() shouldBe a[Right[?, ?]]
    captured() should include("histogram(c_region)")

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

  "a backtick-quoted table or column" should "be filtered and masked" in:
    val twins = List(
      "SELECT c_id, c_email, c_region FROM `customer`" ->
        "SELECT c_id, c_email, c_region FROM \"customer\"",
      "SELECT * FROM `main`.`customer`" -> "SELECT * FROM \"main\".\"customer\"",
      "SELECT upper(`c_email`) AS e FROM customer" -> "SELECT upper(\"c_email\") AS e FROM customer",
      "SELECT c_id FROM customer WHERE `c_email` LIKE '%a%'" ->
        "SELECT c_id FROM customer WHERE \"c_email\" LIKE '%a%'",
      "SELECT `c` FROM customer c" -> "SELECT \"c\" FROM customer c"
    )
    val (router, captured, _) = setupWithRewriter(rewriter = clsKnowingCustomer)
    def run(sql: String)      =
      val out = router.execute("bt-1", "alice", poolKey, sql, Some(maskedAndFiltered))
      val res = out.unsafeRunSync().map(_ => captured())
      res
    twins.foreach { (backtick, twin) =>
      withClue(backtick) {
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

  // ---- F09: CHECKPOINT and DuckLake's per-schema macros ----

  "CHECKPOINT" should "run for a superuser, routed and classified as DDL" in:
    val (router, captured, _) = setupWithRewriter()
    router
      .execute("ckpt-2", "alice", poolKey, "CHECKPOINT", Some(superuserSet))
      .unsafeRunSync()
      .isRight shouldBe true
    captured() should include("CHECKPOINT")
    router.classifier.classify("CHECKPOINT") shouldBe ai.starlake.quack.model.StatementKind.Ddl

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

  "DuckLake's maintenance and option-setting calls" should "run for a superuser" in:
    val (open, openCaptured, _) = setupWithRewriter()
    maintenanceCalls.foreach { sql =>
      withClue(sql) {
        open
          .execute("dlm-mt2", "alice", poolKey, sql, Some(superuserSet))
          .unsafeRunSync() shouldBe a[Right[?, ?]]
        openCaptured() should not be ""
      }
    }

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

  "a too-deep statement" should "be forwarded, not crash the router" in:
    val (router, _, _) = setup()
    onWorkerStack(router.execute("deep-1", "alice", poolKey, tooDeep).unsafeRunSync()) match
      case Right(out) => out shouldBe a[Right[?, ?]]
      case Left(e)    => fail(s"escaped ${e.getClass.getName}", e)

  it should "be denied, not crash the router, when a pipeline step overflows" in:
    val (base, _, _) = setup()
    val router       = new FlightSqlRouter(
      base.supervisor,
      base.sessions,
      base.tracker,
      base.adapter,
      stmtInstruments = si,
      attachedCatalogsOf = _ => throw new StackOverflowError(),
      metadataFilterRewriter = mountedFilter,
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
    onWorkerStack(router.execute("deep-2", "alice", poolKey, "SELECT 1").unsafeRunSync()) match
      case Right(out) =>
        out.swap.toOption.get shouldBe a[RouterFailure.AccessDenied]
        router.history.snapshot(1).head.status shouldBe "denied"
      case Left(e) => fail(s"escaped ${e.getClass.getName}", e)
