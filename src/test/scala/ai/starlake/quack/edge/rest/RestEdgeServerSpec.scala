package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.CertGen
import ai.starlake.quack.ondemand.api.ErrorResponse
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.{Header, StatusCode}
import sttp.tapir.server.ServerEndpoint

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, ServerSocket, Socket, SocketTimeoutException, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** What only the wire can show about the REST data edge (plus a TLS smoke): the transport gates,
  * the Ember limits, and the headers every response carries, including the ones Ember itself
  * generates. The handlers are canned server logic over the real [[RestEdgeEndpoints]]; raw sockets
  * are used wherever the JDK client would normalise the request.
  */
class RestEdgeServerSpec extends AnyFlatSpec with Matchers:

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private def cfg(
      port: Int,
      tls: Boolean = false,
      certDir: String = "",
      maxConnections: Int = 8,
      headerReceiveTimeoutSec: Int = 1
  ) = RestEdgeConfig(
    enabled = true,
    host = "127.0.0.1",
    port = port,
    tlsEnabled = tls,
    tlsCertChain = s"$certDir/server-cert.pem",
    tlsPrivateKey = s"$certDir/server-key.pem",
    defaultLimit = 10,
    maxRows = 100,
    maxResponseBytes = 1L << 20,
    stmtTimeoutSec = 5,
    maxConnections = maxConnections,
    maxHeaderBytes = 16384,
    headerReceiveTimeoutSec = headerReceiveTimeoutSec,
    idleTimeoutSec = 5
  )

  /** `/rows` echoes what the handler received; `/schemas` answers a handler-made 404. */
  private val canned: List[ServerEndpoint[Any, IO]] = List(
    RestEdgeEndpoints.readRows.serverLogic { case (tenant, _, _, _, req) =>
      val body = Json.obj(
        "tenant"    -> Json.fromString(tenant),
        "rawQuery"  -> Json.fromString(req.rawQuery),
        "requestId" -> Json.fromString(req.requestId),
        "auth"      -> Json.fromInt(req.authorization.size),
        "accept"    -> req.accept.fold(Json.Null)(Json.fromString),
        "note"      -> Json.fromString("é漢😀")
      )
      val ok = RestOk(List(Header("Content-Type", "application/json")), BodyBytes.of(body.noSpaces))
      IO.pure(RestEdgeServer.toTapir(Right(ok)))
    },
    RestEdgeEndpoints.listSchemas.serverLogic { _ =>
      IO.pure(
        Left(
          (StatusCode.NotFound, List(Header("Retry-After", "5")), ErrorResponse("not_found", "x"))
        )
      )
    }
  )

  private def withServer[A](
      c: RestEdgeConfig,
      tlsReadDeadline: Option[FiniteDuration] = None,
      tlsHandshakeDeadline: Option[FiniteDuration] = None
  )(body: String => A): A =
    val server = new RestEdgeServer(
      c,
      canned,
      tlsReadDeadline = tlsReadDeadline,
      tlsHandshakeDeadline = tlsHandshakeDeadline
    )
    server.start().unsafeRunSync()
    try body(s"${if c.tlsEnabled then "https" else "http"}://127.0.0.1:${c.port}")
    finally server.stop()

  private val client = HttpClient.newHttpClient()

  private def get(url: String, headers: (String, String)*): HttpResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(url)).GET()
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private val RowsPath = "/api/v1/tenant/acme/database/acme_lake/schemas/main/tables/t/rows"

  /** Sends `request` verbatim and reads until the server closes or 5 s pass. */
  private def raw(port: Int, request: String): String =
    val s = new Socket()
    s.connect(new InetSocketAddress("127.0.0.1", port), 2000)
    s.setSoTimeout(5000)
    try
      s.getOutputStream.write(request.getBytes(UTF_8))
      s.getOutputStream.flush()
      val out = new ByteArrayOutputStream()
      val buf = new Array[Byte](8192)
      var n   = 0
      try while { n = s.getInputStream.read(buf); n >= 0 } do out.write(buf, 0, n)
      catch case _: SocketTimeoutException => ()
      new String(out.toByteArray, UTF_8)
    finally s.close()

  private def statusOf(resp: String): Int = resp.split(" ", 3)(1).toInt

  private def headerOf(resp: String, name: String): Option[String] =
    resp
      .split("\r\n\r\n", 2)(0)
      .split("\r\n")
      .drop(1)
      .collectFirst {
        case l if l.toLowerCase.startsWith(name.toLowerCase + ":") => l.drop(name.length + 1).trim
      }

  private val SecurityHeaders = List(
    "X-Content-Type-Options"  -> "nosniff",
    "Content-Security-Policy" -> "default-src 'none'; frame-ancestors 'none'",
    "Referrer-Policy"         -> "no-referrer"
  )

  private def assertEdgeHeaders(lookup: String => Option[String]): Unit =
    SecurityHeaders.foreach((k, v) => lookup(k) shouldBe Some(v))
    lookup("X-Request-Id").getOrElse("") should fullyMatch regex "[0-9a-f-]{36}"
    lookup("Server") shouldBe None

  // ---- routing and the raw request --------------------------------------------------------------

  "a GET on a route" should "reach the handler with the raw query, every Authorization and a fresh id" in
    withServer(cfg(freePort())) { base =>
      val resp = get(
        s"$base$RowsPath?c=eq.1+2&d=%2B&e=a%20b",
        "Authorization" -> "Bearer qod_pat_x",
        "X-Request-Id"  -> "client-chosen-id",
        "Accept"        -> "text/csv"
      )
      resp.statusCode() shouldBe 200
      resp.headers().firstValue("content-type").orElse("") shouldBe "application/json"
      val body = io.circe.parser.parse(resp.body()).toOption.get.hcursor
      body.get[String]("rawQuery") shouldBe Right("c=eq.1+2&d=%2B&e=a%20b")
      body.get[Int]("auth") shouldBe Right(1)
      body.get[String]("accept") shouldBe Right("text/csv")
      val rid = resp.headers().firstValue("x-request-id").orElse("")
      body.get[String]("requestId") shouldBe Right(rid)
      rid should not be "client-chosen-id"
      assertEdgeHeaders(h => resp.headers().firstValue(h).toScala)
      resp.headers().firstValue("vary").orElse("") shouldBe "Authorization"
      resp.headers().firstValue("cache-control").orElse("") shouldBe "private, no-cache"
      resp.headers().firstValue("strict-transport-security").isPresent shouldBe false
      // A buffered body goes out whole, with its length in bytes, not characters.
      resp.headers().firstValue("content-length").orElse("") shouldBe
        resp.body().getBytes(UTF_8).length.toString
      body.get[String]("note") shouldBe Right("é漢😀")
    }

  it should "keep the handler's own error headers and body" in
    withServer(cfg(freePort())) { base =>
      val resp = get(s"$base/api/v1/tenant/acme/database/acme_lake/schemas")
      resp.statusCode() shouldBe 404
      resp.headers().firstValue("retry-after").orElse("") shouldBe "5"
      resp.body() should include("not_found")
      assertEdgeHeaders(h => resp.headers().firstValue(h).toScala)
    }

  "an unknown path" should "get the JSON 404 with every edge header" in
    withServer(cfg(freePort())) { base =>
      val resp = get(s"$base/api/v1/nope")
      resp.statusCode() shouldBe 404
      resp.body() should include("\"not_found\"")
      assertEdgeHeaders(h => resp.headers().firstValue(h).toScala)
    }

  // ---- transport gates, over a raw socket -------------------------------------------------------

  "a method other than GET" should "get 405" in {
    val port = freePort()
    withServer(cfg(port)) { _ =>
      List("POST", "HEAD", "PUT", "DELETE", "OPTIONS").foreach { m =>
        withClue(m) {
          val resp = raw(port, s"$m $RowsPath HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
          statusOf(resp) shouldBe 405
          headerOf(resp, "Allow") shouldBe Some("GET")
          assertEdgeHeaders(headerOf(resp, _))
        }
      }
    }
  }

  "a GET that carries a body" should "get 400 with Connection: close, the body unread" in {
    val port = freePort()
    withServer(cfg(port)) { _ =>
      val big = raw(
        port,
        s"GET $RowsPath HTTP/1.1\r\nHost: x\r\nContent-Length: 1000000000\r\n\r\n"
      )
      statusOf(big) shouldBe 400
      headerOf(big, "Connection").map(_.toLowerCase) shouldBe Some("close")
      assertEdgeHeaders(headerOf(big, _))
      val chunked = raw(
        port,
        s"GET $RowsPath HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n"
      )
      statusOf(chunked) shouldBe 400
      headerOf(chunked, "Connection").map(_.toLowerCase) shouldBe Some("close")
    }
  }

  "a request head over the header limit" should "get 431 from Ember, with every edge header" in {
    val port = freePort()
    withServer(cfg(port)) { _ =>
      val resp = raw(
        port,
        s"GET $RowsPath HTTP/1.1\r\nHost: x\r\nX-Pad: ${"a" * (17 * 1024)}\r\n" +
          "Connection: close\r\n\r\n"
      )
      withClue(resp.take(600))(statusOf(resp) shouldBe 431)
      assertEdgeHeaders(headerOf(resp, _))
    }
  }

  "a malformed request line" should "get 400 from Ember, with every edge header" in {
    val port = freePort()
    withServer(cfg(port)) { _ =>
      val resp = raw(port, "THIS IS NOT HTTP\r\n\r\n")
      statusOf(resp) shouldBe 400
      assertEdgeHeaders(headerOf(resp, _))
    }
  }

  "a client that sends its headers too slowly" should "have the connection closed" in {
    val port = freePort()
    withServer(cfg(port)) { _ =>
      val s = new Socket()
      s.connect(new InetSocketAddress("127.0.0.1", port), 2000)
      s.setSoTimeout(6000)
      try
        s.getOutputStream.write(s"GET $RowsPath HTTP/1.1\r\nHost: x\r\n".getBytes(UTF_8))
        s.getOutputStream.flush()
        val start = System.nanoTime()
        // Drain whatever the server answers (Ember may write a 408 first) until it closes.
        val buf = new Array[Byte](1024)
        while s.getInputStream.read(buf) >= 0 do ()
        val waited = (System.nanoTime() - start) / 1000000
        waited should be < 5000L
      finally s.close()
    }
  }

  // ---- TLS --------------------------------------------------------------------------------------

  "TLS" should "serve the edge from the Flight edge's PEM pair and add HSTS" in {
    val dir = java.nio.file.Files.createTempDirectory("qod-rest-tls").toString
    CertGen.ensureCertFiles(s"$dir/server-cert.pem", s"$dir/server-key.pem")
    val trustAll = new X509TrustManager:
      def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                     = Array.empty
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array[TrustManager](trustAll), new java.security.SecureRandom())
    val tlsClient = HttpClient.newBuilder().sslContext(ctx).build()
    withServer(cfg(freePort(), tls = true, certDir = dir)) { base =>
      base should startWith("https://")
      val resp = tlsClient.send(
        HttpRequest.newBuilder(URI.create(s"$base$RowsPath")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      resp.statusCode() shouldBe 200
      resp.headers().firstValue("strict-transport-security").orElse("") should include("max-age=")
    }
  }

  it should "close a connection that never speaks, and then serve a client" in {
    // One slot: Ember's header and idle timeouts start only after the TLS handshake, so without the
    // read deadline a silent connection would hold it for ever.
    val dir = java.nio.file.Files.createTempDirectory("qod-rest-tls-silent").toString
    CertGen.ensureCertFiles(s"$dir/server-cert.pem", s"$dir/server-key.pem")
    val trustAll = new X509TrustManager:
      def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                     = Array.empty
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array[TrustManager](trustAll), new java.security.SecureRandom())
    val tlsClient = HttpClient.newBuilder().sslContext(ctx).build()
    withServer(
      cfg(freePort(), tls = true, certDir = dir, maxConnections = 1),
      tlsReadDeadline = Some(1.second)
    ) { base =>
      val uri    = URI.create(base)
      val silent = new java.net.Socket(uri.getHost, uri.getPort)
      try
        silent.setSoTimeout(5000)
        val start = System.nanoTime()
        silent.getInputStream.read() shouldBe -1
        (System.nanoTime() - start) / 1000000L should be < 5000L
      finally silent.close()
      val resp = tlsClient.send(
        HttpRequest.newBuilder(URI.create(s"$base$RowsPath")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      resp.statusCode() shouldBe 200
    }
  }

  it should "close a handshake that drips a byte inside every read deadline" in {
    // The per-read deadline (2 s) never fires on a byte every 300 ms; the handshake as a whole is
    // bounded by the header receive timeout (1 s in cfg), whatever the idle timeout.
    val dir = java.nio.file.Files.createTempDirectory("qod-rest-tls-drip").toString
    CertGen.ensureCertFiles(s"$dir/server-cert.pem", s"$dir/server-key.pem")
    withServer(
      cfg(freePort(), tls = true, certDir = dir, maxConnections = 1),
      tlsReadDeadline = Some(2.seconds)
    ) { base =>
      val uri = URI.create(base)
      val s   = new java.net.Socket(uri.getHost, uri.getPort)
      try
        s.setSoTimeout(300)
        // A handshake record header announcing 16 KiB, then its body a byte at a time.
        val drip   = Iterator(0x16, 0x03, 0x01, 0x40, 0x00) ++ Iterator.continually(0)
        val start  = System.nanoTime()
        var closed = false
        while !closed && (System.nanoTime() - start) / 1000000L < 6000L do
          try
            s.getOutputStream.write(drip.next())
            s.getOutputStream.flush()
            closed = s.getInputStream.read() == -1
          catch
            case _: SocketTimeoutException => ()
            case _: java.io.IOException    => closed = true
        closed shouldBe true
        (System.nanoTime() - start) / 1000000L should be < 4000L
      finally s.close()
    }
  }

  it should "leave a connection past its handshake to the read deadline alone" in {
    // The handshake's budget (1 s) ends with the handshake: a request sent 1.5 s later on the same
    // socket, inside the read deadline (3 s) and the header receive timeout (5 s), is served.
    val dir = java.nio.file.Files.createTempDirectory("qod-rest-tls-after").toString
    CertGen.ensureCertFiles(s"$dir/server-cert.pem", s"$dir/server-key.pem")
    val trustAll = new X509TrustManager:
      def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate]                     = Array.empty
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array[TrustManager](trustAll), new java.security.SecureRandom())
    List("TLSv1.2", "TLSv1.3").foreach { proto =>
      withServer(
        cfg(freePort(), tls = true, certDir = dir, headerReceiveTimeoutSec = 5),
        tlsReadDeadline = Some(3.seconds),
        tlsHandshakeDeadline = Some(1.second)
      ) { base =>
        val uri = URI.create(base)
        val s   = ctx.getSocketFactory
          .createSocket(uri.getHost, uri.getPort)
          .asInstanceOf[javax.net.ssl.SSLSocket]
        try
          s.setEnabledProtocols(Array(proto))
          s.setSoTimeout(5000)
          s.startHandshake()
          Thread.sleep(1500)
          s.getOutputStream.write(
            s"GET $RowsPath HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(UTF_8)
          )
          s.getOutputStream.flush()
          val head = new String(s.getInputStream.readNBytes(12), UTF_8)
          withClue(proto)(head shouldBe "HTTP/1.1 200")
        finally s.close()
      }
    }
  }

  "a page body" should "reach the socket in chunks no bigger than WriteChunkBytes, whole" in {
    // fs2's TLS write buffer grows to the largest chunk written and never shrinks: one chunk per
    // page would leave every keep-alive connection holding a page's worth of heap after the page
    // budget had given it back.
    val page = "x" * (3 * RestEdgeServer.WriteChunkBytes + 17)
    val big  = List(
      RestEdgeEndpoints.readRows.serverLogic { _ =>
        val ok = RestOk(List(Header("Content-Type", "application/json")), BodyBytes.of(page))
        IO.pure(RestEdgeServer.toTapir(Right(ok)))
      }
    )
    val server = new RestEdgeServer(cfg(freePort()), big)
    val req    = org.http4s.Request[IO](
      org.http4s.Method.GET,
      org.http4s.Uri.unsafeFromString(RowsPath)
    )
    val sizes = server.app
      .run(req)
      .flatMap(_.body.chunks.map(_.size).compile.toList)
      .unsafeRunSync()
    sizes.sum should equal(page.length)
    sizes.max should be <= RestEdgeServer.WriteChunkBytes
  }

  extension [A](o: java.util.Optional[A])
    private def toScala: Option[A] = if o.isPresent then Some(o.get) else None
