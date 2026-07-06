package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.sql.{
  Denied,
  StatementValidator,
  ValidationContext,
  ValidationResult
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
import ai.starlake.quack.ondemand.telemetry.{EventJournal, StatementQuery}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Verifies that [[FlightSqlRouter]] persists every recorded statement to the journal via
  * [[EventJournal.offerStatement]], for all outcome statuses: ok, denied, transient, and that the
  * noop journal path silently discards events. Uses the same no-Flight-wire fixture stack as
  * [[FlightSqlRouterAuditSpec]].
  */
class FlightSqlRouterHistorySpec extends AnyFlatSpec with Matchers {

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private def defaultStub: () => QuackResponse = () => TestArrow.okResponse()

  private def buildSupervisor(): PoolSupervisor = {
    val backend = new QuackBackend {
      private val n = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21500 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(id: String)    = IO { n.remove(id); () }
      def isAlive(id: String) = n.contains(id)
      def discoverExisting()  = IO.pure(n.values.toList)
      def cleanup()           = IO(n.clear())
    }
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup
  }

  /** Builds a router wired to a [[RecordingTelemetryStore]]-backed journal. Returns (router,
    * journal, store) so the test can call drainNow() and then assert on store statements via
    * searchStatements.
    */
  private def setupWithJournal(
      stub: () => QuackResponse = defaultStub,
      validator: StatementValidator = StatementValidator.allowAll
  ): (FlightSqlRouter, EventJournal, RecordingTelemetryStore) = {
    val sup     = buildSupervisor()
    val store   = new RecordingTelemetryStore
    val journal = new EventJournal(store)
    val tracker = new NodeLoadTracker
    val client  = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ) {
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        IO.pure(stub())
    }
    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      validator = validator,
      journal = journal
    )
    (router, journal, store)
  }

  "FlightSqlRouter statement journal" should
    "persist an ok SELECT with all fields populated and a real nodeId" in {
      val (router, journal, store) = setupWithJournal()
      router.execute("hist-1", "alice", poolKey, "SELECT 1").unsafeRunSync()
      journal.drainNow()
      val rows = store.searchStatements(StatementQuery())
      rows should have size 1
      val e = rows.head.event
      e.username shouldBe "alice"
      e.tenant shouldBe "acme"
      e.pool shouldBe "sales"
      e.nodeId should not be "-"
      e.status shouldBe "ok"
      e.durationMs should be >= 0L
    }

  it should "persist a denied statement with status denied and nodeId '-'" in {
    val denying = new StatementValidator {
      def validate(ctx: ValidationContext): ValidationResult =
        Denied("insufficient grants", Set.empty)
    }
    val (router, journal, store) = setupWithJournal(validator = denying)
    router.execute("hist-2", "alice", poolKey, "SELECT secret FROM forbidden").unsafeRunSync()
    journal.drainNow()
    val rows = store.searchStatements(StatementQuery())
    rows should have size 1
    val e = rows.head.event
    e.status shouldBe "denied"
    e.nodeId shouldBe "-"
    e.username shouldBe "alice"
    e.tenant shouldBe "acme"
  }

  it should "persist a transient node failure with status transient and the error text" in {
    val transientStub: () => QuackResponse =
      () => QuackResponse.Failed(QuackError.Transient("disk full"), 10L)
    val (router, journal, store) = setupWithJournal(stub = transientStub)
    router.execute("hist-3", "alice", poolKey, "SELECT 1").unsafeRunSync()
    journal.drainNow()
    val rows = store.searchStatements(StatementQuery())
    // One transient row from the first attempt; retry finds no fallback node and returns
    // Unavailable without recording a second event.
    rows should have size 1
    val e = rows.head.event
    e.status shouldBe "transient"
    e.error shouldBe Some("disk full")
  }

  it should "record nothing and throw nothing with the default noop journal" in {
    val sup     = buildSupervisor()
    val tracker = new NodeLoadTracker
    val client  = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ) {
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        IO.pure(defaultStub())
    }
    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    // No journal param -> defaults to EventJournal.noop backed by NoopTelemetryStore
    val router = new FlightSqlRouter(sup, sessions, tracker, adapter)
    val result = router.execute("hist-4", "alice", poolKey, "SELECT 1").unsafeRunSync()
    result shouldBe a[Right[?, ?]]
  }

  it should "cap sql at 500 chars before persisting" in {
    val padding  = "x" * 600
    val longSql  = s"SELECT $padding FROM t"
    val (router, journal, store) = setupWithJournal()
    router.execute("hist-5", "alice", poolKey, longSql).unsafeRunSync()
    journal.drainNow()
    val rows = store.searchStatements(StatementQuery())
    rows should have size 1
    rows.head.event.sql.length shouldBe 500
  }

}