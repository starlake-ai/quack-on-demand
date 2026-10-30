package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.sql.{
  Allowed,
  Denied,
  StatementValidator,
  ValidationContext,
  ValidatorUnavailable
}
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
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Pins the validator-context fields (`poolKey`, `edge`, `statementClass`) a remote validator (OPA)
  * reads instead of re-deriving them, and the router's mapping of `ValidatorUnavailable` to a
  * retryable `RouterFailure.Unavailable` with status "transient" rather than a permission denial.
  * Also pins that a `Denied` carrying `meta` merges it into the denial audit event's details.
  *
  * The router-construction helper below is copied from [[FlightSqlRouterExecuteWithSpec]] (minimal
  * single-pool fixture over `executeWith`, which is the one entry point that lets a test pass a
  * caller-chosen `source` without going through the admin dialect), since that spec -- not
  * `FlightSqlRouterSpec` -- is the one exercising `executeWith` with a custom validator, a `source`
  * override and `RecordingTelemetryStore` directly.
  */
class FlightSqlRouterOpaSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private final case class Fixture(
      router: FlightSqlRouter,
      journal: EventJournal,
      store: RecordingTelemetryStore
  )

  private def routerWith(
      validator: StatementValidator,
      events: ManagerEventSink = ManagerEventSink.noop
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
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val store   = new RecordingTelemetryStore
    val journal = new EventJournal(store)
    val client  =
      new QuackHttpClient(TestArrow.sharedAllocator)
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      journal = journal,
      events = events
    )
    Fixture(router, journal, store)

  private val okSend: FlightSqlRouter.NodeSend[String] =
    (_, _, _, _, _) => IO.pure(NodeOutcome.Ok("ok", 1L, () => ()))

  "FlightSqlRouter" should "pass poolKey, edge and statement class to the validator" in:
    var seen: Option[ValidationContext] = None
    val fx = routerWith { (ctx: ValidationContext) => seen = Some(ctx); Allowed }
    fx.router
      .executeWith(
        "c-1",
        "alice",
        poolKey,
        "INSERT INTO t VALUES (1)",
        None,
        okSend,
        source = "quack",
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    seen.flatMap(_.poolKey) shouldBe Some(poolKey)
    seen.map(_.edge) shouldBe Some("quack")
    seen.map(_.statementClass) shouldBe Some("WRITE")

  it should "map ValidatorUnavailable to RouterFailure.Unavailable and record transient" in:
    val fx  = routerWith((_: ValidationContext) => ValidatorUnavailable("opa down"))
    val out =
      fx.router
        .executeWith(
          "c-2",
          "alice",
          poolKey,
          "SELECT 1",
          None,
          okSend,
          purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
        )
        .unsafeRunSync()
    out match
      case Left(RouterFailure.Unavailable(r)) =>
        r should include("authorization service unavailable")
      case other => fail(other.toString)
    fx.router.history.snapshot(1).head.status shouldBe "transient"

  it should "merge a Denied validator's meta into the denial audit event's details" in:
    val fx = routerWith((_: ValidationContext) =>
      Denied("no grant", Set.empty, Map("authz_source" -> "opa"))
    )
    fx.router
      .executeWith(
        "c-3",
        "alice",
        poolKey,
        "SELECT 1",
        None,
        okSend,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    fx.journal.drainNow()
    fx.store.events should have size 1
    fx.store.events.head.detail should contain("authz_source" -> "opa")

  it should "keep QoD's own audit keys when a validator's meta collides with them" in:
    val fx = routerWith((_: ValidationContext) =>
      Denied(
        "no grant",
        Set.empty,
        Map(
          "sql"          -> "x",
          "reason"       -> "y",
          "durationMs"   -> "z",
          "denied"       -> "forged",
          "authz_source" -> "opa"
        )
      )
    )
    fx.router
      .executeWith(
        "c-4",
        "alice",
        poolKey,
        "SELECT 42",
        None,
        okSend,
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    fx.journal.drainNow()
    fx.store.events should have size 1
    val detail = fx.store.events.head.detail
    detail should contain("sql" -> "SELECT 42")
    detail should contain("authz_source" -> "opa")
    detail.get("reason") should not be Some("y")
    detail.get("durationMs") should not be Some("z")
    detail.get("denied") should not be Some("forged")

  it should "keep the audit source and SessionOpened via at 'flightsql' when only the OPA edge differs" in:
    var seenCtx: Option[ValidationContext] = None
    val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
    val sink: ManagerEventSink = e => { received.add(e); () }
    val fx                     = routerWith(
      { (ctx: ValidationContext) => seenCtx = Some(ctx); Allowed },
      events = sink
    )
    val out = fx.router
      .executeWith(
        "c-6",
        "alice",
        poolKey,
        "INSERT INTO t VALUES (1)",
        None,
        okSend,
        source = "flightsql",
        edge = "mcp",
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    seenCtx.map(_.edge) shouldBe Some("mcp")

    fx.journal.drainNow()
    fx.store.events should have size 1
    fx.store.events.head.origin shouldBe "flightsql"

    val seen = received.toArray.toList.map(_.asInstanceOf[ManagerEvent])
    seen.collectFirst { case ManagerEvent.SessionOpened(_, _, via) => via } shouldBe Some(
      "flightsql"
    )
