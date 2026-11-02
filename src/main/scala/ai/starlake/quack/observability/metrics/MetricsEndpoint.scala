package ai.starlake.quack.observability.metrics

import cats.effect.{IO, SyncIO}
import cats.effect.std.Semaphore
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import sttp.model.{MediaType, StatusCode}
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

/** Bare Tapir endpoint at `/metrics`. Open (no API-key gate, no /api prefix) so scrapers can pull
  * without auth -- same posture as `/health`.
  *
  * Since anyone can call it, one request must cost little whatever the registry holds. A client
  * that stops reading keeps its response in memory until the idle timeout, so a copy of the scrape
  * per request lets about 40 bytes hold a megabyte, and an out-of-memory error is fatal to the
  * manager. So every request within [[MetricsEndpoint.Ttl]] shares one scrape, taken by one of them
  * while the others wait without a thread; the bytes go out in small chunks of that one array,
  * which Ember does not copy whole; a response has [[MetricsEndpoint.ResponseDeadline]] to finish,
  * so a client reading just fast enough to keep each write alive cannot pin an old scrape for
  * hours; and a request waits at most [[MetricsEndpoint.ScrapeWait]] for the gate behind a scrape
  * in progress and answers 503 past it, so a scrape that never returns holds the connection of the
  * request that took it and no other. Only that wait is bounded: the request that took the scrape
  * has no timeout, and its scrape is kept when it ends, however slow.
  *
  * The length is sent, since it is known: an HTTP/1.0 client reads no chunk framing, and a response
  * cut at its deadline then reads as cut, a body shorter than it said.
  *
  * The route is only mounted when there is a Prometheus child registry. When
  * `metrics.prometheus.enabled=false`, `serverEndpoints` returns Nil and the route surfaces as 404.
  */
final class MetricsEndpoint(
    prometheus: Option[PrometheusMeterRegistry],
    beforeScrape: () => Unit,
    ttl: FiniteDuration = MetricsEndpoint.Ttl,
    responseDeadline: FiniteDuration = MetricsEndpoint.ResponseDeadline,
    scrapeWait: FiniteDuration = MetricsEndpoint.ScrapeWait
):

  // Prometheus exposition format media type, per
  // https://prometheus.io/docs/instrumenting/exposition_formats/.
  private val promTextV004 = "text/plain; version=0.0.4; charset=utf-8"

  // The body is declared with its real format: Tapir answers 406 before the route runs when no
  // declared body satisfies the request's Accept header, and it reads the declared media type, not
  // the Content-Type header below (which only spells it as scrapers expect). A stream of bytes
  // declares application/octet-stream, which refuses `Accept: text/plain`.
  //
  // A request that waits too long for the gate answers 503 as a status of the endpoint: raised as an
  // exception it would reach Tapir's handler, which logs an ERROR with a stack trace for each.
  private val metricsEndpoint: PublicEndpoint[Unit, Unit, (InputStream, Long), Any] =
    endpoint.get
      .in("metrics")
      .errorOut(statusCode(StatusCode.ServiceUnavailable))
      .out(
        EndpointIO.Body(
          RawBodyType.InputStreamBody,
          Codec.id[InputStream, MetricsEndpoint.PromText.type](
            MetricsEndpoint.PromText,
            Schema.schemaForInputStream
          ),
          EndpointIO.Info.empty
        )
      )
      .out(header[Long]("Content-Length"))
      .out(header("Content-Type", promTextV004))
      .description("Prometheus text-format scrape endpoint.")

  /** One scrape at a time: a request that finds the gate taken waits as a suspended fiber, with no
    * thread, for at most `scrapeWait`. A scrape that fails releases it, and the next request tries
    * again.
    */
  private val gate: Semaphore[IO] = Semaphore.in[SyncIO, IO](1).unsafeRunSync()

  @volatile private var last: Option[(FiniteDuration, Array[Byte])] = None

  /** `work` once the gate is held, or None when it did not come within `scrapeWait`. Only the wait
    * is bounded: a request that holds the gate has no timeout, and `work` runs to its end. A
    * request that gives up holds nothing and puts nothing back. Nothing is lost to a request
    * cancelled while it waits (`acquire` returns a permit that came as it was cancelled, and
    * `timeoutTo` hands on the permit of an acquire that finished as the timer did), and a request
    * cancelled while it holds the gate puts it back.
    */
  private def underGate[A](work: IO[A]): IO[Option[A]] =
    IO.bracketFull[Boolean, Option[A]](poll =>
      // At once when the gate is free; a wait, which a cancel or the timer ends, when it is not.
      gate.tryAcquire.flatMap {
        case true  => IO.pure(true)
        case false => poll(gate.acquire.as(true).timeoutTo(scrapeWait, IO.pure(false)))
      }
    )(held => if held then work.map(Some(_)) else IO.pure(None))((held, _) =>
      if held then gate.release else IO.unit
    )

  /** The shared scrape, taken when older than the ttl; None if the gate did not come in time. */
  private def current(p: PrometheusMeterRegistry): IO[Option[Array[Byte]]] =
    underGate(
      IO.monotonic.flatMap { now =>
        last match
          case Some((at, bytes)) if now - at < ttl => IO.pure(bytes)
          case _                                   => scrapeAndKeep(p)
      }
    )

  /** A scrape is as old as the moment it ended. Counted from the moment it began, a slow one would
    * be stale for the requests queued behind it, and each of them would scrape again. It is not
    * cancellable: the blocking call cannot be interrupted, and what it took is kept for the next
    * request even when the request that took it is gone.
    */
  private def scrapeAndKeep(p: PrometheusMeterRegistry): IO[Array[Byte]] =
    IO.uncancelable { _ =>
      for
        // `scrape` is synchronous + blocking-ish (serializes meter state to a String).
        bytes <- IO.blocking { beforeScrape(); p.scrape().getBytes(UTF_8) }
        ended <- IO.monotonic
        _     <- IO { last = Some((ended, bytes)) }
      yield bytes
    }

  def serverEndpoints: List[ServerEndpoint[Any, IO]] =
    prometheus.toList.map { p =>
      metricsEndpoint.serverLogic { _ =>
        current(p).map {
          case Some(bytes) =>
            Right(
              (
                new MetricsEndpoint.Response(bytes, System.nanoTime() + responseDeadline.toNanos),
                bytes.length.toLong
              )
            )
          case None => Left(())
        }
      }
    }

