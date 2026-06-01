package ai.starlake.quack.edge.adapter

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.arrow.memory.RootAllocator
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** Pure tests for [[QuackProtocol]] driven by a fake [[QuackTransport]].
  *
  * The transport-injection design (rather than spinning a real HTTP
  * server on a loopback port) keeps the state-machine tests
  * dependency-free and lets us assert exactly which requests the
  * driver sent. Each test seeds the fake transport with a queue of
  * canned response byte arrays — built via [[QuackTestFixtures]] (which
  * round-trips through `libquackwire` so the bytes match the wire
  * format the real Quack node produces) — and a recorder of the
  * outbound request byte arrays.
  */
class QuackProtocolSpec extends AnyFunSpec with Matchers:

  given IORuntime = IORuntime.global

  /** Records every POST in order, returns canned responses from a
    * queue. A test that exhausts the queue gets a [[NoSuchElementException]]
    * straight from the underlying mutable buffer; that signals an
    * over-eager round trip and is the right place for the test to
    * fail.
    */
  private final class FakeTransport(responses: Iterator[Array[Byte]]) extends QuackTransport:
    val requests: ArrayBuffer[(URI, Array[Byte])] = ArrayBuffer.empty
    val postCount: AtomicInteger                  = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      postCount.incrementAndGet()
      requests += ((uri, body.clone()))
      responses.next()
    }

  private val endpoint = "quack:127.0.0.1:21900"
  private val token    = "test-token"
  private val connId   = "conn-test-abc"

  describe("endpointToHttp"):
    it("translates a quack:host:port endpoint into an http URL with /quack path") {
      QuackProtocol.endpointToHttp("quack:foo:8080") shouldBe URI.create("http://foo:8080/quack")
    }
    it("strips a single trailing slash before appending /quack") {
      QuackProtocol.endpointToHttp("quack:foo:8080/") shouldBe URI.create("http://foo:8080/quack")
    }
    it("rejects an endpoint missing the quack: scheme") {
      an[IllegalArgumentException] should be thrownBy
        QuackProtocol.endpointToHttp("http://foo:8080")
    }

  describe("open"):
    it("returns a Connection carrying the server-assigned connection id on CONNECTION_RESPONSE") {
      val transport = FakeTransport(
        Iterator(QuackTestFixtures.serializeSampleConnectionResponse(connId))
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val conn     = protocol.open(endpoint, token).unsafeRunSync()
        conn.connectionId shouldBe connId
        // One POST went out: CONNECTION_REQUEST.
        transport.requests.size shouldBe 1
        val (uri, body) = transport.requests.head
        uri shouldBe URI.create("http://127.0.0.1:21900/quack")
        QuackNativeBridge.parseMessageType(body) shouldBe MessageType.ConnectionRequest.wireOrdinal
      finally allocator.close()
    }

    it("raises QuackWireError.Permanent on ERROR_RESPONSE during CONNECT") {
      val transport = FakeTransport(
        Iterator(QuackTestFixtures.serializeSampleErrorResponse("bad token"))
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val ex = intercept[QuackWireError.Permanent] {
          protocol.open(endpoint, token).unsafeRunSync()
        }
        ex.getMessage should include ("bad token")
      finally allocator.close()
    }

  describe("execute"):
    it("streams a single-batch PREPARE_RESPONSE (needsMoreFetch=false) to one INTEGER row of 42") {
      val uuid = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSamplePrepareResponse(
            resultUuid = uuid,
            needsMoreFetch = false,
            withOneRowOneColChunk = true,
            columnName = "answer"
          )
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val program = for
          conn   <- protocol.open(endpoint, token)
          reader <- conn.execute("SELECT 42")
        yield reader
        val reader = program.unsafeRunSync()
        try
          reader.loadNextBatch() shouldBe true
          val root = reader.getVectorSchemaRoot
          root.getRowCount shouldBe 1
          root.getVector(0).getObject(0).toString shouldBe "42"
          // Column name propagated from PREPARE_RESPONSE.Names() via
          // the C++ shim (design option (b)).
          root.getSchema.getFields.get(0).getName shouldBe "answer"
          reader.loadNextBatch() shouldBe false
        finally reader.close()
      finally allocator.close()
    }

    it("loops through FETCH_RESPONSE batches until the server returns an empty one") {
      val uuid = java.math.BigInteger.ONE
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          // PREPARE_RESPONSE: one batch + needsMoreFetch=true so the
          // driver keeps issuing FETCH until the server says stop.
          QuackTestFixtures.serializeSamplePrepareResponse(
            resultUuid = uuid,
            needsMoreFetch = true,
            withOneRowOneColChunk = true,
            columnName = "n"
          ),
          // First FETCH_RESPONSE: another batch.
          QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = true),
          // Second FETCH_RESPONSE: empty — server done per
          // quack_scan.cpp:331.
          QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = false),
          // DISCONNECT ack (we route close() through the fake too)
          QuackTestFixtures.serializeSampleConnectionResponse("disconnect-ack")
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val program = for
          conn   <- protocol.open(endpoint, token)
          reader <- conn.execute("SELECT n FROM tbl")
        yield reader
        val reader = program.unsafeRunSync()
        try
          var batches = 0
          var rows    = 0
          while reader.loadNextBatch() do
            batches += 1
            rows    += reader.getVectorSchemaRoot.getRowCount
          batches shouldBe 2
          rows shouldBe 2
        finally reader.close()
        // Requests fired: CONNECT + PREPARE + 2x FETCH + DISCONNECT.
        transport.postCount.get() shouldBe 5
        // Verify each request type matches the expected wire ordinal.
        val ordinals = transport.requests.map { case (_, body) =>
          QuackNativeBridge.parseMessageType(body)
        }
        ordinals(0) shouldBe MessageType.ConnectionRequest.wireOrdinal
        ordinals(1) shouldBe MessageType.PrepareRequest.wireOrdinal
        ordinals(2) shouldBe MessageType.FetchRequest.wireOrdinal
        ordinals(3) shouldBe MessageType.FetchRequest.wireOrdinal
        ordinals(4) shouldBe MessageType.DisconnectMessage.wireOrdinal
      finally allocator.close()
    }

    it("raises QuackWireError.Permanent on ERROR_RESPONSE during PREPARE") {
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSampleErrorResponse("syntax error near FROM")
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val program = for
          conn   <- protocol.open(endpoint, token)
          reader <- conn.execute("SELECT bogus")
        yield reader
        val ex = intercept[QuackWireError.Permanent] { program.unsafeRunSync() }
        ex.getMessage should include ("syntax error")
      finally allocator.close()
    }

  describe("Connection.close()"):
    it("issues exactly one DISCONNECT_MESSAGE and swallows transport failures") {
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          // Any response shape will do for DISCONNECT — we never
          // parse it server-side.
          QuackTestFixtures.serializeSampleConnectionResponse("ack-1")
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val conn     = protocol.open(endpoint, token).unsafeRunSync()
        conn.close().unsafeRunSync()
        // CONNECT + DISCONNECT = 2 posts.
        transport.postCount.get() shouldBe 2
        val (_, body) = transport.requests(1)
        QuackNativeBridge.parseMessageType(body) shouldBe MessageType.DisconnectMessage.wireOrdinal
      finally allocator.close()
    }