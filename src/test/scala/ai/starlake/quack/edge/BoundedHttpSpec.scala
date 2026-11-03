package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.EchoingServer
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.net.http.{HttpClient, HttpRequest}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, TimeUnit}

/** [[BoundedHttp.send]] against a real local `HttpServer`: the answer is read under a size and a
  * time limit over the WHOLE exchange. The server handlers record how far they got and when their
  * connection broke, so a case can tell a client that stopped reading from one that merely
  * returned.
  */
class BoundedHttpSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private val MiB   = 1024L * 1024L
  private val UTF_8 = StandardCharsets.UTF_8

  // What the endless and the trickling handlers did, read by the cases after the client returned.
  private val endlessWritten = new AtomicLong
  private val endlessEnded   = new CountDownLatch(1)
  private val chunkedWritten = new AtomicLong
  private val chunkedEnded   = new CountDownLatch(1)
  private val trickleEnded   = new CountDownLatch(1)
  private val trickleBytes   = new AtomicLong

  private val SecretInRequest = "s3cr3t-client-secret-0123"

  private var executor: ExecutorService = null
  private var server: HttpServer        = null

  private def base = s"http://127.0.0.1:${server.getAddress.getPort}"

  private def reply(ex: HttpExchange, status: Int, body: Array[Byte]): Unit =
    ex.sendResponseHeaders(status, body.length.toLong)
    ex.getResponseBody.write(body)
    ex.close()

  /** Writes until the peer is gone, `chunk` bytes at a time, counting what the socket took. */
  private def writeUntilBroken(ex: HttpExchange, chunk: Int, written: AtomicLong): Unit =
    val block = Array.fill[Byte](chunk)('a'.toByte)
    try
      val out = ex.getResponseBody
      while true do
        out.write(block)
        out.flush()
        written.addAndGet(chunk.toLong)
    catch case _: IOException => ()

  override def beforeAll(): Unit =
    executor = Executors.newCachedThreadPool()
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.setExecutor(executor)
    server.createContext("/small", ex => reply(ex, 200, """{"a":"é"}""".getBytes(UTF_8)))
    server.createContext("/at-cap", ex => reply(ex, 200, Array.fill[Byte](1000)('b'.toByte)))
    server.createContext("/over-cap", ex => reply(ex, 200, Array.fill[Byte](1001)('b'.toByte)))
    server.createContext("/status/404", ex => reply(ex, 404, "no such thing".getBytes(UTF_8)))
    server.createContext("/status/503", ex => reply(ex, 503, "busy".getBytes(UTF_8)))
    // 256 MiB declared, written as fast as the client takes it, until the connection breaks.
    server.createContext(
      "/endless",
      ex =>
        ex.sendResponseHeaders(200, 256 * MiB)
        writeUntilBroken(ex, 32 * 1024, endlessWritten)
        endlessEnded.countDown()
        ex.close()
    )
    // The same, for the cases that only read the text of a Left: it leaves the counters alone.
    server.createContext(
      "/endless-quiet",
      ex =>
        ex.sendResponseHeaders(200, 256 * MiB)
        writeUntilBroken(ex, 32 * 1024, new AtomicLong)
        ex.close()
    )
    // The same with no declared length (chunked).
    server.createContext(
      "/endless-chunked",
      ex =>
        ex.sendResponseHeaders(200, 0)
        writeUntilBroken(ex, 32 * 1024, chunkedWritten)
        chunkedEnded.countDown()
        ex.close()
    )
    // Headers at once, then one byte every 20 ms for 30 s: never over any size cap.
    server.createContext(
      "/trickle",
      ex =>
        ex.sendResponseHeaders(200, 1 * MiB)
        try
          val out = ex.getResponseBody
          var i   = 0
          while i < 1500 do
            out.write('x')
            out.flush()
            trickleBytes.incrementAndGet()
            Thread.sleep(20)
            i += 1
        catch case _: IOException => ()
        trickleEnded.countDown()
        ex.close()
    )
    // The same trickle, for the case that only reads the text of a Left: it leaves the counters alone.
    server.createContext(
      "/trickle-quiet",
      ex =>
        ex.sendResponseHeaders(200, 1 * MiB)
        try
          val out = ex.getResponseBody
          var i   = 0
          while i < 1500 do
            out.write('x')
            out.flush()
            Thread.sleep(20)
            i += 1
        catch case _: IOException => ()
        ex.close()
    )
    // No answer at all for 4 s: the headers themselves are late.
    server.createContext(
      "/slow-start",
      ex =>
        Thread.sleep(4000)
        try reply(ex, 200, "late".getBytes(UTF_8))
        catch case _: IOException => ()
    )
    server.start()

  override def afterAll(): Unit =
    server.stop(0)
    executor.shutdownNow()
    ()

  private def get(path: String): HttpRequest =
    // The request's own timeout is far past every deadline below, so only the hard one can fire.
    HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).GET().build()

  private def post(path: String, body: String): HttpRequest =
    HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofSeconds(60))
      .header("Authorization", s"Bearer $SecretInRequest")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

  private def timed[A](f: => A): (A, Long) =
    val t0 = System.nanoTime()
    val a  = f
    (a, (System.nanoTime() - t0) / 1000000L)

  "BoundedHttp.send" should "return the status and the whole body of an answer within the cap" in {
    BoundedHttp.send(client, get("/small"), 1024, 10000, "test") shouldBe
      Right((200, """{"a":"é"}"""))
  }

  it should "accept an answer of exactly the cap and refuse one byte over it" in {
    BoundedHttp.send(client, get("/at-cap"), 1000, 10000, "test").map(_._2.length) shouldBe
      Right(1000)
    BoundedHttp.send(client, get("/over-cap"), 1000, 10000, "test") shouldBe
      Left("test response exceeds 1000 bytes")
  }

  it should "pass a status other than 200 through with its body" in {
    BoundedHttp.send(client, get("/status/404"), 1024, 10000, "test") shouldBe
      Right((404, "no such thing"))
    BoundedHttp.send(client, get("/status/503"), 1024, 10000, "test") shouldBe Right((503, "busy"))
  }

  it should "answer an answer over the cap with a Left, and stop reading the rest of it" in {
    val (out, ms) = timed(BoundedHttp.send(client, get("/endless"), 64 * 1024, 30000, "test"))
    out shouldBe Left("test response exceeds 64 KiB")
    // Not 30 s, and not the 256 MiB: the cap ended the read, and the connection with it.
    ms should be < 10000L
    endlessEnded.await(10, TimeUnit.SECONDS) shouldBe true
    endlessWritten.get should be < (32 * MiB)
  }

  it should "do the same when the answer declares no length" in {
    BoundedHttp.send(client, get("/endless-chunked"), 64 * 1024, 30000, "test") shouldBe
      Left("test response exceeds 64 KiB")
    chunkedEnded.await(10, TimeUnit.SECONDS) shouldBe true
    chunkedWritten.get should be < (32 * MiB)
  }

  it should "give up on a body that trickles past the deadline, within about the deadline" in {
    val (out, ms) = timed(BoundedHttp.send(client, get("/trickle"), 1024 * 1024, 700, "test"))
    out shouldBe Left("test request timed out after 700 ms")
    ms should (be >= 600L and be < 5000L)
    // The cancel released the connection: the handler, which had 30 s of bytes to go, saw it break.
    trickleEnded.await(10, TimeUnit.SECONDS) shouldBe true
    trickleBytes.get should be < 200L
  }

  it should "give up on an answer whose headers are late, within about the deadline" in {
    val (out, ms) = timed(BoundedHttp.send(client, get("/slow-start"), 1024, 500, "test"))
    out shouldBe Left("test request timed out after 500 ms")
    ms should (be >= 400L and be < 3500L)
  }

  it should "answer an unreachable port with a Left" in {
    val port = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val free = port.getLocalPort
    port.close()
    val request =
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$free/x")).timeout(Duration.ofSeconds(5))
    val (out, ms) =
      timed(BoundedHttp.send(client, request.GET().build(), 1024, 10000, "test"))
    out.isLeft shouldBe true
    ms should be < 5000L
  }

  it should "carry nothing of the request in any Left" in {
    // The form of a token request holds the client secret; a header can hold a bearer token.
    val form = s"grant_type=authorization_code&client_secret=$SecretInRequest"
    val port = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val free = port.getLocalPort
    port.close()
    val unreachable = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$free/token"))
      .timeout(Duration.ofSeconds(5))
      .header("Authorization", s"Bearer $SecretInRequest")
      .POST(HttpRequest.BodyPublishers.ofString(form))
      .build()
    val lefts = List(
      BoundedHttp.send(client, unreachable, 1024, 10000, "token endpoint"),
      BoundedHttp.send(client, post("/over-cap", form), 1000, 10000, "token endpoint"),
      BoundedHttp.send(client, post("/trickle-quiet", form), 1024 * 1024, 500, "token endpoint"),
      BoundedHttp.send(client, post("/slow-start", form), 1024, 300, "token endpoint")
    ).map(_.left.toOption)
    lefts.foreach(_ shouldBe defined)
    lefts.flatten.foreach { text =>
      text should not include SecretInRequest
      text should not include "client_secret"
      text should not include "Authorization"
    }
  }

  it should "keep the texts OpaClient has always answered, for what = OPA" in {
    BoundedHttp.send(client, get("/endless"), 1024 * 1024, 30000, "OPA") shouldBe
      Left("OPA response exceeds 1 MiB")
    BoundedHttp.send(client, get("/slow-start"), 1024 * 1024, 300, "OPA") shouldBe
      Left("OPA request timed out after 300 ms")
  }

  "BoundedHttp.MaxIdpAnswerBytes" should "be 256 KiB" in {
    BoundedHttp.MaxIdpAnswerBytes shouldBe 256 * 1024
  }

  "BoundedHttp.MaxJwksBytes and JwksTimeoutMs" should "be Nimbus' own 50 KiB, and 5 s over the whole read" in {
    BoundedHttp.MaxJwksBytes shouldBe 50 * 1024
    BoundedHttp.JwksTimeoutMs shouldBe 5000L
  }

  // ----- the token exchange: a form that holds the client secret, and a server that is not ours -----

  private val Form = s"grant_type=authorization_code&code=abc&client_secret=$SecretInRequest"

  private def exchange(url: String, refused: Int => String = c => s"refused $c") =
    BoundedHttp.tokenExchange(client, url, Form, refused)

  "BoundedHttp.tokenExchange" should "return the body of a 200 answer, sending the form to the endpoint" in {
    exchange(base + "/small") shouldBe Right("""{"a":"é"}""")
  }

  it should "name a refused status with the caller's text for its code, never the answer" in {
    exchange(base + "/status/503", c => s"token endpoint HTTP $c") shouldBe
      Left("token endpoint HTTP 503")
    exchange(base + "/status/404", c => s"Token exchange failed (HTTP $c)") shouldBe
      Left("Token exchange failed (HTTP 404)")
  }

  it should "answer every other failure with the one fixed text, nothing of the exchange in it" in {
    val unreachable =
      val port = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val free = port.getLocalPort
      port.close()
      s"http://127.0.0.1:$free/token"
    val failures = List(
      "unreachable"      -> exchange(unreachable),
      "over the cap"     -> exchange(base + "/endless-quiet"),
      "not a URL"        -> exchange("http://[not-a-url"),
      "no scheme at all" -> exchange("/token")
    )
    failures.foreach { (what, out) =>
      withClue(s"[$what] ")(out shouldBe Left(BoundedHttp.TokenEndpointFailed))
    }
    BoundedHttp.TokenEndpointFailed should not include SecretInRequest
  }

  it should "answer a server that quotes the form back in a malformed answer with the fixed text" in
    // The JDK refuses a malformed status line (or Content-Length) with an exception whose message
    // quotes it, so `send` itself passes the quote on, and a form echoed there holds the secret.
    List(EchoingServer.Shape.StatusLine, EchoingServer.Shape.ContentLength).foreach { shape =>
      val server = new EchoingServer(shape)
      try
        val request = HttpRequest
          .newBuilder(URI.create(server.base + "/token"))
          .timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(Form))
          .build()
        val raw = BoundedHttp.send(client, request, 1024, 10000, "token endpoint")
        withClue(s"[$shape] send says $raw, and ") {
          // What send alone does, and why the helper exists: the JDK's message quotes the form.
          raw.left.toOption.exists(_.contains(SecretInRequest)) shouldBe true
        }
        exchange(server.base + "/token") shouldBe Left(BoundedHttp.TokenEndpointFailed)
      finally server.close()
    }
