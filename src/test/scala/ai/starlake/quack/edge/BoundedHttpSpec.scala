package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{AtOnce, EchoingServer, MalformedServer}
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import ch.qos.logback.classic.Level
import com.sun.net.httpserver.{HttpExchange, HttpServer, HttpsConfigurator, HttpsServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.http.{HttpClient, HttpRequest}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, TimeUnit}
import javax.net.ssl.{SSLContext, SSLServerSocket, SSLSocket}

/** [[BoundedHttp.send]] against a real local `HttpServer`: the answer is read under a size and a
  * time limit over the WHOLE exchange. The server handlers record how far they got and when their
  * connection broke, so a case can tell a client that stopped reading from one that merely
  * returned.
  */
class BoundedHttpSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with Eventually:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

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

  // The exchanges of the older cases: room for plenty, and the last case checks that none of them
  // left a permit taken, however it ended.
  private val SharedLimit = 1024
  private val shared      = new BoundedHttp.Exchanges(SharedLimit)

  // The answers the permit cases hold back until a case lets them go.
  @volatile private var gate: CountDownLatch = new CountDownLatch(0)
  private def awaitGate(): Unit              = gate.await(10, TimeUnit.SECONDS)

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
    // The same three endings, held back by the gate: an answer, an answer over the cap, and none
    // (the connection is closed without a word).
    server.createContext(
      "/gate-ok",
      ex =>
        awaitGate()
        reply(ex, 200, "ok".getBytes(UTF_8))
    )
    server.createContext(
      "/gate-big",
      ex =>
        awaitGate()
        reply(ex, 200, Array.fill[Byte](2000)('b'.toByte))
    )
    server.createContext(
      "/gate-drop",
      ex =>
        awaitGate()
        ex.close()
    )
    server.start()

  override def afterAll(): Unit =
    shared.close()
    server.stop(0)
    executor.shutdownNow()
    ()

  private def get(path: String): HttpRequest =
    // The request's own timeout is far past every deadline below, so only the hard one can fire.
    HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).GET().build()

  private def getFrom(url: String): HttpRequest =
    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build()

  private def post(path: String, body: String): HttpRequest =
    HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofSeconds(60))
      .header("Authorization", s"Bearer $SecretInRequest")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

  /** [[BoundedHttp.send]] through `shared`, unless a case brings its own `Exchanges`. */
  private def send(
      request: HttpRequest,
      cap: Int,
      timeoutMs: Long,
      what: String,
      exchanges: BoundedHttp.Exchanges = shared,
      redirects: HttpClient.Redirect = HttpClient.Redirect.NEVER,
      secret: Option[String] = None
  ): Either[String, (Int, String)] =
    BoundedHttp.send(request, cap, timeoutMs, what, exchanges, redirects, secret)

  private def timed[A](f: => A): (A, Long) =
    val t0 = System.nanoTime()
    val a  = f
    (a, (System.nanoTime() - t0) / 1000000L)

  "BoundedHttp.send" should "return the status and the whole body of an answer within the cap" in {
    send(get("/small"), 1024, 10000, "test") shouldBe Right((200, """{"a":"é"}"""))
  }

  it should "accept an answer of exactly the cap and refuse one byte over it" in {
    send(get("/at-cap"), 1000, 10000, "test").map(_._2.length) shouldBe Right(1000)
    send(get("/over-cap"), 1000, 10000, "test") shouldBe Left("test response exceeds 1000 bytes")
  }

  it should "pass a status other than 200 through with its body" in {
    send(get("/status/404"), 1024, 10000, "test") shouldBe Right((404, "no such thing"))
    send(get("/status/503"), 1024, 10000, "test") shouldBe Right((503, "busy"))
  }

  it should "answer an answer over the cap with a Left, and stop reading the rest of it" in {
    val (out, ms) = timed(send(get("/endless"), 64 * 1024, 30000, "test"))
    out shouldBe Left("test response exceeds 64 KiB")
    // Not 30 s, and not the 256 MiB: the cap ended the read, and the connection with it.
    ms should be < 10000L
    endlessEnded.await(10, TimeUnit.SECONDS) shouldBe true
    endlessWritten.get should be < (32 * MiB)
  }

  it should "do the same when the answer declares no length" in {
    send(get("/endless-chunked"), 64 * 1024, 30000, "test") shouldBe
      Left("test response exceeds 64 KiB")
    chunkedEnded.await(10, TimeUnit.SECONDS) shouldBe true
    chunkedWritten.get should be < (32 * MiB)
  }

  it should "give up on a body that trickles past the deadline, within about the deadline" in {
    val (out, ms) = timed(send(get("/trickle"), 1024 * 1024, 700, "test"))
    out shouldBe Left("test request timed out after 700 ms")
    ms should (be >= 600L and be < 5000L)
    // The cancel released the connection: the handler, which had 30 s of bytes to go, saw it break.
    trickleEnded.await(10, TimeUnit.SECONDS) shouldBe true
    trickleBytes.get should be < 200L
  }

  it should "give up on an answer whose headers are late, within about the deadline" in {
    val (out, ms) = timed(send(get("/slow-start"), 1024, 500, "test"))
    out shouldBe Left("test request timed out after 500 ms")
    ms should (be >= 400L and be < 3500L)
  }

  it should "answer an unreachable port with a Left" in {
    val port = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val free = port.getLocalPort
    port.close()
    val request =
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$free/x")).timeout(Duration.ofSeconds(5))
    val (out, ms) = timed(send(request.GET().build(), 1024, 10000, "test"))
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
      send(unreachable, 1024, 10000, "token endpoint"),
      send(post("/over-cap", form), 1000, 10000, "token endpoint"),
      send(post("/trickle-quiet", form), 1024 * 1024, 500, "token endpoint"),
      send(post("/slow-start", form), 1024, 300, "token endpoint")
    ).map(_.left.toOption)
    lefts.foreach(_ shouldBe defined)
    lefts.flatten.foreach { text =>
      text should not include SecretInRequest
      text should not include "client_secret"
      text should not include "Authorization"
    }
  }

  it should "keep the texts OpaClient has always answered, for what = OPA" in {
    send(get("/endless"), 1024 * 1024, 30000, "OPA") shouldBe Left("OPA response exceeds 1 MiB")
    send(get("/slow-start"), 1024 * 1024, 300, "OPA") shouldBe
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

  private def exchange(
      url: String,
      refused: Int => String = c => s"refused $c",
      exchanges: BoundedHttp.Exchanges = shared
  ) =
    BoundedHttp.tokenExchange(url, Form, refused, exchanges)

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
        val raw = send(request, 1024, 10000, "token endpoint")
        withClue(s"[$shape] send says $raw, and ") {
          // What send alone does, and why the helper exists: the JDK's message quotes the form.
          raw.left.toOption.exists(_.contains(SecretInRequest)) shouldBe true
        }
        exchange(server.base + "/token") shouldBe Left(BoundedHttp.TokenEndpointFailed)
      finally server.close()
    }

  it should "log the class of what the JDK raised, never its text, which can quote the form" in
    List(EchoingServer.Shape.StatusLine, EchoingServer.Shape.ContentLength).foreach { shape =>
      val server = new EchoingServer(shape)
      try
        val (out, events) =
          LogCapture.logged(BoundedHttp.getClass.getName)(exchange(server.base + "/token"))
        val logged = events.map(_.getFormattedMessage)
        withClue(s"[$shape] $out, logged $logged: ") {
          out shouldBe Left(BoundedHttp.TokenEndpointFailed)
          logged should not be empty
          logged.foreach { line =>
            line should not include SecretInRequest
            line should not include "client_secret"
            line should not include "Invalid"
          }
          logged.exists(_.matches(".*\\b[A-Z][A-Za-z]*Exception\\b.*")) shouldBe true
        }
      finally server.close()
    }

  // ----- an exchange leaves nothing behind -----
  //
  // The JDK's HttpClient keeps a connection whose response headers it refuses: its descriptor and
  // its buffers stay reachable from the client for as long as the client lives, and `send` has no
  // handle on it to close. Anyone can make the manager read the answer of an issuer a tenant admin
  // picked, so each such answer was a descriptor and its buffers for good (a 380 KB status line
  // halted the manager at -Xmx256m after 151 callbacks, a 40 character one stops it at the file
  // limit). A client is reused while its exchanges end in a clean answer, and shut down at once
  // after any other ending, which closes everything it holds.

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.ISO_8859_1)

  /** One answer a server can give, and how the client is meant to leave its connection: `clean` is
    * a good answer, whose connection is kept for the next exchange; every other shape is refused,
    * and its connection is closed at once.
    */
  private final case class Shape(
      name: String,
      answer: Array[Byte],
      redirects: HttpClient.Redirect = HttpClient.Redirect.NEVER,
      clean: Boolean = false
  )

  private val Following = HttpClient.Redirect.NORMAL

  private val Shapes = List(
    Shape(
      "a good answer the client could keep alive",
      bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"),
      clean = true
    ),
    Shape("an invalid status line", bytes("NOT-HTTP " + ("x" * 40) + "\r\n\r\n")),
    Shape(
      "an invalid status line of 380000 characters",
      bytes(s"NOT-HTTP ${"x" * 380000}\r\n\r\n")
    ),
    // The JDK quotes the line, and a log line could be forged with what the quote holds.
    Shape(
      "an invalid status line holding control characters",
      bytes("NOT-HTTP a\u0007b\u001b[2Jc\td\r\n\r\n")
    ),
    Shape("a status code that is no number", bytes("HTTP/1.1 abc OK\r\n\r\n")),
    Shape("a status code below 100", bytes("HTTP/1.1 099 OK\r\n\r\n")),
    Shape("a bare CR in the status line", bytes("HTTP/1.1 200 OK\rX\r\n\r\n")),
    Shape("a header name with a space", bytes("HTTP/1.1 200 OK\r\nBad Name: x\r\n\r\n")),
    Shape("a status line over 393216 bytes", bytes(s"HTTP/1.1 ${"y" * 400000}\r\n\r\n")),
    Shape("a header over 393216 bytes", bytes(s"HTTP/1.1 200 OK\r\nX: ${"y" * 400000}\r\n\r\n")),
    Shape(
      "a Content-Length that is no number",
      bytes("HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\n")
    ),
    Shape("an HTTP/2 preface", bytes("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")),
    // The key set of an identity provider is read by a client that follows redirects, which gives
    // an answer more ways to be refused.
    Shape(
      "a Content-Length too large to parse",
      bytes("HTTP/1.1 200 OK\r\nContent-Length: 99999999999999999999\r\n\r\n"),
      Following
    ),
    Shape(
      "a Location that is no URI",
      bytes("HTTP/1.1 302 Found\r\nLocation: ht!tp://x y\r\nContent-Length: 0\r\n\r\n"),
      Following
    ),
    Shape(
      "a 302 with no Location",
      bytes("HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n"),
      Following
    ),
    Shape("too many 1xx answers", bytes("HTTP/1.1 103 Early Hints\r\n\r\n" * 10), Following)
  )

  // `HttpClient.shutdownNow` is the JDK's from 21, the floor of the manager. On 17, which the build
  // can still compile on, a client goes only at a GC (measured: Temurin 17.0.20 closes the
  // connection of a refused answer then, Zulu 17.0.4.1 never does), so the cases that look at what
  // an exchange leaves behind, a moment after it, need 21 and are cancelled on 17.
  private val HasShutdownNow: Boolean =
    scala.util.Try(classOf[HttpClient].getMethod("shutdownNow")).isSuccess

  private def sendToShape(
      shape: Shape,
      server: MalformedServer,
      exchanges: BoundedHttp.Exchanges
  ) =
    send(getFrom(server.base + "/x"), 256 * 1024, 5000L, "probe", exchanges, shape.redirects)

  Shapes.foreach { shape =>
    if shape.clean then
      "BoundedHttp.send" should s"keep the connection of ${shape.name} for the next exchange, and let it go with its client" in {
        assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
        val exchanges = new BoundedHttp.Exchanges(8)
        val server    = new MalformedServer(shape.answer)
        try
          sendToShape(shape, server, exchanges) shouldBe Right((200, "{}"))
          // Not closed: the client is kept for the next exchange to the same origin.
          server.awaitClosed(300L) shouldBe false
          exchanges.close()
          server.awaitClosed(2000L) shouldBe true
        finally
          exchanges.close()
          server.close()
      }
    else
      "BoundedHttp.send" should s"leave no connection open after ${shape.name}" in {
        assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
        val exchanges = new BoundedHttp.Exchanges(8)
        val server    = new MalformedServer(shape.answer)
        try
          val out = sendToShape(shape, server, exchanges)
          // The server holds the connection until the client hangs up: it must within about 2 s.
          withClue(s"[${out.left.map(_.take(100))}] the client kept the connection open: ") {
            server.awaitClosed(2000L) shouldBe true
          }
          // And the client that held it is gone: nothing is kept for the next exchange.
          exchanges.clients shouldBe 0L
        finally
          exchanges.close()
          server.close()
      }

    it should s"answer a short text, with no control character in it, after ${shape.name}" in {
      val exchanges = new BoundedHttp.Exchanges(8)
      val server    = new MalformedServer(shape.answer)
      try
        val out = sendToShape(shape, server, exchanges)
        withClue(s"[${out.left.map(_.take(100))}, ${out.left.map(_.length)} characters] ") {
          out.left.foreach { text =>
            text.length should be <= 200
            text.exists(Character.isISOControl(_)) shouldBe false
          }
        }
      finally
        exchanges.close()
        server.close()
    }
  }

  private def openDescriptors: Option[Long] =
    ManagementFactory.getOperatingSystemMXBean match
      case unix: com.sun.management.UnixOperatingSystemMXBean =>
        Some(unix.getOpenFileDescriptorCount)
      case _ => None

  // The cheap count, polled: listing the threads allocates enough to bring on a GC, and a GC
  // reclaims a client nobody shut down (and its selector thread with it), which would hide that.
  private def liveThreads: Int = ManagementFactory.getThreadMXBean.getThreadCount

  private def refusedExchanges(server: MalformedServer, n: Int): Unit =
    (1 to n).foreach(i => send(getFrom(server.base + s"/i$i"), 1024, 5000L, "probe"))

  // A credential in a header can come back in the JDK's text: a server echoes what it was sent into
  // a malformed status line, and the JDK quotes the line. The text is cut, so the credential is
  // masked BEFORE the cut: a cut through the middle of a long one would leave a part of it that no
  // later masking could find.
  "BoundedHttp.send, with a secret" should "mask it in the JDK's text before the text is cut" in {
    val token  = "Zq9" * 100 // 300 characters, longer than the cut
    val line   = s"NOT-HTTP ${"x" * 120} Bearer $token"
    val server = new MalformedServer(bytes(line + "\r\n\r\n"))
    try
      val request = HttpRequest
        .newBuilder(URI.create(server.base + "/v1/data"))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", s"Bearer $token")
        .GET()
        .build()

      val masked = send(request, 1024, 5000L, "OPA", secret = Some(token)).left.toOption.get
      masked should include("<redacted>")
      masked should not include "Zq9Zq9"
      masked.length should be <= 200

      // Without it the text is only cut: what a cut leaves of a long credential is the credential's
      // beginning, which is why the caller names it.
      val unmasked = send(request, 1024, 5000L, "OPA").left.toOption.get
      unmasked should include("Zq9Zq9")
    finally server.close()
  }

  "BoundedHttp.send, over many refused answers" should "keep no thread" in {
    assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
    val server = new MalformedServer(bytes("NOT-HTTP " + ("x" * 40) + "\r\n\r\n"))
    try
      refusedExchanges(server, 3) // class loading, the default SSL context
      val before = liveThreads
      refusedExchanges(server, 200)
      // The selector thread of a client ends when the client is shut down, a moment later. The
      // pool the clients share keeps a few threads for a minute; 200 clients would be 200 more.
      eventually(liveThreads should be <= (before + 20))
    finally server.close()
  }

  it should "keep no descriptor" in {
    assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
    assume(openDescriptors.isDefined, "this JVM does not count its open file descriptors")
    val server = new MalformedServer(bytes("NOT-HTTP " + ("x" * 40) + "\r\n\r\n"))
    try
      refusedExchanges(server, 3) // class loading, the default SSL context
      val before = openDescriptors.get
      refusedExchanges(server, 200)
      // Two ends of 200 connections are 400 descriptors in this JVM, if none is let go.
      eventually((openDescriptors.get - before) should be <= 20L)
    finally server.close()
  }

  // ----- a bound on how many exchanges run at once -----
  //
  // A client costs a selector thread beside the blocking one: 1000 simultaneous starts against a
  // hanging issuer peaked at 2132 threads with a client for each, and only the REST budget of 1024
  // connections bounded them. A failed thread start is an OutOfMemoryError, which is fatal, and
  // some Kubernetes clusters set pids.max to 1024 or 4096. `send` takes a permit with tryAcquire,
  // gives it back when the exchange ends however it ended, and with none free answers Busy at once
  // and starts nothing: waiting would hold the blocking threads this is meant to save.

  /** An exchange held in flight by the server: it runs on a thread of its own on an `Exchanges` of
    * two (one for any one origin), the permits are seen taken while it runs, `ending` lets it end,
    * and the permits are seen back once `send` returned. Answers what `send` answered.
    */
  private def permitsAround(path: String, timeoutMs: Long)(
      ending: => Unit
  ): Either[String, (Int, String)] =
    val exchanges = new BoundedHttp.Exchanges(2)
    val origin    = BoundedHttp.Origin.of(get(path).uri, HttpClient.Redirect.NEVER)
    gate = new CountDownLatch(1)
    val answer = new AtomicReference[Either[String, (Int, String)]]()
    val sender = new Thread(() => answer.set(send(get(path), 1000, timeoutMs, "probe", exchanges)))
    sender.setDaemon(true)
    sender.start()
    // The origin's permit is the client's, and an exchange that ends badly takes its client out of
    // the cache, so the case holds the client itself to see its permit come back.
    val client = withClue("the permits are taken while the exchange is in flight: ") {
      eventually {
        exchanges.available shouldBe 1
        exchanges.originFree(origin) shouldBe Some(0)
      }
      exchanges.clientOf(origin).getOrElse(fail("the exchange has no client"))
    }
    ending
    sender.join(15000L)
    withClue("both permits are back once send returned: ") {
      exchanges.available shouldBe 2
      client.permits.availablePermits shouldBe 1
    }
    exchanges.close()
    answer.get

  "BoundedHttp.send, with a bound on the exchanges" should "hold a permit of the whole and of the origin for the whole exchange and give them back after an answer" in {
    permitsAround("/gate-ok", 5000L)(gate.countDown()) shouldBe Right((200, "ok"))
  }

  it should "give the permits back after an answer over the cap" in {
    permitsAround("/gate-big", 5000L)(gate.countDown()) shouldBe
      Left("probe response exceeds 1000 bytes")
  }

  it should "give the permits back after an exception" in {
    // The server closes the connection without a word: the JDK raises.
    val answer = permitsAround("/gate-drop", 5000L)(gate.countDown())
    answer.isLeft shouldBe true
  }

  it should "give the permits back after a deadline" in {
    permitsAround("/slow-start", 400L)(()) shouldBe Left("probe request timed out after 400 ms")
  }

  it should "give the origin's permit back, so that an origin of one can be asked again and again" in {
    // Per origin one (half of two): a permit that stayed taken after any ending would refuse the
    // next exchange to the same origin.
    val exchanges = new BoundedHttp.Exchanges(2)
    gate = new CountDownLatch(1)
    gate.countDown()
    try
      List("/gate-ok", "/gate-big", "/gate-drop", "/small", "/gate-ok").foreach { path =>
        val out = send(get(path), 1000, 5000L, "probe", exchanges)
        withClue(s"[$path] ")(out should not be Left(BoundedHttp.Busy))
      }
      // One more, past an exchange that timed out.
      send(get("/slow-start"), 1000, 300L, "probe", exchanges) shouldBe
        Left("probe request timed out after 300 ms")
      send(get("/small"), 1000, 5000L, "probe", exchanges).map(_._1) shouldBe Right(200)
      exchanges.available shouldBe 2
    finally exchanges.close()
  }

  it should "answer Busy at once, starting no thread and asking nobody, while every permit is taken" in {
    val exchanges = new BoundedHttp.Exchanges(1)
    val hanging   = new MalformedServer(Array.emptyByteArray) // reads the request, never answers
    try
      val first  = new AtomicReference[Either[String, (Int, String)]]()
      val holder = new Thread(() =>
        first.set(send(getFrom(hanging.base + "/held"), 1000, 2000L, "probe", exchanges))
      )
      holder.setDaemon(true)
      holder.start()
      eventually(exchanges.available shouldBe 0)
      // The holder connects after it took the permit, and its client has its selector thread by
      // then: count both before measuring.
      eventually(hanging.connections shouldBe 1)
      val threads      = liveThreads
      val (second, ms) =
        timed(send(getFrom(hanging.base + "/refused"), 1000, 2000L, "probe", exchanges))

      second shouldBe Left(BoundedHttp.Busy)
      ms should be < 500L
      // Fifty more: a client for each would be fifty selector threads.
      (1 to 50).foreach { i =>
        send(getFrom(hanging.base + s"/r$i"), 1000, 2000L, "probe", exchanges) shouldBe
          Left(BoundedHttp.Busy)
      }
      liveThreads should be <= (threads + 5)
      hanging.connections shouldBe 1 // none of the refused ones even connected

      holder.join(10000L)
      first.get shouldBe Left("probe request timed out after 2000 ms")
      exchanges.available shouldBe 1
    finally
      exchanges.close()
      hanging.close()
  }

  "BoundedHttp.tokenExchange, with no permit free" should "answer the fixed text and send nothing" in {
    val server = new EchoingServer(EchoingServer.Shape.StatusLine)
    try
      // AtOnce: a call that waited for a permit would park here for good, with nobody to give one.
      val (answer, events) = LogCapture.logged(BoundedHttp.getClass.getName)(
        AtOnce(exchange(server.base + "/token", exchanges = new BoundedHttp.Exchanges(0)))
      )

      answer shouldBe Left(BoundedHttp.TokenEndpointFailed)
      server.requests shouldBe 0
      // Below WARN: a refusal is the cheapest thing an anonymous caller can ask for, so a WARN for
      // each would be a log flood.
      events.filter(_.getLevel.isGreaterOrEqual(Level.WARN)) shouldBe empty
    finally server.close()
  }

  // ----- one client per origin, and half of the limit for any one origin -----
  //
  // A client for each exchange left a TIME_WAIT for each on this host (about 470 a second per
  // destination on Linux, then "Cannot assign requested address" for up to 60 s), which anyone
  // could reach with callbacks against a shared identity provider. So a client is kept for its
  // origin and reused while its exchanges end in a clean answer, and a hanging origin takes at
  // most half of the limit, so that it cannot refuse every other one.

  private val Ok200 = bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")

  private def keepAliveServer = new MalformedServer(_ => Ok200, repeat = true)

  private def originOf(url: String, redirects: HttpClient.Redirect = HttpClient.Redirect.NEVER) =
    BoundedHttp.Origin.of(URI.create(url), redirects)

  /** Starts `n` exchanges to `url` on threads of their own (they end by `deadlineMs` at the latest,
    * or when the server goes) and answers the threads.
    */
  private def heldExchanges(
      exchanges: BoundedHttp.Exchanges,
      url: String,
      n: Int,
      deadlineMs: Long
  ): List[Thread] =
    (1 to n).toList.map { i =>
      val thread = new Thread(() =>
        val _ = send(getFrom(s"$url/held$i"), 1000, deadlineMs, "probe", exchanges)
      )
      thread.setDaemon(true)
      thread.start()
      thread
    }

  "BoundedHttp.send, over one origin" should "reuse the client and its connection while exchanges end in a clean answer" in {
    val exchanges = new BoundedHttp.Exchanges(8)
    val server    = keepAliveServer
    try
      (1 to 5).foreach { i =>
        send(getFrom(server.base + s"/i$i"), 1024, 5000L, "probe", exchanges) shouldBe
          Right((200, "{}"))
        // The JDK puts a connection back in its pool a moment after the answer is complete.
        Thread.sleep(20)
      }
      server.connections shouldBe 1
      exchanges.clients shouldBe 1L
    finally
      exchanges.close()
      server.close()
  }

  it should "reuse the client for an answer with any status, since a complete answer is a clean one" in {
    val exchanges = new BoundedHttp.Exchanges(8)
    val server    = new MalformedServer(
      _ => bytes("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 4\r\n\r\nbusy"),
      repeat = true
    )
    try
      (1 to 3).foreach { i =>
        send(getFrom(server.base + s"/i$i"), 1024, 5000L, "probe", exchanges) shouldBe
          Right((503, "busy"))
        Thread.sleep(20)
      }
      server.connections shouldBe 1
    finally
      exchanges.close()
      server.close()
  }

  it should "take one server spelled two ways for one origin, and two ports, schemes or policies for two" in {
    val never               = HttpClient.Redirect.NEVER
    def origin(url: String) = BoundedHttp.Origin.of(URI.create(url), never)

    // The path, the query, the user info (the JDK never sends it) and the case of the scheme and
    // the host do not make another origin, and a missing port is the scheme's own.
    origin("http://idp.example/a") shouldBe origin("HTTP://IDP.Example:80/b?c=d")
    origin("https://idp.example/a") shouldBe origin("https://user:pw@idp.example:443/")
    origin("http://[::1]:8080/a") shouldBe origin("http://[::1]:8080/b")

    origin("http://idp.example/") should not be origin("http://idp.example:8080/")
    origin("http://idp.example/") should not be origin("https://idp.example/")
    origin("http://idp.example/") should not be origin("http://idp2.example/")
    origin("http://idp.example/") should not be
      BoundedHttp.Origin.of(URI.create("http://idp.example/"), HttpClient.Redirect.NORMAL)
  }

  it should "give a different redirect policy its own client, since the policy is part of the origin" in {
    val exchanges = new BoundedHttp.Exchanges(8)
    val server    = keepAliveServer
    try
      send(getFrom(server.base + "/a"), 1024, 5000L, "probe", exchanges) shouldBe Right((200, "{}"))
      send(
        getFrom(server.base + "/b"),
        1024,
        5000L,
        "probe",
        exchanges,
        HttpClient.Redirect.NORMAL
      ) shouldBe Right((200, "{}"))
      exchanges.clients shouldBe 2L
    finally
      exchanges.close()
      server.close()
  }

  it should "shut a client down at once after a malformed answer, and give the next exchange a new one" in {
    assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
    val exchanges = new BoundedHttp.Exchanges(8)
    val server    = new MalformedServer(
      i => if i == 0 then bytes("NOT-HTTP " + ("x" * 40) + "\r\n\r\n") else Ok200,
      repeat = true
    )
    try
      send(getFrom(server.base + "/a"), 1024, 5000L, "probe", exchanges).isLeft shouldBe true
      // The connection of the refused answer is closed within about 2 s of send returning, and the
      // client that held it is gone.
      server.awaitClosed(2000L) shouldBe true
      exchanges.clients shouldBe 0L

      send(getFrom(server.base + "/b"), 1024, 5000L, "probe", exchanges) shouldBe
        Right((200, "{}"))
      server.connections shouldBe 2
      exchanges.clients shouldBe 1L
    finally
      exchanges.close()
      server.close()
  }

  it should "answer a Left at once, and throw nothing, to an exchange whose client another exchange of the origin shut down" in {
    assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
    val exchanges = new BoundedHttp.Exchanges(8)
    // The first connection is held with no answer, the second is answered with garbage, the rest
    // are good.
    val server = new MalformedServer(
      i =>
        if i == 0 then Array.emptyByteArray
        else if i == 1 then bytes("NOT-HTTP " + ("x" * 40) + "\r\n\r\n")
        else Ok200,
      repeat = true
    )
    try
      val held   = new AtomicReference[Either[String, (Int, String)]]()
      val holder = new Thread(() =>
        held.set(send(getFrom(server.base + "/held"), 1000, 10000L, "probe", exchanges))
      )
      holder.setDaemon(true)
      holder.start()
      eventually(server.connections shouldBe 1)

      // Another exchange of the origin ends badly, which shuts the client down under the first.
      send(getFrom(server.base + "/bad"), 1000, 5000L, "probe", exchanges).isLeft shouldBe true

      val (_, ms) = timed(holder.join(5000L))
      holder.isAlive shouldBe false
      withClue(s"[${held.get}] ") {
        held.get.isLeft shouldBe true
        held.get should not be Left(BoundedHttp.Busy)
      }
      // At once: not the 10 s of its deadline.
      ms should be < 5000L
      // And the origin is asked again with a client of its own, as if nothing had happened.
      send(getFrom(server.base + "/again"), 1000, 5000L, "probe", exchanges) shouldBe
        Right((200, "{}"))
      exchanges.available shouldBe 8
    finally
      exchanges.close()
      server.close()
  }

  it should "not shut down a client that replaced the one a late failure belongs to" in {
    val exchanges = new BoundedHttp.Exchanges(8)
    val server    = keepAliveServer
    try
      val origin = originOf(server.base)
      send(getFrom(server.base + "/1"), 1024, 5000L, "probe", exchanges) shouldBe
        Right((200, "{}"))
      val first = exchanges.clientOf(origin).getOrElse(fail("the exchange left no client"))

      // An exchange that ended badly drops the first client...
      exchanges.discard(origin, first)
      exchanges.clientOf(origin) shouldBe None
      // ...and a later exchange builds another.
      send(getFrom(server.base + "/2"), 1024, 5000L, "probe", exchanges) shouldBe
        Right((200, "{}"))
      val second = exchanges.clientOf(origin).getOrElse(fail("the exchange left no client"))
      (second eq first) shouldBe false

      // The failure of an older exchange of the first client arrives late: the second stays, and is
      // not shut down (its connection still serves the next exchange).
      exchanges.discard(origin, first)
      exchanges.clientOf(origin).exists(_ eq second) shouldBe true
      Thread.sleep(20)
      send(getFrom(server.base + "/3"), 1024, 5000L, "probe", exchanges) shouldBe
        Right((200, "{}"))
      server.connections shouldBe 2 // one for each client, and the second one's was reused
    finally
      exchanges.close()
      server.close()
  }

  it should "give an origin half of the limit, and still serve another origin" in {
    val exchanges = new BoundedHttp.Exchanges(4)              // 2 for any one origin
    val hanging   = new MalformedServer(Array.emptyByteArray) // reads the request, never answers
    val other     = keepAliveServer
    try
      val holders = heldExchanges(exchanges, hanging.base, 2, 10000L)
      eventually {
        exchanges.originFree(originOf(hanging.base)) shouldBe Some(0)
        hanging.connections shouldBe 2
      }
      exchanges.available shouldBe 2

      // The third exchange to the same origin is refused at once, and never connects.
      val (third, ms) =
        timed(send(getFrom(hanging.base + "/third"), 1000, 10000L, "probe", exchanges))
      third shouldBe Left(BoundedHttp.Busy)
      ms should be < 500L
      hanging.connections shouldBe 2

      // Another origin still goes through.
      send(getFrom(other.base + "/x"), 1024, 5000L, "probe", exchanges) shouldBe Right((200, "{}"))

      hanging.close() // the held exchanges end
      holders.foreach(_.join(10000L))
      eventually(exchanges.available shouldBe 4)
    finally
      exchanges.close()
      hanging.close()
      other.close()
  }

  it should "refuse every origin past the limit of all, and take them again as exchanges end" in {
    val exchanges = new BoundedHttp.Exchanges(2) // 1 for any one origin
    val a         = new MalformedServer(Array.emptyByteArray)
    val b         = new MalformedServer(Array.emptyByteArray)
    val c         = keepAliveServer
    try
      val holders = heldExchanges(exchanges, a.base, 1, 10000L) ++
        heldExchanges(exchanges, b.base, 1, 10000L)
      eventually(exchanges.available shouldBe 0)
      // AtOnce: a call that waited for a permit would park here for good.
      List(a, b, c).foreach { s =>
        AtOnce(send(getFrom(s.base + "/x"), 1000, 1000L, "probe", exchanges)) shouldBe
          Left(BoundedHttp.Busy)
      }
      c.connections shouldBe 0

      a.close()
      b.close()
      holders.foreach(_.join(10000L))
      eventually(exchanges.available shouldBe 2)
      send(getFrom(c.base + "/x"), 1024, 5000L, "probe", exchanges) shouldBe Right((200, "{}"))
    finally
      exchanges.close()
      a.close()
      b.close()
      c.close()
  }

  it should "refuse an origin that has used its share without a permit of all, so that a flood against it starves no other origin" in {
    val exchanges = new BoundedHttp.Exchanges(4)              // 2 for any one origin
    val hanging   = new MalformedServer(Array.emptyByteArray) // reads the request, never answers
    val other     = keepAliveServer
    val stop      = new AtomicBoolean(false)
    try
      val holders = heldExchanges(exchanges, hanging.base, 2, 20000L)
      eventually {
        exchanges.originFree(originOf(hanging.base)) shouldBe Some(0)
        hanging.connections shouldBe 2
      }
      // Eight threads ask the spent origin over and over, each answer a Busy. A refusal that took a
      // permit of all for the moment it took to refuse would hold the two that are free most of the
      // time, and another origin would be refused for it.
      val hammers = (1 to 8).toList.map { _ =>
        val thread = new Thread(() =>
          while !stop.get do
            val _ = send(getFrom(hanging.base + "/more"), 1000, 1000L, "probe", exchanges)
        )
        thread.setDaemon(true)
        thread.start()
        thread
      }
      val answers = (1 to 200).toList.map { i =>
        send(getFrom(other.base + s"/o$i"), 1024, 5000L, "probe", exchanges)
      }
      stop.set(true)
      hammers.foreach(_.join(10000L))

      answers.count(_ == Left(BoundedHttp.Busy)) shouldBe 0
      answers.distinct shouldBe List(Right((200, "{}")))
    finally
      stop.set(true)
      exchanges.close()
      hanging.close()
      other.close()
  }

  it should "hold at most 128 clients however many origins are asked" in {
    val exchanges = new BoundedHttp.Exchanges(8)
    try
      (1 to 300).foreach { i =>
        exchanges.clientFor(
          BoundedHttp.Origin("http", s"host$i.example", 80, HttpClient.Redirect.NEVER)
        )
      }
      exchanges.clients should (be > 100L and be <= 128L)
    finally exchanges.close()
  }

  it should "shut an idle client down by itself, its connection with it, with no traffic to prompt it" in {
    assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
    val exchanges = new BoundedHttp.Exchanges(4, idleTtl = Duration.ofMillis(400))
    val server    = keepAliveServer
    try
      send(getFrom(server.base + "/x"), 1024, 5000L, "probe", exchanges) shouldBe Right((200, "{}"))
      // Kept for the next exchange...
      server.awaitClosed(100L) shouldBe false
      // ...and gone once it has been idle for its time, though nothing else touches the cache.
      eventually(server.awaitClosed(100L) shouldBe true)
      exchanges.clients shouldBe 0L
    finally
      exchanges.close()
      server.close()
  }

  // ----- the JDK's HTTP/2 reader queues inbound frames with no bound -----
  //
  // The body cap, jdk.http.maxHeaderSize and the deadline do not limit it, and the JDK offers
  // `Upgrade: h2c` on every http:// request: a tenant's issuer answering 101 and then flooding PING,
  // SETTINGS, zero-length DATA or WINDOW_UPDATE frames halted the manager at -Xmx1024m in 6.2 s, from
  // one anonymous GET (and over TLS with ALPN h2 too). HTTP/1.1 has no such frame. So every client
  // this manager builds to a URL someone else chose speaks HTTP/1.1 only. The flood itself is in the
  // scratch rig; here a bounded one.

  private def frame(kind: Int, payload: Array[Byte]): Array[Byte] =
    val header = new Array[Byte](9)
    header(0) = (payload.length >> 16).toByte
    header(1) = (payload.length >> 8).toByte
    header(2) = payload.length.toByte
    header(3) = kind.toByte
    header ++ payload

  "BoundedHttp.send, on the wire" should "speak HTTP/1.1 only: the request head offers no upgrade" in {
    val exchanges = new BoundedHttp.Exchanges(4)
    val server    = keepAliveServer
    try
      send(getFrom(server.base + "/x"), 1024, 5000L, "probe", exchanges) shouldBe Right((200, "{}"))
      val head = server.heads.headOption
        .getOrElse(fail("the server read no request"))
        .toLowerCase(Locale.ROOT)
      head should startWith("get /x http/1.1")
      head should not include "upgrade"
      head should not include "http2-settings"
    finally
      exchanges.close()
      server.close()
  }

  it should "answer a 101 it never asked for, with a flood of frames after it, at once and not as a success" in {
    // 20000 PING frames: 340 KB, bounded, where the flood of the scratch rig never ends.
    val pings  = Array.fill(20000)(frame(6, new Array[Byte](8))).flatten
    val answer = bytes(
      "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n\r\n"
    ) ++ pings
    val exchanges = new BoundedHttp.Exchanges(4)
    val server    = new MalformedServer(answer)
    try
      val (out, ms) =
        timed(send(getFrom(server.base + "/x"), 256 * 1024, 6000L, "probe", exchanges))
      withClue(s"[$out after $ms ms] ") {
        // A client that does not read HTTP/2 does not switch to it: the 101 is refused outright
        // (21: a Left, "Unexpected 101 response, when not upgrading") or answered as the status it
        // is (17.0.4.1: Right 101, which every caller of ours takes for a refusal). Either way it is
        // not the 6 s deadline of a client that went on to wait for an HTTP/2 answer.
        out match
          case Left(_)         => succeed
          case Right((101, _)) => succeed
          case other           => fail(s"a 101 became $other")
        ms should be < 2000L
      }
    finally
      exchanges.close()
      server.close()
  }

  // ----- TLS: how every identity provider and most OPA servers are reached -----
  //
  // No other case performs a TLS exchange. The JVM's default context is replaced meanwhile (a client
  // takes the default when it is built, and the origins here are new), and put back after.

  private def withTls[A](body: TlsFixture => A): A =
    val tls      = new TlsFixture
    val previous = SSLContext.getDefault
    SSLContext.setDefault(tls.clientContext)
    try body(tls)
    finally
      SSLContext.setDefault(previous)
      tls.close()

  /** An HTTPS server of `com.sun.net.httpserver`, counting what it served. */
  private final class Https(context: SSLContext) extends AutoCloseable:
    private val pool  = Executors.newCachedThreadPool()
    private val https =
      HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    val requests = new AtomicInteger

    https.setHttpsConfigurator(new HttpsConfigurator(context))
    https.setExecutor(pool)
    https.start()

    def base: String = s"https://127.0.0.1:${https.getAddress.getPort}"

    def route(path: String)(handler: HttpExchange => Unit): Unit =
      https.createContext(
        path,
        ex =>
          requests.incrementAndGet()
          try handler(ex)
          finally ex.close()
      )

    override def close(): Unit =
      https.stop(0)
      pool.shutdownNow()
      ()

  /** A TLS server that answers any request with the application protocol the handshake agreed on:
    * it prefers `h2` and accepts `http/1.1`, as a real server does, so the answer says what the
    * client's offer made of it.
    */
  private final class AlpnServer(context: SSLContext) extends AutoCloseable:
    private val socket = context.getServerSocketFactory
      .createServerSocket(0, 16, InetAddress.getLoopbackAddress)
      .asInstanceOf[SSLServerSocket]
    private val params = socket.getSSLParameters
    params.setApplicationProtocols(Array("h2", "http/1.1"))
    socket.setSSLParameters(params)

    private val acceptor = new Thread(() => accept(), "alpn-server")
    acceptor.setDaemon(true)
    acceptor.start()

    def base: String = s"https://127.0.0.1:${socket.getLocalPort}"

    private def accept(): Unit =
      try
        while !socket.isClosed do
          val connection = socket.accept().asInstanceOf[SSLSocket]
          val worker     = new Thread(() => serve(connection), "alpn-server-connection")
          worker.setDaemon(true)
          worker.start()
      catch case _: IOException => ()

    private def serve(connection: SSLSocket): Unit =
      try
        connection.startHandshake()
        val protocol = Option(connection.getApplicationProtocol).getOrElse("")
        // The request head: an HTTP/1.1 one, or the preface of an HTTP/2 client, which ends in the
        // same blank line.
        val in     = connection.getInputStream
        var window = 0
        var open   = true
        while open && window != 0x0d0a0d0a do
          val b = in.read()
          if b < 0 then open = false else window = (window << 8) | b
        val body = bytes(protocol)
        val out  = connection.getOutputStream
        out.write(bytes(s"HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n") ++ body)
        out.flush()
        Thread.sleep(300)
      catch case _: Exception => ()
      finally
        try connection.close()
        catch case _: IOException => ()

    override def close(): Unit =
      try socket.close()
      catch case _: IOException => ()

  "BoundedHttp.send over TLS" should "complete an exchange on HTTP/1.1, whatever the server prefers in ALPN" in
    withTls { tls =>
      val exchanges = new BoundedHttp.Exchanges(4)
      val server    = new AlpnServer(tls.serverContext(tls.good))
      try
        // A client offering h2 would be answered h2 here (the server prefers it) and could not read
        // the HTTP/1.1 answer that follows. This one offers no h2: the exchange completes, and the
        // protocol the handshake agreed on is not h2 (the JDK offers no ALPN at all to a client that
        // speaks HTTP/1.1 only, so the server sees none).
        val out = send(getFrom(server.base + "/x"), 1024, 10000L, "probe", exchanges)
        withClue(s"[$out] ") {
          out.map(_._1) shouldBe Right(200)
          out.map(_._2) should not be Right("h2")
        }
      finally
        exchanges.close()
        server.close()
    }

  it should "refuse a server whose certificate names another host, sending it nothing" in
    withTls { tls =>
      val exchanges = new BoundedHttp.Exchanges(4)
      val server    = new Https(tls.serverContext(tls.wrongHost))
      server.route("/x")(ex => reply(ex, 200, "ok".getBytes(UTF_8)))
      try
        val out = send(getFrom(server.base + "/x"), 1024, 10000L, "probe", exchanges)
        withClue(s"[$out] ") {
          out.isLeft shouldBe true
          out.left.toOption.exists(_.contains("SSLHandshakeException")) shouldBe true
        }
        server.requests.get shouldBe 0
      finally
        exchanges.close()
        server.close()
    }

  it should "follow a redirect from https to https and not one from https to http" in
    withTls { tls =>
      val exchanges = new BoundedHttp.Exchanges(4)
      val server    = new Https(tls.serverContext(tls.good))
      val plain     = keepAliveServer
      def redirectTo(ex: HttpExchange, location: String): Unit =
        ex.getResponseHeaders.add("Location", location)
        ex.sendResponseHeaders(302, -1L)
      server.route("/ok")(ex => reply(ex, 200, "ok".getBytes(UTF_8)))
      server.route("/to-https")(ex => redirectTo(ex, s"${server.base}/ok"))
      server.route("/to-http")(ex => redirectTo(ex, s"${plain.base}/x"))
      try
        send(
          getFrom(server.base + "/to-https"),
          1024,
          10000L,
          "probe",
          exchanges,
          HttpClient.Redirect.NORMAL
        ) shouldBe Right((200, "ok"))
        // NORMAL never goes from https to http: the 302 is the answer, and the plain server is
        // never asked.
        send(
          getFrom(server.base + "/to-http"),
          1024,
          10000L,
          "probe",
          exchanges,
          HttpClient.Redirect.NORMAL
        ) shouldBe Right((302, ""))
        plain.connections shouldBe 0
      finally
        exchanges.close()
        plain.close()
        server.close()
    }

  // ----- the JDK of a manager that cannot shut a client down -----

  "BoundedHttp.canShutdownNow" should "say whether the JDK has HttpClient.shutdownNow" in {
    BoundedHttp.canShutdownNow shouldBe HasShutdownNow
  }

  "BoundedHttp.idpExchanges" should "hold 128 permits, all of them back once the exchanges above ended" in {
    BoundedHttp.IdpExchangeLimit shouldBe 128
    // eventually: an exchange another spec of this JVM left in flight gives its permit back by
    // its own deadline, 15 s at most.
    eventually(timeout(Span(20, Seconds))) {
      BoundedHttp.idpExchanges.available shouldBe 128
    }
  }

  // The last case of the spec: whatever way each exchange above ended, none left a permit taken.
  "The exchanges of this spec" should "have left no permit taken" in {
    shared.available shouldBe SharedLimit
  }
