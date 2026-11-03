package ai.starlake.quack.observability.metrics

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
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
  * while the others wait for it without a thread; the bytes go out in small chunks of that one
  * array, which Ember does not copy whole; a response has [[MetricsEndpoint.ResponseDeadline]] to
  * finish, so a client reading just fast enough to keep each write alive cannot pin an old scrape
  * for hours; and a request waits at most [[MetricsEndpoint.ScrapeWait]] for a scrape in progress
  * and answers 503 past it, so a scrape that never returns holds the connection of the request that
  * took it, and that of each request after it only for the wait. Only that wait is bounded: the
  * request that took the scrape has no timeout, and its scrape is kept when it ends, however slow.
  *
  * A request that waits holds nothing a cancel could lose: it reads the answer of the scrape that
  * is running, and a cancel, which an HTTP/2 reset or a server stop brings at any moment, drops it
  * from that scrape's listeners and changes nothing else.
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
  // A request that waits too long for a scrape in progress answers 503 as a status of the endpoint:
  // raised as an exception it would reach Tapir's handler, which logs an ERROR with a stack trace
  // for each.
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

  /** Where the one scrape is: idle, with the last one, or running, with the answer to wait on. */
  private val state: Ref[IO, MetricsEndpoint.ScrapeState] =
    Ref.unsafe(MetricsEndpoint.ScrapeState.Idle(None))

  /** The shared scrape: the last one while younger than the ttl, else the one running, else a new
    * one taken by this request. None when the one running did not end within `scrapeWait`. A
    * request that waits holds no thread, and nothing a cancel could lose: it only reads the running
    * scrape's answer. A request that takes a scrape is not cancellable: the blocking call cannot be
    * interrupted, and what it took is kept for the next request even when its own is gone. So there
    * is one scrape at a time, and the state always ends idle.
    */
  private def current(p: PrometheusMeterRegistry): IO[Option[Array[Byte]]] =
    (IO.monotonic, Deferred[IO, Either[Throwable, Array[Byte]]]).flatMapN { (now, mine) =>
      IO.uncancelable { poll =>
        state.modify {
          case idle @ MetricsEndpoint.ScrapeState.Idle(Some((at, bytes))) if now - at < ttl =>
            (idle, IO.pure(Some(bytes)))
          case running @ MetricsEndpoint.ScrapeState.Running(done) =>
            (
              running,
              poll(done.get.map(Option(_)).timeoutTo(scrapeWait, IO.none)).flatMap {
                case Some(answer) => IO.fromEither(answer).map(Some(_))
                case None         => IO.none
              }
            )
          case MetricsEndpoint.ScrapeState.Idle(_) =>
            (MetricsEndpoint.ScrapeState.Running(mine), scrapeAndKeep(p, mine).map(Some(_)))
        }.flatten
      }
    }

  /** A scrape is as old as the moment it ended. A failed one leaves nothing to share, so the next
    * request tries again, and the requests waiting on it get its failure.
    */
  private def scrapeAndKeep(
      p: PrometheusMeterRegistry,
      done: Deferred[IO, Either[Throwable, Array[Byte]]]
  ): IO[Array[Byte]] =
    // `scrape` is synchronous + blocking-ish (serializes meter state to a String).
    IO.blocking { beforeScrape(); p.scrape().getBytes(UTF_8) }.attempt.flatMap { outcome =>
      IO.monotonic.flatMap { ended =>
        state.set(MetricsEndpoint.ScrapeState.Idle(outcome.toOption.map((ended, _)))) *>
          done.complete(outcome) *>
          IO.fromEither(outcome)
      }
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

  /** How long a request waits for a scrape in progress. A scrape that never returns (a hung bean, a
    * lock) stays the running one, and every request after it would hold its connection for as long;
    * past this a waiter answers 503 instead, so one stuck scrape holds one connection and not one
    * per scrape attempt (a Prometheus scraping every 15 s would fill 260 in about 65 minutes). Only
    * the wait is bounded: the request that took the scrape has no timeout, and what it scraped is
    * kept when it ends, however slow. Prometheus' own default scrape timeout is 10 s: it has given
    * up by then.
    */
  val ScrapeWait: FiniteDuration = 10.seconds

  /** Whether a scrape is running, or what the last one took and when it ended. */
  private enum ScrapeState:
    case Idle(last: Option[(FiniteDuration, Array[Byte])])
    case Running(done: Deferred[IO, Either[Throwable, Array[Byte]]])

  /** One response's view of a shared scrape. Past the deadline a read fails rather than ends, so
    * the connection drops and a client never takes a cut scrape for a whole one.
    */
  final class Response(bytes: Array[Byte], deadlineNanos: Long) extends ByteArrayInputStream(bytes):
    override def read(b: Array[Byte], off: Int, len: Int): Int =
      if System.nanoTime() - deadlineNanos > 0 then
        throw new IOException("the /metrics response passed its deadline")
      super.read(b, off, len)
