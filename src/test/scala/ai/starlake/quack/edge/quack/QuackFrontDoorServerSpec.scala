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
import java.util.concurrent.atomic.AtomicInteger
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
      bodyTimeout: Long = 300,
      maxConnections: Int = 1024
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
      bodyTimeoutSec = bodyTimeout,
      maxConnections = maxConnections
    )

  /** Echo handler: answers an ERROR_RESPONSE carrying the request length. */
  private val echo: Array[Byte] => IO[Array[Byte]] =
    bytes => IO.pure(QuackWire.encodeError(s"len=${bytes.length}"))

  private val LiveSession = "live-session"

  private def withServer[A](
      c: QuackNativeConfig,
      handle: Array[Byte] => IO[Array[Byte]] = echo,
      tlsReadDeadline: FiniteDuration = QuackFrontDoorServer.TlsReadDeadline
  )(body: String => A): A =
    val server = new QuackFrontDoorServer(
      c,
      handle,
      _ == LiveSession,
      _ => IO.unit,
      IO.unit,
      tlsReadDeadline = tlsReadDeadline
    )
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

  /** A request with just the header lines given and no body: the status line, the lower-cased head
    * and the body bytes of what the server answers.
    */
  private def rawHead(base: String, headers: String*): (String, String, Array[Byte]) =
    val uri  = URI.create(base)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    try
      sock.setSoTimeout(15000)
      val head =
        ("POST /quack HTTP/1.1" +: "Host: localhost" +: headers).mkString("", "\r\n", "\r\n\r\n")
      sock.getOutputStream.write(head.getBytes("ISO-8859-1"))
      sock.getOutputStream.flush()
      readResponse(sock)
    finally sock.close()

  /** A request announcing `declared` bytes, of which one of `bytes` goes out every `gapMillis`
    * while the server listens: the status line the server answers, and how many milliseconds after
    * the request head went out it came.
    */
  private def rawTrickle(
      base: String,
      declared: Long,
      bytes: Array[Byte],
      gapMillis: Long
  ): (String, Long) =
    val uri  = URI.create(base)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    try
      sock.setSoTimeout(15000)
      val out = sock.getOutputStream
      out.write(
        ("POST /quack HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" +
          s"Content-Length: $declared\r\n\r\n").getBytes("ISO-8859-1")
      )
      out.flush()
      val start  = System.nanoTime()
      val writer = new Thread(() =>
        try
          bytes.foreach { b =>
            out.write(b.toInt)
            out.flush()
            Thread.sleep(gapMillis)
          }
        catch case _: java.io.IOException => () // The server answered, and closed.
      )
      writer.setDaemon(true)
      writer.start()
      val (status, _, _) = readResponse(sock)
      (status, (System.nanoTime() - start) / 1000000L)
    finally sock.close()

  /** Everything the server sends until it closes. A close that reaches a client still writing can
    * arrive as a reset: what came before it is kept.
    */
  private def readResponse(sock: java.net.Socket): (String, String, Array[Byte]) =
    val received = new java.io.ByteArrayOutputStream()
    val buffer   = new Array[Byte](4096)
    val in       = sock.getInputStream
    try
      Iterator.continually(in.read(buffer)).takeWhile(_ >= 0).foreach(received.write(buffer, 0, _))
    catch case _: java.net.SocketException if received.size > 0 => ()
    val all  = received.toByteArray
    val text = new String(all, "ISO-8859-1")
    val end  = text.indexOf("\r\n\r\n")
    withClue("the server sent no response head: ")(end should be >= 0)
    (text.takeWhile(_ != '\r'), text.substring(0, end).toLowerCase, all.drop(end + 4))

  /** Connects and sends one whole request that asks for the connection to be closed after its
    * answer, then leaves the answer unread: the server serves the connection when it has a slot for
    * it.
    */
  private def sendRequest(base: String, body: Array[Byte]): java.net.Socket =
    val uri  = URI.create(base)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    val head =
      "POST /quack HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" +
        s"Content-Length: ${body.length}\r\n\r\n"
    sock.getOutputStream.write(head.getBytes("ISO-8859-1") ++ body)
    sock.getOutputStream.flush()
    sock

  /** A connection that has had one request answered and stays open: it holds a connection slot
    * until the caller closes it. The request names the live session, since the server closes the
    * connection after the answer to a message it does not admit.
    */
  private def answeredAndHeld(base: String): java.net.Socket =
    val uri  = URI.create(base)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    sock.setSoTimeout(15000)
    val body = sessionMessage(LiveSession, 0)
    sock.getOutputStream.write(
      s"POST /quack HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\n\r\n"
        .getBytes("ISO-8859-1") ++ body
    )
    sock.getOutputStream.flush()
    // The answer is a head that announces its length, and then that many bytes.
    val in   = sock.getInputStream
    val head = new StringBuilder
    while !head.toString.endsWith("\r\n\r\n") do
      val next = in.read()
      withClue("the server closed the connection before it answered: ")(next should be >= 0)
      head.append(next.toChar)
    val length = """(?i)content-length: (\d+)""".r.findFirstMatchIn(head.toString)
    in.readNBytes(length.map(_.group(1).toInt).getOrElse(fail(s"the answer has no length: $head")))
    sock

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

  it should "answer 411 to chunked framing, in any spelling, without running the handler" in:
    val calls = new AtomicInteger(0)
    withServer(cfg(freePort()), handle = bytes => IO(calls.incrementAndGet()) *> echo(bytes)) {
      base =>
        Seq(
          "Transfer-Encoding: chunked",
          "transfer-encoding: CHUNKED",
          "Transfer-Encoding: foo, chunked"
        ).foreach { header =>
          withClue(s"$header: ") {
            val (status, head, _) = rawHead(base, header)
            status should include("411")
            head should include("connection: close")
          }
        }
        calls.get shouldBe 0
    }

  it should "answer 408 at the deadline to a client that keeps sending a byte at a time" in:
    withServer(cfg(freePort(), connectTimeout = 1)) { base =>
      // A byte every 200 ms never leaves a read waiting long: only a deadline on the whole body
      // ends this one, where a timeout on each read would let it send its 47 bytes in 9 s.
      val message          = connectMessage(0)
      val (status, millis) = rawTrickle(base, message.length.toLong, message, gapMillis = 200)
      status should include("408")
      millis should (be >= 900L and be < 4000L)
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

  "The connection limit" should "hold a client until a connection leaves, then answer it" in:
    // One slot, held by a connection that is answered and then kept open.
    withServer(cfg(freePort(), maxConnections = 1)) { base =>
      val holder  = answeredAndHeld(base)
      val message = sessionMessage(LiveSession, 5)
      try
        val client = sendRequest(base, message)
        try
          client.setSoTimeout(1500)
          // The client connects and sends its request, and is not answered while the slot is taken.
          a[java.net.SocketTimeoutException] should be thrownBy client.getInputStream.read()
          // Once the holder leaves, the slot is free and the client is answered.
          holder.close()
          client.setSoTimeout(15000)
          val (status, _, bytes) = readResponse(client)
          status should include("200")
          QuackWire.decodeErrorMessage(bytes) shouldBe Right(s"len=${message.length}")
        finally client.close()
      finally holder.close()
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

  /** A directory holding the PEM pair the TLS listener serves, and a client that trusts it. */
  private def tlsFixture(): (String, HttpClient) =
    val dir = java.nio.file.Files.createTempDirectory("qod-quack-tls").toString
    CertGen.ensureCertFiles(s"$dir/server-cert.pem", s"$dir/server-key.pem")
    val trustAll = new X509TrustManager:
      def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                     = Array.empty
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array[TrustManager](trustAll), new java.security.SecureRandom())
    (dir, HttpClient.newBuilder().sslContext(ctx).build())

  "TLS" should "serve the same endpoint from the Flight edge's PEM pair" in:
    val (dir, client) = tlsFixture()
    withServer(cfg(freePort(), tls = true, certDir = dir)) { base =>
      val resp = post(client, base, Array.fill[Byte](3)(7))
      resp.statusCode() shouldBe 200
      QuackWire.decodeErrorMessage(resp.body()) shouldBe Right("len=3")
    }

  it should "close a silent connection, and then serve a client" in:
    val (dir, client) = tlsFixture()
    // One slot: a connection that never speaks would hold it for ever, since Ember's own timeouts
    // start only after the handshake.
    withServer(
      cfg(freePort(), tls = true, certDir = dir, maxConnections = 1),
      tlsReadDeadline = 1.second
    ) { base =>
      val uri    = URI.create(base)
      val silent = new java.net.Socket(uri.getHost, uri.getPort)
      try
        silent.setSoTimeout(5000)
        val start = System.nanoTime()
        silent.getInputStream.read() shouldBe -1
        (System.nanoTime() - start) / 1000000L should be < 5000L
      finally silent.close()
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
