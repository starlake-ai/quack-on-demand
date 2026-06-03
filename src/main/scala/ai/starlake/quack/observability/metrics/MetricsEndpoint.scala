package ai.starlake.quack.observability.metrics

import cats.effect.IO
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint

/** Bare Tapir endpoint at `/metrics`. Open (no API-key gate, no /api prefix) so scrapers can pull
  * without auth -- same posture as `/health`.
  *
  * The route is only mounted when there is a Prometheus child registry. When
  * `metrics.prometheus.enabled=false`, `serverEndpoints` returns Nil and the route surfaces as 404.
  */
final class MetricsEndpoint(
    prometheus: Option[PrometheusMeterRegistry],
    beforeScrape: () => Unit
):

  // Prometheus exposition format media type, per
  // https://prometheus.io/docs/instrumenting/exposition_formats/.
  private val promTextV004 = "text/plain; version=0.0.4; charset=utf-8"

  private val metricsEndpoint: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("metrics")
      .out(stringBody)
      .out(header("Content-Type", promTextV004))
      .description("Prometheus text-format scrape endpoint.")

  def serverEndpoints: List[ServerEndpoint[Any, IO]] =
    prometheus.toList.map { p =>
      metricsEndpoint.serverLogicSuccess { _ =>
        // `scrape` is synchronous + blocking-ish (serializes meter state to
        // a String). Use IO.blocking so it doesn't stall the EC.
        IO.blocking { beforeScrape(); p.scrape() }
      }
    }
