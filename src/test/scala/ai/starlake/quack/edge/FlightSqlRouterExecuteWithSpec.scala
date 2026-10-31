package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.EventJournal
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** `executeWith[A]` runs the whole statement pipeline around a caller-supplied node call. These
  * cases pin what the Quack front door relies on: the same gates, records, registry entries and
  * retry arm as `execute`, with the audit source the caller names.
  */
class FlightSqlRouterExecuteWithSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private final case class Fixture(
      router: FlightSqlRouter,
      journal: EventJournal,
      store: RecordingTelemetryStore,
      nodes: List[RunningNode]
  )

  private def setup(
      nodeCount: Int = 1
  ): Fixture =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21700 + n.size,
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
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, nodeCount)).unsafeRunSync()
    val store   = new RecordingTelemetryStore
    val journal = new EventJournal(store)
    val client  =
      new QuackHttpClient(TestArrow.sharedAllocator)
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral()),
      journal = journal,
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
    Fixture(router, journal, store, sup.get(poolKey).get.nodes)

  "executeWith" should "run the pipeline around a custom node call and record the outcome" in:
    val fx   = setup()
    var seen = List.empty[(String, String, Option[String], Boolean)]
    val send: FlightSqlRouter.NodeSend[String] = (node, sql, prelude, recordLoad, _) =>
      IO {
        seen = seen :+ (node.nodeId, sql, prelude, recordLoad)
        NodeOutcome.Ok("payload", 7L, () => ())
      }
    val out = fx.router
      .executeWith(
        "c-1",
        "alice",
        poolKey,
        "SELECT 1",
        None,
        send,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    out.map(r => (r.value, r.nodeId, r.durationMs)) shouldBe Right(
      ("payload", fx.nodes.head.nodeId, 7L)
    )
    // The pipeline hands the node call the USE-prefixed statement (in-memory pools resolve to
    // memory.main), exactly what the Arrow adapter receives.
    seen shouldBe List((fx.nodes.head.nodeId, "USE memory.main; SELECT 1", None, true))
    val rec = fx.router.history.snapshot(10).head
    rec.status shouldBe "ok"
    rec.nodeId shouldBe fx.nodes.head.nodeId
    rec.sql shouldBe "SELECT 1"

  it should "keep the statement registered until the caller closes it" in:
    val fx                                     = setup()
    var during                                 = -1
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _, _) =>
      IO {
        during = fx.router.registry.list().size
        NodeOutcome.Ok("payload", 1L, () => ())
      }
    val out = fx.router
      .executeWith(
        "c-2",
        "alice",
        poolKey,
        "SELECT 1",
        None,
        send,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    during shouldBe 1
    fx.router.registry.list() should have size 1
    out.toOption.get.close()
    fx.router.registry.list() shouldBe empty

  it should "retry a transient failure on another node" in:
    val fx                                     = setup(nodeCount = 2)
    var calls                                  = List.empty[String]
    val send: FlightSqlRouter.NodeSend[String] = (node, _, _, _, _) =>
      IO {
        calls = calls :+ node.nodeId
        if calls.size == 1 then NodeOutcome.Transient("connection refused", 2L)
        else NodeOutcome.Ok("second", 3L, () => ())
      }
    val out = fx.router
      .executeWith(
        "c-3",
        "alice",
        poolKey,
        "SELECT 1",
        None,
        send,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    out.map(_.value) shouldBe Right("second")
    calls.distinct should have size 2

  it should "give up on a transient failure when no other node exists" in:
    val fx                                     = setup()
    val send: FlightSqlRouter.NodeSend[String] =
      (_, _, _, _, _) => IO.pure(NodeOutcome.Transient("connection refused", 2L))
    val out = fx.router
      .executeWith(
        "c-4",
        "alice",
        poolKey,
        "SELECT 1",
        None,
        send,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    out match
      case Left(RouterFailure.Unavailable(_)) => succeed
      case other                              => fail(s"expected Unavailable, got $other")

  it should "classify a permanent node error without retrying" in:
    val fx                                     = setup(nodeCount = 2)
    var calls                                  = 0
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _, _) =>
      IO {
        calls += 1
        NodeOutcome.Permanent("Catalog Error: Table with name t does not exist", 2L)
      }
    val out = fx.router
      .executeWith(
        "c-5",
        "alice",
        poolKey,
        "SELECT 1",
        None,
        send,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    calls shouldBe 1
    out match
      case Left(RouterFailure.NotFound(m)) => m should include("does not exist")
      case other                           => fail(s"expected NotFound, got $other")

  it should "record a node's refusal as a denial under the caller's source" in:
    val fx                                     = setup()
    var calls                                  = 0
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _, _) =>
      IO {
        calls += 1
        NodeOutcome.Permanent("Permission Error: QoD: user:alice lacks grants on t", 1L)
      }
    val out = fx.router
      .executeWith(
        "c-6",
        "alice",
        poolKey,
        "INSERT INTO t VALUES (1)",
        None,
        send,
        source = "quack",
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    calls shouldBe 1
    out match
      case Left(RouterFailure.AccessDenied(m)) => m should include("lacks grants on t")
      case other                               => fail(s"expected AccessDenied, got $other")
    fx.journal.drainNow()
    fx.store.events should have size 1
    fx.store.events.head.origin shouldBe "quack"
    fx.store.events.head.outcome shouldBe "denied"

  it should "stamp a successful write's audit event with the caller's source" in:
    val fx                                     = setup()
    val send: FlightSqlRouter.NodeSend[String] =
      (_, _, _, _, _) => IO.pure(NodeOutcome.Ok("count", 1L, () => ()))
    fx.router
      .executeWith(
        "c-7",
        "alice",
        poolKey,
        "INSERT INTO t VALUES (1)",
        None,
        send,
        source = "quack",
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    fx.journal.drainNow()
    fx.store.events should have size 1
    fx.store.events.head.origin shouldBe "quack"
    fx.store.events.head.action shouldBe "sql.write"

  it should "hand every node call the principal of the statement's effective set" in:
    val fx   = setup(nodeCount = 2)
    var seen = List.empty[ai.starlake.quack.edge.authz.NodePrincipal]
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _, principal) =>
      IO {
        seen = seen :+ principal
        if seen.size == 1 then NodeOutcome.Transient("connection refused", 1L)
        else NodeOutcome.Ok("ok", 1L, () => ())
      }
    val user = ai.starlake.quack.ondemand.state.RbacUser("u-7", Some("acme"), "alice", "user")
    val eff  = ai.starlake.quack.ondemand.rbac.EffectiveSet(user, Nil, Nil, Nil, Nil)
    fx.router
      .executeWith(
        "c-8",
        "alice",
        poolKey,
        "SELECT 1",
        Some(eff),
        send,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    // The retry on the second node runs for the same principal.
    seen shouldBe List.fill(2)(
      ai.starlake.quack.edge.authz.NodePrincipal(
        "u-7",
        "alice",
        Some("acme"),
        superuser = false,
        system = false,
        context = Some(
          ai.starlake.quack.edge.authz
            .DecideContext(eff, poolKey, None, ai.starlake.quack.edge.authz.DecidePurpose.Live)
        )
      )
    )

  private val aliceEff = ai.starlake.quack.ondemand.rbac.EffectiveSet(
    ai.starlake.quack.ondemand.state.RbacUser("u-7", Some("acme"), "alice", "user"),
    Nil,
    Nil,
    Nil,
    Nil
  )

  "a dry run's statement" should "reach the node call with a DryRun context and record nothing" in:
    val fx   = setup()
    var seen = List.empty[ai.starlake.quack.edge.authz.NodePrincipal]
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _, principal) =>
      IO {
        seen = seen :+ principal
        NodeOutcome.Permanent("Permission Error: QoD: dry run", 1L)
      }
    // Live: the node's refusal is a denial, in the history.
    fx.router
      .executeWith(
        "dr-1",
        "alice",
        poolKey,
        "SELECT 1",
        Some(aliceEff),
        send,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
      .left
      .map(_.reason) shouldBe Left("access denied: Permission Error: QoD: dry run")
    // A dry run: the node judges it (here, refuses it as a dry run), under a DryRun context.
    fx.router
      .executeWith(
        "dr-2",
        "alice",
        poolKey,
        "SELECT 1",
        Some(aliceEff),
        send,
        recordExecution = false,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.DryRun
      )
      .unsafeRunSync()
      .left
      .map(_.reason) shouldBe Left("access denied: Permission Error: QoD: dry run")
    seen.map(_.context.map(_.purpose)) shouldBe List(
      Some(ai.starlake.quack.edge.authz.DecidePurpose.Live),
      Some(ai.starlake.quack.edge.authz.DecidePurpose.DryRun)
    )
    // Only the live denial is in the history: a dry run records nothing.
    fx.router.history.snapshot(10).map(_.status) shouldBe List("denied")

  it should "never wake a suspended pool" in:
    val fx                                     = setup()
    var calls                                  = 0
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _, _) =>
      IO {
        calls += 1
        NodeOutcome.Ok("ran", 1L, () => ())
      }
    fx.router.supervisor.suspendPool(poolKey, "rest").unsafeRunSync()
    fx.router
      .executeWith(
        "dr-3",
        "alice",
        poolKey,
        "SELECT 1",
        Some(aliceEff),
        send,
        recordExecution = false,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.DryRun
      )
      .unsafeRunSync()
      .left
      .map(_.reason) shouldBe Left(FlightSqlRouter.PoolNotWoken)
    calls shouldBe 0
    fx.router.supervisor.get(poolKey).get.suspended shouldBe true

  it should "be refused for a superuser and for the system, before any pool lookup or node call" in:
    val fx                                     = setup()
    var calls                                  = 0
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _, _) =>
      IO {
        calls += 1
        NodeOutcome.Ok("ran", 1L, () => ())
      }
    // A node admits both without asking the decision endpoint: the statement would run.
    val superuser = aliceEff.copy(user = aliceEff.user.copy(tenant = None))
    val system    = aliceEff.copy(system = true)
    def dry(eff: Option[ai.starlake.quack.ondemand.rbac.EffectiveSet], key: PoolKey) =
      fx.router
        .executeWith(
          "dr-4",
          "alice",
          key,
          "INSERT INTO t VALUES (1)",
          eff,
          send,
          recordExecution = false,
          purpose = ai.starlake.quack.edge.authz.DecidePurpose.DryRun
        )
        .unsafeRunSync()
    val unknown = PoolKey("acme", "acme_default", "nope")
    List(Some(superuser), Some(system), None).foreach { eff =>
      dry(eff, poolKey) shouldBe Left(
        RouterFailure.AccessDenied(FlightSqlRouter.DryRunPrincipalRequired)
      )
      // Before the pool is looked up: an unknown pool is refused the same way, not 404.
      dry(eff, unknown) shouldBe Left(
        RouterFailure.AccessDenied(FlightSqlRouter.DryRunPrincipalRequired)
      )
    }
    calls shouldBe 0
    // The nearest allowed: a tenant user's dry run reaches the node call.
    dry(Some(aliceEff), poolKey).isRight shouldBe true
    calls shouldBe 1
    // Through dryRun too: refused with no handle minted, for either principal.
    List(superuser, system).foreach { eff =>
      fx.router.dryRun("alice", poolKey, "INSERT INTO t VALUES (1)", eff).unsafeRunSync() shouldBe
        ((Some(RouterFailure.AccessDenied(FlightSqlRouter.DryRunPrincipalRequired)), Nil))
    }
    // A tenant user's dryRun gets past the check to the node (none listens here).
    fx.router
      .dryRun("alice", poolKey, "SELECT 1", aliceEff)
      .unsafeRunSync()
      ._1
      .map(_.reason) should not be Some(FlightSqlRouter.DryRunPrincipalRequired)
    fx.router.history.snapshot(10) shouldBe empty

  "the adapter's authorizer" should "tell onMint each handle it mints, as the prelude carries it" in:
    val keys    = ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    val adapter = new QuackHttpAdapter(
      new QuackHttpClient(TestArrow.sharedAllocator),
      new NodeLoadTracker,
      keys
    )
    val seen = scala.collection.mutable.ListBuffer.empty[ai.starlake.quack.edge.authz.MintedHandle]
    val authorize = adapter.authorizer(
      setup().nodes.head,
      ai.starlake.quack.edge.authz.NodePrincipal.System,
      m => { seen += m; () }
    )
    val preludes = List(authorize(), authorize())
    seen.map(m => ai.starlake.quack.edge.authz.NodeHandles.prelude(m.text)).toList shouldBe preludes
    seen.map(_.nonce).distinct should have size 2
