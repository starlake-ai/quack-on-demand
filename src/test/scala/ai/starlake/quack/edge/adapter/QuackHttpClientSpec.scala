package ai.starlake.quack.edge.adapter

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Covers [[QuackHttpClient]], whose only path is the native wire client.
  *
  * Why this spec is shaped the way it is:
  *
  *   - The [[QuackProtocol]] is injected via the `protocolFactory` constructor parameter. A
  *     [[FakeTransport]] queue-fed with canned wire bytes from [[QuackTestFixtures]] drives the
  *     protocol's state machine end-to-end without any real network I/O.
  *   - There is no embedded fallback any more (DuckDB 2.0's JDBC dropped the Arrow export it
  *     needed), so the constructor takes no client-mode flag and every call goes to the protocol.
  */
class QuackHttpClientSpec extends AnyFunSpec with Matchers:

  private val endpoint = "quack:127.0.0.1:21900"
  private val token    = "test-token"
  private val connId   = "conn-test-7"

  /** The prelude a session handle rides on; the fakes answer it themselves and count it apart, so
    * every other count below is of the statements alone.
    */
  private val prelude                               = "CALL system.main.qod_session('test.handle')"
  private val authorize: () => String               = () => prelude
  private def isPrelude(body: Array[Byte]): Boolean =
    new String(body, java.nio.charset.StandardCharsets.ISO_8859_1)
      .contains("CALL system.main.qod_session(")
  private val preludeOk: Array[Byte] =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.ONE, false, false)

  /** Records every POST in order, returns canned responses from a queue. Exhausting the queue
    * raises a [[NoSuchElementException]] straight from the iterator -- that signals an over-eager
    * round trip and is the right place for the test to fail.
    */
  private final class FakeTransport(
      responses: Iterator[Array[Byte]],
      refusePrelude: Option[String] = None
  ) extends QuackTransport:
    val requests: ArrayBuffer[(URI, Array[Byte])]          = ArrayBuffer.empty
    val postCount: AtomicInteger                           = AtomicInteger(0)
    val preludes: AtomicInteger                            = AtomicInteger(0)
    val order: ArrayBuffer[String]                         = ArrayBuffer.empty
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      if isPrelude(body) then
        preludes.incrementAndGet()
        order += "prelude"
        refusePrelude.map(QuackTestFixtures.serializeSampleErrorResponse).getOrElse(preludeOk)
      else
        postCount.incrementAndGet()
        requests += ((uri, body.clone()))
        order += s"message-${QuackNativeBridge.parseMessageType(body)}"
        responses.next()
    }

  /** Always-failing transport for the permanent-error path. */
  private final class FailingTransport(err: QuackWireError) extends QuackTransport:
    val postCount: AtomicInteger                           = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      postCount.incrementAndGet()
      throw err
    }

  /** Answers by message type and records every request type in arrival order. `onPrepare` gets the
    * 1-based index of the PREPARE so a test can make one of them fail or block. CONNECT, DISCONNECT
    * and HEARTBEAT always succeed.
    */
  private final class ScriptedTransport(
      onPrepare: Int => IO[Array[Byte]],
      onConnect: IO[Array[Byte]] = IO(QuackTestFixtures.serializeSampleConnectionResponse(connId))
  ) extends QuackTransport:
    val types: ConcurrentLinkedQueue[Int]                  = ConcurrentLinkedQueue[Int]()
    private val prepares                                   = AtomicInteger(0)
    val preludes: AtomicInteger                            = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO.defer {
      val t = QuackNativeBridge.parseMessageType(body)
      if t == MessageType.PrepareRequest.wireOrdinal && isPrelude(body) then
        preludes.incrementAndGet()
        IO(preludeOk)
      else
        types.add(t)
        if t == MessageType.PrepareRequest.wireOrdinal then onPrepare(prepares.incrementAndGet())
        else if t == MessageType.ConnectionRequest.wireOrdinal then onConnect
        else IO(QuackTestFixtures.serializeSampleConnectionResponse(connId))
    }
    def seen: List[Int]       = types.asScala.toList
    def count(m: MessageType) = seen.count(_ == m.wireOrdinal)

  /** Polls `cond` until it holds or `within` elapses; true iff it held. */
  private def eventually(within: FiniteDuration)(cond: => Boolean): Boolean =
    val deadline = System.nanoTime() + within.toNanos
    var ok       = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(25)
      ok = cond
    ok

  private val prepareOk: Array[Byte] =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.TWO, false, true)

  /** Builds a `QuackHttpClient` with an injected protocol factory. */
  private def clientWith(
      allocator: RootAllocator,
      factory: BufferAllocator => QuackProtocol
  ): QuackHttpClient =
    new QuackHttpClient(allocator, protocolFactory = Some(factory))

  describe("query routing"):
    it("routes every query through the protocol factory: there is no other client") {
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          prepareOk,
          QuackTestFixtures.serializeSampleConnectionResponse("disconnect-ack")
        )
      )
      val allocator = new RootAllocator()
      try
        val built  = AtomicInteger(0)
        val client = new QuackHttpClient(
          allocator,
          protocolFactory = Some { alloc =>
            built.incrementAndGet()
            new QuackProtocol(transport, alloc)
          }
        )
        val resp = client.query(endpoint, token, "SELECT 1", None, authorize).unsafeRunSync()
        resp shouldBe a[QuackResponse.Ok]
        resp.asInstanceOf[QuackResponse.Ok].close()
        built.get() shouldBe 1
        transport.postCount.get() should be >= 2
      finally allocator.close()
    }

  describe("queryNative happy path"):
    it("returns QuackResponse.Ok with the expected Arrow row when the wire round-trips cleanly") {
      val uuid      = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSamplePrepareResponse(
            resultUuid = uuid,
            needsMoreFetch = false,
            withOneRowOneColChunk = true,
            columnName = "answer"
          ),
          // DISCONNECT ack -- the protocol's `close` swallows the body so
          // any valid response shape will do.
          QuackTestFixtures.serializeSampleConnectionResponse("disconnect-ack")
        )
      )
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 42", None, authorize).unsafeRunSync()
        resp match
          case QuackResponse.Ok(reader, _, close) =>
            try
              reader.loadNextBatch() shouldBe true
              val root = reader.getVectorSchemaRoot
              root.getRowCount shouldBe 1
              root.getVector(0).getObject(0).toString shouldBe "42"
              reader.loadNextBatch() shouldBe false
            finally close()
            // CONNECT + PREPARE + DISCONNECT = 3 posts. The DISCONNECT
            // fires exactly once -- via the reader's `close()` cascade
            // (DISCONNECT-owner rule). No double-fire from `queryNative`.
            transport.postCount.get() shouldBe 3
            val ordinals = transport.requests.map { case (_, body) =>
              QuackNativeBridge.parseMessageType(body)
            }
            ordinals(0) shouldBe MessageType.ConnectionRequest.wireOrdinal
            ordinals(1) shouldBe MessageType.PrepareRequest.wireOrdinal
            ordinals(2) shouldBe MessageType.DisconnectMessage.wireOrdinal
            // The session handle went first: right after CONNECT, before the statement.
            transport.preludes.get() shouldBe 1
            transport.order.take(2) shouldBe
              List(s"message-${MessageType.ConnectionRequest.wireOrdinal}", "prelude")
          case other => fail(s"expected QuackResponse.Ok, got $other")
      finally allocator.close()
    }

  describe("queryNative error mapping"):
    it(
      "maps QuackWireError.Permanent from the protocol to QuackResponse.Failed(QuackError.Permanent)"
    ) {
      val transport = FailingTransport(QuackWireError.Permanent("bad token"))
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 1", None, authorize).unsafeRunSync()
        resp match
          case QuackResponse.Failed(QuackError.Permanent(msg), _) =>
            msg should include("bad token")
          case other => fail(s"expected QuackResponse.Failed(Permanent, _), got $other")
      finally allocator.close()
    }

    it("maps QuackWireError.Transient to QuackResponse.Failed(QuackError.Transient)") {
      val transport = FailingTransport(QuackWireError.Transient("HTTP 503"))
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 1", None, authorize).unsafeRunSync()
        resp match
          case QuackResponse.Failed(QuackError.Transient(msg), _) =>
            msg should include("HTTP 503")
          case other => fail(s"expected QuackResponse.Failed(Transient, _), got $other")
      finally allocator.close()
    }

  describe("DISCONNECT-owner rule"):
    it("issues exactly one DISCONNECT_MESSAGE per successful native query") {
      // Same fixture shape as the happy-path test, but we count
      // DISCONNECT ordinals after `close()` and assert "exactly one" to
      // pin the plan §7.2 amendment: the chained reader's `close()`
      // cascades to `Connection.close()`; `QuackResponse.Ok.close` does
      // NOT call `conn.close()` again.
      val uuid      = java.math.BigInteger.ONE
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSamplePrepareResponse(
            resultUuid = uuid,
            needsMoreFetch = false,
            withOneRowOneColChunk = true,
            columnName = "n"
          ),
          QuackTestFixtures.serializeSampleConnectionResponse("ack-1")
        )
      )
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 1", None, authorize).unsafeRunSync()
        resp match
          case QuackResponse.Ok(reader, _, close) =>
            // Drain + close. Both `reader.close()` (which the
            // ChainedQuackArrowReader's close override executes) and the
            // outer `close` callback funnel through the same teardown
            // chain; we invoke the outer callback because that is what
            // QuackHttpAdapter does in production.
            try while reader.loadNextBatch() do ()
            finally close()
          case other => fail(s"expected QuackResponse.Ok, got $other")
        val disconnectOrdinals = transport.requests.count { case (_, body) =>
          QuackNativeBridge.parseMessageType(body) == MessageType.DisconnectMessage.wireOrdinal
        }
        disconnectOrdinals shouldBe 1
      finally allocator.close()
    }

  private def drainCount(reader: org.apache.arrow.vector.ipc.ArrowReader): Int =
    var n = 0
    while reader.loadNextBatch() do n += reader.getVectorSchemaRoot.getRowCount
    n

  describe("queryStamped (native bracket)"):
    it("runs prelude, sql, COMMIT-on-exhaustion, DISCONNECT-on-close, on one connection") {
      val allocator = new RootAllocator()
      try
        val transport = FakeTransport(
          Iterator(
            QuackTestFixtures.serializeSampleConnectionResponse(connId),
            // prelude result: empty
            QuackTestFixtures.serializeSamplePrepareResponse(
              java.math.BigInteger.ONE,
              false,
              false
            ),
            // user sql result: one row, value 42
            QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.TWO, false, true),
            // COMMIT result: empty
            QuackTestFixtures.serializeSamplePrepareResponse(
              java.math.BigInteger.TEN,
              false,
              false
            ),
            // DISCONNECT response content is ignored by Connection.close
            QuackTestFixtures.serializeSampleConnectionResponse(connId)
          )
        )
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val resp   = client
          .queryStamped(
            endpoint,
            token,
            authorize,
            "BEGIN; CALL stamp()",
            "USE db.main; INSERT INTO t VALUES (1)"
          )
          .unsafeRunSync()
        resp shouldBe a[QuackResponse.Ok]
        val ok = resp.asInstanceOf[QuackResponse.Ok]
        // Bracket so far: CONNECTION + PREPARE(prelude) + PREPARE(sql). COMMIT is lazy.
        transport.postCount.get() shouldBe 3
        // A session handle came first for the stamp and for the statement.
        transport.preludes.get() shouldBe 2
        drainCount(ok.rows) shouldBe 1
        // Exhaustion fired the COMMIT PREPARE, behind a handle of its own.
        transport.postCount.get() shouldBe 4
        transport.preludes.get() shouldBe 3
        ok.close()
        // Close cascades exactly one DISCONNECT.
        transport.postCount.get() shouldBe 5
      finally allocator.close()
    }

    it("propagates a COMMIT failure as a stream error, not a silent success") {
      val allocator = new RootAllocator()
      try
        val transport = FakeTransport(
          Iterator(
            QuackTestFixtures.serializeSampleConnectionResponse(connId),
            QuackTestFixtures
              .serializeSamplePrepareResponse(java.math.BigInteger.ONE, false, false),
            QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.TWO, false, true),
            QuackTestFixtures.serializeSampleErrorResponse("transaction conflict"),
            // DISCONNECT response for the trailing close (content ignored)
            QuackTestFixtures.serializeSampleConnectionResponse(connId)
          )
        )
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val ok     = client
          .queryStamped(
            endpoint,
            token,
            authorize,
            "BEGIN; CALL stamp()",
            "INSERT INTO t VALUES (1)"
          )
          .unsafeRunSync()
          .asInstanceOf[QuackResponse.Ok]
        ok.rows.loadNextBatch() shouldBe true // the Count row
        val err = intercept[QuackWireError.Permanent] {
          ok.rows.loadNextBatch() // exhaustion -> COMMIT -> ERROR_RESPONSE
        }
        err.msg should include("transaction conflict")
        // Release buffers so allocator.close() below does not report a leak; commit was
        // already attempted, so close only cascades the DISCONNECT.
        ok.close()
      finally allocator.close()
    }

    it("fires COMMIT at close when the caller abandons the stream early") {
      val allocator = new RootAllocator()
      try
        val transport = FakeTransport(
          Iterator(
            QuackTestFixtures.serializeSampleConnectionResponse(connId),
            QuackTestFixtures
              .serializeSamplePrepareResponse(java.math.BigInteger.ONE, false, false),
            QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.TWO, false, true),
            QuackTestFixtures
              .serializeSamplePrepareResponse(java.math.BigInteger.TEN, false, false),
            QuackTestFixtures.serializeSampleConnectionResponse(connId)
          )
        )
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val ok     = client
          .queryStamped(
            endpoint,
            token,
            authorize,
            "BEGIN; CALL stamp()",
            "INSERT INTO t VALUES (1)"
          )
          .unsafeRunSync()
          .asInstanceOf[QuackResponse.Ok]
        ok.close() // no drain at all
        // CONNECTION + 2 PREPAREs + COMMIT PREPARE + DISCONNECT
        transport.postCount.get() shouldBe 5
      finally allocator.close()
    }

    it("falls back to the plain unstamped path when the prelude fails (fail-open)") {
      val allocator = new RootAllocator()
      try
        val transport = FakeTransport(
          Iterator(
            QuackTestFixtures.serializeSampleConnectionResponse(connId),
            QuackTestFixtures.serializeSampleErrorResponse("ducklake_set_commit_message not found"),
            // Connection.close after the failed prelude
            QuackTestFixtures.serializeSampleConnectionResponse(connId),
            // fresh plain connection + sql
            QuackTestFixtures.serializeSampleConnectionResponse("conn-fallback"),
            QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.TWO, false, true),
            // DISCONNECT response for the trailing close (content ignored)
            QuackTestFixtures.serializeSampleConnectionResponse("conn-fallback")
          )
        )
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val resp   = client
          .queryStamped(
            endpoint,
            token,
            authorize,
            "BEGIN; CALL stamp()",
            "INSERT INTO t VALUES (1)"
          )
          .unsafeRunSync()
        resp shouldBe a[QuackResponse.Ok]
        val ok = resp.asInstanceOf[QuackResponse.Ok]
        drainCount(ok.rows) shouldBe 1
        // Release buffers so allocator.close() below does not report a leak.
        ok.close()
      finally allocator.close()
    }

  describe("native connection release"):
    it("closes the connection when PREPARE answers ERROR_RESPONSE, and stops its heartbeats") {
      val transport = ScriptedTransport(_ =>
        IO(QuackTestFixtures.serializeSampleErrorResponse("Catalog Error: no such table"))
      )
      val allocator = new RootAllocator()
      try
        // Lease 3 s: a beat every second, so a leaked heartbeat shows within the wait below.
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc, 3))
        val resp   =
          client.query(endpoint, token, "SELECT * FROM missing", None, authorize).unsafeRunSync()
        resp match
          case QuackResponse.Failed(QuackError.Permanent(msg), _) => msg should include("no such")
          case other => fail(s"expected Failed(Permanent), got $other")
        transport.count(MessageType.DisconnectMessage) shouldBe 1
        Thread.sleep(1500)
        transport.count(MessageType.HeartbeatRequest) shouldBe 0
      finally allocator.close()
    }

    it("closes the connection and frees the result when a column type has no Arrow mapping") {
      val transport =
        ScriptedTransport(_ => IO(QuackTestFixtures.serializeTypeColumnPrepareResponse()))
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        (1 to 50).foreach { _ =>
          client
            .query(endpoint, token, "SELECT get_type(42)", None, authorize)
            .unsafeRunSync() match
            case QuackResponse.Failed(QuackError.Permanent(msg), _) => msg should include("VARCHAR")
            case other => fail(s"expected Failed(Permanent), got $other")
        }
        transport.count(MessageType.DisconnectMessage) shouldBe 50
        allocator.getAllocatedMemory shouldBe 0L
      finally allocator.close()
    }

    it("rolls a stamped write back and frees the result when a column type has no Arrow mapping") {
      val transport = ScriptedTransport {
        case 1 =>
          IO(
            QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.ONE, false, false)
          )
        case _ => IO(QuackTestFixtures.serializeTypeColumnPrepareResponse())
      }
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        client
          .queryStamped(endpoint, token, authorize, "BEGIN; CALL stamp()", "SELECT get_type(42)")
          .unsafeRunSync() match
          case QuackResponse.Failed(QuackError.Permanent(msg), _) => msg should include("VARCHAR")
          case other => fail(s"expected Failed(Permanent), got $other")
        transport.count(MessageType.PrepareRequest) shouldBe 2
        transport.count(MessageType.DisconnectMessage) shouldBe 1
        allocator.getAllocatedMemory shouldBe 0L
      finally allocator.close()
    }

    it("closes the connection when a cancel lands while PREPARE is in flight") {
      // PREPARE blocks uninterruptibly (like JdkHttpTransport), so the timeout's cancel is only
      // observed once it returns: the reader built from that response must be closed, not dropped.
      val transport = ScriptedTransport(_ => IO.blocking { Thread.sleep(800); prepareOk })
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc, 3))
        val got    =
          client.query(endpoint, token, "SELECT 42", None, authorize).timeout(100.millis).attempt
        got.unsafeRunSync().left.toOption.get shouldBe a[java.util.concurrent.TimeoutException]
        eventually(5.seconds)(transport.count(MessageType.DisconnectMessage) == 1) shouldBe true
        val afterClose = transport.count(MessageType.HeartbeatRequest)
        Thread.sleep(1500)
        transport.count(MessageType.HeartbeatRequest) shouldBe afterClose
      finally allocator.close() // throws if the dropped reader leaked Arrow buffers
    }

    it("skips PREPARE and closes the connection when a cancel lands during CONNECT") {
      val transport = ScriptedTransport(
        _ => IO(prepareOk),
        onConnect = IO.blocking {
          Thread.sleep(800)
          QuackTestFixtures.serializeSampleConnectionResponse(connId)
        }
      )
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val got    =
          client.query(endpoint, token, "DELETE FROM t", None, authorize).timeout(100.millis)
        got.attempt
          .unsafeRunSync()
          .left
          .toOption
          .get shouldBe a[java.util.concurrent.TimeoutException]
        eventually(5.seconds)(transport.count(MessageType.DisconnectMessage) == 1) shouldBe true
        // A timed-out caller's statement is never sent.
        transport.count(MessageType.PrepareRequest) shouldBe 0
      finally allocator.close()
    }

    it("rolls a cancelled stamped write back through DISCONNECT, never COMMIT") {
      val transport = ScriptedTransport {
        case 1 =>
          IO(QuackTestFixtures.serializeSamplePrepareResponse(BigInt(1).bigInteger, false, false))
        case _ => IO.blocking { Thread.sleep(800); prepareOk }
      }
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val got    = client
          .queryStamped(
            endpoint,
            token,
            authorize,
            "BEGIN; CALL stamp()",
            "INSERT INTO t VALUES (1)"
          )
          .timeout(100.millis)
          .attempt
          .unsafeRunSync()
        got.left.toOption.get shouldBe a[java.util.concurrent.TimeoutException]
        eventually(5.seconds)(transport.count(MessageType.DisconnectMessage) == 1) shouldBe true
        // prelude + sql only: a cancelled caller was told the write failed, so no COMMIT.
        transport.count(MessageType.PrepareRequest) shouldBe 2
      finally allocator.close()
    }

    it("closes the stamped connection when the prelude PREPARE fails, then retries plain") {
      val transport = ScriptedTransport {
        case 1 => IO(QuackTestFixtures.serializeSampleErrorResponse("no stamp function"))
        case _ => IO(QuackTestFixtures.serializeSampleErrorResponse("constraint violated"))
      }
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val resp   = client
          .queryStamped(
            endpoint,
            token,
            authorize,
            "BEGIN; CALL stamp()",
            "INSERT INTO t VALUES (1)"
          )
          .unsafeRunSync()
        resp shouldBe a[QuackResponse.Failed]
        // Two connections (stamped bracket, plain fallback), both released.
        transport.count(MessageType.ConnectionRequest) shouldBe 2
        transport.count(MessageType.DisconnectMessage) shouldBe 2
      finally allocator.close()
    }

  describe("session handles"):
    it("never sends the statement when the node refuses its session handle, and fails Transient") {
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSampleConnectionResponse("disconnect-ack")
        ),
        refusePrelude = Some("qod-node: session handle refused: handle MAC does not verify")
      )
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, _ => new QuackProtocol(transport, allocator))
        client.query(endpoint, token, "DELETE FROM t", None, authorize).unsafeRunSync() match
          case QuackResponse.Failed(QuackError.Transient(msg), _) =>
            msg should include("refused the statement's session handle")
            msg should include("does not verify")
          case other => fail(s"expected Failed(Transient), got $other")
        // CONNECT, then DISCONNECT: the statement's PREPARE was never sent.
        transport.postCount.get() shouldBe 2
      finally allocator.close()
    }

    it("fails Permanent, sending no statement, when there is no principal to vouch for") {
      val transport = ScriptedTransport(_ => IO(prepareOk))
      val allocator = new RootAllocator()
      try
        val client             = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        val none: () => String =
          () => throw new IllegalArgumentException("no principal is bound to this statement")
        client.query(endpoint, token, "SELECT 1", None, none).unsafeRunSync() match
          case QuackResponse.Failed(QuackError.Permanent(msg), _) =>
            msg should include("no principal")
          case other => fail(s"expected Failed(Permanent), got $other")
        transport.count(MessageType.PrepareRequest) shouldBe 0
        transport.count(MessageType.DisconnectMessage) shouldBe 1
      finally allocator.close()
    }

    it("sends no handle at all for the canary") {
      val transport = ScriptedTransport(_ => IO(prepareOk))
      val allocator = new RootAllocator()
      try
        val client = clientWith(allocator, alloc => new QuackProtocol(transport, alloc))
        client.queryWithoutHandle(endpoint, token, "SELECT 1").unsafeRunSync() match
          case QuackResponse.Ok(_, _, close) => close()
          case other                         => fail(s"expected Ok, got $other")
        transport.preludes.get() shouldBe 0
        transport.count(MessageType.PrepareRequest) shouldBe 1
      finally allocator.close()
    }
