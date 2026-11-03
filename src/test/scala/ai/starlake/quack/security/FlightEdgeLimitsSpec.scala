package ai.starlake.quack.security

import ai.starlake.quack.{FlightConfig, Main}
import ai.starlake.quack.edge.EdgeConfig
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import com.google.protobuf.{Any => ProtoAny}
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.apache.arrow.flight.{
  FlightCallHeaders,
  FlightDescriptor,
  FlightRuntimeException,
  FlightStatusCode,
  HeaderCallOption
}
import org.apache.arrow.flight.sql.impl.FlightSql
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.io.{DataInputStream, EOFException}
import java.net.{Socket, SocketTimeoutException}
import java.util.Base64
import java.util.logging.{Level, Logger}
import scala.collection.mutable.ArrayBuffer

/** What a client can make the Flight edge hold: gRPC messages and header lists. A message over the
  * limit is refused with `RESOURCE_EXHAUSTED` as soon as its length prefix arrives, with or without
  * credentials, and a header list over the limit is refused by a client that ignores what the
  * server advertises, as a hostile one does.
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

  private def basic: HeaderCallOption =
    val hdrs = new FlightCallHeaders()
    hdrs.insert("tenant", SecurityFixtures.TenantName)
    hdrs.insert("pool", SecurityFixtures.PoolName)
    val creds = s"${SecurityFixtures.AliceUsername}:${SecurityFixtures.AlicePassword}"
    hdrs.insert(
      "authorization",
      s"Basic ${Base64.getEncoder.encodeToString(creds.getBytes("UTF-8"))}"
    )
    new HeaderCallOption(hdrs)

  private val sqlInfo =
    FlightDescriptor.command(
      ProtoAny.pack(FlightSql.CommandGetSqlInfo.getDefaultInstance).toByteArray
    )

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
