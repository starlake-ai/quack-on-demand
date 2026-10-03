package ai.starlake.quack.boot

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.sql.{Denied, StatementValidator, ValidationContext, ValidationResult}
import ai.starlake.quack.edge.{FlightSqlRouter, RouterFailure, SessionRegistry}
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.ExecCaller
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.EventJournal
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/** The routed executor's production router leg ([[RoutedExecutor.viaRouter]]) against a real
  * router: what the caller value carries (source, preferred node) must reach the router, and a
  * token timeout must not drop the result it stops waiting for without closing it.
  */
class RoutedExecutorRouterLegSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private final case class Fixture(
      sup: PoolSupervisor,
      router: FlightSqlRouter,
      journal: EventJournal,
      store: RecordingTelemetryStore,
      nodes: List[RunningNode]
  ):
    def leg: RoutedExecutor.Run = RoutedExecutor.viaRouter(router)

    /** The whole executor (restriction checks, then the leg); a system caller skips the handshake
      * so these cases need no grants.
      */
    def executor = RoutedExecutor(sup, StatementClassifier.default, leg)(recordExecution = true)

  private def setup(
      nodeCount: Int = 1,
      validator: StatementValidator = StatementValidator.allowAll,
      respond: () => IO[QuackResponse] = () => IO.pure(TestArrow.okResponse())
  ): Fixture =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21800 + n.size,
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
    val client  = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        respond()
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      journal = journal
    )
    Fixture(sup, router, journal, store, sup.get(poolKey).get.nodes)

  private val denying = new StatementValidator:
    def validate(ctx: ValidationContext): ValidationResult = Denied("no grant", Set.empty)

  private def timedSystem(id: String, ms: Int): ExecCaller =
    ExecCaller
      .system(id)
      .copy(restriction = TokenRestriction.Unrestricted.copy(stmtTimeoutMs = Some(ms)))

  "RoutedExecutor.viaRouter" should "route to the caller's preferred node" in:
    val fx     = setup(nodeCount = 3)
    val target = fx.nodes(2).nodeId
    val caller = ExecCaller.unrestricted("c-1", "alice").copy(preferredNode = Some(target))
    val out    = fx.leg(caller, poolKey, "SELECT 1", None, true).unsafeRunSync()
    out.map(_.nodeId) shouldBe Right(target)
    out.foreach(_.close())

  it should "record the caller's source as the audit origin" in:
    val fx     = setup(validator = denying)
    val caller = ExecCaller.unrestricted("c-2", "alice").copy(source = "rest-data")
    fx.leg(caller, poolKey, "SELECT * FROM t", None, true).unsafeRunSync() shouldBe a[Left[?, ?]]
    fx.journal.drainNow()
    fx.store.events.map(_.origin) shouldBe List("rest-data")

  it should "keep the FlightSQL origin for a caller that names no source" in:
    val fx = setup(validator = denying)
    fx.leg(ExecCaller.unrestricted("c-3", "alice"), poolKey, "SELECT * FROM t", None, true)
      .unsafeRunSync()
    fx.journal.drainNow()
    fx.store.events.map(_.origin) shouldBe List("flightsql")

  "the routed executor" should "answer within the token's limit and close a late result" in:
    // The node call blocks like the real one (IO.blocking, not interruptible by cancellation) and
    // answers after 1.5s whatever happens, so a wait that is not bounded shows up as elapsed time
    // rather than as a hung suite.
    val release = new CountDownLatch(1)
    val fx      = setup(respond =
      () => IO.blocking { release.await(1500, TimeUnit.MILLISECONDS); TestArrow.okResponse() }
    )
    val t0  = System.nanoTime()
    val out = fx.executor(timedSystem("c-4", 50), poolKey, "SELECT 1").unsafeRunSync()
    (System.nanoTime() - t0).nanos should be < 1.second
    out match
      case Left(RouterFailure.Unavailable(m)) => m should include("50ms")
      case other                              => fail(s"expected Unavailable, got $other")
    // The statement is still running on the node and still registered.
    fx.router.registry.list() should have size 1
    release.countDown()
    // Closing the late result deregisters it; a plain timeoutTo dropped it unclosed.
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while fx.router.registry.list().nonEmpty && System.nanoTime() < deadline do Thread.sleep(10)
    fx.router.registry.list() shouldBe empty

  it should "hand a result delivered within the limit to the caller, still open" in:
    val fx  = setup()
    val out = fx.executor(timedSystem("c-5", 5000), poolKey, "SELECT 1").unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    fx.router.registry.list() should have size 1
    out.foreach(_.close())
    fx.router.registry.list() shouldBe empty
