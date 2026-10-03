package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.{MeterRegistry, Timer}

import java.time.Duration

/** Meters of the REST data edge, one instance per manager:
  *
  *   - `qod_rest_requests_total{route,format,status}`: every answer, by route TEMPLATE name
  *     (`schemas`, `tables`, `table`, `rows`; `preflight` for a CORS preflight and `none` for
  *     anything the server refused before a route), negotiated format (`json`, `csv`, `arrow`,
  *     `parquet`, `none` for an error) and HTTP status;
  *   - `qod_rest_request_seconds{route,format}`: time to the full answer, a streamed body included;
  *   - `qod_rest_rejections_total{reason}`: what the abuse controls turned away (`auth_failures`
  *     for the failed-authentication throttle, whatever the request, a CORS preflight included;
  *     `concurrency` for the in-flight and Parquet caps);
  *   - `qod_rest_stream_aborts_total{route,format}`: streamed bodies that did not end cleanly: cut
  *     by an error or a cap after their first byte, abandoned by their client mid-way, or released
  *     without ever starting (their request is still counted, with its 200, once).
  *
  * Labels never carry a concrete path, a tenant, a parameter or a client: every one of them comes
  * from a fixed vocabulary, so cardinality is bounded whatever the traffic.
  */
final class RestEdgeInstruments(registry: MeterRegistry):

  private val timers = new java.util.concurrent.ConcurrentHashMap[(String, String), Timer]()

  def request(route: String, format: String, status: Int, durationNanos: Long): Unit =
    registry
      .counter(
        "qod_rest_requests_total",
        "route",
        route,
        "format",
        format,
        "status",
        status.toString
      )
      .increment()
    timers
      .computeIfAbsent(
        (route, format),
        _ =>
          Timer
            .builder("qod_rest_request_seconds")
            .tag("route", route)
            .tag("format", format)
            .publishPercentileHistogram()
            .register(registry)
      )
      .record(Duration.ofNanos(durationNanos))

  def rejection(reason: String): Unit =
    registry.counter("qod_rest_rejections_total", "reason", reason).increment()

  def streamAborted(route: String, format: String): Unit =
    registry.counter("qod_rest_stream_aborts_total", "route", route, "format", format).increment()

object RestEdgeInstruments:

  /** Route name of anything the server answered before a route. */
  val NoRoute = "none"

  /** Format of an answer that is not a 200 body. */
  val NoFormat = "none"

  val noop = new RestEdgeInstruments(
    new io.micrometer.core.instrument.composite.CompositeMeterRegistry()
  )
