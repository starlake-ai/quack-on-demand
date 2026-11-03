package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{AtOnce, EchoingServer, MalformedServer}
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import ch.qos.logback.classic.Level
import com.sun.net.httpserver.{HttpExchange, HttpServer}
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
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{CountDownLatch, ExecutorService, Executors, Semaphore, TimeUnit}
import scala.jdk.CollectionConverters.*

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

  // The permits of the exchanges of the older cases: plenty, and the last case checks that none is
  // left taken by any of them, however its exchange ended.
  private val SharedPermits = 1024
  private val shared        = new Semaphore(SharedPermits)

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

  private def timed[A](f: => A): (A, Long) =
    val t0 = System.nanoTime()
    val a  = f
    (a, (System.nanoTime() - t0) / 1000000L)

  "BoundedHttp.send" should "return the status and the whole body of an answer within the cap" in {
    BoundedHttp.send(get("/small"), 1024, 10000, "test", shared) shouldBe
      Right((200, """{"a":"é"}"""))
  }

  it should "accept an answer of exactly the cap and refuse one byte over it" in {
    BoundedHttp.send(get("/at-cap"), 1000, 10000, "test", shared).map(_._2.length) shouldBe
      Right(1000)
    BoundedHttp.send(get("/over-cap"), 1000, 10000, "test", shared) shouldBe
      Left("test response exceeds 1000 bytes")
  }

  it should "pass a status other than 200 through with its body" in {
    BoundedHttp.send(get("/status/404"), 1024, 10000, "test", shared) shouldBe
      Right((404, "no such thing"))
    BoundedHttp.send(get("/status/503"), 1024, 10000, "test", shared) shouldBe Right((503, "busy"))
  }

  it should "answer an answer over the cap with a Left, and stop reading the rest of it" in {
    val (out, ms) = timed(BoundedHttp.send(get("/endless"), 64 * 1024, 30000, "test", shared))
    out shouldBe Left("test response exceeds 64 KiB")
    // Not 30 s, and not the 256 MiB: the cap ended the read, and the connection with it.
    ms should be < 10000L
    endlessEnded.await(10, TimeUnit.SECONDS) shouldBe true
    endlessWritten.get should be < (32 * MiB)
  }

  it should "do the same when the answer declares no length" in {
    BoundedHttp.send(get("/endless-chunked"), 64 * 1024, 30000, "test", shared) shouldBe
      Left("test response exceeds 64 KiB")
    chunkedEnded.await(10, TimeUnit.SECONDS) shouldBe true
    chunkedWritten.get should be < (32 * MiB)
  }

  it should "give up on a body that trickles past the deadline, within about the deadline" in {
    val (out, ms) = timed(BoundedHttp.send(get("/trickle"), 1024 * 1024, 700, "test", shared))
    out shouldBe Left("test request timed out after 700 ms")
    ms should (be >= 600L and be < 5000L)
    // The cancel released the connection: the handler, which had 30 s of bytes to go, saw it break.
    trickleEnded.await(10, TimeUnit.SECONDS) shouldBe true
    trickleBytes.get should be < 200L
  }

  it should "give up on an answer whose headers are late, within about the deadline" in {
    val (out, ms) = timed(BoundedHttp.send(get("/slow-start"), 1024, 500, "test", shared))
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
      timed(BoundedHttp.send(request.GET().build(), 1024, 10000, "test", shared))
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
      BoundedHttp.send(unreachable, 1024, 10000, "token endpoint", shared),
      BoundedHttp.send(post("/over-cap", form), 1000, 10000, "token endpoint", shared),
      BoundedHttp.send(post("/trickle-quiet", form), 1024 * 1024, 500, "token endpoint", shared),
      BoundedHttp.send(post("/slow-start", form), 1024, 300, "token endpoint", shared)
    ).map(_.left.toOption)
    lefts.foreach(_ shouldBe defined)
    lefts.flatten.foreach { text =>
      text should not include SecretInRequest
      text should not include "client_secret"
      text should not include "Authorization"
    }
  }

  it should "keep the texts OpaClient has always answered, for what = OPA" in {
    BoundedHttp.send(get("/endless"), 1024 * 1024, 30000, "OPA", shared) shouldBe
      Left("OPA response exceeds 1 MiB")
    BoundedHttp.send(get("/slow-start"), 1024 * 1024, 300, "OPA", shared) shouldBe
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
      permits: Semaphore = shared
  ) =
    BoundedHttp.tokenExchange(url, Form, refused, permits)

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
        val raw = BoundedHttp.send(request, 1024, 10000, "token endpoint", shared)
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
  // limit). Every exchange has a client of its own, shut down when it ends.

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.ISO_8859_1)

  private final case class Shape(
      name: String,
      answer: Array[Byte],
      redirects: HttpClient.Redirect = HttpClient.Redirect.NEVER
  )

  private val Following = HttpClient.Redirect.NORMAL

  private val Shapes = List(
    Shape(
      "a good answer the client could keep alive",
      bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")
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

  private def sendToShape(shape: Shape, server: MalformedServer) =
    BoundedHttp.send(
      getFrom(server.base + "/x"),
      256 * 1024,
      5000L,
      "probe",
      shared,
      shape.redirects
    )

  Shapes.foreach { shape =>
    "BoundedHttp.send" should s"leave no connection open after ${shape.name}" in {
      assume(HasShutdownNow, "HttpClient.shutdownNow exists from JDK 21")
      val server = new MalformedServer(shape.answer)
      try
        val out = sendToShape(shape, server)
        // The server holds the connection until the client hangs up: it must within about 2 s.
        withClue(s"[${out.left.map(_.take(100))}] the client kept the connection open: ") {
          server.awaitClosed(2000L) shouldBe true
        }
      finally server.close()
    }

    it should s"answer a short text, with no control character in it, after ${shape.name}" in {
      val server = new MalformedServer(shape.answer)
      try
        val out = sendToShape(shape, server)
        withClue(s"[${out.left.map(_.take(100))}, ${out.left.map(_.length)} characters] ") {
          out.left.foreach { text =>
            text.length should be <= 200
            text.exists(Character.isISOControl(_)) shouldBe false
          }
        }
      finally server.close()
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
    (1 to n).foreach(i =>
      BoundedHttp.send(getFrom(server.base + s"/i$i"), 1024, 5000L, "probe", shared)
    )

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

      val masked =
        BoundedHttp
          .send(request, 1024, 5000L, "OPA", shared, secret = Some(token))
          .left
          .toOption
          .get
      masked should include("<redacted>")
      masked should not include "Zq9Zq9"
      masked.length should be <= 200

      // Without it the text is only cut: what a cut leaves of a long credential is the credential's
      // beginning, which is why the caller names it.
      val unmasked = BoundedHttp.send(request, 1024, 5000L, "OPA", shared).left.toOption.get
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
  // A client for each exchange costs a selector thread beside the blocking one: 1000 simultaneous
  // starts against a hanging issuer peaked at 2132 threads, and only the REST budget of 1024
  // connections bounded them. A failed thread start is an OutOfMemoryError, which is fatal, and
  // some Kubernetes clusters set pids.max to 1024 or 4096. `send` takes a permit with tryAcquire,
  // gives it back when the exchange ends however it ended, and with none free answers Busy at once
  // and starts nothing: waiting would hold the blocking threads this is meant to save.

  /** An exchange held in flight by the server: it runs on a thread of its own with the only permit,
    * the permit is seen taken while it runs, `ending` lets it end, and the permit is seen back once
    * `send` returned. Answers what `send` answered.
    */
  private def permitAround(path: String, timeoutMs: Long)(
      ending: => Unit
  ): Either[String, (Int, String)] =
    val permits = new Semaphore(1)
    gate = new CountDownLatch(1)
    val answer = new AtomicReference[Either[String, (Int, String)]]()
    val sender =
      new Thread(() => answer.set(BoundedHttp.send(get(path), 1000, timeoutMs, "probe", permits)))
    sender.setDaemon(true)
    sender.start()
    withClue("the permit is taken while the exchange is in flight: ") {
      eventually(permits.availablePermits shouldBe 0)
    }
    ending
    sender.join(15000L)
    withClue("the permit is back once send returned: ")(permits.availablePermits shouldBe 1)
    answer.get

  "BoundedHttp.send, with a bound on the exchanges" should "hold a permit for the whole exchange and give it back after an answer" in {
    permitAround("/gate-ok", 5000L)(gate.countDown()) shouldBe Right((200, "ok"))
  }

  it should "give the permit back after an answer over the cap" in {
    permitAround("/gate-big", 5000L)(gate.countDown()) shouldBe
      Left("probe response exceeds 1000 bytes")
  }

  it should "give the permit back after an exception" in {
    // The server closes the connection without a word: the JDK raises.
    val answer = permitAround("/gate-drop", 5000L)(gate.countDown())
    answer.isLeft shouldBe true
  }

  it should "give the permit back after a deadline" in {
    permitAround("/slow-start", 400L)(()) shouldBe Left("probe request timed out after 400 ms")
  }

  it should "answer Busy at once, starting no thread and asking nobody, while every permit is taken" in {
    val permits = new Semaphore(1)
    val hanging = new MalformedServer(Array.emptyByteArray) // reads the request, never answers
    try
      val first  = new AtomicReference[Either[String, (Int, String)]]()
      val holder = new Thread(() =>
        first.set(BoundedHttp.send(getFrom(hanging.base + "/held"), 1000, 2000L, "probe", permits))
      )
      holder.setDaemon(true)
      holder.start()
      eventually(permits.availablePermits shouldBe 0)
      val threads      = liveThreads
      val (second, ms) =
        timed(BoundedHttp.send(getFrom(hanging.base + "/refused"), 1000, 2000L, "probe", permits))

      second shouldBe Left(BoundedHttp.Busy)
      ms should be < 500L
      // Fifty more: a client for each would be fifty selector threads.
      (1 to 50).foreach { i =>
        BoundedHttp.send(getFrom(hanging.base + s"/r$i"), 1000, 2000L, "probe", permits) shouldBe
          Left(BoundedHttp.Busy)
      }
      liveThreads should be <= (threads + 5)
      hanging.connections shouldBe 1 // none of the refused ones even connected

      holder.join(10000L)
      first.get shouldBe Left("probe request timed out after 2000 ms")
      permits.availablePermits shouldBe 1
    finally hanging.close()
  }

  "BoundedHttp.tokenExchange, with no permit free" should "answer the fixed text and send nothing" in {
    val server = new EchoingServer(EchoingServer.Shape.StatusLine)
    try
      // AtOnce: a call that waited for a permit would park here for good, with nobody to give one.
      val (answer, events) = LogCapture.logged(BoundedHttp.getClass.getName)(
        AtOnce(exchange(server.base + "/token", permits = new Semaphore(0)))
      )

      answer shouldBe Left(BoundedHttp.TokenEndpointFailed)
      server.requests shouldBe 0
      // Below WARN: a refusal is the cheapest thing an anonymous caller can ask for, so a WARN for
      // each would be a log flood.
      events.filter(_.getLevel.isGreaterOrEqual(Level.WARN)) shouldBe empty
    finally server.close()
  }

  "BoundedHttp.idpExchanges" should "hold 128 permits, all of them back once the exchanges above ended" in {
    BoundedHttp.IdpExchangeLimit shouldBe 128
    // eventually: an exchange another spec of this JVM left in flight gives its permit back by
    // its own deadline, 15 s at most.
    eventually(timeout(Span(20, Seconds))) {
      BoundedHttp.idpExchanges.availablePermits shouldBe 128
    }
  }

  // The last case of the spec: whatever way each exchange above ended, none left a permit taken.
  "The exchanges of this spec" should "have left no permit taken" in {
    shared.availablePermits shouldBe SharedPermits
  }
