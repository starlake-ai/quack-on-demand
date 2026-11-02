package ai.starlake.quack.observability.metrics

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.comcast.ip4s.{Host, Port}
import io.micrometer.prometheusmetrics.{
  PrometheusConfig as MmPrometheusConfig,
  PrometheusMeterRegistry
}
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import java.io.{IOException, InputStream}
import java.lang.management.ManagementFactory
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class MetricsEndpointSpec extends AnyFlatSpec with Matchers:

  /** The media type Prometheus 3 insists on: a scrape without a valid one is refused. */
  private val promTextV004 = "text/plain; version=0.0.4; charset=utf-8"

  private val scrapeRequest = Request[IO](Method.GET, uri"/metrics")

  private def app(endpoint: MetricsEndpoint): HttpApp[IO] =
    Http4sServerInterpreter[IO]().toRoutes(endpoint.serverEndpoints).orNotFound

  /** The same with Tapir's request log off, for a request that is meant to fail: it logs those as
    * an ERROR with a stack trace.
    */
  private def quietApp(endpoint: MetricsEndpoint): HttpApp[IO] =
    Http4sServerInterpreter[IO](
      Http4sServerOptions.customiseInterceptors[IO].serverLog(None).options
    ).toRoutes(endpoint.serverEndpoints).orNotFound

  /** A registry holding what a manager's statement timers put in a scrape: one timer per (tenant,
    * pool, status), about 7.5 KiB of text each.
    */
  private def populated(timers: Int): PrometheusMeterRegistry =
    val prom  = new PrometheusMeterRegistry(MmPrometheusConfig.DEFAULT)
    val stmts = new StatementInstruments(prom)
    (0 until timers).foreach(i => stmts.record(f"tenant-$i%04d", "bi", "ok", 5L))
    prom

  "MetricsEndpoint" should "return 200 with the prometheus text format and the expected metric line" in:
    val prom = new PrometheusMeterRegistry(MmPrometheusConfig.DEFAULT)
    prom.counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok").increment()
    val refreshes = new java.util.concurrent.atomic.AtomicInteger(0)
    val endpoint  = new MetricsEndpoint(Some(prom), () => refreshes.incrementAndGet())
    val routes    = Http4sServerInterpreter[IO]().toRoutes(endpoint.serverEndpoints)

    val req  = Request[IO](Method.GET, uri"/metrics")
    val resp = routes.orNotFound.run(req).unsafeRunSync()

    resp.status.code shouldBe 200
    resp.headers
      .get(org.typelevel.ci.CIString("Content-Type"))
      .map(_.head.value)
      .getOrElse("") should include("text/plain")
    val body = resp.bodyText.compile.string.unsafeRunSync()
    body should include("statements_total{")
    body should include("tenant=\"acme\"")
    body should include("status=\"ok\"")
    refreshes.get() shouldBe 1

  it should "not mount the route when there is no Prometheus child" in:
    val endpoint = new MetricsEndpoint(None, () => ())
    val routes   = Http4sServerInterpreter[IO]().toRoutes(endpoint.serverEndpoints)

    val resp = routes.orNotFound.run(Request[IO](Method.GET, uri"/metrics")).unsafeRunSync()
    resp.status.code shouldBe 404

  it should "send exactly the media type Prometheus expects" in:
    val endpoint = new MetricsEndpoint(Some(populated(1)), () => ())
    val resp     = app(endpoint).run(scrapeRequest).unsafeRunSync()
    resp.status.code shouldBe 200
    resp.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.toList.map(_.value)) shouldBe
      Some(List(promTextV004))

  // Tapir answers 406 before the route runs when no body the endpoint declares satisfies the
  // request's Accept header, and it reads the media type the body is DECLARED with, not the
  // Content-Type header. A body declared as a stream of bytes (application/octet-stream) turned
  // `Accept: text/plain` into a 406 for the scrapers that send it.

  /** What scrapers send, by name: each of these must be answered with the scrape. */
  private val acceptedScrapers: List[(String, String)] = List(
    "text/plain, Datadog's OpenMetrics check" -> "text/plain",
    "a wildcard subtype"                      -> "text/*",
    "the exposition format's own"             -> "text/plain;version=0.0.4",
    "anything, as curl sends"                 -> "*/*",
    "Prometheus 3"                            ->
      ("application/openmetrics-text;version=1.0.0;escaping=allow-utf-8;q=0.5," +
        "application/openmetrics-text;version=0.0.1;q=0.4," +
        "text/plain;version=1.0.0;escaping=allow-utf-8;q=0.3,text/plain;version=0.0.4;q=0.2," +
        "*/*;q=0.1"),
    "Telegraf's default" ->
      ("application/vnd.google.protobuf;proto=io.prometheus.client.MetricFamily;" +
        "encoding=delimited;q=0.7,text/plain;version=0.0.4;q=0.3")
  )

  private def scrapeAccepting(accept: String): Request[IO] =
    scrapeRequest.putHeaders(Header.Raw(org.typelevel.ci.CIString("Accept"), accept))

  it should "answer each scraper's Accept header with the scrape and the exact media type" in:
    val prom     = populated(2)
    val expected = prom.scrape().getBytes(UTF_8)
    val handler  = app(new MetricsEndpoint(Some(prom), () => ()))
    // Every answer is collected before any is judged, so that a failure names each scraper it
    // refuses and not only the first.
    val answers = acceptedScrapers.map { (who, accept) =>
      val resp = handler.run(scrapeAccepting(accept)).unsafeRunSync()
      (
        who,
        resp.status.code,
        resp.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.toList.map(_.value)),
        resp.body.compile.to(Array).unsafeRunSync().toSeq
      )
    }
    answers.map((who, status, _, _) => who -> status) shouldBe
      acceptedScrapers.map((who, _) => who -> 200)
    answers.map((_, _, contentType, _) => contentType).distinct shouldBe
      List(Some(List(promTextV004)))
    answers.map((_, _, _, body) => body).distinct shouldBe List(expected.toSeq)

  it should "still refuse a client that accepts nothing it serves" in:
    val handler = app(new MetricsEndpoint(Some(populated(1)), () => ()))
    handler.run(scrapeAccepting("application/json")).unsafeRunSync().status.code shouldBe 406

  // The scrape is public, and a client that stops reading keeps its response in memory until the
  // idle timeout: a scrape of 1.36 MB held by each of 295 stalled requests filled 924 MiB of a
  // 1 GiB heap (reproduced). So one request must cost little whatever the registry holds.

  it should "share one scrape between concurrent requests, which all get the same whole text" in:
    val prom      = populated(10)
    val refreshes = new AtomicInteger(0)
    // The scrape takes a moment, so the other requests are waiting for it when it ends.
    val slowScrape: () => Unit = () =>
      refreshes.incrementAndGet()
      Thread.sleep(200)
    val endpoint = new MetricsEndpoint(Some(prom), slowScrape)
    val handler  = app(endpoint)
    val bodies   = (1 to 50).toList
      .parTraverse(_ => handler.run(scrapeRequest).flatMap(_.body.compile.to(Array)))
      .unsafeRunSync()
    refreshes.get() shouldBe 1
    bodies.map(_.toSeq).distinct should have size 1
    bodies.head shouldBe prom.scrape().getBytes(UTF_8)

  it should "scrape again once the shared scrape is older than its ttl" in:
    val refreshes = new AtomicInteger(0)
    val endpoint  =
      new MetricsEndpoint(Some(populated(1)), () => refreshes.incrementAndGet(), ttl = 2.seconds)
    val handler = app(endpoint)
    handler.run(scrapeRequest).flatMap(_.body.compile.drain).unsafeRunSync()
    handler.run(scrapeRequest).flatMap(_.body.compile.drain).unsafeRunSync()
    refreshes.get() shouldBe 1
    Thread.sleep(2300)
    handler.run(scrapeRequest).flatMap(_.body.compile.drain).unsafeRunSync()
    refreshes.get() shouldBe 2

  it should "share a scrape that took longer than its ttl with the requests that waited for it" in:
    // A scrape is as old as the moment it ended: counted from the moment it began, a slow one
    // would be stale for every request queued behind it, and each of them would scrape again.
    // The margins are wide on purpose. The requests that waited take the gate one after another
    // and each compares its own clock with the moment the scrape ended, so a pause of the JVM
    // between two of those hand-offs must not read as a stale scrape: with a ttl of 1 s it takes
    // a pause of over a second, where a ttl of 100 ms needed a pause of tens of milliseconds.
    val refreshes              = new AtomicInteger(0)
    val slowScrape: () => Unit = () =>
      refreshes.incrementAndGet()
      Thread.sleep(1500)
    val endpoint = new MetricsEndpoint(Some(populated(1)), slowScrape, ttl = 1.second)
    val handler  = app(endpoint)
    (1 to 10).toList
      .parTraverse(_ => handler.run(scrapeRequest).flatMap(_.body.compile.drain))
      .unsafeRunSync()
    refreshes.get() shouldBe 1

  it should "keep the requests waiting for a scrape off the threads" in:
    val started             = new CountDownLatch(1)
    val release             = new CountDownLatch(1)
    val blocked: () => Unit = () =>
      started.countDown()
      release.await(30, TimeUnit.SECONDS)
    val endpoint = new MetricsEndpoint(Some(populated(1)), blocked)
    val handler  = app(endpoint)
    val threads  = ManagementFactory.getThreadMXBean
    // The runtime and its pools exist before the count is taken.
    IO.unit.unsafeRunSync()
    val before  = threads.getThreadCount
    val running = (1 to 100).toList
      .parTraverse(_ => handler.run(scrapeRequest).flatMap(_.body.compile.drain))
      .unsafeToFuture()
    try
      started.await(10, TimeUnit.SECONDS) shouldBe true
      Thread.sleep(1000)
      // 99 requests wait for the one scrape: a thread each would add about 99.
      withClue(s"threads before: $before, while waiting: ${threads.getThreadCount}: ")(
        (threads.getThreadCount - before) should be < 20
      )
    finally release.countDown()
    Await.result(running, 30.seconds)

  it should "write a large scrape in chunks of at most 8 KiB, never as one array" in:
    // A chunk as large as the scrape is what Ember copies once more, per request, and holds
    // until the client reads it: the copy that filled the heap in the flood above.
    val prom     = populated(60)
    val expected = prom.scrape().getBytes(UTF_8)
    expected.length should be > 256 * 1024
    val endpoint = new MetricsEndpoint(Some(prom), () => ())
    val resp     = app(endpoint).run(scrapeRequest).unsafeRunSync()
    resp.status.code shouldBe 200
    val chunks = resp.body.chunks.compile.toList.unsafeRunSync()
    chunks.map(_.size.toLong).sum shouldBe expected.length
    chunks.map(_.size).max should be <= 8192
    fs2.Chunk.concat(chunks).toArray shouldBe expected

  it should "answer a failed scrape with an error, and scrape again for the next request" in:
    val calls    = new AtomicInteger(0)
    val endpoint = new MetricsEndpoint(
      Some(populated(1)),
      () => if calls.incrementAndGet() == 1 then throw new IllegalStateException("scrape failed")
    )
    val handler = quietApp(endpoint)
    handler.run(scrapeRequest).unsafeRunSync().status.code shouldBe 500
    // A hang here would mean the failed scrape kept the gate.
    val next = handler.run(scrapeRequest).timeout(5.seconds).unsafeRunSync()
    next.status.code shouldBe 200
    next.bodyText.compile.string.unsafeRunSync() should include("statement_duration_seconds")
    calls.get() shouldBe 2

  // The bound is on the wait for the gate behind a scrape in progress, never on the scrape: the
  // request that holds the gate has no timeout, and what it scraped is kept whenever it ends. A
  // request that waits `scrapeWait` answers 503, as a status of the endpoint and not as an
  // exception reaching Tapir's handler, which logs an ERROR with a stack trace for every one.

  /** The lines Tapir's server log writes while `f` runs, as (level, message), with its logger at
    * DEBUG so that a handled request shows too, and no hand-off to the root appender: the test's
    * lines stay out of the suite output. Suites run one after the other in the forked JVM, so
    * nothing else writes to this logger meanwhile.
    */
  private def tapirLog[A](f: => A): (A, List[(Level, String)]) =
    val log =
      LoggerFactory
        .getLogger("sttp.tapir.server.http4s.Http4sDefaultServerLog")
        .asInstanceOf[Logger]
    val appender          = new ListAppender[ILoggingEvent]
    val (level, additive) = (log.getLevel, log.isAdditive)
    appender.start()
    log.addAppender(appender)
    log.setLevel(Level.DEBUG)
    log.setAdditive(false)
    try
      val a = f
      (a, appender.list.asScala.toList.map(e => (e.getLevel, e.getFormattedMessage)))
    finally
      log.setLevel(level)
      log.setAdditive(additive)
      log.detachAppender(appender)

  it should "serve a scrape slower than the wait to the request that took it, and keep it for the next" in:
    // Bounded as a whole, a scrape slower than the wait was never served, and taken again by every
    // request after it.
    val calls                  = new AtomicInteger(0)
    val slowScrape: () => Unit = () =>
      calls.incrementAndGet()
      Thread.sleep(800)
    val prom     = populated(2)
    val expected = prom.scrape().getBytes(UTF_8)
    val handler  = app(new MetricsEndpoint(Some(prom), slowScrape, scrapeWait = 300.millis))
    val first    = handler.run(scrapeRequest).unsafeRunSync()
    first.status.code shouldBe 200
    first.body.compile.to(Array).unsafeRunSync() shouldBe expected
    // Within the ttl: served from that scrape, with no second one.
    val next = handler.run(scrapeRequest).unsafeRunSync()
    next.status.code shouldBe 200
    next.body.compile.to(Array).unsafeRunSync() shouldBe expected
    calls.get() shouldBe 1

  it should "answer 503 and log no error to a request that waited past its bound, and serve the one that took the scrape" in:
    // A scrape that takes long (a hung bean, a lock) keeps the gate. Each request after it would
    // wait there holding a connection slot of the listener for as long as the scrape lasts: a
    // Prometheus scraping every 15 s would fill 260 slots in about 65 minutes. So a request waits at
    // most `scrapeWait` for the gate; only the one that took the scrape stays until it returns.
    val wedged             = new CountDownLatch(1)
    val release            = new CountDownLatch(1)
    val calls              = new AtomicInteger(0)
    val scrape: () => Unit = () =>
      if calls.incrementAndGet() == 1 then
        wedged.countDown()
        release.await(60, TimeUnit.SECONDS)
    val prom     = populated(1)
    val expected = prom.scrape().getBytes(UTF_8)
    // The default server log: an exception reaching Tapir's handler would be logged at ERROR.
    val handler = app(new MetricsEndpoint(Some(prom), scrape, scrapeWait = 500.millis))
    val holder  = handler
      .run(scrapeRequest)
      .flatMap(r => r.body.compile.to(Array).map(r.status.code -> _))
      .unsafeToFuture()
    try
      wedged.await(10, TimeUnit.SECONDS) shouldBe true
      // The outer timeout is only here so that a missing bound fails the case instead of hanging it.
      val began            = System.nanoTime()
      val (waiter, logged) = tapirLog(handler.run(scrapeRequest).timeout(5.seconds).unsafeRunSync())
      val waitedMs         = (System.nanoTime() - began) / 1000000L
      // How the log was checked: Tapir's own logger is read through an appender while the request
      // runs. Nothing at WARN or above may be in it, which is where an exception would be logged
      // (as an ERROR with a stack trace), and the status is asserted with it so that a failure
      // shows both.
      (waiter.status.code, logged.filter(_._1.isGreaterOrEqual(Level.WARN)).map(_._1)) shouldBe
        (503, Nil)
      waitedMs should be >= 400L
      waitedMs should be < 3000L
      // The 503 is in it as a handled request (DEBUG), which shows that this is the logger in use.
      logged.map(_._2).exists(_.contains("response: 503")) shouldBe true
      // Waiters that gave up leave nothing behind: another one gives up the same way.
      handler.run(scrapeRequest).timeout(5.seconds).unsafeRunSync().status.code shouldBe 503
    finally release.countDown()
    // The scrape ends: the request that took it answers normally, however long it took.
    val (status, body) = Await.result(holder, 10.seconds)
    status shouldBe 200
    body shouldBe expected
    // The gate is free, and the next request is served from that scrape with no second one.
    val next = handler.run(scrapeRequest).timeout(5.seconds).unsafeRunSync()
    next.status.code shouldBe 200
    next.body.compile.to(Array).unsafeRunSync() shouldBe expected
    calls.get() shouldBe 1

  it should "keep the scrape of a request that is cancelled while it takes it, and free the gate" in:
    // The blocking call cannot be interrupted: the cancel waits for it, and what it took is stamped.
    val started                = new CountDownLatch(1)
    val calls                  = new AtomicInteger(0)
    val slowScrape: () => Unit = () =>
      calls.incrementAndGet()
      started.countDown()
      Thread.sleep(500)
    val handler = app(new MetricsEndpoint(Some(populated(1)), slowScrape))
    val next    = (for
      fiber <- handler.run(scrapeRequest).start
      _     <- IO.blocking(started.await(10, TimeUnit.SECONDS))
      _     <- fiber.cancel
      next  <- handler.run(scrapeRequest).timeout(5.seconds)
    yield next).unsafeRunSync()
    next.status.code shouldBe 200
    calls.get() shouldBe 1

  it should "let a request that is cancelled while it waits go at once, and lose no permit to it" in:
    val wedged             = new CountDownLatch(1)
    val release            = new CountDownLatch(1)
    val calls              = new AtomicInteger(0)
    val scrape: () => Unit = () =>
      if calls.incrementAndGet() == 1 then
        wedged.countDown()
        release.await(60, TimeUnit.SECONDS)
    // A wait of 30 s: it is the cancel, not the bound, that ends these waits.
    val handler = app(new MetricsEndpoint(Some(populated(1)), scrape, scrapeWait = 30.seconds))
    val holder  = handler.run(scrapeRequest).flatMap(_.body.compile.drain).unsafeToFuture()
    try
      wedged.await(10, TimeUnit.SECONDS) shouldBe true
      (for
        waiters <- List.fill(20)(handler.run(scrapeRequest).start).sequence
        _       <- IO.sleep(300.millis) // queued on the gate
        _       <- waiters.traverse_(_.cancel).timeout(5.seconds)
      yield ()).unsafeRunSync()
    finally release.countDown()
    Await.ready(holder, 10.seconds)
    // Nothing is left of the cancelled waiters: the gate serves the requests that follow.
    (1 to 5).toList
      .parTraverse(_ => handler.run(scrapeRequest).timeout(5.seconds).map(_.status.code))
      .unsafeRunSync() shouldBe List.fill(5)(200)
    calls.get() shouldBe 1

  it should "let a request that gave up release nothing, so that no two scrapes ever run at once" in:
    val running                = new AtomicInteger(0)
    val peak                   = new AtomicInteger(0)
    val slowScrape: () => Unit = () =>
      peak.accumulateAndGet(running.incrementAndGet(), math.max(_, _))
      Thread.sleep(400)
      running.decrementAndGet()
    // A ttl of zero: every request that gets the gate scrapes.
    val handler = app(
      new MetricsEndpoint(Some(populated(1)), slowScrape, ttl = 0.seconds, scrapeWait = 100.millis)
    )
    def burst: List[Int] =
      (1 to 6).toList
        .parTraverse(_ => handler.run(scrapeRequest).map(_.status.code))
        .unsafeRunSync()
    // One request takes the gate, and the five that wait for it give up.
    burst.sorted shouldBe List(200, 503, 503, 503, 503, 503)
    peak.set(0)
    // Had they each put a permit back, the gate would let several of these through at once.
    burst.sorted shouldBe List(200, 503, 503, 503, 503, 503)
    peak.get() shouldBe 1

  it should "give a Prometheus scraping every 5 s a fresh scrape each time" in:
    // observability/prometheus.yml, observability/prometheus-compose.yml and
    // charts/quack-on-demand/local-stack-k8s/prometheus.yaml scrape every 5 s. A scrape is stamped
    // when it ended, so the next scrape finds it (the interval minus the time the last one took)
    // old: a ttl of 5 s repeated half the samples (measured: 4 of 7 were new), and a ttl of 4 s
    // leaves a second for the scrape.
    (5.seconds - MetricsEndpoint.Ttl) should be >= 1.second

  it should "fail a response read past its deadline, and never end it as if it were whole" in:
    val prom     = populated(60)
    val endpoint = new MetricsEndpoint(Some(prom), () => (), responseDeadline = 1.second)
    val resp     = app(endpoint).run(scrapeRequest).unsafeRunSync()
    val received = new AtomicInteger(0)
    // A reader that pauses between chunks for longer than the deadline.
    val outcome = resp.body.chunks
      .evalTap(c => IO(received.addAndGet(c.size)) *> IO.sleep(1500.millis))
      .compile
      .drain
      .attempt
      .unsafeRunSync()
    val failure = outcome.left.toOption.getOrElse(fail("the response ended instead of failing"))
    failure shouldBe an[IOException]
    failure.getMessage should include("deadline")
    received.get() should be < prom.scrape().length

  it should "send the length of the scrape, which it knows" in:
    // Chunk framing reaches an HTTP/1.0 client as body bytes, and a cut chunked response is only
    // told by a missing terminator. With the length declared a client reads the whole body or sees
    // it short.
    val prom     = populated(2)
    val expected = prom.scrape().getBytes(UTF_8)
    val resp     = app(new MetricsEndpoint(Some(prom), () => ())).run(scrapeRequest).unsafeRunSync()
    resp.headers
      .get(org.typelevel.ci.CIString("Content-Length"))
      .map(_.toList.map(_.value)) shouldBe
      Some(List(expected.length.toString))
    resp.headers.get(org.typelevel.ci.CIString("Transfer-Encoding")) shouldBe None

  // What a scraper sees, through the real server.

  /** One response off a raw socket: the status line, the lower-cased headers, the body, and the
    * size of each HTTP chunk it came in (none for a Content-Length).
    */
  private final case class Wire(
      status: String,
      headers: Map[String, String],
      body: Array[Byte],
      chunkSizes: List[Int]
  )

  /** A line off `in`, without its CRLF. */
  @tailrec private def readLine(in: InputStream, line: StringBuilder = new StringBuilder): String =
    val c = in.read()
    if c < 0 then line.toString
    else if c == '\n' && line.nonEmpty && line.last == '\r' then line.dropRight(1).toString
    else readLine(in, line.append(c.toChar))

  /** The chunks of a chunked body, each as it came. */
  @tailrec private def readChunks(
      in: InputStream,
      read: List[Array[Byte]] = Nil
  ): List[Array[Byte]] =
    val size = Integer.parseInt(readLine(in).trim, 16)
    if size == 0 then
      readLine(in)
      read.reverse
    else
      val data = in.readNBytes(size)
      readLine(in)
      readChunks(in, data :: read)

  /** A chunked body is read as such, so that a response framed any other way than by its length
    * fails the assertions on its headers and chunks instead of hanging the read.
    */
  private def readResponse(in: InputStream): Wire =
    val status  = readLine(in)
    val headers = Iterator
      .continually(readLine(in))
      .takeWhile(_.nonEmpty)
      .map(h => h.substring(0, h.indexOf(':')).toLowerCase -> h.substring(h.indexOf(':') + 1).trim)
      .toMap
    if headers.get("transfer-encoding").contains("chunked") then
      val chunks = readChunks(in)
      Wire(status, headers, Array.concat(chunks*), chunks.map(_.length))
    else Wire(status, headers, in.readNBytes(headers("content-length").toInt), Nil)

  /** `f` given the port of `endpoint` served by Ember on a free one. */
  private def onEmber[A](endpoint: MetricsEndpoint)(f: Int => A): A =
    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(0).get)
      .withHttpApp(app(endpoint))
      .build
      .use(server => IO.blocking(f(server.addressIp4s.port.value)))
      .unsafeRunSync()

  it should "serve a scraper over HTTP with its length: the exact media type, the whole text, and the next request on the same connection" in:
    val prom      = populated(60)
    val refreshes = new AtomicInteger(0)
    val endpoint  = new MetricsEndpoint(Some(prom), () => refreshes.incrementAndGet())
    val expected  = prom.scrape().getBytes(UTF_8)
    onEmber(endpoint) { port =>
      scala.util.Using.resource(new Socket("127.0.0.1", port)) { socket =>
        socket.setSoTimeout(15000)
        val in = new java.io.BufferedInputStream(socket.getInputStream)
        // Two requests on one connection: the first response must end where its length says for
        // the second to be read.
        val responses = (1 to 2).toList.map { _ =>
          socket.getOutputStream.write(
            "GET /metrics HTTP/1.1\r\nHost: l\r\nAccept: text/plain\r\n\r\n".getBytes(UTF_8)
          )
          socket.getOutputStream.flush()
          readResponse(in)
        }
        responses.foreach { r =>
          r.status shouldBe "HTTP/1.1 200 OK"
          r.headers("content-type") shouldBe promTextV004
          r.headers.get("content-length") shouldBe Some(expected.length.toString)
          r.headers.get("transfer-encoding") shouldBe None
          r.chunkSizes shouldBe Nil
          r.body shouldBe expected
        }
        refreshes.get() shouldBe 1
      }
    }

  it should "frame the scrape by its length for a client that speaks HTTP/1.0" in:
    // Such a client does not read chunk framing: it would take the chunk sizes for body bytes.
    val prom     = populated(60)
    val expected = prom.scrape().getBytes(UTF_8)
    onEmber(new MetricsEndpoint(Some(prom), () => ())) { port =>
      scala.util.Using.resource(new Socket("127.0.0.1", port)) { socket =>
        socket.setSoTimeout(15000)
        socket.getOutputStream.write("GET /metrics HTTP/1.0\r\n\r\n".getBytes(UTF_8))
        socket.getOutputStream.flush()
        // HTTP/1.0 ends at the close of the connection: the body is everything after the head.
        val all  = socket.getInputStream.readAllBytes()
        val text = new String(all, java.nio.charset.StandardCharsets.ISO_8859_1)
        val head = text.substring(0, text.indexOf("\r\n\r\n")).toLowerCase
        head should include(s"content-length: ${expected.length}")
        head should not include "transfer-encoding"
        all.drop(head.length + 4) shouldBe expected
      }
    }
