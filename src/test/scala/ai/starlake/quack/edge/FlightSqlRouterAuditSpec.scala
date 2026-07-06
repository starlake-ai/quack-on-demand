package ai.starlake.quack.edge

import ai.starlake.acl.model.TableRef
import ai.starlake.acl.parser.{TableAccess, Verb}
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.sql.{Allowed, Denied, StatementValidator, ValidationContext, ValidationResult}
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

/** Verifies that [[FlightSqlRouter]] emits the correct audit journal events for data-plane
  * outcomes: denied statements (data-denial/sql.denied), write statements (data-write/sql.write),
  * DDL statements (data-write/sql.ddl), and that SELECT queries produce no event.
  *
  * Uses the same no-Flight-wire fixture stack as [[FlightSqlRouterSpec]].
  */
class FlightSqlRouterAuditSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private def defaultStub: () => QuackResponse = () => TestArrow.okResponse()

  private def buildSupervisor(): PoolSupervisor =
    val backend = new QuackBackend:
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
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup

  /** Builds a router wired to a [[RecordingTelemetryStore]]-backed journal.
    * Returns (router, journal, store) so the test can call drainNow() and then
    * assert on store.events.
    */
  private def setupWithJournal(
      stub: () => QuackResponse = defaultStub,
      validator: StatementValidator = StatementValidator.allowAll
  ): (FlightSqlRouter, EventJournal, RecordingTelemetryStore) =
    val sup  = buildSupervisor()
    val store   = new RecordingTelemetryStore
    val journal = new EventJournal(store)
    val tracker = new NodeLoadTracker
    val client = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        IO.pure(stub())
    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    val router = new FlightSqlRouter(
      sup,
      sessions,
      tracker,
      adapter,
      validator = validator,
      journal = journal
    )
    (router, journal, store)

  "FlightSqlRouter audit journal" should
    "emit data-write/sql.write/ok for an INSERT with actor, tenant, origin, sql, durationMs" in:
      val (router, journal, store) = setupWithJournal()
      router
        .execute("aud-1", "alice", poolKey, "INSERT INTO acme.public.t VALUES (1)")
        .unsafeRunSync()
      journal.drainNow()
      store.events should have size 1
      val e = store.events.head
      e.family shouldBe "data-write"
      e.action shouldBe "sql.write"
      e.outcome shouldBe "ok"
      e.actor shouldBe "alice"
      e.tenant shouldBe Some("acme")
      e.origin shouldBe "flightsql"
      e.detail.contains("sql") shouldBe true
      e.detail.contains("durationMs") shouldBe true

  it should "emit data-write/sql.ddl/ok for a CREATE TABLE" in:
    val (router, journal, store) = setupWithJournal()
    router
      .execute("aud-2", "alice", poolKey, "CREATE TABLE acme.public.foo (id INT)")
      .unsafeRunSync()
    journal.drainNow()
    store.events should have size 1
    val e = store.events.head
    e.family shouldBe "data-write"
    e.action shouldBe "sql.ddl"
    e.outcome shouldBe "ok"

  it should "emit NO journal event for a SELECT" in:
    val (router, journal, store) = setupWithJournal()
    router.execute("aud-3", "alice", poolKey, "SELECT 1").unsafeRunSync()
    journal.drainNow()
    store.events shouldBe empty

  it should "emit data-denial/sql.denied/denied with canonical:verb in detail(denied)" in:
    val access  = TableAccess(TableRef("cat", "sch", "tbl"), Verb.Write)
    val denying = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult =
        Denied("insufficient grants", Set(access))
    val (router, journal, store) = setupWithJournal(validator = denying)
    router
      .execute("aud-4", "alice", poolKey, "INSERT INTO cat.sch.tbl VALUES (1)")
      .unsafeRunSync()
    journal.drainNow()
    store.events should have size 1
    val e = store.events.head
    e.family shouldBe "data-denial"
    e.action shouldBe "sql.denied"
    e.outcome shouldBe "denied"
    e.actor shouldBe "alice"
    e.tenant shouldBe Some("acme")
    e.origin shouldBe "flightsql"
    e.detail("denied") shouldBe "cat.sch.tbl:Write"

  it should "not throw and not record when using the default noop journal (EventJournal.noop)" in:
    val sup      = buildSupervisor()
    val tracker  = new NodeLoadTracker
    val client   = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        IO.pure(defaultStub())
    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    // No journal param -> defaults to EventJournal.noop
    val router = new FlightSqlRouter(sup, sessions, tracker, adapter)
    val result = router
      .execute("aud-5", "alice", poolKey, "INSERT INTO acme.public.t VALUES (1)")
      .unsafeRunSync()
    result shouldBe a[Right[?, ?]]