object MetricsEndpoint:

  /** The exposition format as a media type, parameters included, for Tapir to match the request's
    * Accept header against.
    */
  private object PromText extends CodecFormat:
    override val mediaType: MediaType =
      MediaType("text", "plain", Some("utf-8"), Map("version" -> "0.0.4"))

  /** How long requests share a scrape. A response can pin the scrape it started with for
    * [[ResponseDeadline]] and then one stuck write, 60 s at most (Ember's idle timeout): about 24
    * scrapes held at worst, ((4 s + 30 s + 60 s) / 4 s), however many clients stall.
    *
    * It is under the 5 s interval at which this repository's own Prometheus configurations scrape
    * (`observability/prometheus.yml`, `observability/prometheus-compose.yml` and
    * `charts/quack-on-demand/local-stack-k8s/prometheus.yaml`). A scrape is stamped when it ended,
    * so the next one finds it the interval minus the time it took old, and a ttl of 5 s repeated
    * half of the samples (measured: 4 of 7 were new).
    */
  val Ttl: FiniteDuration = 4.seconds

  /** How long a response has to finish. Prometheus' own default scrape timeout is 10 s. */
  val ResponseDeadline: FiniteDuration = 30.seconds

  /** How long a request waits for the gate behind a scrape in progress. A scrape that never returns
    * (a hung bean, a lock) holds the gate, and every request after it would hold its connection for
    * as long; past this a waiter answers 503 instead, so one stuck scrape holds one connection and
    * not one per scrape attempt (a Prometheus scraping every 15 s would fill 260 in about 65
    * minutes). Only the wait is bounded: the request that took the scrape has no timeout, and what
    * it scraped is kept when it ends, however slow. Prometheus' own default scrape timeout is 10 s:
    * it has given up by then.
    */
  val ScrapeWait: FiniteDuration = 10.seconds

  /** One response's view of a shared scrape. Past the deadline a read fails rather than ends, so
    * the connection drops and a client never takes a cut scrape for a whole one.
    */
  final class Response(bytes: Array[Byte], deadlineNanos: Long) extends ByteArrayInputStream(bytes):
    override def read(b: Array[Byte], off: Int, len: Int): Int =
      if System.nanoTime() - deadlineNanos > 0 then
        throw new IOException("the /metrics response passed its deadline")
      super.read(b, off, len)
