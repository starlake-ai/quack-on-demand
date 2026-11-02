package ai.starlake.quack.observability.metrics

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
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
    val refreshes              = new AtomicInteger(0)
    val slowScrape: () => Unit = () =>
      refreshes.incrementAndGet()
      Thread.sleep(500)
    val endpoint = new MetricsEndpoint(Some(populated(1)), slowScrape, ttl = 100.millis)
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

  it should "serve a scraper over HTTP: chunked, the exact media type, the whole text, and the next request on the same connection" in:
    val prom      = populated(60)
    val refreshes = new AtomicInteger(0)
    val endpoint  = new MetricsEndpoint(Some(prom), () => refreshes.incrementAndGet())
    val expected  = prom.scrape().getBytes(UTF_8)
    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(0).get)
      .withHttpApp(app(endpoint))
      .build
      .use(server =>
        IO.blocking {
          val port = server.addressIp4s.port.value
          scala.util.Using.resource(new Socket("127.0.0.1", port)) { socket =>
            socket.setSoTimeout(15000)
            val in = new java.io.BufferedInputStream(socket.getInputStream)
            // Two requests on one connection: the first response must end where it should for the
            // second to be read.
            val responses = (1 to 2).toList.map { _ =>
              socket.getOutputStream.write(
                "GET /metrics HTTP/1.1\r\nHost: l\r\n\r\n".getBytes(UTF_8)
              )
              socket.getOutputStream.flush()
              readResponse(in)
            }
            responses.foreach { r =>
              r.status shouldBe "HTTP/1.1 200 OK"
              r.headers("content-type") shouldBe promTextV004
              r.headers.get("transfer-encoding") shouldBe Some("chunked")
              r.headers.get("content-length") shouldBe None
              r.body shouldBe expected
              r.chunkSizes.sum shouldBe expected.length
              r.chunkSizes.max should be <= 8192
            }
            refreshes.get() shouldBe 1
          }
        }
      )
      .unsafeRunSync()
