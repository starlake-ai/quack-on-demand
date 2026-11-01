package ai.starlake.quack.edge.quack

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.CertGen
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ServerSocket
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.security.cert.X509Certificate
import java.time.Instant
import scala.concurrent.duration.*
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}

/** The HTTP shell around the dispatcher: framing, limits, the node-compatible GET and CORS answers,
  * and TLS from the same PEM pair the Flight edge uses.
  */
class QuackFrontDoorServerSpec extends AnyFlatSpec with Matchers:

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private def cfg(
      port: Int,
      tls: Boolean = false,
      certDir: String = "",
      maxBody: Long = 1 << 20,
      maxConnect: Long = 65536,
      connectTimeout: Long = 30,
      bodyTimeout: Long = 300
  ) =
    QuackNativeConfig(
      enabled = true,
      host = "127.0.0.1",
      port = port,
      tlsEnabled = tls,
      tlsCertChain = s"$certDir/server-cert.pem",
      tlsPrivateKey = s"$certDir/server-key.pem",
      maxHeartbeatTimeoutSec = 3600,
      maxBodyBytes = maxBody,
      maxConnectBodyBytes = maxConnect,
      connectBodyTimeoutSec = connectTimeout,
      bodyTimeoutSec = bodyTimeout
    )

  /** Echo handler: answers an ERROR_RESPONSE carrying the request length. */
  private val echo: Array[Byte] => IO[Array[Byte]] =
    bytes => IO.pure(QuackWire.encodeError(s"len=${bytes.length}"))

  private val LiveSession = "live-session"

  private def withServer[A](c: QuackNativeConfig)(body: String => A): A =
    val server = new QuackFrontDoorServer(c, echo, _ == LiveSession, _ => IO.unit, IO.unit)
    server.start().unsafeRunSync()
    try body(s"${if c.tlsEnabled then "https" else "http"}://127.0.0.1:${c.port}")
    finally server.stop()

  /** A message for `id` with `extra` bytes after its header. */
  private def sessionMessage(id: String, extra: Int): Array[Byte] =
    QuackWire.encodeDisconnect(id) ++ Array.fill[Byte](extra)(0)

  private def connectMessage(extra: Int): Array[Byte] =
    QuackWire.encodeConnectionRequest(
      QuackWire.ConnectionRequest("t=1", "v2.0.0", "osx_arm64", 3L, 3L, "c", 0L),
      1L
    ) ++ Array.fill[Byte](extra)(0)

  private val plain = HttpClient.newHttpClient()

  private def post(client: HttpClient, base: String, body: Array[Byte]): HttpResponse[Array[Byte]] =
    client.send(
      HttpRequest
        .newBuilder(URI.create(s"$base/quack"))
        .header("Content-Type", "application/vnd.duckdb")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build(),
      HttpResponse.BodyHandlers.ofByteArray()
    )

  /** What a raw socket gets: the status line, the lower-cased head, and the body bytes. Announces
    * `declared` bytes and sends only `first` (then `rest`, after `pause` ms, when given). The
    * socket is read to its end, so a request the server answers keep-alive passes `closing = true`.
    */
  private def rawPost(
      base: String,
      declared: Long,
      first: Array[Byte],
      rest: Array[Byte] = Array.emptyByteArray,
      pause: Long = 0,
      closing: Boolean = false
  ): (String, String, Array[Byte]) =
    val uri  = URI.create(base)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    try
      sock.setSoTimeout(15000)
      val out = sock.getOutputStream
      val own = if closing then "Connection: close\r\n" else ""
      out.write(
        s"POST /quack HTTP/1.1\r\nHost: localhost\r\n${own}Content-Length: $declared\r\n\r\n"
          .getBytes("ISO-8859-1")
      )
      out.write(first)
      out.flush()
      if rest.nonEmpty then
        Thread.sleep(pause)
        out.write(rest)
        out.flush()
      readResponse(sock)
    finally sock.close()

  private val CrLf: Array[Byte] = "\r\n".getBytes("ISO-8859-1")

  /** Like [[rawPost]], but with no announced length: each of `chunks` goes out as one HTTP chunk,
    * `pause` ms apart, so a limit has to hold on the stream itself. The final empty chunk goes out
    * only when `last` is set: a request that is refused is answered before its body ends.
    */
  private def rawChunkedPost(
      base: String,
      chunks: Seq[Array[Byte]],
      last: Boolean,
      pause: Long = 300
  ): (String, String, Array[Byte]) =
    val uri  = URI.create(base)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    try
      sock.setSoTimeout(15000)
      val out = sock.getOutputStream
      out.write(
        ("POST /quack HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" +
          "Transfer-Encoding: chunked\r\n\r\n").getBytes("ISO-8859-1")
      )
      chunks.foreach { c =>
        // One write per chunk: a trailing CRLF the server never reads would make its close a reset.
        val framed = s"${c.length.toHexString}\r\n".getBytes("ISO-8859-1") ++ c ++ CrLf
        out.write(framed)
        out.flush()
        Thread.sleep(pause)
      }
      if last then
        out.write("0\r\n\r\n".getBytes("ISO-8859-1"))
        out.flush()
      readResponse(sock)
    finally sock.close()

  private def readResponse(sock: java.net.Socket): (String, String, Array[Byte]) =
    val all  = sock.getInputStream.readAllBytes()
    val text = new String(all, "ISO-8859-1")
    val end  = text.indexOf("\r\n\r\n")
    (text.takeWhile(_ != '\r'), text.substring(0, end).toLowerCase, all.drop(end + 4))

  "POST /quack" should "hand the body to the dispatcher and answer 200 with the wire media type" in:
    withServer(cfg(freePort())) { base =>
      val resp = post(plain, base, Array.fill[Byte](5)(1))
      resp.statusCode() shouldBe 200
      resp.headers().firstValue("content-type").orElse("") should startWith(
        "application/vnd.duckdb"
      )
      QuackWire.decodeErrorMessage(resp.body()) shouldBe Right("len=5")
    }

  it should "keep a CONNECTION_REQUEST within its own limit" in:
    withServer(cfg(freePort(), maxConnect = 128)) { base =>
      post(plain, base, connectMessage(0)).statusCode() shouldBe 200
      post(plain, base, connectMessage(1024)).statusCode() shouldBe 400
    }

  it should "read a live session's message up to the full limit" in:
    withServer(cfg(freePort(), maxBody = 2048, maxConnect = 64)) { base =>
      val ok = post(plain, base, sessionMessage(LiveSession, 1024))
      ok.statusCode() shouldBe 200
      QuackWire.decodeErrorMessage(ok.body()) shouldBe
        Right(s"len=${sessionMessage(LiveSession, 1024).length}")
      post(plain, base, sessionMessage(LiveSession, 4096)).statusCode() shouldBe 400
    }

  it should "answer an unknown connection id without reading the rest of its body" in:
    withServer(cfg(freePort(), maxBody = 1L << 30)) { base =>
      val first                 = QuackWire.encodeDisconnect("ghost")
      val (status, head, bytes) = rawPost(base, declared = 1L << 28, first)
      status should include("200")
      head should include("connection: close")
      QuackWire.decodeErrorMessage(bytes) shouldBe Right(s"len=${first.length}")
    }

  it should "answer 408 when a connect stops arriving" in:
    withServer(cfg(freePort(), connectTimeout = 1)) { base =>
      val (status, head, _) = rawPost(base, declared = 1024, connectMessage(0).take(3))
      status should include("408")
      head should include("connection: close")
    }

  it should "give a live session's body the longer deadline" in:
    withServer(cfg(freePort(), connectTimeout = 1, bodyTimeout = 30)) { base =>
      val first          = QuackWire.encodeDisconnect(LiveSession)
      val rest           = Array.fill[Byte](16)(0)
      val (status, _, _) =
        rawPost(
          base,
          declared = first.length.toLong + 16,
          first,
          rest,
          pause = 1500,
          closing = true
        )
      status should include("200")
    }

  it should "answer 408 when a live session's body misses its deadline" in:
    withServer(cfg(freePort(), connectTimeout = 1, bodyTimeout = 2)) { base =>
      val (status, _, _) = rawPost(base, declared = 1024, QuackWire.encodeDisconnect(LiveSession))
      status should include("408")
    }

  it should "refuse an announced length over the limit without waiting for the body" in:
    withServer(cfg(freePort(), maxConnect = 128)) { base =>
      val (status, head, _) = rawPost(base, declared = 1L << 20, connectMessage(0))
      status should include("400")
      head should include("connection: close")
    }

  it should "hold a body of unannounced length to its limit on the stream" in:
    withServer(cfg(freePort(), maxConnect = 128)) { base =>
      val (ok, _, echoed) = rawChunkedPost(base, Seq(connectMessage(0)), last = true)
      ok should include("200")
      QuackWire.decodeErrorMessage(echoed) shouldBe Right(s"len=${connectMessage(0).length}")
      // The header is within the limit; the second chunk takes the message past it.
      val (status, head, _) =
        rawChunkedPost(base, Seq(connectMessage(0), Array.fill[Byte](1024)(0)), last = false)
      status should include("400")
      head should include("connection: close")
    }

  it should "read a header that arrives in pieces" in:
    withServer(cfg(freePort())) { base =>
      val msg                = sessionMessage(LiveSession, 16)
      val (status, _, bytes) =
        rawPost(base, msg.length.toLong, msg.take(3), msg.drop(3), pause = 300, closing = true)
      status should include("200")
      QuackWire.decodeErrorMessage(bytes) shouldBe Right(s"len=${msg.length}")
    }

  it should "stop waiting for a header that has not completed within the peek limit" in:
    withServer(cfg(freePort())) { base =>
      // Field 2 (connection id) announcing 1 MiB, of which only a little over the limit ever comes.
      val unfinished = Array[Byte](2, 0, 0x80.toByte, 0x80.toByte, 0x40) ++
        Array.fill[Byte](QuackBodyRead.PeekMaxBytes + 1000)(97)
      val (status, head, bytes) = rawPost(base, declared = 1L << 28, unfinished)
      status should include("200")
      head should include("connection: close")
      val seen = QuackWire.decodeErrorMessage(bytes).toOption.get.stripPrefix("len=").toInt
      seen should (be >= QuackBodyRead.PeekMaxBytes and be <= unfinished.length)
    }

  it should "give a CONNECTION_REQUEST's body only the connect deadline" in:
    withServer(cfg(freePort(), connectTimeout = 1, bodyTimeout = 30)) { base =>
      val start          = System.nanoTime()
      val (status, _, _) = rawPost(base, declared = 1024, connectMessage(0))
      status should include("408")
      (System.nanoTime() - start) / 1000000000L should be < 10L
    }

  it should "drop a connection whose request head never finishes" in:
    withServer(cfg(freePort())) { base =>
      val uri  = URI.create(base)
      val sock = new java.net.Socket(uri.getHost, uri.getPort)
      try
        sock.setSoTimeout(15000)
        sock.getOutputStream.write("POST /quack HTTP/1.1\r\nHost: l".getBytes("ISO-8859-1"))
        sock.getOutputStream.flush()
        val start = System.nanoTime()
        sock.getInputStream.readAllBytes()
        (System.nanoTime() - start) / 1000000000L should be < 10L
      finally sock.close()
    }

  "GET /" should "identify the endpoint like a node does" in:
    withServer(cfg(freePort())) { base =>
      val resp = plain.send(
        HttpRequest.newBuilder(URI.create(s"$base/")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      resp.statusCode() shouldBe 200
      resp.body() should include("Quack")
      resp.body() should include("ATTACH")
    }

  "OPTIONS /quack" should "answer the permissive CORS preamble" in:
    withServer(cfg(freePort())) { base =>
      val resp = plain.send(
        HttpRequest
          .newBuilder(URI.create(s"$base/quack"))
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .build(),
        HttpResponse.BodyHandlers.discarding()
      )
      resp.statusCode() shouldBe 204
      resp.headers().firstValue("access-control-allow-origin").orElse("") shouldBe "*"
    }

  "TLS" should "serve the same endpoint from the Flight edge's PEM pair" in:
    val dir = java.nio.file.Files.createTempDirectory("qod-quack-tls").toString
    CertGen.ensureCertFiles(s"$dir/server-cert.pem", s"$dir/server-key.pem")
    val trustAll = new X509TrustManager:
      def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                     = Array.empty
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array[TrustManager](trustAll), new java.security.SecureRandom())
    val client = HttpClient.newBuilder().sslContext(ctx).build()
    withServer(cfg(freePort(), tls = true, certDir = dir)) { base =>
      val resp = post(client, base, Array.fill[Byte](3)(7))
      resp.statusCode() shouldBe 200
      QuackWire.decodeErrorMessage(resp.body()) shouldBe Right("len=3")
    }

  "the sweeper" should "call sweep with a fresh clock on every tick" in:
    val seen                       = scala.collection.mutable.ArrayBuffer.empty[Instant]
    val sweep: Instant => IO[Unit] = now => IO(seen.synchronized { seen += now; () })
    val server                     =
      new QuackFrontDoorServer(cfg(freePort()), echo, _ => false, sweep, IO.unit, 100.millis)
    server.start().unsafeRunSync()
    try Thread.sleep(1000)
    finally server.stop()
    val ticks = seen.synchronized(seen.toList)
    ticks.size should be >= 3
    ticks.distinct.size shouldBe ticks.size
