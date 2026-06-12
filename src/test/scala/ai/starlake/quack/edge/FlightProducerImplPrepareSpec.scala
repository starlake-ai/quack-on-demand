package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.model.{NodeSpec, PoolKey, RoleDistribution, RunningNode, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.google.protobuf.{Any => ProtoAny, ByteString}
import org.apache.arrow.flight.FlightProducer.{CallContext, StreamListener}
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.flight.{FlightDescriptor, Result}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

class FlightProducerImplPrepareSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  /** Build a real FlightSqlRouter + FlightProducerImpl backed by a stubbed QuackHttpClient that
    * records every (sql, nodeId) pair sent. Returns the producer, the recording buffer, and the
    * fixed peer string the test will use as `peerIdentity`. */
  private def setupProducer() =
    val backend = new QuackBackend:
      private val n = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(s.nodeId, s.poolKey, s.role, "127.0.0.1",
                            24000 + n.size, "tok", Some(4L), None, Instant.EPOCH,
                            maxConcurrent = s.maxConcurrent)
        n.put(s.nodeId, r); r
      }
      def stop(id: String)    = IO { n.remove(id); () }
      def isAlive(id: String) = n.contains(id)
      def discoverExisting()  = IO.pure(n.values.toList)
      def cleanup()           = IO { n.clear() }
    val tracker  = new NodeLoadTracker
    val sup      = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup.createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val sent = mutable.Buffer.empty[(String, String)] // (endpoint, sql)
    val client = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient   = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        sent += (endpoint -> sql)
        IO.pure(TestArrow.okResponse())

    val adapter  = new QuackHttpAdapter(client, tracker)
    val sessions = new SessionRegistry
    val router   = new FlightSqlRouter(sup, sessions, tracker, adapter)
    val producer = new FlightProducerImpl(router)

    val peer = s"peer-${java.util.UUID.randomUUID()}"
    ConnectionContext.bind(peer, poolKey, s"conn-${java.util.UUID.randomUUID()}", "alice")
    (producer, sent, peer)

  private val emptyHeaders = new org.apache.arrow.flight.CallHeaders:
    def get(k: String): String                                            = null
    def getByte(k: String): Array[Byte]                                   = null
    def getAll(k: String): java.lang.Iterable[String]                     = java.util.Collections.emptyList()
    def getAllByte(k: String): java.lang.Iterable[Array[Byte]]            = java.util.Collections.emptyList()
    def insert(k: String, v: String): Unit                                = ()
    def insert(k: String, v: Array[Byte]): Unit                           = ()
    def keys(): java.util.Set[String]                                     = java.util.Collections.emptySet()
    def containsKey(k: String): Boolean                                   = false

  private def fakeCallContext(peer: String): CallContext = new CallContext:
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

  private final class RecordingListener extends StreamListener[Result]:
    val onNextValue = new AtomicReference[Result](null)
    val onErrorRef  = new AtomicReference[Throwable](null)
    @volatile var completed: Boolean = false
    def onNext(t: Result): Unit         = onNextValue.set(t)
    def onError(throwable: Throwable): Unit = onErrorRef.set(throwable)
    def onCompleted(): Unit             = completed = true

  private def runPrepare(producer: FlightProducerImpl, peer: String, sql: String): RecordingListener =
    val req = FlightSql.ActionCreatePreparedStatementRequest
      .newBuilder().setQuery(sql).build()
    val listener = new RecordingListener
    producer.createPreparedStatement(req, fakeCallContext(peer), listener)
    listener

  private def decodePrepareResult(r: Result): FlightSql.ActionCreatePreparedStatementResult =
    val any = ProtoAny.parseFrom(r.getBody)
    any.unpack(classOf[FlightSql.ActionCreatePreparedStatementResult])

  // ---- tests ----

  "FlightProducerImpl.createPreparedStatement" should
    "skip the node call entirely for an INSERT (fixes today's double-INSERT hazard)" in:
      val (producer, sent, peer) = setupProducer()
      val listener = runPrepare(producer, peer, "INSERT INTO t VALUES (1)")
      listener.onErrorRef.get() shouldBe null
      listener.completed shouldBe true
      sent shouldBe empty

  it should "send a wrapped LIMIT-0 probe (not the original SELECT) so Prepare is cheap" in:
    val (producer, sent, peer) = setupProducer()
    val listener = runPrepare(producer, peer, "SELECT a FROM t")
    listener.onErrorRef.get() shouldBe null
    sent.size shouldBe 1
    // wrapWithDefaultSchema prepends `USE memory.main;` for InMemory pools. Strip it.
    val sql = sent.head._2
    val core = sql.split(";").map(_.trim).filter(_.nonEmpty).last
    core shouldBe "SELECT * FROM (SELECT a FROM t) AS _qod_probe LIMIT 0"

  it should "return a non-empty dataset_schema for SELECT (clients dispatch as query)" in:
    val (producer, _, peer) = setupProducer()
    val listener = runPrepare(producer, peer, "SELECT a FROM t")
    val result   = decodePrepareResult(listener.onNextValue.get())
    result.getDatasetSchema.size() should be > 0

  it should "return an EMPTY dataset_schema for INSERT (clients dispatch as update)" in:
    val (producer, _, peer) = setupProducer()
    val listener = runPrepare(producer, peer, "INSERT INTO t VALUES (1)")
    val result   = decodePrepareResult(listener.onNextValue.get())
    // An empty Arrow schema still serializes to some IPC bytes (the framing); the test that the
    // schema is "empty" is: no fields. We deserialize and check fields().isEmpty.
    val schemaBytes = result.getDatasetSchema.toByteArray
    val schema = org.apache.arrow.vector.ipc.message.MessageSerializer.deserializeSchema(
      new org.apache.arrow.vector.ipc.ReadChannel(
        java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(schemaBytes))
      )
    )
    schema.getFields.size() shouldBe 0