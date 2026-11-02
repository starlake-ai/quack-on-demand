package ai.starlake.quack.edge.adapter

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.arrow.memory.RootAllocator
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Pure tests for [[QuackProtocol]] driven by a fake [[QuackTransport]].
  *
  * The transport-injection design (rather than spinning a real HTTP server on a loopback port)
  * keeps the state-machine tests dependency-free and lets us assert exactly which requests the
  * driver sent. Each test seeds the fake transport with a queue of canned response byte arrays --
  * built via [[QuackTestFixtures]] (which round-trips through `libquackwire` so the bytes match the
  * wire format the real Quack node produces) -- and a recorder of the outbound request byte arrays.
  */
class QuackProtocolSpec extends AnyFunSpec with Matchers:

  given IORuntime = IORuntime.global

  /** Records every POST in order, returns canned responses from a queue. A test that exhausts the
    * queue gets a [[NoSuchElementException]] straight from the underlying mutable buffer; that
    * signals an over-eager round trip and is the right place for the test to fail.
    */
  private final class FakeTransport(responses: Iterator[Array[Byte]]) extends QuackTransport:
    val requests: ArrayBuffer[(URI, Array[Byte])]          = ArrayBuffer.empty
    val postCount: AtomicInteger                           = AtomicInteger(0)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      postCount.incrementAndGet()
      requests += ((uri, body.clone()))
      responses.next()
    }

  /** Polls `cond` until it holds or `within` elapses; true iff it held. */
  private def eventually(within: FiniteDuration)(cond: => Boolean): Boolean =
    val deadline = System.nanoTime() + within.toNanos
    var ok       = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(25)
      ok = cond
    ok

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
        val ex       = intercept[QuackWireError.Permanent] {
          protocol.open(endpoint, token).unsafeRunSync()
        }
        ex.getMessage should include("bad token")
      finally allocator.close()
    }

    it("fails the IO non-fatally, before any POST, when the native cannot load") {
      val transport = FakeTransport(Iterator.empty)
      val allocator = new RootAllocator()
      val broken    = QuackNativeSupport
        .probe(() => throw new ExceptionInInitializerError(new UnsatisfiedLinkError("boom")))
        .get
      try
        val protocol = new QuackProtocol(transport, allocator)
        val got      = protocol.open(endpoint, token, () => throw broken).attempt.unsafeRunSync()
        got.isLeft shouldBe true
        val e = got.left.toOption.get
        e shouldBe a[IllegalStateException]
        scala.util.control.NonFatal(e) shouldBe true
        e.getCause.getMessage shouldBe "boom"
        transport.postCount.get() shouldBe 0
      finally allocator.close()
    }

    it("sends HEARTBEAT_REQUESTs every lease / 3 while open, and none after close") {
      val heartbeatType = MessageType.HeartbeatRequest.wireOrdinal
      final class BeatTransport extends QuackTransport:
        val types = java.util.concurrent.ConcurrentLinkedQueue[Int]()
        def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
          types.add(QuackNativeBridge.parseMessageType(body))
          QuackTestFixtures.serializeSampleConnectionResponse(connId)
        }
      val transport = BeatTransport()
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator, nodeLeaseSec = 3) // beat every 1s
        val conn     = protocol.open(endpoint, token).unsafeRunSync()
        def beats    = transport.types.asScala.count(_ == heartbeatType)
        eventually(5.seconds)(beats >= 2) shouldBe true
        conn.close().unsafeRunSync()
        val afterClose = beats
        Thread.sleep(1500)
        beats shouldBe afterClose
      finally allocator.close()
    }

    it("asks the node for the configured lease in CONNECTION_REQUEST") {
      val transport = FakeTransport(
        Iterator(QuackTestFixtures.serializeSampleConnectionResponse(connId))
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator, nodeLeaseSec = 42)
        protocol.open(endpoint, token).unsafeRunSync()
        val sent = ai.starlake.quack.edge.quack.QuackWire
          .frame(transport.requests.head._2)
          .flatMap(ai.starlake.quack.edge.quack.QuackWire.decodeConnectionRequest)
          .fold(e => fail(s"undecodable CONNECTION_REQUEST: $e"), identity)
        sent.heartbeatTimeoutSec shouldBe 42L
        sent.authString shouldBe token
      finally allocator.close()
    }

    it("closes promptly, DISCONNECT included, while a heartbeat hangs") {
      val beatStarted = new java.util.concurrent.CountDownLatch(1)
      val releaseBeat = new java.util.concurrent.CountDownLatch(1)
      final class HangingBeatTransport extends QuackTransport:
        val types = java.util.concurrent.ConcurrentLinkedQueue[Int]()
        def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO.defer {
          val t = QuackNativeBridge.parseMessageType(body)
          types.add(t)
          if t == MessageType.HeartbeatRequest.wireOrdinal then
            // Uninterruptible, like JdkHttpTransport's IO.blocking send to a hung node.
            IO.blocking {
              beatStarted.countDown()
              releaseBeat.await()
              QuackTestFixtures.serializeSampleConnectionResponse(connId)
            }
          else IO(QuackTestFixtures.serializeSampleConnectionResponse(connId))
        }
      val transport = HangingBeatTransport()
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator, nodeLeaseSec = 3)
        val conn     = protocol.open(endpoint, token).unsafeRunSync()
        beatStarted.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        scala.concurrent.Await.result(conn.close().unsafeToFuture(), 3.seconds)
        transport.types.asScala.count(_ == MessageType.DisconnectMessage.wireOrdinal) shouldBe 1
      finally
        releaseBeat.countDown()
        allocator.close()
    }

    it("rejects a lease below one second at construction") {
      val allocator = new RootAllocator()
      try
        val e = intercept[IllegalArgumentException] {
          new QuackProtocol(new FakeTransport(Iterator.empty), allocator, nodeLeaseSec = 0)
        }
        e.getMessage should include("nodeLeaseSec")
      finally allocator.close()
    }

  describe("heartbeatEvery"):
    it("is a third of the lease, at least one second") {
      QuackProtocol.heartbeatEvery(30) shouldBe 10.seconds
      QuackProtocol.heartbeatEvery(3) shouldBe 1.second
      QuackProtocol.heartbeatEvery(1) shouldBe 1.second
    }

  describe("execute"):
    it("streams a single-batch PREPARE_RESPONSE (needsMoreFetch=false) to one INTEGER row of 42") {
      val uuid      = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
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
        val program  = for
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
      val uuid      = java.math.BigInteger.ONE
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
          // Second FETCH_RESPONSE: empty -- server done per
          // quack_scan.cpp:331.
          QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = false),
          // DISCONNECT ack (we route close() through the fake too)
          QuackTestFixtures.serializeSampleConnectionResponse("disconnect-ack")
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val program  = for
          conn   <- protocol.open(endpoint, token)
          reader <- conn.execute("SELECT n FROM tbl")
        yield reader
        val reader = program.unsafeRunSync()
        try
          var batches = 0
          var rows    = 0
          while reader.loadNextBatch() do
            batches += 1
            rows += reader.getVectorSchemaRoot.getRowCount
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

    it("exposes a stable VectorSchemaRoot across FETCH boundaries (Flight streaming contract)") {
      // Regression for the corrupt-batch bug: the Flight edge's `streamArrow`
      // captures `getVectorSchemaRoot` ONCE, calls `start(root)`, then loops
      // `putNext()` + `loadNextBatch()`. An ArrowReader must therefore return a
      // STABLE root that each `loadNextBatch()` repopulates. The chained reader
      // previously swapped to a fresh child root on every FETCH, so after the
      // first FETCH the captured root pointed at a closed (released) child and
      // the wire batch carried a stale row count with zero-length columns
      // ("mismatch number of rows in column ...: got=0, want=N").
      val uuid      = java.math.BigInteger.ONE
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSamplePrepareResponse(
            resultUuid = uuid,
            needsMoreFetch = true,
            withOneRowOneColChunk = true,
            columnName = "n"
          ),
          QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = true),
          QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = false),
          QuackTestFixtures.serializeSampleConnectionResponse("disconnect-ack")
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val program  = for
          conn   <- protocol.open(endpoint, token)
          reader <- conn.execute("SELECT n FROM tbl")
        yield reader
        val reader = program.unsafeRunSync()
        try
          // Capture the root once, exactly like FlightProducerImpl.streamArrow.
          val root = reader.getVectorSchemaRoot
          reader.loadNextBatch() shouldBe true // initial PREPARE batch
          root.getRowCount shouldBe 1
          root.getVector(0).getObject(0).toString shouldBe "42"
          reader.loadNextBatch() shouldBe true // FETCH batch -> child swap
          // The SAME captured root must now reflect the FETCH batch with live
          // (non-released) buffers, not a stale/closed child root.
          (reader.getVectorSchemaRoot eq root) shouldBe true
          root.getRowCount shouldBe 1
          root.getVector(0).getObject(0).toString shouldBe "42"
          reader.loadNextBatch() shouldBe false // empty FETCH -> done
        finally reader.close()
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
        val program  = for
          conn   <- protocol.open(endpoint, token)
          reader <- conn.execute("SELECT bogus")
        yield reader
        val ex = intercept[QuackWireError.Permanent](program.unsafeRunSync())
        ex.getMessage should include("syntax error")
      finally allocator.close()
    }

    // Audit F14: the schema export threw after the import, and nothing closed the imported reader,
    // so every such statement leaked its native result for the life of the manager.
    it("fails a column type with no Arrow mapping with a cast hint, releasing the result") {
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeTypeColumnPrepareResponse()
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val program  = for
          conn   <- protocol.open(endpoint, token)
          reader <- conn.execute("SELECT get_type(42)")
        yield reader
        val ex = intercept[QuackWireError.Permanent](program.unsafeRunSync())
        ex.getMessage should include("type TYPE")
        ex.getMessage should include("cast it to VARCHAR")
        allocator.getAllocatedMemory shouldBe 0L
      finally allocator.close()
    }

  describe("Connection.close()"):
    it("issues exactly one DISCONNECT_MESSAGE and swallows transport failures") {
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          // Any response shape will do for DISCONNECT -- we never
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

  describe("executeDiscard"):
    it("drains a single-batch result and does NOT send DISCONNECT") {
      val uuid      = java.math.BigInteger.valueOf(7L)
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSamplePrepareResponse(uuid, false, true)
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val conn     = protocol.open(endpoint, token).unsafeRunSync()
        conn.executeDiscard("BEGIN; CALL something()").unsafeRunSync()
        // CONNECTION_REQUEST + PREPARE_REQUEST only; a DISCONNECT would be a third POST.
        transport.postCount.get() shouldBe 2
      finally allocator.close()
    }

    it("drains a multi-batch result through the FETCH loop") {
      val uuid      = java.math.BigInteger.valueOf(8L)
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSamplePrepareResponse(uuid, true, true),
          QuackTestFixtures.serializeSampleFetchResponse(false)
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val conn     = protocol.open(endpoint, token).unsafeRunSync()
        conn.executeDiscard("COMMIT").unsafeRunSync()
        // CONNECTION + PREPARE + one FETCH; still no DISCONNECT.
        transport.postCount.get() shouldBe 3
      finally allocator.close()
    }

    it("surfaces an ERROR_RESPONSE as QuackWireError.Permanent") {
      val transport = FakeTransport(
        Iterator(
          QuackTestFixtures.serializeSampleConnectionResponse(connId),
          QuackTestFixtures.serializeSampleErrorResponse("no such function")
        )
      )
      val allocator = new RootAllocator()
      try
        val protocol = new QuackProtocol(transport, allocator)
        val conn     = protocol.open(endpoint, token).unsafeRunSync()
        val err      = intercept[QuackWireError.Permanent] {
          conn.executeDiscard("BEGIN; CALL nope()").unsafeRunSync()
        }
        err.msg should include("no such function")
      finally allocator.close()
    }

  describe("JdkHttpTransport"):
    it("reports a refused connection as Transient: nothing reached the node, a retry is safe") {
      val closed = { val s = new java.net.ServerSocket(0); val p = s.getLocalPort; s.close(); p }
      val transport =
        new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient())
      val err = intercept[QuackWireError.Transient] {
        transport.post(URI.create(s"http://127.0.0.1:$closed/quack"), Array.emptyByteArray)
          .unsafeRunSync()
      }
      err.msg should include("cannot connect")
    }
