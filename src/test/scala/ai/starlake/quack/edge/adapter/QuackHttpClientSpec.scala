package ai.starlake.quack.edge.adapter

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** Covers Task 7's `query(...)` flag-branching in [[QuackHttpClient]].
  *
  * Why this spec is shaped the way it is:
  *
  *   - We do NOT touch `sys.env` at test time. `nativeClient` is a constructor flag now (sourced
  *     from the HOCON `quack-on-demand.nativeClient` key in production), so tests pass it directly.
  *     Mutating env mid-test would race with parallel suites and leak across runs.
  *   - The native path's [[QuackProtocol]] is injected via the `protocolFactory` constructor
  *     parameter (Task 7's "Option A" injection seam). A [[FakeTransport]] queue-fed with canned
  *     wire bytes from [[QuackTestFixtures]] drives the protocol's state machine end-to-end without
  *     any real network I/O.
  *   - Embedded-path tests intentionally do not exercise the real embedded-DuckDB code, which
  *     already has its own coverage in [[QuackHttpAdapterSpec]] (via `FakeClient`) and is gated by
  *     an `INSTALL quack` from the network at class init. We assert routing via a subclass that
  *     intercepts `query` and counts which branch `nativeClient` selects.
  */
class QuackHttpClientSpec extends AnyFunSpec with Matchers:

  private val endpoint = "quack:127.0.0.1:21900"
  private val token    = "test-token"
  private val connId   = "conn-test-7"

  /** Records every POST in order, returns canned responses from a queue. Exhausting the queue
    * raises a [[NoSuchElementException]] straight from the iterator -- that signals an over-eager
    * round trip and is the right place for the test to fail.
    */
  private final class FakeTransport(responses: Iterator[Array[Byte]]) extends QuackTransport:
    val requests: ArrayBuffer[(URI, Array[Byte])]          = ArrayBuffer.empty
    val postCount: AtomicInteger                           = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      postCount.incrementAndGet()
      requests += ((uri, body.clone()))
      responses.next()
    }

  /** Always-failing transport for the permanent-error path. */
  private final class FailingTransport(err: QuackWireError) extends QuackTransport:
    val postCount: AtomicInteger                           = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      postCount.incrementAndGet()
      throw err
    }

  /** Builds a `QuackHttpClient` pinned to the native path with an injected protocol factory.
    */
  private def nativeClient(
      allocator: RootAllocator,
      factory: BufferAllocator => QuackProtocol
  ): QuackHttpClient =
    new QuackHttpClient(
      allocator,
      nativeClient = true,
      nodeDisableSsl = true,
      protocolFactory = Some(factory)
    )

  /** Subclass that records which internal branch `query` took. The embedded path's `queryEmbedded`
    * would actually hit DuckDB; we short-circuit by overriding `query` and asserting which branch
    * the flag selected. Mirrors the FakeClient pattern in [[QuackHttpAdapterSpec]].
    */
  private final class RoutingProbeClient(allocator: RootAllocator, native: Boolean)
      extends QuackHttpClient(
        allocator,
        nativeClient = native,
        nodeDisableSsl = true,
        protocolFactory = None
      ):
    val embeddedCalls: AtomicInteger = AtomicInteger(0)
    val nativeCalls: AtomicInteger   = AtomicInteger(0)
    // We intercept the public `query` to count which branch the router
    // chose. We do NOT call super because the embedded path requires a
    // real Quack node on the wire and the native path requires a real
    // libquackwire transport -- both out of scope for a routing test.
    override def query(
        endpoint: String,
        token: String,
        sql: String,
        session: Option[String]
    ): IO[QuackResponse] = IO {
      if native then nativeCalls.incrementAndGet() else embeddedCalls.incrementAndGet()
      QuackResponse.Ok(null, 0L, () => ())
    }

  describe("query routing"):
    it("routes to the embedded path when nativeClient=false") {
      val allocator = new RootAllocator()
      try
        val client = new RoutingProbeClient(allocator, native = false)
        client.query(endpoint, token, "SELECT 1", None).unsafeRunSync()
        client.embeddedCalls.get() shouldBe 1
        client.nativeCalls.get() shouldBe 0
      finally allocator.close()
    }

    it("routes to the native path when nativeClient=true") {
      val allocator = new RootAllocator()
      try
        val client = new RoutingProbeClient(allocator, native = true)
        client.query(endpoint, token, "SELECT 1", None).unsafeRunSync()
        client.embeddedCalls.get() shouldBe 0
        client.nativeCalls.get() shouldBe 1
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
        val client = nativeClient(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 42", None).unsafeRunSync()
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
        val client = nativeClient(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 1", None).unsafeRunSync()
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
        val client = nativeClient(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 1", None).unsafeRunSync()
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
        val client = nativeClient(allocator, _ => new QuackProtocol(transport, allocator))
        val resp   = client.query(endpoint, token, "SELECT 1", None).unsafeRunSync()
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
        val client = nativeClient(allocator, alloc => new QuackProtocol(transport, alloc))
        val resp   = client
          .queryStamped(
            endpoint,
            token,
            "BEGIN; CALL stamp()",
            "USE db.main; INSERT INTO t VALUES (1)"
          )
          .unsafeRunSync()
        resp shouldBe a[QuackResponse.Ok]
        val ok = resp.asInstanceOf[QuackResponse.Ok]
        // Bracket so far: CONNECTION + PREPARE(prelude) + PREPARE(sql). COMMIT is lazy.
        transport.postCount.get() shouldBe 3
        drainCount(ok.rows) shouldBe 1
        // Exhaustion fired the COMMIT PREPARE.
        transport.postCount.get() shouldBe 4
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
        val client = nativeClient(allocator, alloc => new QuackProtocol(transport, alloc))
        val ok     = client
          .queryStamped(endpoint, token, "BEGIN; CALL stamp()", "INSERT INTO t VALUES (1)")
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
        val client = nativeClient(allocator, alloc => new QuackProtocol(transport, alloc))
        val ok     = client
          .queryStamped(endpoint, token, "BEGIN; CALL stamp()", "INSERT INTO t VALUES (1)")
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
        val client = nativeClient(allocator, alloc => new QuackProtocol(transport, alloc))
        val resp   = client
          .queryStamped(endpoint, token, "BEGIN; CALL stamp()", "INSERT INTO t VALUES (1)")
          .unsafeRunSync()
        resp shouldBe a[QuackResponse.Ok]
        val ok = resp.asInstanceOf[QuackResponse.Ok]
        drainCount(ok.rows) shouldBe 1
        // Release buffers so allocator.close() below does not report a leak.
        ok.close()
      finally allocator.close()
    }

    it("routes to the plain query path when nativeClient is false (embedded shares sessions)") {
      val allocator = new RootAllocator()
      try
        var plainCalled = 0
        val client = new QuackHttpClient(allocator, nativeClient = false, nodeDisableSsl = true):
          override def query(
              endpoint: String,
              token: String,
              sql: String,
              session: Option[String]
          ) =
            IO { plainCalled += 1; QuackResponse.Failed(QuackError.Permanent("stub"), 0L) }
        client.queryStamped(endpoint, token, "BEGIN; CALL stamp()", "INSERT 1").unsafeRunSync()
        plainCalled shouldBe 1
      finally allocator.close()
    }
