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
    * records every (sql, nodeId) pair sent. Returns the producer, the recording buffer, the
    * underlying router (so tests can inspect the history store), and the fixed peer string the
    * test will use as `peerIdentity`. */
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
    (producer, sent, router, peer)

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
      val (producer, sent, _, peer) = setupProducer()
      val listener = runPrepare(producer, peer, "INSERT INTO t VALUES (1)")
      listener.onErrorRef.get() shouldBe null
      listener.completed shouldBe true
      sent shouldBe empty

  it should "send a wrapped LIMIT-0 probe (not the original SELECT) so Prepare is cheap" in:
    val (producer, sent, _, peer) = setupProducer()
    val listener = runPrepare(producer, peer, "SELECT a FROM t")
    listener.onErrorRef.get() shouldBe null
    sent.size shouldBe 1
    // wrapWithDefaultSchema prepends `USE memory.main;` for InMemory pools. Strip it.
    val sql = sent.head._2
    val core = sql.split(";").map(_.trim).filter(_.nonEmpty).last
    core shouldBe "SELECT * FROM (SELECT a FROM t) AS _qod_probe LIMIT 0"

  it should "return a non-empty dataset_schema for SELECT (clients dispatch as query)" in:
    val (producer, _, _, peer) = setupProducer()
    val listener = runPrepare(producer, peer, "SELECT a FROM t")
    val result   = decodePrepareResult(listener.onNextValue.get())
    result.getDatasetSchema.size() should be > 0

  it should "return an EMPTY dataset_schema for INSERT (clients dispatch as update)" in:
    val (producer, _, _, peer) = setupProducer()
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

  /** Deserialize a serialized IPC Arrow schema from raw bytes. */
  private def parseSchema(bytes: Array[Byte]): org.apache.arrow.vector.types.pojo.Schema =
    org.apache.arrow.vector.ipc.message.MessageSerializer.deserializeSchema(
      new org.apache.arrow.vector.ipc.ReadChannel(
        java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(bytes))
      )
    )

  // The Apache Arrow Flight SQL ODBC driver (Power BI, Excel) reads
  // `parameter_schema` from the Prepare result to learn the parameter
  // count. An absent field throws "Tried reading schema message, was null
  // or length 0" inside the driver. Quack has no parameter binding (R9 is
  // out of scope), so we advertise an empty schema = zero parameters.

  it should "set parameter_schema to an empty schema on a SELECT prepare" in:
    val (producer, _, _, peer) = setupProducer()
    val listener = runPrepare(producer, peer, "SELECT a FROM t")
    val result   = decodePrepareResult(listener.onNextValue.get())
    // bytes field: non-empty payload = field set on the wire
    result.getParameterSchema.size() should be > 0
    parseSchema(result.getParameterSchema.toByteArray).getFields.size() shouldBe 0

  it should "set parameter_schema to an empty schema on an INSERT prepare" in:
    val (producer, _, _, peer) = setupProducer()
    val listener = runPrepare(producer, peer, "INSERT INTO t VALUES (1)")
    val result   = decodePrepareResult(listener.onNextValue.get())
    // bytes field: non-empty payload = field set on the wire
    result.getParameterSchema.size() should be > 0
    parseSchema(result.getParameterSchema.toByteArray).getFields.size() shouldBe 0

  // ---- GetSchema (R5 / Power BI ODBC) ----
  //
  // The Apache Arrow Flight SQL ODBC driver calls GetSchema BEFORE fetching
  // rows so it can answer SQLDescribeCol / SQLNumResultCols. Without our
  // override both forms (`CommandStatementQuery`, `CommandPreparedStatementQuery`)
  // fall through to the NoOp throw, and the driver surfaces
  // "Tried reading schema message, was null or length 0" inside Power BI.

  it should "return the cached schema for getSchemaPreparedStatement after a SELECT prepare" in:
    val (producer, _, _, peer) = setupProducer()
    val prepListener = runPrepare(producer, peer, "SELECT a FROM t")
    val prepResult   = decodePrepareResult(prepListener.onNextValue.get())
    val handle       = prepResult.getPreparedStatementHandle
    val cmd          = FlightSql.CommandPreparedStatementQuery
      .newBuilder()
      .setPreparedStatementHandle(handle)
      .build()
    val schemaResult = producer.getSchemaPreparedStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(handle.toByteArray)
    )
    schemaResult should not be null
    schemaResult.getSchema should not be null
    // Probe SQL is a stubbed TestArrow response (single-column "i" Int32),
    // so the schema isn't empty; the precise shape is fixture-dependent.
    // What matters here is "non-null, parseable schema" which is what the
    // ODBC driver needs.
    schemaResult.getSchema.getFields.size() should be >= 1

  it should "return an empty schema for getSchemaPreparedStatement after an INSERT prepare" in:
    val (producer, _, _, peer) = setupProducer()
    val prepListener = runPrepare(producer, peer, "INSERT INTO t VALUES (1)")
    val prepResult   = decodePrepareResult(prepListener.onNextValue.get())
    val handle       = prepResult.getPreparedStatementHandle
    val cmd          = FlightSql.CommandPreparedStatementQuery
      .newBuilder()
      .setPreparedStatementHandle(handle)
      .build()
    val schemaResult = producer.getSchemaPreparedStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(handle.toByteArray)
    )
    schemaResult.getSchema.getFields.size() shouldBe 0

  it should "throw INVALID_ARGUMENT for getSchemaPreparedStatement with unknown handle" in:
    val (producer, _, _, peer) = setupProducer()
    val cmd                    = FlightSql.CommandPreparedStatementQuery
      .newBuilder()
      .setPreparedStatementHandle(com.google.protobuf.ByteString.copyFromUtf8("nope"))
      .build()
    val ex = intercept[org.apache.arrow.flight.FlightRuntimeException] {
      producer.getSchemaPreparedStatement(
        cmd, fakeCallContext(peer), FlightDescriptor.command(Array.emptyByteArray)
      )
    }
    ex.status().code() shouldBe org.apache.arrow.flight.FlightStatusCode.INVALID_ARGUMENT

  it should "probe and return the result schema for getSchemaStatement on a SELECT" in:
    val (producer, _, _, peer) = setupProducer()
    val cmd                    = FlightSql.CommandStatementQuery
      .newBuilder()
      .setQuery("SELECT a FROM t")
      .build()
    val schemaResult           = producer.getSchemaStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(Array.emptyByteArray)
    )
    schemaResult.getSchema.getFields.size() should be >= 1

  it should "return an empty schema for getSchemaStatement on an INSERT" in:
    val (producer, _, _, peer) = setupProducer()
    val cmd                    = FlightSql.CommandStatementQuery
      .newBuilder()
      .setQuery("INSERT INTO t VALUES (1)")
      .build()
    val schemaResult           = producer.getSchemaStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(Array.emptyByteArray)
    )
    schemaResult.getSchema.getFields.size() shouldBe 0

  // The Arrow Flight SQL ODBC driver reads the result schema from
  // FlightInfo.schema, NOT the dedicated GetSchema RPC. Returning null
  // (the previous behavior) makes the driver fall back to reading the
  // Schema from the DoGet IPC stream, which it doesn't do for column
  // description -> Power BI throws "Tried reading schema message, was
  // null or length 0". Returning the cached Prepare-time schema fixes it
  // and stays compatible with ADBC (the stream emits the same schema).

  it should "populate FlightInfo.schema on getFlightInfoPreparedStatement after a SELECT prepare" in:
    val (producer, _, _, peer) = setupProducer()
    val prepListener = runPrepare(producer, peer, "SELECT a FROM t")
    val prepResult   = decodePrepareResult(prepListener.onNextValue.get())
    val handle       = prepResult.getPreparedStatementHandle
    val cmd          = FlightSql.CommandPreparedStatementQuery
      .newBuilder()
      .setPreparedStatementHandle(handle)
      .build()
    val info = producer.getFlightInfoPreparedStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(handle.toByteArray)
    )
    info.getSchema should not be null
    info.getSchema.getFields.size() should be >= 1

  it should "populate FlightInfo.schema as empty on getFlightInfoPreparedStatement after an INSERT prepare" in:
    val (producer, _, _, peer) = setupProducer()
    val prepListener = runPrepare(producer, peer, "INSERT INTO t VALUES (1)")
    val prepResult   = decodePrepareResult(prepListener.onNextValue.get())
    val handle       = prepResult.getPreparedStatementHandle
    val cmd          = FlightSql.CommandPreparedStatementQuery
      .newBuilder()
      .setPreparedStatementHandle(handle)
      .build()
    val info = producer.getFlightInfoPreparedStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(handle.toByteArray)
    )
    info.getSchema should not be null
    info.getSchema.getFields.size() shouldBe 0

  // Same FlightInfo.schema fix for the un-prepared (one-shot) statement path.
  // ProbeWrap runs the LIMIT-0 probe to capture the result schema before the
  // client's DoGet. For DML/DDL (SkipExecute) we keep the schema null on
  // FlightInfo: ADBC would 400 on a mismatch between an "empty" advertised
  // schema and the single-row Count the stream actually emits.

  it should "populate FlightInfo.schema on getFlightInfoStatement for a SELECT" in:
    val (producer, _, _, peer) = setupProducer()
    val cmd                    = FlightSql.CommandStatementQuery
      .newBuilder()
      .setQuery("SELECT a FROM t")
      .build()
    val info = producer.getFlightInfoStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(Array.emptyByteArray)
    )
    info.getSchema should not be null
    info.getSchema.getFields.size() should be >= 1

  it should "leave FlightInfo.schema null on getFlightInfoStatement for an INSERT" in:
    val (producer, _, _, peer) = setupProducer()
    val cmd                    = FlightSql.CommandStatementQuery
      .newBuilder()
      .setQuery("INSERT INTO t VALUES (1)")
      .build()
    val info = producer.getFlightInfoStatement(
      cmd, fakeCallContext(peer), FlightDescriptor.command(Array.emptyByteArray)
    )
    // FlightInfo coerces null on the wire to an empty Schema on the read side,
    // so the assertion is on field count: still zero, no probe performed.
    info.getSchema.getFields.size() shouldBe 0

  it should "throw UNAUTHENTICATED for getSchemaStatement when peer has no connection context" in:
    val (producer, _, _, _) = setupProducer()
    val cmd                 = FlightSql.CommandStatementQuery
      .newBuilder()
      .setQuery("SELECT a FROM t")
      .build()
    val anonPeer            = s"anonymous-${java.util.UUID.randomUUID()}"
    val ex = intercept[org.apache.arrow.flight.FlightRuntimeException] {
      producer.getSchemaStatement(
        cmd, fakeCallContext(anonPeer), FlightDescriptor.command(Array.emptyByteArray)
      )
    }
    ex.status().code() shouldBe org.apache.arrow.flight.FlightStatusCode.UNAUTHENTICATED

  // ---- Prepare + Execute merge into a single history row ----

  /** Walk through the FlightSQL Prepare action + the subsequent Execute, and return whatever
    * the router's history store recorded across both halves. */
  private def runPrepareThenExecute(producer: FlightProducerImpl, peer: String, sql: String): Unit =
    val prepListener = runPrepare(producer, peer, sql)
    val prepResult   = decodePrepareResult(prepListener.onNextValue.get())
    val handle       = prepResult.getPreparedStatementHandle.toStringUtf8
    val command      = FlightSql.CommandPreparedStatementQuery
      .newBuilder()
      .setPreparedStatementHandle(com.google.protobuf.ByteString.copyFromUtf8(handle))
      .build()
    val execListener = new org.apache.arrow.flight.FlightProducer.ServerStreamListener:
      // Only the abstract methods need implementations; the Java defaults cover the rest.
      def isCancelled(): Boolean                                                       = false
      def isReady(): Boolean                                                            = true
      def setOnCancelHandler(h: Runnable): Unit                                        = ()
      def start(
          root: org.apache.arrow.vector.VectorSchemaRoot,
          provider: org.apache.arrow.vector.dictionary.DictionaryProvider,
          options: org.apache.arrow.vector.ipc.message.IpcOption
      ): Unit = ()
      def putNext(): Unit                                                              = ()
      def putNext(metadata: org.apache.arrow.memory.ArrowBuf): Unit                    = ()
      def putMetadata(metadata: org.apache.arrow.memory.ArrowBuf): Unit                = ()
      def error(throwable: Throwable): Unit                                            = ()
      def completed(): Unit                                                            = ()
    producer.getStreamPreparedStatement(command, fakeCallContext(peer), execListener)

  it should "record ONE history row per SELECT (Prepare suppressed) with prepareDurationMs set" in:
    val (producer, _, router, peer) = setupProducer()
    val historyBefore = router.history.size
    runPrepareThenExecute(producer, peer, "SELECT a FROM t")
    val added = router.history.snapshot(8).take(router.history.size - historyBefore)
    added.size shouldBe 1
    added.head.prepareDurationMs shouldBe defined
    // The original SELECT (not the wrapped probe) is what the operator sees.
    added.head.sql shouldBe "SELECT a FROM t"

  it should "record ONE history row per INSERT with prepareDurationMs absent (Prepare skipped)" in:
    val (producer, _, router, peer) = setupProducer()
    val historyBefore = router.history.size
    runPrepareThenExecute(producer, peer, "INSERT INTO t VALUES (1)")
    val added = router.history.snapshot(8).take(router.history.size - historyBefore)
    added.size shouldBe 1
    added.head.prepareDurationMs shouldBe None
    added.head.sql shouldBe "INSERT INTO t VALUES (1)"

  // ---- acceptPutStatement: literal (non-prepared) DML via executeUpdate ----

  private final class RecordingPutListener
      extends StreamListener[org.apache.arrow.flight.PutResult]:
    val metadata    = new AtomicReference[Array[Byte]](null)
    val onErrorRef  = new AtomicReference[Throwable](null)
    @volatile var completed: Boolean = false
    def onNext(r: org.apache.arrow.flight.PutResult): Unit =
      // Copy the metadata bytes out synchronously -- the producer releases the
      // backing ArrowBuf as soon as run() returns.
      val buf = r.getApplicationMetadata
      if buf != null then
        val bytes = new Array[Byte](buf.readableBytes().toInt)
        buf.getBytes(buf.readerIndex(), bytes)
        metadata.set(bytes)
    def onError(t: Throwable): Unit = onErrorRef.set(t)
    def onCompleted(): Unit         = completed = true

  private def runUpdate(producer: FlightProducerImpl, peer: String, sql: String): RecordingPutListener =
    val cmd = FlightSql.CommandStatementUpdate.newBuilder().setQuery(sql).build()
    val ack = new RecordingPutListener
    // We never read the FlightStream for a plain statement update (no bound
    // params), so null is safe here.
    producer.acceptPutStatement(cmd, fakeCallContext(peer), null, ack).run()
    ack

  "FlightProducerImpl.acceptPutStatement" should
    "execute a literal INSERT through the router and ack a DoPutUpdateResult" in:
      val (producer, sent, _, peer) = setupProducer()
      val ack = runUpdate(producer, peer, "INSERT INTO t VALUES (1)")
      ack.onErrorRef.get() shouldBe null
      ack.completed shouldBe true
      // The statement actually reached a node.
      sent.exists(_._2.contains("INSERT INTO t VALUES (1)")) shouldBe true
      // The update count round-trips as a DoPutUpdateResult. The stubbed node
      // returns `SELECT 1 AS x` (one row), so the extracted count is 1.
      val meta = ack.metadata.get()
      meta should not be null
      FlightSql.DoPutUpdateResult.parseFrom(meta).getRecordCount shouldBe 1L

  it should "ack onError (not onCompleted) when no pool is bound to the peer" in:
    val (producer, _, _, _) = setupProducer()
    val ack = runUpdate(producer, "unbound-peer", "INSERT INTO t VALUES (1)")
    ack.completed shouldBe false
    ack.onErrorRef.get() should not be null

  // ---- acceptPutPreparedStatementUpdate: the path Arrow JDBC / DBeaver use ----
  // (they prepare every statement, so a literal INSERT arrives as a prepared
  // update, not a CommandStatementUpdate).

  private def runPreparedUpdate(producer: FlightProducerImpl, peer: String, sql: String): RecordingPutListener =
    val prep   = runPrepare(producer, peer, sql)
    val handle = decodePrepareResult(prep.onNextValue.get()).getPreparedStatementHandle
    val cmd = FlightSql.CommandPreparedStatementUpdate
      .newBuilder()
      .setPreparedStatementHandle(handle)
      .build()
    val ack = new RecordingPutListener
    producer.acceptPutPreparedStatementUpdate(cmd, fakeCallContext(peer), null, ack).run()
    ack

  "FlightProducerImpl.acceptPutPreparedStatementUpdate" should
    "execute a prepared literal INSERT and ack a DoPutUpdateResult" in:
      val (producer, sent, _, peer) = setupProducer()
      val ack = runPreparedUpdate(producer, peer, "INSERT INTO t VALUES (1)")
      ack.onErrorRef.get() shouldBe null
      ack.completed shouldBe true
      sent.exists(_._2.contains("INSERT INTO t VALUES (1)")) shouldBe true
      FlightSql.DoPutUpdateResult.parseFrom(ack.metadata.get()).getRecordCount shouldBe 1L

  it should "ack onError for an unknown prepared-statement handle" in:
    val (producer, _, _, peer) = setupProducer()
    val cmd = FlightSql.CommandPreparedStatementUpdate
      .newBuilder()
      .setPreparedStatementHandle(ByteString.copyFromUtf8("bogus-handle"))
      .build()
    val ack = new RecordingPutListener
    producer.acceptPutPreparedStatementUpdate(cmd, fakeCallContext(peer), null, ack).run()
    ack.completed shouldBe false
    ack.onErrorRef.get() should not be null