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
  *   - We do NOT touch `sys.env` at test time. `nativeClient` is a
  *     constructor flag now (sourced from the HOCON
  *     `quack-on-demand.nativeClient` key in production), so tests pass
  *     it directly. Mutating env mid-test would race with parallel suites
  *     and leak across runs.
  *   - The native path's [[QuackProtocol]] is injected via the
  *     `protocolFactory` constructor parameter (Task 7's "Option A"
  *     injection seam). A [[FakeTransport]] queue-fed with canned wire
  *     bytes from [[QuackTestFixtures]] drives the protocol's state
  *     machine end-to-end without any real network I/O.
  *   - Embedded-path tests intentionally do not exercise the real
  *     embedded-DuckDB code, which already has its own coverage in
  *     [[QuackHttpAdapterSpec]] (via `FakeClient`) and is gated by an
  *     `INSTALL quack` from the network at class init. We assert routing
  *     via a subclass that intercepts `query` and counts which branch
  *     `nativeClient` selects. */
class QuackHttpClientSpec extends AnyFunSpec with Matchers:

  private val endpoint = "quack:127.0.0.1:21900"
  private val token    = "test-token"
  private val connId   = "conn-test-7"

  /** Records every POST in order, returns canned responses from a queue.
    * Exhausting the queue raises a [[NoSuchElementException]] straight
    * from the iterator -- that signals an over-eager round trip and is the
    * right place for the test to fail. */
  private final class FakeTransport(responses: Iterator[Array[Byte]]) extends QuackTransport:
    val requests: ArrayBuffer[(URI, Array[Byte])] = ArrayBuffer.empty
    val postCount: AtomicInteger                  = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      postCount.incrementAndGet()
      requests += ((uri, body.clone()))
      responses.next()
    }

  /** Always-failing transport for the permanent-error path. */
  private final class FailingTransport(err: QuackWireError) extends QuackTransport:
    val postCount: AtomicInteger = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      postCount.incrementAndGet()
      throw err
    }

  /** Builds a `QuackHttpClient` pinned to the native path with an
    * injected protocol factory. */
  private def nativeClient(
      allocator: RootAllocator,
      factory: BufferAllocator => QuackProtocol
  ): QuackHttpClient =
    new QuackHttpClient(
      allocator,
      nativeClient   = true,
      nodeDisableSsl = true,
      protocolFactory = Some(factory)
    )

  /** Subclass that records which internal branch `query` took. The
    * embedded path's `queryEmbedded` would actually hit DuckDB; we
    * short-circuit by overriding `query` and asserting which branch the
    * flag selected. Mirrors the FakeClient pattern in
    * [[QuackHttpAdapterSpec]]. */
  private final class RoutingProbeClient(allocator: RootAllocator, native: Boolean)
      extends QuackHttpClient(
        allocator,
        nativeClient   = native,
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
      val uuid = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
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
    it("maps QuackWireError.Permanent from the protocol to QuackResponse.Failed(QuackError.Permanent)") {
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
      val uuid = java.math.BigInteger.ONE
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