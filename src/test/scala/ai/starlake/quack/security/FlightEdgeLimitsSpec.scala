package ai.starlake.quack.security

import ai.starlake.quack.{FlightConfig, Main}
import ai.starlake.quack.edge.{EdgeConfig, FlightProducerImpl}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import com.google.protobuf.{Any => ProtoAny}
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.apache.arrow.flight.{
  Action,
  AsyncPutListener,
  CallOptions,
  Criteria,
  FlightCallHeaders,
  FlightDescriptor,
  FlightRuntimeException,
  FlightStatusCode,
  HeaderCallOption,
  Ticket
}
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.io.{DataInputStream, EOFException}
import java.net.{Socket, SocketTimeoutException}
import java.util.Base64
import java.util.logging.{Level, Logger}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** What a client can make the Flight edge hold: gRPC messages and header lists. A message over the
  * limit is refused with `RESOURCE_EXHAUSTED` as soon as its length prefix arrives, with or without
  * credentials, and a header list over the limit is refused by a client that ignores what the
  * server advertises, as a hostile one does. A call with no credentials gets only server metadata,
  * and only a limited number of them run at once.
  */
class FlightEdgeLimitsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  import Main.given

  // gRPC logs each refusal below at WARNING with a stack trace, through java.util.logging; a strong
  // reference keeps the logger (and so its level) alive for the whole run.
  private val grpcLog   = Logger.getLogger("io.grpc.netty")
  private val grpcLevel = grpcLog.getLevel

  override def beforeAll(): Unit = grpcLog.setLevel(Level.OFF)

  override def afterAll(): Unit = grpcLog.setLevel(grpcLevel)

  private def boot(edgeConfig: EdgeConfig => EdgeConfig) =
    FlightEdgeHarness.boot(
      SecurityFixtures.freshStore().store,
      enableProviders = true,
      tls = false,
      wiring = FlightEdgeHarness.RouterWiring(
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      ),
      edgeConfig = edgeConfig
    )

  private def basicAuthorization: String =
    val creds = s"${SecurityFixtures.AliceUsername}:${SecurityFixtures.AlicePassword}"
    s"Basic ${Base64.getEncoder.encodeToString(creds.getBytes("UTF-8"))}"

  private def basic: HeaderCallOption =
    val hdrs = new FlightCallHeaders()
    hdrs.insert("tenant", SecurityFixtures.TenantName)
    hdrs.insert("pool", SecurityFixtures.PoolName)
    hdrs.insert("authorization", basicAuthorization)
    new HeaderCallOption(hdrs)

  private val sqlInfo =
    FlightDescriptor.command(
      ProtoAny.pack(FlightSql.CommandGetSqlInfo.getDefaultInstance).toByteArray
    )

  private def soon[A](body: => A): A =
    org.scalatest.concurrent.Eventually.eventually(
      org.scalatest.concurrent.Eventually.timeout(10.seconds),
      org.scalatest.concurrent.Eventually.interval(100.millis)
    )(body)

  // ---- messages ------------------------------------------------------------------------------

  "the edge" should "refuse an inbound message over the limit" in {
    val h      = boot(_.copy(maxInboundMessageBytes = 1024))
    val client = h.newClient()
    try
      val big = FlightDescriptor.command(new Array[Byte](2048))
      val ex  = intercept[FlightRuntimeException](client.getInfo(big, basic))
      ex.status().code() shouldBe FlightStatusCode.RESOURCE_EXHAUSTED
    finally
      client.close()
      h.shutdown()
  }

  it should "refuse it from a client with no credentials too" in {
    val h      = boot(_.copy(maxInboundMessageBytes = 1024))
    val client = h.newClient()
    try
      val big = FlightDescriptor.command(new Array[Byte](2048))
      val ex  = intercept[FlightRuntimeException](client.getInfo(big))
      ex.status().code() shouldBe FlightStatusCode.RESOURCE_EXHAUSTED
    finally
      client.close()
      h.shutdown()
  }

  it should "read a message under the limit, and keep serving after a refusal" in {
    val h      = boot(_.copy(maxInboundMessageBytes = 1024))
    val client = h.newClient()
    try
      // Not a FlightSQL command, so the producer answers it, but the limit does not.
      val small = FlightDescriptor.command(new Array[Byte](512))
      val ex    = intercept[FlightRuntimeException](client.getInfo(small, basic))
      ex.status().code() should not be FlightStatusCode.RESOURCE_EXHAUSTED
      intercept[FlightRuntimeException](
        client.getInfo(FlightDescriptor.command(new Array[Byte](2048)), basic)
      ).status().code() shouldBe FlightStatusCode.RESOURCE_EXHAUSTED
      client.getInfo(sqlInfo) should not be null
    finally
      client.close()
      h.shutdown()
  }

  // ---- headers -------------------------------------------------------------------------------

  it should "refuse a header list over the limit and keep serving" in {
    val h      = boot(_.copy(maxHeaderBytes = 2048))
    val client = h.newClient()
    try
      val hdrs = new FlightCallHeaders()
      hdrs.insert("x-large", "y" * 4096)
      intercept[FlightRuntimeException](client.getInfo(sqlInfo, new HeaderCallOption(hdrs)))
      val next = h.newClient()
      try next.getInfo(sqlInfo) should not be null
      finally next.close()
    finally
      client.close()
      h.shutdown()
  }

  it should "refuse a header list over the limit from a client that ignores the server's settings" in {
    val h      = boot(_.copy(maxHeaderBytes = 2048))
    val client = h.newClient()
    try
      // The Flight client stops short of sending what the server's SETTINGS forbid, so this speaks
      // HTTP/2 itself: one header of 4 KiB against a limit of 2 KiB is answered with a stream or
      // connection error, never left to wait for a message.
      val refused = rawHeaderCall(h.port, valueBytes = 4096, waitMs = 10000, stopOnAnswer = true)
      info(s"HTTP/2 frame types the server answered a 4096 byte header with: $refused")
      withClue(s"frames seen: $refused: ")(refused should not be empty)
      // The same call with a header under the limit is accepted: the server waits for its message.
      val accepted = rawHeaderCall(h.port, valueBytes = 1024, waitMs = 1500, stopOnAnswer = false)
      withClue(s"frames seen: $accepted: ")(accepted shouldBe empty)
      client.getInfo(sqlInfo) should not be null
    finally
      client.close()
      h.shutdown()
  }

  // ---- threads -------------------------------------------------------------------------------

  private def edgeThreads(port: Int): Int =
    Thread.getAllStackTraces.keySet.asScala.count(_.getName.startsWith(s"flight-edge-$port-"))

  it should "serve many calls on a fixed number of threads" in {
    val h                  = boot(_.copy(flightThreads = 16))
    val client             = h.newClient()
    given ExecutionContext = ExecutionContext.global
    try
      val calls = Future.sequence((1 to 64).map(_ => Future(client.getInfo(sqlInfo))))
      Await.result(calls, 60.seconds).size shouldBe 64
      edgeThreads(h.port) should (be > 0 and be <= 16)
    finally
      client.close()
      h.shutdown()
  }

  it should "leave none of its threads behind when it stops" in {
    val h      = boot(identity)
    val client = h.newClient()
    try client.getInfo(sqlInfo) should not be null
    finally
      client.close()
      h.shutdown()
    soon(edgeThreads(h.port) shouldBe 0)
  }

  // ---- calls with no credentials -------------------------------------------------------------

  private val timed = CallOptions.timeout(10, java.util.concurrent.TimeUnit.SECONDS)

  private def unauthenticated(body: => Any): org.scalatest.Assertion =
    val ex = intercept[FlightRuntimeException](body)
    ex.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED

  "a call with no credentials" should "be refused on every method but GetFlightInfo and DoGet" in {
    val h      = boot(identity)
    val client = h.newClient()
    try
      unauthenticated(client.listFlights(Criteria.ALL).iterator().hasNext)
      unauthenticated(client.listActions().iterator().hasNext)
      unauthenticated(client.doAction(new Action("x")).hasNext)
      unauthenticated(client.getSchema(sqlInfo))
      val root = VectorSchemaRoot.create(new Schema(java.util.List.of()), h.allocator)
      try
        val update = FlightSql.CommandStatementUpdate.newBuilder().setQuery("SELECT 1").build()
        val put    = client.startPut(
          FlightDescriptor.command(ProtoAny.pack(update).toByteArray),
          root,
          new AsyncPutListener()
        )
        unauthenticated(put.getResult())
      finally root.close()
    finally
      client.close()
      h.shutdown()
  }

  it should "get server metadata through GetSqlInfo and GetXdbcTypeInfo" in {
    val h      = boot(identity)
    val client = h.newClient()
    try
      val typeInfo = FlightDescriptor.command(
        ProtoAny.pack(FlightSql.CommandGetXdbcTypeInfo.getDefaultInstance).toByteArray
      )
      List(sqlInfo, typeInfo).foreach { descriptor =>
        val info   = client.getInfo(descriptor)
        val stream = client.getStream(info.getEndpoints.get(0).getTicket)
        try stream.next() // reads without an UNAUTHENTICATED
        finally stream.close()
      }
    finally
      client.close()
      h.shutdown()
  }

  it should "be refused any other command, at GetFlightInfo and at DoGet" in {
    val h      = boot(identity)
    val client = h.newClient()
    try
      val tableTypes = ProtoAny.pack(FlightSql.CommandGetTableTypes.getDefaultInstance).toByteArray
      unauthenticated(client.getInfo(FlightDescriptor.command(tableTypes)))
      val stream = client.getStream(new Ticket(tableTypes))
      try unauthenticated(stream.next())
      finally stream.close()
    finally
      client.close()
      h.shutdown()
  }

  it should "be refused a command longer than server metadata needs" in {
    // GetSqlInfo answers a row per id listed, repeats included: 16 million ids in a 16 MiB message
    // cost 2 GiB of memory no limit covers.
    val h      = boot(identity)
    val client = h.newClient()
    try
      val long = FlightSql.CommandGetSqlInfo.newBuilder()
      (1 to FlightProducerImpl.MaxAnonymousCommandBytes).foreach(_ => long.addInfo(0))
      val command = ProtoAny.pack(long.build()).toByteArray
      unauthenticated(client.getInfo(FlightDescriptor.command(command)))
      val stream = client.getStream(new Ticket(command))
      try unauthenticated(stream.next())
      finally stream.close()
    finally
      client.close()
      h.shutdown()
  }

  it should "be able to ask for every id GetSqlInfo defines" in {
    val h      = boot(identity)
    val client = h.newClient()
    try
      val all = FlightSql.CommandGetSqlInfo.newBuilder()
      FlightSql.SqlInfo.values
        .filter(_ != FlightSql.SqlInfo.UNRECOGNIZED)
        .foreach(id => all.addInfo(id.getNumber))
      val command = ProtoAny.pack(all.build()).toByteArray
      client.getInfo(FlightDescriptor.command(command)) should not be null
    finally
      client.close()
      h.shutdown()
  }

  it should "still let a credentialed client read other metadata" in {
    val h      = boot(identity)
    val client = h.newClient()
    try
      val tableTypes = ProtoAny.pack(FlightSql.CommandGetTableTypes.getDefaultInstance).toByteArray
      client.getInfo(FlightDescriptor.command(tableTypes), basic) should not be null
    finally
      client.close()
      h.shutdown()
  }

  it should "hold no thread for a DoPut that sends nothing" in {
    def parked = Thread.getAllStackTraces.asScala.count((_, stack) =>
      stack.exists(_.getMethodName == "getDescriptor")
    )
    val h      = boot(identity)
    val before = parked
    val socket = silentCalls(h.port, "DoPut", 32)
    try
      Thread.sleep(1000L)
      parked should be <= before
    finally
      socket.close()
      h.shutdown()
  }

  "calls with no credentials" should "be limited, leave others alone, and give their place back" in {
    val h      = boot(_.copy(maxAnonymousCalls = 8))
    val client = h.newClient()
    val socket = silentCalls(h.port, "GetFlightInfo", 8)
    try
      soon {
        val ex = intercept[FlightRuntimeException](client.getInfo(sqlInfo, timed))
        ex.status().code() shouldBe FlightStatusCode.UNAVAILABLE
      }
      client.getInfo(sqlInfo, basic, timed) should not be null
      socket.close()
      // The client left without a word: its calls must give their places back.
      soon(client.getInfo(sqlInfo, timed) should not be null)
    finally
      socket.close()
      client.close()
      h.shutdown()
  }

  // ---- DoPut ---------------------------------------------------------------------------------

  private def updateDescriptor: FlightDescriptor =
    val update = FlightSql.CommandStatementUpdate.newBuilder().setQuery("SELECT 1").build()
    FlightDescriptor.command(ProtoAny.pack(update).toByteArray)

  "DoPuts that send nothing" should "leave the edge serving, and give their slots back" in {
    // 16 threads make 4 slots. Without them, 16 such calls would hold every thread for good.
    val h      = boot(_.copy(flightThreads = 16, putDrainTimeoutMs = 1000L))
    val client = h.newClient()
    val socket = silentCalls(h.port, "DoPut", 32, credentials = true)
    try
      client.getInfo(sqlInfo, basic, timed) should not be null
      socket.close()
      val root = VectorSchemaRoot.create(new Schema(java.util.List.of()), h.allocator)
      try
        // A slot comes back by the deadline: whatever the statement then answers, it is not "too
        // many concurrent updates". A refused stream may also fail to complete, which proves nothing.
        soon {
          val put =
            client.startPut(updateDescriptor, root, new AsyncPutListener(), basic, timed)
          put.completed()
          val outcome = scala.util.Try(put.getResult())
          outcome.failed.toOption.map(_.getMessage).getOrElse("") should not include
            "too many concurrent"
        }
      finally root.close()
    finally
      socket.close()
      client.close()
      h.shutdown()
  }

  "DoPuts waiting for their data" should "leave the edge serving" in {
    val h      = boot(_.copy(flightThreads = 16))
    val client = h.newClient()
    val root   = VectorSchemaRoot.create(new Schema(java.util.List.of()), h.allocator)
    try
      val waiting =
        (1 to 32).map(_ => client.startPut(updateDescriptor, root, new AsyncPutListener(), basic))
      // 16 threads make 4 drain slots: the other 28 are refused, and every thread stays usable.
      // Without the slots, 16 of them would wait on every thread for data no thread could deliver.
      client.getInfo(sqlInfo, basic, timed) should not be null
      // A refused stream is already closed: completing it may throw, and that proves nothing here.
      waiting.foreach(stream => scala.util.Try(stream.completed()))
    finally
      root.close()
      client.close()
      h.shutdown()
  }

  "A DoPut whose client stops sending" should "be answered TIMED_OUT at the deadline" in {
    val h      = boot(_.copy(putDrainTimeoutMs = 500L))
    val client = h.newClient()
    val root   = VectorSchemaRoot.create(new Schema(java.util.List.of()), h.allocator)
    try
      val put = client.startPut(updateDescriptor, root, new AsyncPutListener(), basic, timed)
      val ex  = intercept[FlightRuntimeException](put.getResult())
      ex.status().code() shouldBe FlightStatusCode.TIMED_OUT
    finally
      root.close()
      client.close()
      h.shutdown()
  }

  // ---- configuration -------------------------------------------------------------------------

  /** The bundled application.conf, the real environment left out and `env` standing in for it. */
  private def load(env: Map[String, String] = Map.empty): FlightConfig =
    val conf = ConfigFactory
      .parseString(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-flightsql").loadOrThrow[FlightConfig]

  "the limits" should "default to 16 MiB and 64 KiB, in the edge and in the shipped configuration" in {
    val edge = EdgeConfig("127.0.0.1", 31338, tlsEnabled = false, "", "")
    edge.maxInboundMessageBytes shouldBe 16777216
    edge.maxHeaderBytes shouldBe 65536
    load().maxInboundMessageBytes shouldBe 16777216
    load().maxHeaderBytes shouldBe 65536
  }

  it should "come from PROXY_MAX_INBOUND_MESSAGE_BYTES and PROXY_MAX_HEADER_BYTES" in {
    load(Map("PROXY_MAX_INBOUND_MESSAGE_BYTES" -> "1048576")).maxInboundMessageBytes shouldBe
      1048576
    load(Map("PROXY_MAX_HEADER_BYTES" -> "16384")).maxHeaderBytes shouldBe 16384
  }

  it should "be refused at boot unless positive" in
    List(
      Map("PROXY_MAX_INBOUND_MESSAGE_BYTES" -> "0"),
      Map("PROXY_MAX_INBOUND_MESSAGE_BYTES" -> "-1"),
      Map("PROXY_MAX_HEADER_BYTES"          -> "0"),
      Map("PROXY_MAX_HEADER_BYTES"          -> "-65536"),
      Map("PROXY_MAX_HEADER_BYTES"          -> "lots")
    ).foreach(env => withClue(env)(an[Exception] should be thrownBy load(env)))

  "the limit on calls with no credentials" should "default to 256, in the edge and in the shipped configuration" in {
    EdgeConfig("127.0.0.1", 31338, tlsEnabled = false, "", "").maxAnonymousCalls shouldBe 256
    load().maxAnonymousCalls shouldBe 256
  }

  it should "come from PROXY_MAX_ANONYMOUS_CALLS" in {
    load(Map("PROXY_MAX_ANONYMOUS_CALLS" -> "16")).maxAnonymousCalls shouldBe 16
  }

  it should "be refused at boot unless positive" in
    List("0", "-1", "lots").foreach(value =>
      withClue(value)(
        an[Exception] should be thrownBy load(Map("PROXY_MAX_ANONYMOUS_CALLS" -> value))
      )
    )

  "the edge's threads" should "default to 512, in the edge and in the shipped configuration" in {
    EdgeConfig("127.0.0.1", 31338, tlsEnabled = false, "", "").flightThreads shouldBe 512
    load().flightThreads shouldBe 512
  }

  it should "come from PROXY_FLIGHT_THREADS" in {
    load(Map("PROXY_FLIGHT_THREADS" -> "16")).flightThreads shouldBe 16
    load(Map("PROXY_FLIGHT_THREADS" -> "1024")).flightThreads shouldBe 1024
  }

  it should "be refused at boot below 16" in
    List("15", "0", "-1", "lots").foreach(value =>
      withClue(value)(an[Exception] should be thrownBy load(Map("PROXY_FLIGHT_THREADS" -> value)))
    )

  // ---- a client that speaks HTTP/2 by hand ---------------------------------------------------

  private val Preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("US-ASCII")

  private def hpackInt(prefixBits: Int, n: Int): Array[Byte] =
    val max = (1 << prefixBits) - 1
    if n < max then Array(n.toByte)
    else
      val out  = ArrayBuffer[Byte](max.toByte)
      var rest = n - max
      while rest >= 128 do
        out += ((rest % 128) | 128).toByte
        rest /= 128
      out += rest.toByte
      out.toArray

  /** A header field without indexing and with a new name, as HPACK writes it. */
  private def literal(name: String, value: String): Array[Byte] =
    val n = name.getBytes("US-ASCII")
    val v = value.getBytes("US-ASCII")
    Array(0.toByte) ++ hpackInt(7, n.length) ++ n ++ hpackInt(7, v.length) ++ v

  private def frame(typ: Int, flags: Int, stream: Int, payload: Array[Byte]): Array[Byte] =
    val len = payload.length
    Array(
      ((len >> 16) & 0xff).toByte,
      ((len >> 8) & 0xff).toByte,
      (len & 0xff).toByte,
      typ.toByte,
      flags.toByte,
      ((stream >> 24) & 0x7f).toByte,
      ((stream >> 16) & 0xff).toByte,
      ((stream >> 8) & 0xff).toByte,
      (stream & 0xff).toByte
    ) ++ payload

  /** Opens `count` calls of `method` on one plain HTTP/2 connection to the edge, each its HEADERS
    * and nothing after them, as a client that stalls does, with the credentials of `basic` when
    * `credentials` is set. Returns the open socket, which the caller closes.
    */
  private def silentCalls(
      port: Int,
      method: String,
      count: Int,
      credentials: Boolean = false
  ): Socket =
    val socket = new Socket("127.0.0.1", port)
    val out    = socket.getOutputStream
    out.write(Preface)
    out.write(frame(4, 0, 0, Array.empty))
    val identityHeaders =
      if credentials then
        Array(
          literal("tenant", SecurityFixtures.TenantName),
          literal("pool", SecurityFixtures.PoolName),
          literal("authorization", basicAuthorization)
        )
      else Array.empty[Array[Byte]]
    val block = (Array(
      literal(":method", "POST"),
      literal(":scheme", "http"),
      literal(":path", s"/arrow.flight.protocol.FlightService/$method"),
      literal(":authority", s"127.0.0.1:$port"),
      literal("content-type", "application/grpc"),
      literal("te", "trailers")
    ) ++ identityHeaders).flatten
    (0 until count).foreach(i => out.write(frame(1, 0x4, 2 * i + 1, block)))
    out.flush()
    socket

  /** Opens an anonymous GetFlightInfo call on a plain HTTP/2 connection to the edge, with one
    * header of `valueBytes`, never sends its message, and returns the types of the frames the
    * server sent back for it within `waitMs` (SETTINGS, PING and WINDOW_UPDATE left out), -1
    * standing for the connection ending. With `stopOnAnswer` it returns at the first one. An empty
    * list means the server took the call and waited for its message.
    */
  private def rawHeaderCall(
      port: Int,
      valueBytes: Int,
      waitMs: Int,
      stopOnAnswer: Boolean
  ): List[Int] =
    val socket = new Socket("127.0.0.1", port)
    try
      socket.setSoTimeout(100)
      val out = socket.getOutputStream
      out.write(Preface)
      out.write(frame(4, 0, 0, Array.empty))
      val block = Array(
        literal(":method", "POST"),
        literal(":scheme", "http"),
        literal(":path", "/arrow.flight.protocol.FlightService/GetFlightInfo"),
        literal(":authority", s"127.0.0.1:$port"),
        literal("content-type", "application/grpc"),
        literal("te", "trailers"),
        literal("x-large", "y" * valueBytes)
      ).flatten
      out.write(frame(1, 0x4, 1, block))
      out.flush()
      val in       = new DataInputStream(socket.getInputStream)
      val deadline = System.nanoTime() + waitMs * 1000000L
      val seen     = ArrayBuffer.empty[Int]
      var open     = true
      while open && System.nanoTime() < deadline && !(stopOnAnswer && seen.nonEmpty) do
        try
          val head = new Array[Byte](9)
          in.readFully(head)
          val len = ((head(0) & 0xff) << 16) | ((head(1) & 0xff) << 8) | (head(2) & 0xff)
          in.readFully(new Array[Byte](len))
          val typ = head(3) & 0xff
          if typ != 4 && typ != 6 && typ != 8 then seen += typ
        catch
          case _: SocketTimeoutException => ()
          case _: EOFException           =>
            seen += -1
            open = false
          case _: java.io.IOException =>
            seen += -1
            open = false
      seen.toList
    finally socket.close()
