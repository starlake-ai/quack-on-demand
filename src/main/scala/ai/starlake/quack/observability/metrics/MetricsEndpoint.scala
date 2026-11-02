package ai.starlake.quack.observability.metrics

import cats.effect.{IO, SyncIO}
import cats.effect.std.Semaphore
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
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
  * which Ember does not copy whole; and a response has [[MetricsEndpoint.ResponseDeadline]] to
  * finish, so a client reading just fast enough to keep each write alive cannot pin an old scrape
  * for hours.
  *
  * The route is only mounted when there is a Prometheus child registry. When
  * `metrics.prometheus.enabled=false`, `serverEndpoints` returns Nil and the route surfaces as 404.
  */
final class MetricsEndpoint(
    prometheus: Option[PrometheusMeterRegistry],
    beforeScrape: () => Unit,
    ttl: FiniteDuration = MetricsEndpoint.Ttl,
    responseDeadline: FiniteDuration = MetricsEndpoint.ResponseDeadline
):

  // Prometheus exposition format media type, per
  // https://prometheus.io/docs/instrumenting/exposition_formats/.
  private val promTextV004 = "text/plain; version=0.0.4; charset=utf-8"

  private val metricsEndpoint: PublicEndpoint[Unit, Unit, InputStream, Any] =
    endpoint.get
      .in("metrics")
      .out(inputStreamBody)
      .out(header("Content-Type", promTextV004))
      .description("Prometheus text-format scrape endpoint.")

  /** One scrape at a time: a request that finds the gate taken waits as a suspended fiber, with no
    * thread. A scrape that fails releases it, and the next request tries again.
    */
  private val gate: Semaphore[IO] = Semaphore.in[SyncIO, IO](1).unsafeRunSync()

  @volatile private var last: Option[(FiniteDuration, Array[Byte])] = None

  private def current(p: PrometheusMeterRegistry): IO[Array[Byte]] =
    gate.permit.surround(
      IO.monotonic.flatMap { now =>
        last match
          case Some((at, bytes)) if now - at < ttl => IO.pure(bytes)
          case _                                   => scrapeAndKeep(p)
      }
    )

  /** A scrape is as old as the moment it ended. Counted from the moment it began, a slow one would
    * be stale for the requests queued behind it, and each of them would scrape again.
    */
  private def scrapeAndKeep(p: PrometheusMeterRegistry): IO[Array[Byte]] =
    for
      // `scrape` is synchronous + blocking-ish (serializes meter state to a String).
      bytes <- IO.blocking { beforeScrape(); p.scrape().getBytes(UTF_8) }
      ended <- IO.monotonic
      _     <- IO { last = Some((ended, bytes)) }
    yield bytes

  def serverEndpoints: List[ServerEndpoint[Any, IO]] =
    prometheus.toList.map { p =>
      metricsEndpoint.serverLogicSuccess { _ =>
        current(p).map(bytes =>
          new MetricsEndpoint.Response(bytes, System.nanoTime() + responseDeadline.toNanos)
        )
      }
    }

object MetricsEndpoint:

  /** How long requests share a scrape. A response can pin the scrape it started with for
    * [[ResponseDeadline]] and then one stuck write, 60 s at most (Ember's idle timeout): about 19
    * scrapes held at worst, ((5 s + 30 s + 60 s) / 5 s), however many clients stall.
    */
  val Ttl: FiniteDuration = 5.seconds

  /** How long a response has to finish. Prometheus' own default scrape timeout is 10 s. */
  val ResponseDeadline: FiniteDuration = 30.seconds

  /** One response's view of a shared scrape. Past the deadline a read fails rather than ends, so
    * the connection drops and a client never takes a cut scrape for a whole one.
    */
  final class Response(bytes: Array[Byte], deadlineNanos: Long) extends ByteArrayInputStream(bytes):
    override def read(b: Array[Byte], off: Int, len: Int): Int =
      if System.nanoTime() - deadlineNanos > 0 then
        throw new IOException("the /metrics response passed its deadline")
      super.read(b, off, len)
