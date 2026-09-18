package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
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
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.flight.FlightProducer.{CallContext, ServerStreamListener}
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.flight.{FlightRuntimeException, FlightStatusCode}
import org.apache.arrow.vector.VectorSchemaRoot
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

/** #106: two independent, empirically-reproduced FlightSQL edge failures.
  *
  *   - Signature 2 - DuckDB streams `Success: BOOLEAN` for
  *     BEGIN/COMMIT/DROP/ALTER/VACUUM/ANALYZE/SET instead of the `Count: BIGINT` the edge
  *     advertised, tripping ADBC's advertised-vs-actual schema check.
  *     [[FlightProducerImpl.coerceSuccessToCount]] normalizes the stream.
  *   - Signature 1 - a stamped write's COMMIT epilogue can lose a genuine DuckLake concurrency race
  *     AFTER the statement's own result already streamed, surfacing as an opaque internal error.
  *     [[FlightProducerImpl.commitConflictOrInternal]] classifies it as a retryable UNAVAILABLE
  *     instead.
  */
class FlightProducerImplSuccessCoercionSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  /** Backed by a stubbed [[QuackHttpClient]] whose `query` override is swappable per test, so a
    * DML/DDL-advertised statement can return whatever Arrow shape the node would really produce.
    */
  private def setupProducer(
      queryResponse: String => IO[QuackResponse]
  ): (FlightProducerImpl, String) =
    val backend = new QuackBackend:
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
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val client = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        queryResponse(sql)
    val adapter  = new QuackHttpAdapter(client, tracker)
    val router   = new FlightSqlRouter(sup, new SessionRegistry, tracker, adapter)
    val producer = new FlightProducerImpl(router)

    val peer = s"peer-${java.util.UUID.randomUUID()}"
    ConnectionContext.bind(peer, poolKey, s"conn-${java.util.UUID.randomUUID()}", "alice")
    (producer, peer)

  private val emptyHeaders = new org.apache.arrow.flight.CallHeaders:
    def get(k: String)                          = null
    def getByte(k: String)                      = null
    def getAll(k: String)                       = java.util.Collections.emptyList[String]()
    def getAllByte(k: String)                   = java.util.Collections.emptyList[Array[Byte]]()
    def insert(k: String, v: String): Unit      = ()
    def insert(k: String, v: Array[Byte]): Unit = ()
    def keys()                                  = java.util.Collections.emptySet[String]()
    def containsKey(k: String)                  = false

  private def fakeContext(peer: String): CallContext = new CallContext:
    def peerIdentity() = peer
    def isCancelled()  = false
    def getHeaders()   = emptyHeaders
    def getMiddleware[T <: org.apache.arrow.flight.FlightServerMiddleware](
        key: org.apache.arrow.flight.FlightServerMiddleware.Key[T]
    ): T = null.asInstanceOf[T]
    def getMiddleware() = java.util.Collections.emptyMap[
      org.apache.arrow.flight.FlightServerMiddleware.Key[?],
      org.apache.arrow.flight.FlightServerMiddleware
    ]()

  /** Captures the schema + Count value the producer streamed, then blocks the test thread until a
    * terminal signal (completed/error) arrives - the prepared path streams asynchronously.
    */
  private final class CapturingListener extends ServerStreamListener:
    @volatile var schema: org.apache.arrow.vector.types.pojo.Schema = null
    @volatile var rowCount: Int                                     = -1
    @volatile var countValue: Long                                  = -1L
    @volatile var putNextCalls: Int                                 = 0
    val onErrorRef                     = new AtomicReference[Throwable](null)
    private val terminal               = new java.util.concurrent.CountDownLatch(1)
    private var root: VectorSchemaRoot = null
    def awaitTerminal(): Unit          =
      if !terminal.await(10, java.util.concurrent.TimeUnit.SECONDS) then
        throw new AssertionError("stream saw neither completed() nor error() within 10s")
    override def isCancelled(): Boolean                = false
    override def isReady(): Boolean                    = true
    override def setOnCancelHandler(h: Runnable): Unit = ()
    override def start(r: VectorSchemaRoot): Unit      =
      root = r
      schema = r.getSchema
    override def start(
        r: VectorSchemaRoot,
        dict: org.apache.arrow.vector.dictionary.DictionaryProvider
    ): Unit = start(r)
    override def start(
        r: VectorSchemaRoot,
        dict: org.apache.arrow.vector.dictionary.DictionaryProvider,
        opts: org.apache.arrow.vector.ipc.message.IpcOption
    ): Unit = start(r)
    override def putNext(): Unit =
      putNextCalls += 1
      rowCount = root.getRowCount
      Option(root.getVector("Count")).foreach { v =>
        val bv = v.asInstanceOf[org.apache.arrow.vector.BigIntVector]
        if rowCount > 0 && !bv.isNull(0) then countValue = bv.get(0)
      }
    override def putNext(metadata: org.apache.arrow.memory.ArrowBuf): Unit     = putNext()
    override def putMetadata(metadata: org.apache.arrow.memory.ArrowBuf): Unit = ()
    override def error(t: Throwable): Unit                                     =
      onErrorRef.set(t)
      terminal.countDown()
    override def completed(): Unit = terminal.countDown()

  private final class RecordingResultListener
      extends org.apache.arrow.flight.FlightProducer.StreamListener[org.apache.arrow.flight.Result]:
    val ref = new AtomicReference[org.apache.arrow.flight.Result](null)
    def onNext(r: org.apache.arrow.flight.Result): Unit = ref.set(r)
    def onError(t: Throwable): Unit                     = ()
    def onCompleted(): Unit                             = ()

  // ---- coerceSuccessToCount: pure, direct ----

  "coerceSuccessToCount" should "coerce a single-row Success:BOOLEAN root into Count:int64 = 0" in:
    val (producer, _) = setupProducer(_ => IO.pure(TestArrow.okResponse()))
    val reader        = TestArrow.readerFor("SELECT true AS \"Success\"")
    try
      reader.loadNextBatch()
      val root    = reader.getVectorSchemaRoot
      val coerced = producer.coerceSuccessToCount(root)
      coerced should not be theSameInstanceAs(root)
      coerced.getSchema.getFields.size() shouldBe 1
      coerced.getSchema.getFields.get(0).getName shouldBe "Count"
      coerced.getRowCount shouldBe 1
      coerced
        .getVector("Count")
        .asInstanceOf[org.apache.arrow.vector.BigIntVector]
        .get(0) shouldBe 0L
      coerced.close()
    finally reader.close()

  it should "pass a Count:int64 root through unchanged" in:
    val (producer, _) = setupProducer(_ => IO.pure(TestArrow.okResponse()))
    val reader        = TestArrow.readerFor("SELECT 1 AS \"Count\"")
    try
      reader.loadNextBatch()
      val root = reader.getVectorSchemaRoot
      producer.coerceSuccessToCount(root) should be theSameInstanceAs root
    finally reader.close()

  it should "pass a multi-column result through unchanged, even if column 0 is a boolean Success" in:
    val (producer, _) = setupProducer(_ => IO.pure(TestArrow.okResponse()))
    val reader        = TestArrow.readerFor("SELECT true AS \"Success\", 'x' AS detail")
    try
      reader.loadNextBatch()
      val root = reader.getVectorSchemaRoot
      producer.coerceSuccessToCount(root) should be theSameInstanceAs root
    finally reader.close()

  it should "coerce a zero-row Success:BOOLEAN stream too (BEGIN's real live shape)" in:
    val (producer, _) = setupProducer(_ => IO.pure(TestArrow.okResponse()))
    val reader        = TestArrow.readerFor("SELECT true AS \"Success\" WHERE FALSE")
    try
      reader.loadNextBatch()
      val root = reader.getVectorSchemaRoot
      root.getRowCount shouldBe 0
      val coerced = producer.coerceSuccessToCount(root)
      coerced should not be theSameInstanceAs(root)
      coerced.getRowCount shouldBe 1
      coerced
        .getVector("Count")
        .asInstanceOf[org.apache.arrow.vector.BigIntVector]
        .get(0) shouldBe 0L
      coerced.close()
    finally reader.close()

  it should "pass an empty stream of an unrelated shape through unchanged" in:
    val (producer, _) = setupProducer(_ => IO.pure(TestArrow.okResponse()))
    val reader        = TestArrow.readerFor("SELECT 1 AS x WHERE FALSE")
    try
      reader.loadNextBatch()
      val root = reader.getVectorSchemaRoot
      root.getRowCount shouldBe 0
      producer.coerceSuccessToCount(root) should be theSameInstanceAs root
    finally reader.close()

  // ---- commitConflictOrInternal: pure, direct ----

  "commitConflictOrInternal" should "classify a DuckLake commit-conflict message as a retryable UNAVAILABLE" in:
    val (producer, _) = setupProducer(_ => IO.pure(TestArrow.okResponse()))
    val t             = QuackWireError.Permanent(
      "Invalid Input Error: Failed to commit: Failed to commit DuckLake transaction.\n" +
        "Failed to load DuckLake - table with id 76 references schema id 1 that does not exist"
    )
    val ex = producer.commitConflictOrInternal("streaming Arrow batches", t)
    ex shouldBe a[FlightRuntimeException]
    ex.asInstanceOf[FlightRuntimeException].status().code() shouldBe FlightStatusCode.UNAVAILABLE

  it should "fall back to the opaque internal error for anything else" in:
    val (producer, _) = setupProducer(_ => IO.pure(TestArrow.okResponse()))
    val ex            =
      producer.commitConflictOrInternal("streaming Arrow batches", new RuntimeException("boom"))
    ex shouldBe a[FlightRuntimeException]
    ex.asInstanceOf[FlightRuntimeException].status().code() shouldBe FlightStatusCode.INTERNAL
    ex.getMessage should include("internal error")

  // ---- edge-level: the literal (un-prepared) DoGet path ----

  "getStreamStatement" should "coerce a DDL-advertised Success:BOOLEAN node reply to Count:int64 = 0" in:
    val (producer, peer) = setupProducer { _ =>
      IO.pure(QuackResponse.Ok(TestArrow.readerFor("SELECT true AS \"Success\""), 5L, () => ()))
    }
    val ticket = FlightSql.TicketStatementQuery
      .newBuilder()
      .setStatementHandle(
        com.google.protobuf.ByteString.copyFromUtf8("DROP TABLE IF EXISTS nosuch")
      )
      .build()
    val listener = new CapturingListener
    producer.getStreamStatement(ticket, fakeContext(peer), listener)
    listener.awaitTerminal()
    listener.onErrorRef.get() shouldBe null
    listener.schema.getFields.size() shouldBe 1
    listener.schema.getFields.get(0).getName shouldBe "Count"
    listener.rowCount shouldBe 1
    listener.countValue shouldBe 0L
    // Exactly one putNext for the coerced row, not one per (discarded) node batch.
    listener.putNextCalls shouldBe 1

  // Regression: BEGIN's real live shape is a ZERO-ROW Success:BOOLEAN result (the rowCount==1
  // gate this test pins against was caught live during #106 verification - see coerceSuccessToCount).
  it should "coerce a DDL-advertised statement's zero-row Success:BOOLEAN node reply too" in:
    val (producer, peer) = setupProducer { _ =>
      IO.pure(
        QuackResponse.Ok(
          TestArrow.readerFor("SELECT true AS \"Success\" WHERE FALSE"),
          5L,
          () => ()
        )
      )
    }
    val ticket = FlightSql.TicketStatementQuery
      .newBuilder()
      .setStatementHandle(com.google.protobuf.ByteString.copyFromUtf8("BEGIN"))
      .build()
    val listener = new CapturingListener
    producer.getStreamStatement(ticket, fakeContext(peer), listener)
    listener.awaitTerminal()
    listener.onErrorRef.get() shouldBe null
    listener.schema.getFields.get(0).getName shouldBe "Count"
    listener.rowCount shouldBe 1
    listener.countValue shouldBe 0L

  it should "leave an ordinary Count:int64 DML reply untouched" in:
    val (producer, peer) = setupProducer { _ =>
      IO.pure(
        QuackResponse.Ok(TestArrow.readerFor("SELECT CAST(3 AS BIGINT) AS \"Count\""), 5L, () => ())
      )
    }
    val ticket = FlightSql.TicketStatementQuery
      .newBuilder()
      .setStatementHandle(com.google.protobuf.ByteString.copyFromUtf8("DELETE FROM t"))
      .build()
    val listener = new CapturingListener
    producer.getStreamStatement(ticket, fakeContext(peer), listener)
    listener.awaitTerminal()
    listener.onErrorRef.get() shouldBe null
    listener.countValue shouldBe 3L

  it should "leave a real SELECT result untouched even if its only column is boolean" in:
    val (producer, peer) = setupProducer { _ =>
      IO.pure(QuackResponse.Ok(TestArrow.readerFor("SELECT true AS flag"), 5L, () => ()))
    }
    val ticket = FlightSql.TicketStatementQuery
      .newBuilder()
      .setStatementHandle(com.google.protobuf.ByteString.copyFromUtf8("SELECT true AS flag"))
      .build()
    val listener = new CapturingListener
    producer.getStreamStatement(ticket, fakeContext(peer), listener)
    listener.awaitTerminal()
    listener.onErrorRef.get() shouldBe null
    listener.schema.getFields.get(0).getName shouldBe "flag"

  // ---- edge-level: the prepared-statement DoGet path ----

  "getStreamPreparedStatement" should
    "coerce a DDL-advertised prepared statement's Success:BOOLEAN reply to Count:int64 = 0" in:
      val (producer, peer) = setupProducer { _ =>
        IO.pure(QuackResponse.Ok(TestArrow.readerFor("SELECT true AS \"Success\""), 5L, () => ()))
      }
      val prepReq = FlightSql.ActionCreatePreparedStatementRequest
        .newBuilder()
        .setQuery("ALTER TABLE t RENAME TO t2")
        .build()
      val prepListener = new RecordingResultListener
      producer.createPreparedStatement(prepReq, fakeContext(peer), prepListener)
      val prepResult = com.google.protobuf.Any
        .parseFrom(prepListener.ref.get().getBody)
        .unpack(classOf[FlightSql.ActionCreatePreparedStatementResult])
      val handle = prepResult.getPreparedStatementHandle

      val cmd = FlightSql.CommandPreparedStatementQuery
        .newBuilder()
        .setPreparedStatementHandle(handle)
        .build()
      val listener = new CapturingListener
      producer.getStreamPreparedStatement(cmd, fakeContext(peer), listener)
      listener.awaitTerminal()
      listener.onErrorRef.get() shouldBe null
      listener.schema.getFields.size() shouldBe 1
      listener.schema.getFields.get(0).getName shouldBe "Count"
      listener.countValue shouldBe 0L
      listener.putNextCalls shouldBe 1
