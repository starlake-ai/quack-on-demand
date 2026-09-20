package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.sql.{Denied, StatementValidator, ValidationContext, ValidationResult}
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
      nodeCount: Int = 1,
      validator: StatementValidator = StatementValidator.allowAll
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
      new QuackHttpClient(TestArrow.sharedAllocator, nativeClient = true, nodeDisableSsl = true)
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      journal = journal
    )
    Fixture(router, journal, store, sup.get(poolKey).get.nodes)

  "executeWith" should "run the pipeline around a custom node call and record the outcome" in:
    val fx   = setup()
    var seen = List.empty[(String, String, Option[String], Boolean)]
    val send: FlightSqlRouter.NodeSend[String] = (node, sql, prelude, recordLoad) =>
      IO {
        seen = seen :+ (node.nodeId, sql, prelude, recordLoad)
        NodeOutcome.Ok("payload", 7L, () => ())
      }
    val out = fx.router
      .executeWith("c-1", "alice", poolKey, "SELECT 1", None, send)
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
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _) =>
      IO {
        during = fx.router.registry.list().size
        NodeOutcome.Ok("payload", 1L, () => ())
      }
    val out = fx.router.executeWith("c-2", "alice", poolKey, "SELECT 1", None, send).unsafeRunSync()
    during shouldBe 1
    fx.router.registry.list() should have size 1
    out.toOption.get.close()
    fx.router.registry.list() shouldBe empty

  it should "retry a transient failure on another node" in:
    val fx                                     = setup(nodeCount = 2)
    var calls                                  = List.empty[String]
    val send: FlightSqlRouter.NodeSend[String] = (node, _, _, _) =>
      IO {
        calls = calls :+ node.nodeId
        if calls.size == 1 then NodeOutcome.Transient("connection refused", 2L)
        else NodeOutcome.Ok("second", 3L, () => ())
      }
    val out = fx.router.executeWith("c-3", "alice", poolKey, "SELECT 1", None, send).unsafeRunSync()
    out.map(_.value) shouldBe Right("second")
    calls.distinct should have size 2

  it should "give up on a transient failure when no other node exists" in:
    val fx                                     = setup()
    val send: FlightSqlRouter.NodeSend[String] =
      (_, _, _, _) => IO.pure(NodeOutcome.Transient("connection refused", 2L))
    val out = fx.router.executeWith("c-4", "alice", poolKey, "SELECT 1", None, send).unsafeRunSync()
    out match
      case Left(RouterFailure.Unavailable(_)) => succeed
      case other                              => fail(s"expected Unavailable, got $other")

  it should "classify a permanent node error without retrying" in:
    val fx                                     = setup(nodeCount = 2)
    var calls                                  = 0
    val send: FlightSqlRouter.NodeSend[String] = (_, _, _, _) =>
      IO {
        calls += 1
        NodeOutcome.Permanent("Catalog Error: Table with name t does not exist", 2L)
      }
    val out = fx.router.executeWith("c-5", "alice", poolKey, "SELECT 1", None, send).unsafeRunSync()
    calls shouldBe 1
    out match
      case Left(RouterFailure.NotFound(m)) => m should include("does not exist")
      case other                           => fail(s"expected NotFound, got $other")

  it should "record a denial under the caller's source and never call the node" in:
    val denying = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult = Denied("no grant", Set.empty)
    val fx                                     = setup(validator = denying)
    var calls                                  = 0
    val send: FlightSqlRouter.NodeSend[String] =
      (_, _, _, _) => IO { calls += 1; NodeOutcome.Ok("never", 1L, () => ()) }
    val out = fx.router
      .executeWith(
        "c-6",
        "alice",
        poolKey,
        "INSERT INTO t VALUES (1)",
        None,
        send,
        source = "quack"
      )
      .unsafeRunSync()
    calls shouldBe 0
    out match
      case Left(RouterFailure.AccessDenied(m)) => m should include("no grant")
      case other                               => fail(s"expected AccessDenied, got $other")
    fx.journal.drainNow()
    fx.store.events should have size 1
    fx.store.events.head.origin shouldBe "quack"
    fx.store.events.head.outcome shouldBe "denied"

  it should "stamp a successful write's audit event with the caller's source" in:
    val fx                                     = setup()
    val send: FlightSqlRouter.NodeSend[String] =
      (_, _, _, _) => IO.pure(NodeOutcome.Ok("count", 1L, () => ()))
    fx.router
      .executeWith(
        "c-7",
        "alice",
        poolKey,
        "INSERT INTO t VALUES (1)",
        None,
        send,
        source = "quack"
      )
      .unsafeRunSync()
    fx.journal.drainNow()
    fx.store.events should have size 1
    fx.store.events.head.origin shouldBe "quack"
    fx.store.events.head.action shouldBe "sql.write"
