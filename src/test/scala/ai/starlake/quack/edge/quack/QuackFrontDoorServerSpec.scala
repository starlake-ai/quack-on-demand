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

  private def cfg(port: Int, tls: Boolean = false, certDir: String = "", maxBody: Long = 1 << 20) =
    QuackNativeConfig(
      enabled = true,
      host = "127.0.0.1",
      port = port,
      tlsEnabled = tls,
      tlsCertChain = s"$certDir/server-cert.pem",
      tlsPrivateKey = s"$certDir/server-key.pem",
      maxHeartbeatTimeoutSec = 3600,
      maxBodyBytes = maxBody
    )

  /** Echo handler: answers an ERROR_RESPONSE carrying the request length. */
  private val echo: Array[Byte] => IO[Array[Byte]] =
    bytes => IO.pure(QuackWire.encodeError(s"len=${bytes.length}"))

  private def withServer[A](c: QuackNativeConfig)(body: String => A): A =
    val server = new QuackFrontDoorServer(c, echo, _ => IO.unit, IO.unit)
    server.start().unsafeRunSync()
    try body(s"${if c.tlsEnabled then "https" else "http"}://127.0.0.1:${c.port}")
    finally server.stop()

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

  "POST /quack" should "hand the body to the dispatcher and answer 200 with the wire media type" in:
    withServer(cfg(freePort())) { base =>
      val resp = post(plain, base, Array.fill[Byte](5)(1))
      resp.statusCode() shouldBe 200
      resp.headers().firstValue("content-type").orElse("") should startWith(
        "application/vnd.duckdb"
      )
      QuackWire.decodeErrorMessage(resp.body()) shouldBe Right("len=5")
    }

  it should "refuse a body over the configured limit with 400" in:
    withServer(cfg(freePort(), maxBody = 16)) { base =>
      post(plain, base, Array.fill[Byte](17)(1)).statusCode() shouldBe 400
      post(plain, base, Array.fill[Byte](16)(1)).statusCode() shouldBe 200
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
    val server = new QuackFrontDoorServer(cfg(freePort()), echo, sweep, IO.unit, 100.millis)
    server.start().unsafeRunSync()
    try Thread.sleep(1000)
    finally server.stop()
    val ticks = seen.synchronized(seen.toList)
    ticks.size should be >= 3
    ticks.distinct.size shouldBe ticks.size
