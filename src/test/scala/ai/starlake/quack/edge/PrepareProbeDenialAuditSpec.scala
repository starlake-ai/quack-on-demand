package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
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
import org.apache.arrow.flight.FlightProducer.{CallContext, StreamListener}
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.flight.{FlightDescriptor, FlightRuntimeException, Result}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

/** Issue #202: a read refused while its schema is probed (Prepare, or the GetFlightInfo of a
  * literal statement) never reaches an Execute, so the probe's refusal is the only trace of it. It
  * is recorded once, as the client's statement, in history and as `sql.denied`.
  */
class PrepareProbeDenialAuditSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private val Refusal = "Permission Error: QoD: user:alice lacks grants on b.s.t:read"

  private def setup(answer: () => QuackResponse) =
    val backend = new QuackBackend:
      def allowsLocalFiles = true

      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          24500 + n.size,
          "tok",
          Some(4L),
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
      .createTenantDb(
        poolKey.tenant,
        poolKey.tenantDb,
        TenantDbKind.InMemory,
        Map.empty,
        "",
        writerTrusted = true
      )
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val client = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) = IO.pure(answer())
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val store   = new RecordingTelemetryStore
    val journal = new EventJournal(store)
    val router  = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      adapter,
      journal = journal,
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
    val producer = new FlightProducerImpl(router, preparedIdleTtlSec = 3600L)
    val peer     = s"peer-${java.util.UUID.randomUUID()}"
    ConnectionContext.bind(
      peer,
      poolKey,
      s"conn-${java.util.UUID.randomUUID()}",
      "alice",
      grants = None
    )
    (producer, router, journal, store, peer)

  private def refused() = QuackResponse.Failed(QuackError.Permanent(Refusal), 1L)

  private val emptyHeaders = new org.apache.arrow.flight.CallHeaders:
    def get(k: String): String                                 = null
    def getByte(k: String): Array[Byte]                        = null
    def getAll(k: String): java.lang.Iterable[String]          = java.util.Collections.emptyList()
    def getAllByte(k: String): java.lang.Iterable[Array[Byte]] = java.util.Collections.emptyList()
    def insert(k: String, v: String): Unit                     = ()
    def insert(k: String, v: Array[Byte]): Unit                = ()
    def keys(): java.util.Set[String]                          = java.util.Collections.emptySet()
    def containsKey(k: String): Boolean                        = false

  private def context(peer: String): CallContext = new CallContext:
    def peerIdentity(): String = peer
    def isCancelled(): Boolean = false
    def getHeaders()           = emptyHeaders
    def getMiddleware[T <: org.apache.arrow.flight.FlightServerMiddleware](
        key: org.apache.arrow.flight.FlightServerMiddleware.Key[T]
    ): T = null.asInstanceOf[T]
    def getMiddleware(): java.util.Map[
      org.apache.arrow.flight.FlightServerMiddleware.Key[?],
      org.apache.arrow.flight.FlightServerMiddleware
    ] = java.util.Collections.emptyMap()

  private final class Listener extends StreamListener[Result]:
    val error                       = new AtomicReference[Throwable](null)
    def onNext(t: Result): Unit     = ()
    def onError(t: Throwable): Unit = error.set(t)
    def onCompleted(): Unit         = ()

  private def prepare(producer: FlightProducerImpl, peer: String, sql: String): Listener =
    val listener = new Listener
    producer.createPreparedStatement(
      FlightSql.ActionCreatePreparedStatementRequest.newBuilder().setQuery(sql).build(),
      context(peer),
      listener
    )
    listener

  private val Select = "SELECT count(*) FROM b.s.t"

  "A read refused at Prepare" should "be recorded once as sql.denied, under the client's SQL" in {
    val (producer, router, journal, store, peer) = setup(() => refused())
    prepare(producer, peer, Select).error.get() should not be null
    journal.drainNow()
    val denials = store.events.filter(_.action == "sql.denied")
    denials should have size 1
    val e = denials.head
    e.family shouldBe "data-denial"
    e.outcome shouldBe "denied"
    e.actor shouldBe "alice"
    e.tenant shouldBe Some("acme")
    e.origin shouldBe "flightsql"
    e.detail("sql") shouldBe Select
    e.detail("reason") should include("lacks grants on b.s.t:read")
    val rows = router.history.snapshot(8)
    rows should have size 1
    rows.head.status shouldBe "denied"
    rows.head.sql shouldBe Select
  }

  "A read refused at GetFlightInfo" should "be recorded once as sql.denied" in {
    val (producer, _, journal, store, peer) = setup(() => refused())
    an[FlightRuntimeException] should be thrownBy producer.getFlightInfoStatement(
      FlightSql.CommandStatementQuery.newBuilder().setQuery(Select).build(),
      context(peer),
      FlightDescriptor.command(Array.emptyByteArray)
    )
    journal.drainNow()
    val denials = store.events.filter(_.action == "sql.denied")
    denials should have size 1
    denials.head.detail("sql") shouldBe Select
  }

  "A probe that fails without a refusal" should "record nothing, as before" in {
    val (producer, router, journal, store, peer) =
      setup(() => QuackResponse.Failed(QuackError.Permanent("Catalog Error: no such table"), 1L))
    prepare(producer, peer, Select).error.get() should not be null
    journal.drainNow()
    store.events shouldBe empty
    router.history.size shouldBe 0
  }

  "A probe that succeeds" should "record nothing until the Execute" in {
    val (producer, router, journal, store, peer) = setup(() => TestArrow.okResponse())
    prepare(producer, peer, Select).error.get() shouldBe null
    journal.drainNow()
    store.events shouldBe empty
    router.history.size shouldBe 0
  }
