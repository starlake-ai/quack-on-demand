package ai.starlake.quack.security

import ai.starlake.quack.{FlightConfig, Main}
import ai.starlake.quack.edge.{
  AnonymousStreamLimit,
  ConnectionContext,
  ConnectionProbation,
  EdgeConfig,
  FlightProducerImpl
}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import com.google.protobuf.{Any => ProtoAny, ByteString}
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.apache.arrow.flight.{
  Action,
  AsyncPutListener,
  CallOptions,
  Criteria,
  FlightCallHeaders,
  FlightClient,
  FlightDescriptor,
  FlightRuntimeException,
  FlightStatusCode,
  HeaderCallOption,
  Location,
  Ticket
}
import org.apache.arrow.flight.impl.Flight
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.io.{DataInputStream, EOFException}
import java.net.{Socket, SocketTimeoutException}
import java.security.cert.X509Certificate
import java.util.{Base64, UUID}
import java.util.logging.{Level, Logger}
import javax.net.ssl.{SSLContext, SSLSocket, TrustManager, X509TrustManager}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** What a client can make the Flight edge hold: gRPC messages and header lists. A message over the
  * limit is refused with `RESOURCE_EXHAUSTED` as soon as its length prefix arrives, with or without
  * credentials, and a header list over the limit is refused by a client that ignores what the
  * server advertises, as a hostile one does. A call with no credentials gets only server metadata,
  * only a limited number of them run at once, and a connection holds little until it has shown a
  * working credential, whatever its streams do.
  */
class FlightEdgeLimitsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  import Main.given

  // gRPC logs each refusal below at WARNING with a stack trace, through java.util.logging; a strong
  // reference keeps the logger (and so its level) alive for the whole run.
  private val grpcLog   = Logger.getLogger("io.grpc.netty")
  private val grpcLevel = grpcLog.getLevel

  override def beforeAll(): Unit = grpcLog.setLevel(Level.OFF)

  override def afterAll(): Unit = grpcLog.setLevel(grpcLevel)

  private def boot(edgeConfig: EdgeConfig => EdgeConfig, tls: Boolean = false) =
    FlightEdgeHarness.boot(
      SecurityFixtures.freshStore().store,
      enableProviders = true,
      tls = tls,
      wiring = FlightEdgeHarness.RouterWiring(
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      ),
      edgeConfig = edgeConfig
    )

  private def basicAuthorization(password: String = SecurityFixtures.AlicePassword): String =
    val creds = s"${SecurityFixtures.AliceUsername}:$password"
    s"Basic ${Base64.getEncoder.encodeToString(creds.getBytes("UTF-8"))}"

  private def identityAnd(authorization: String): Seq[(String, String)] =
    Seq(
      "tenant"        -> SecurityFixtures.TenantName,
      "pool"          -> SecurityFixtures.PoolName,
      "authorization" -> authorization
    )

  private def rightPassword = identityAnd(basicAuthorization())
  private def wrongPassword = identityAnd(basicAuthorization("nope"))

  private def options(headers: Seq[(String, String)]): HeaderCallOption =
    val hdrs = new FlightCallHeaders()
    headers.foreach((name, value) => hdrs.insert(name, value))
    new HeaderCallOption(hdrs)

  private def basic: HeaderCallOption = options(rightPassword)

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
    val client = plain(h.port)
    try
      (1 to ConnectionProbation.MaxStreams).foreach(_ => client.open("DoPut"))
      Thread.sleep(1000L)
      parked should be <= before
    finally
      client.close()
      h.shutdown()
  }

  "calls with no credentials" should "be limited, leave others alone, and give their place back" in {
    val h      = boot(_.copy(maxAnonymousCalls = 8))
    val client = h.newClient()
    val silent = plain(h.port)
    try
      (1 to 8).foreach(_ => silent.open("GetFlightInfo"))
      soon {
        val ex = intercept[FlightRuntimeException](client.getInfo(sqlInfo, timed))
        ex.status().code() shouldBe FlightStatusCode.UNAVAILABLE
      }
      client.getInfo(sqlInfo, basic, timed) should not be null
      silent.close()
      // The client left without a word: its calls must give their places back.
      soon(client.getInfo(sqlInfo, timed) should not be null)
    finally
      silent.close()
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
    val silent = plain(h.port)
    try
      // A working credential first: past 8 streams, a connection without one is refused them.
      silent.open("GetFlightInfo", rightPassword)
      silent.await(10000)(silent.lifted) shouldBe true
      (1 to 32).foreach(_ => silent.open("DoPut", rightPassword))
      client.getInfo(sqlInfo, basic, timed) should not be null
      silent.close()
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
      silent.close()
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

  // ---- connections on probation -------------------------------------------------------------

  /** Every way a stream stays open on the server when its client never ends it, and whether the
    * server answers it first: gRPC itself before any interceptor (an unknown method, an unknown
    * encoding), at the HTTP level, the guard, the check of a credential, or nobody at all.
    */
  private val streamKinds: List[(String, RawClient => Int, Boolean)] = List(
    ("an unknown method", _.open("NoSuchMethod"), true),
    ("an unknown grpc-encoding", _.open("GetFlightInfo", Seq("grpc-encoding" -> "nope")), true),
    ("the content type text/plain", _.open("GetFlightInfo", contentType = "text/plain"), true),
    ("a refused DoPut", _.open("DoPut"), true),
    ("a silent GetFlightInfo", _.open("GetFlightInfo"), false),
    ("a wrong password", _.open("GetFlightInfo", wrongPassword), true)
  )

  /** 8 streams stay, answered or not, and the 9th is refused. */
  private def holdsEightStreams(
      client: RawClient,
      open: RawClient => Int,
      answers: Boolean
  ): org.scalatest.Assertion =
    val held = (1 to ConnectionProbation.MaxStreams).map(_ => open(client))
    if answers then client.await(15000)(held.forall(client.answered)) shouldBe true
    val ninth = open(client)
    client.await(5000)(client.resets(ninth).nonEmpty) shouldBe true
    client.resets(ninth) shouldBe List(ErrorRefusedStream)
    held.foreach(stream => client.resets(stream) shouldBe empty)
    client.lifted shouldBe false

  "a connection on probation" should "hold at most 8 open streams, whatever they are" in {
    val h = boot(identity)
    try
      streamKinds.foreach { (label, open, answers) =>
        withClue(s"$label: ") {
          val client = plain(h.port)
          try holdsEightStreams(client, open, answers)
          finally client.close()
        }
      }
    finally h.shutdown()
  }

  it should "hold them for a client that acknowledges SETTINGS too" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      client.await(5000)(client.advertisedStreams.nonEmpty) shouldBe true
      client.acknowledgeSettings()
      holdsEightStreams(client, _.open("DoPut"), answers = true)
    finally
      client.close()
      h.shutdown()
  }

  it should "say 8 in the server's first SETTINGS" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      client.await(5000)(client.advertisedStreams.nonEmpty) shouldBe true
      client.advertisedStreams shouldBe List(ConnectionProbation.MaxStreams.toLong)
    finally
      client.close()
      h.shutdown()
  }

  /** The limit is gone: 16 more silent streams and no reset. A refusal comes at once. */
  private def noLimitLeft(client: RawClient, headers: Seq[(String, String)]) =
    (1 to 16).foreach(_ => client.open("GetFlightInfo", headers))
    client.await(500)(client.anyReset) shouldBe false

  it should "end at a working credential: no limit, and SETTINGS say so" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      client.open("GetFlightInfo", rightPassword)
      client.await(10000)(client.lifted) shouldBe true
      noLimitLeft(client, rightPassword)
    finally
      client.close()
      h.shutdown()
  }

  it should "stay ended when the client acknowledges both SETTINGS afterwards" in {
    // Netty applies a SETTINGS value on its ACK: the first one's 8 must not come back for good.
    val h      = boot(identity)
    val client = plain(h.port)
    try
      client.open("GetFlightInfo", rightPassword)
      client.await(10000)(client.lifted) shouldBe true
      client.acknowledgeSettings()
      client.acknowledgeSettings()
      noLimitLeft(client, rightPassword)
    finally
      client.close()
      h.shutdown()
  }

  it should "end at a session bearer, on a new connection" in {
    val h      = boot(identity)
    val client = plain(h.port)
    val peer   = s"peer-${UUID.randomUUID()}"
    try
      val key = h.supervisor
        .findPoolKeyByTenantAndPoolName(SecurityFixtures.TenantName, SecurityFixtures.PoolName)
        .get
      ConnectionContext.bind(peer, key, UUID.randomUUID().toString, "alice", grants = None)
      client.open("GetFlightInfo", Seq("authorization" -> s"Bearer $peer"))
      client.await(10000)(client.lifted) shouldBe true
    finally
      client.close()
      h.shutdown()
  }

  private def closedBeforeAnything(port: Int): org.scalatest.Assertion =
    val socket = new Socket("127.0.0.1", port)
    try
      socket.setSoTimeout(5000)
      socket.getInputStream.read() shouldBe -1
    finally socket.close()

  "connections on probation" should "be limited, and one past the limit closed at once" in {
    val h       = boot(_.copy(maxUnauthenticatedConnections = 3))
    val clients = (1 to 3).map(_ => plain(h.port))
    val next    = ArrayBuffer.empty[RawClient]
    try
      clients.foreach(c => c.await(5000)(c.advertisedStreams.nonEmpty) shouldBe true)
      closedBeforeAnything(h.port)
      // One that proves itself leaves a place.
      clients.head.open("GetFlightInfo", rightPassword)
      clients.head.await(10000)(clients.head.lifted) shouldBe true
      next += plain(h.port)
      next.head.await(5000)(next.head.advertisedStreams.nonEmpty) shouldBe true
      closedBeforeAnything(h.port)
      // One that leaves does too.
      clients.last.close()
      soon {
        val another = plain(h.port)
        next += another
        another.await(1000)(another.advertisedStreams.nonEmpty) shouldBe true
      }
    finally
      clients.foreach(_.close())
      next.foreach(_.close())
      h.shutdown()
  }

  they should "be closed at the timeout, whatever their streams" in {
    val h = boot(_.copy(probationTimeoutMs = 300L))
    try
      streamKinds.foreach { (label, open, answers) =>
        withClue(s"$label: ") {
          val client  = plain(h.port)
          val started = System.nanoTime()
          try
            val held = (1 to ConnectionProbation.MaxStreams).map(_ => open(client))
            client.closedWithin(5000) shouldBe true
            // gRPC's own close waits for streams with no limit: this one did not.
            ((System.nanoTime() - started) / 1000000L) should be >= 200L
            if answers then held.foreach(stream => client.resets(stream) shouldBe empty)
          finally client.close()
        }
      }
      // A connection that never says anything.
      val silent = new Socket("127.0.0.1", h.port)
      try
        silent.setSoTimeout(5000)
        silent.getInputStream.readAllBytes()
        succeed
      finally silent.close()
    finally h.shutdown()
  }

  they should "give their anonymous places back at the timeout" in {
    val h      = boot(_.copy(probationTimeoutMs = 1000L, maxAnonymousCalls = 2))
    val silent = plain(h.port)
    val client = h.newClient()
    try
      (1 to 2).foreach(_ => silent.open("GetFlightInfo"))
      soon {
        val ex = intercept[FlightRuntimeException](client.getInfo(sqlInfo, timed))
        ex.status().code() shouldBe FlightStatusCode.UNAVAILABLE
      }
      silent.closedWithin(5000) shouldBe true
      soon(client.getInfo(sqlInfo, timed) should not be null)
    finally
      silent.close()
      client.close()
      h.shutdown()
  }

  "a connection that showed a credential" should "stay past the timeout" in {
    val h      = boot(_.copy(probationTimeoutMs = 300L))
    val client = plain(h.port)
    try
      client.open("GetFlightInfo", rightPassword)
      client.await(10000)(client.lifted) shouldBe true
      client.closedWithin(800) shouldBe false
      val stream = client.open("GetFlightInfo", rightPassword)
      client.data(stream, descriptorMessage(sqlInfoCommand), end = true)
      client.await(10000)(client.answered(stream)) shouldBe true
    finally
      client.close()
      h.shutdown()
  }

  // ---- a stream with no credential -----------------------------------------------------------

  private def sqlInfoCommand: Array[Byte] =
    ProtoAny.pack(FlightSql.CommandGetSqlInfo.getDefaultInstance).toByteArray

  private def grpcMessage(length: Int, body: Array[Byte]): Array[Byte] =
    java.nio.ByteBuffer.allocate(5 + body.length).put(0.toByte).putInt(length).put(body).array()

  private def descriptorMessage(command: Array[Byte]): Array[Byte] =
    val body = Flight.FlightDescriptor
      .newBuilder()
      .setType(Flight.FlightDescriptor.DescriptorType.CMD)
      .setCmd(ByteString.copyFrom(command))
      .build()
      .toByteArray
    grpcMessage(body.length, body)

  private def pastTheLimit(client: RawClient, stream: Int): Unit =
    client.data(stream, grpcMessage(1 << 20, new Array[Byte](4100)))
    client.data(stream, new Array[Byte](4100))

  "a stream with no credential" should "be reset past 8 KiB of message, in two DATA frames" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      val stream = client.open("GetFlightInfo")
      client.data(stream, grpcMessage(1 << 20, new Array[Byte](4100)))
      client.await(300)(client.resets(stream).nonEmpty) shouldBe false
      client.data(stream, new Array[Byte](4100))
      client.await(5000)(client.resets(stream).nonEmpty) shouldBe true
      client.resets(stream) shouldBe List(ErrorCancel)
    finally
      client.close()
      h.shutdown()
  }

  it should "go on to its answer under it" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      val small = client.open("GetFlightInfo")
      client.data(small, descriptorMessage(sqlInfoCommand), end = true)
      client.await(10000)(client.answered(small)) shouldBe true
      // A FlightInfo: response headers, a message, then trailers.
      client.frames(small).map(_.typ) shouldBe List(Headers, 0, Headers)
      // At the limit exactly: not reset, and refused by the limit on an anonymous command.
      val limit = descriptorMessage(new Array[Byte](AnonymousStreamLimit.MaxBytes.toInt - 5))
      limit.length shouldBe 5 + AnonymousStreamLimit.MaxBytes.toInt
      val full = client.open("GetFlightInfo")
      client.data(full, limit, end = true)
      client.await(10000)(client.answered(full)) shouldBe true
      client.resets(full) shouldBe empty
    finally
      client.close()
      h.shutdown()
  }

  it should "be reset on a connection that showed a credential too, and a credentialed one not" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      val credentialed = client.open("GetFlightInfo", rightPassword)
      client.await(10000)(client.lifted) shouldBe true
      pastTheLimit(client, credentialed)
      client.await(500)(client.resets(credentialed).nonEmpty) shouldBe false
      val anonymous = client.open("GetFlightInfo")
      pastTheLimit(client, anonymous)
      client.await(5000)(client.resets(anonymous).nonEmpty) shouldBe true
      client.resets(credentialed) shouldBe empty
    finally
      client.close()
      h.shutdown()
  }

  it should "be refused a compressed message at its headers" in {
    // The limit counts wire bytes, and 8 KiB of gzip inflates to 8 MiB.
    val h      = boot(identity)
    val client = plain(h.port)
    try
      val stream = client.open("GetFlightInfo", Seq("grpc-encoding" -> "gzip"))
      client.await(10000)(client.answered(stream)) shouldBe true
    finally
      client.close()
      h.shutdown()
  }

  "a refused call" should "answer HEADERS with END_STREAM and no reset" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      val stream = client.open("DoPut")
      client.await(10000)(client.answered(stream)) shouldBe true
      client.frames(stream).map(f => (f.typ, f.flags & EndStream)) shouldBe
        List((Headers, EndStream))
      client.await(300)(client.anyReset) shouldBe false
    finally
      client.close()
      h.shutdown()
  }

  "a client's resets" should "close its connection past 1000 in a window" in {
    val h      = boot(identity)
    val client = plain(h.port)
    try
      (1 to ConnectionProbation.MaxResetsPerWindow).foreach { _ =>
        client.reset(client.open("DoPut"))
      }
      client.closedWithin(500) shouldBe false
      client.reset(client.open("DoPut"))
      client.closedWithin(5000) shouldBe true
    finally
      client.close()
      h.shutdown()
  }

  // ---- TLS -----------------------------------------------------------------------------------

  private def tlsFlightClient(h: FlightEdgeHarness.Harness): FlightClient =
    FlightClient
      .builder(h.allocator, Location.forGrpcTls(h.host, h.port))
      .useTls()
      .verifyServer(false)
      .build()

  "the edge over TLS" should "serve the right password and refuse a wrong one" in {
    val h      = boot(identity, tls = true)
    val client = tlsFlightClient(h)
    try
      client.getInfo(sqlInfo, basic, timed) should not be null
      unauthenticated(client.getInfo(sqlInfo, options(wrongPassword), timed))
    finally
      client.close()
      h.shutdown()
  }

  it should "hold a connection to 8 streams, and lift that at a working credential" in {
    val h = boot(identity, tls = true)
    try
      val held = overTls(h.port)
      try holdsEightStreams(held, _.open("DoPut"), answers = true)
      finally held.close()
      val proven = overTls(h.port)
      try
        proven.open("GetFlightInfo", rightPassword)
        proven.await(10000)(proven.lifted) shouldBe true
        noLimitLeft(proven, rightPassword)
      finally proven.close()
    finally h.shutdown()
  }

  it should "close a connection past the limit before any TLS handshake" in {
    val h     = boot(_.copy(maxUnauthenticatedConnections = 1), tls = true)
    val first = overTls(h.port)
    try
      first.await(5000)(first.advertisedStreams.nonEmpty) shouldBe true
      closedBeforeAnything(h.port)
    finally
      first.close()
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

  "the limit on connections with no working credential" should "default to 1024, in the edge and in the shipped configuration" in {
    EdgeConfig(
      "127.0.0.1",
      31338,
      tlsEnabled = false,
      "",
      ""
    ).maxUnauthenticatedConnections shouldBe
      1024
    load().maxUnauthenticatedConnections shouldBe 1024
  }

  it should "come from PROXY_MAX_UNAUTHENTICATED_CONNECTIONS" in {
    load(
      Map("PROXY_MAX_UNAUTHENTICATED_CONNECTIONS" -> "16")
    ).maxUnauthenticatedConnections shouldBe
      16
  }

  it should "be refused at boot unless positive" in
    List("0", "-1", "lots").foreach(value =>
      withClue(value)(
        an[Exception] should be thrownBy load(
          Map("PROXY_MAX_UNAUTHENTICATED_CONNECTIONS" -> value)
        )
      )
    )

  it should "say at boot when the heap takes fewer" in {
    // No heap fits this many, so this is the budget at work at any heap.
    val printed = new java.io.ByteArrayOutputStream()
    val h = Console.withOut(printed)(boot(_.copy(maxUnauthenticatedConnections = Int.MaxValue)))
    h.shutdown()
    val line = printed.toString("UTF-8")
    line should include("The FlightSQL edge takes at most")
    line should include("PROXY_MAX_UNAUTHENTICATED_CONNECTIONS")
    line should include("against a tenth of this")
  }

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

  private val Headers   = 1
  private val RstStream = 3
  private val Settings  = 4

  private val EndStream          = 0x1
  private val ErrorRefusedStream = 7
  private val ErrorCancel        = 8

  private val SettingMaxConcurrentStreams = 3

  private final case class Frame(typ: Int, flags: Int, stream: Int, payload: Array[Byte])

  private def callHeaders(
      port: Int,
      method: String,
      contentType: String,
      more: Seq[(String, String)]
  ): Array[Byte] =
    (Seq(
      literal(":method", "POST"),
      literal(":scheme", "http"),
      literal(":path", s"/arrow.flight.protocol.FlightService/$method"),
      literal(":authority", s"127.0.0.1:$port"),
      literal("content-type", contentType),
      literal("te", "trailers")
    ) ++ more.map((name, value) => literal(name, value))).toArray.flatten

  /** An HTTP/2 client by hand, on a connection that has sent its preface and SETTINGS. It does only
    * what a case tells it to, so it never ends a stream or acknowledges the server's SETTINGS
    * unless told to, as a hostile client does not.
    */
  private final class RawClient(socket: Socket, port: Int) extends AutoCloseable:
    private val out      = socket.getOutputStream
    private val in       = socket.getInputStream
    private val received = ArrayBuffer.empty[Frame]
    private var unparsed = Array.emptyByteArray
    private var nextId   = 1
    private var ended    = false

    socket.setSoTimeout(50)
    out.write(Preface)
    out.write(frame(Settings, 0, 0, Array.empty))
    out.flush()

    /** Sends a call's HEADERS, with no END_STREAM, and returns its stream. */
    def open(
        method: String,
        headers: Seq[(String, String)] = Nil,
        contentType: String = "application/grpc"
    ): Int =
      val stream = nextId
      nextId += 2
      out.write(frame(Headers, 0x4, stream, callHeaders(port, method, contentType, headers)))
      out.flush()
      stream

    def data(stream: Int, bytes: Array[Byte], end: Boolean = false): Unit =
      out.write(frame(0, if end then EndStream else 0, stream, bytes))
      out.flush()

    def reset(stream: Int): Unit =
      out.write(frame(RstStream, 0, stream, java.nio.ByteBuffer.allocate(4).putInt(8).array()))
      out.flush()

    def acknowledgeSettings(): Unit =
      out.write(frame(Settings, 0x1, 0, Array.empty))
      out.flush()

    /** Reads what the server sends until `done` holds, the connection ends or `waitMs` passes, and
      * says whether `done` holds.
      */
    def await(waitMs: Long)(done: => Boolean): Boolean =
      val deadline = System.nanoTime() + waitMs * 1000000L
      while !done && !ended && System.nanoTime() < deadline do read()
      done

    def closedWithin(waitMs: Long): Boolean = await(waitMs)(ended)

    def frames(stream: Int): List[Frame] = received.filter(_.stream == stream).toList

    /** The server ended its side of the stream, which leaves it half open until this client ends
      * its own.
      */
    def answered(stream: Int): Boolean =
      frames(stream).exists(f => f.typ != RstStream && (f.flags & EndStream) != 0)

    /** The error code of each RST_STREAM on the stream. */
    def resets(stream: Int): List[Int] =
      frames(stream)
        .filter(_.typ == RstStream)
        .map(f => java.nio.ByteBuffer.wrap(f.payload).getInt)

    def anyReset: Boolean = received.exists(_.typ == RstStream)

    /** Each MAX_CONCURRENT_STREAMS the server's SETTINGS have said, in order. */
    def advertisedStreams: List[Long] =
      received
        .filter(f => f.typ == Settings && (f.flags & 0x1) == 0)
        .flatMap(_.payload.grouped(6))
        .map(java.nio.ByteBuffer.wrap)
        .collect {
          case entry if entry.getShort(0) == SettingMaxConcurrentStreams =>
            entry.getInt(2).toLong & 0xffffffffL
        }
        .toList

    def lifted: Boolean = advertisedStreams.contains(Int.MaxValue.toLong)

    override def close(): Unit = socket.close()

    private def read(): Unit =
      val chunk = new Array[Byte](65536)
      val count =
        try in.read(chunk)
        catch
          case _: SocketTimeoutException => 0
          case _: java.io.IOException    => -1
      if count < 0 then ended = true
      else
        unparsed = unparsed ++ chunk.take(count)
        parse()

    @scala.annotation.tailrec
    private def parse(): Unit =
      if unparsed.length >= 9 then
        val len =
          ((unparsed(0) & 0xff) << 16) | ((unparsed(1) & 0xff) << 8) | (unparsed(2) & 0xff)
        if unparsed.length >= 9 + len then
          received += Frame(
            unparsed(3) & 0xff,
            unparsed(4) & 0xff,
            java.nio.ByteBuffer.wrap(unparsed, 5, 4).getInt & 0x7fffffff,
            unparsed.slice(9, 9 + len)
          )
          unparsed = unparsed.drop(9 + len)
          parse()

  private def plain(port: Int): RawClient = new RawClient(new Socket("127.0.0.1", port), port)

  /** Over TLS with ALPN `h2`, trusting the edge's generated certificate. */
  private def overTls(port: Int): RawClient =
    val trustAll = new X509TrustManager:
      def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def getAcceptedIssuers(): Array[X509Certificate]                              = Array.empty
    val context = SSLContext.getInstance("TLS")
    context.init(null, Array[TrustManager](trustAll), null)
    val socket = context.getSocketFactory.createSocket("127.0.0.1", port).asInstanceOf[SSLSocket]
    val params = socket.getSSLParameters
    params.setApplicationProtocols(Array("h2"))
    socket.setSSLParameters(params)
    socket.startHandshake()
    new RawClient(socket, port)

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
