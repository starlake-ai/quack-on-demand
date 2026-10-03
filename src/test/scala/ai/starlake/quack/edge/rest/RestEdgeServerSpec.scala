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

  private def cfg(port: Int, tls: Boolean = false, certDir: String = "") = RestEdgeConfig(
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
    maxConnections = 8,
    maxHeaderBytes = 16384,
    headerReceiveTimeoutSec = 1,
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
        "note"      -> Json.fromString("é漢😀"),
        "client"    -> Json.fromString(req.client)
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

  private def withServer[A](c: RestEdgeConfig, throttle: Option[AuthThrottle] = None)(
      body: String => A
  ): A =
    val server = new RestEdgeServer(c, canned, throttle = throttle)
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

  // ---- the client key of the failed-auth throttle, on the wire --------------------------------

  private def clientOf(resp: HttpResponse[String]): String =
    io.circe.parser.parse(resp.body()).toOption.get.hcursor.get[String]("client").toOption.get

  "the client key" should "be the TCP peer, ignoring X-Forwarded-For from an untrusted peer" in
    withServer(cfg(freePort())) { base =>
      clientOf(get(s"$base$RowsPath", "X-Forwarded-For" -> "203.0.113.5")) shouldBe "127.0.0.1"
      clientOf(get(s"$base$RowsPath")) shouldBe "127.0.0.1"
    }

  it should "be the right-most untrusted X-Forwarded-For hop behind a trusted proxy" in
    withServer(cfg(freePort()).copy(trustedProxies = "127.0.0.1/32")) { base =>
      clientOf(get(s"$base$RowsPath", "X-Forwarded-For" -> "203.0.113.5")) shouldBe "203.0.113.5"
      clientOf(
        get(s"$base$RowsPath", "X-Forwarded-For" -> "203.0.113.5, 198.51.100.1")
      ) shouldBe "198.51.100.1"
      clientOf(get(s"$base$RowsPath", "X-Forwarded-For" -> "garbage")) shouldBe "127.0.0.1"
    }

  private def blockedThrottle(keys: String*): AuthThrottle =
    val t = new AuthThrottle(2, 60, 300, 1000)
    keys.foreach(k => (1 to 3).foreach(_ => t.recordFailure(k)))
    t

  "a blocked client" should "get 429 on every request, whatever the path or method" in {
    val port = freePort()
    withServer(cfg(port), Some(blockedThrottle("127.0.0.1"))) { base =>
      List(s"$base$RowsPath", s"$base/api/v1/nope").foreach { url =>
        val resp = get(url, "Authorization" -> "Bearer qod_pat_x")
        resp.statusCode() shouldBe 429
        resp.body() should include("too_many_auth_failures")
        resp.headers().firstValue("retry-after").orElse("") shouldBe "300"
        assertEdgeHeaders(h => resp.headers().firstValue(h).toScala)
      }
      val post = raw(port, s"POST $RowsPath HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
      statusOf(post) shouldBe 429
    }
  }

  it should "not block the other clients of the same trusted proxy" in
    withServer(
      cfg(freePort()).copy(trustedProxies = "127.0.0.1/32"),
      Some(blockedThrottle("203.0.113.5"))
    ) { base =>
      get(s"$base$RowsPath", "X-Forwarded-For" -> "203.0.113.5").statusCode() shouldBe 429
      get(s"$base$RowsPath", "X-Forwarded-For" -> "203.0.113.6").statusCode() shouldBe 200
      // The proxy's own key is not the blocked client's either.
      get(s"$base$RowsPath").statusCode() shouldBe 200
    }

  extension [A](o: java.util.Optional[A])
    private def toScala: Option[A] = if o.isPresent then Some(o.get) else None
