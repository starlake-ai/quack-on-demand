package ai.starlake.quack.observability.metrics

import cats.effect.{IO, Resource}
import com.typesafe.scalalogging.LazyLogging
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.prometheusmetrics.{PrometheusConfig as MmPrometheusConfig, PrometheusMeterRegistry}

import scala.jdk.CollectionConverters._

/** Container for Micrometer registries. Always-on Prometheus child when
  * `cfg.prometheus.enabled=true`; cloud children attached by later tasks via
  * `attach(...)` helpers. The `composite` is the single registry consumers
  * (instruments, gauges) write to — Micrometer fans out to every attached
  * child. `prometheus.scrape()` produces the `/metrics` body. */
final class MetricsRegistry(
    val composite: CompositeMeterRegistry,
    val prometheus: Option[PrometheusMeterRegistry]
):
  /** Closes all attached child registries. Each cloud child stops its push
    * scheduler on close; the composite stops accepting writes. */
  def close(): Unit =
    composite.close()
    prometheus.foreach(_.close())

object MetricsRegistry extends LazyLogging:

  /** Build the registry from config. Returns a `Resource` so daemon threads
    * spawned by cloud registries (added in later tasks) shut down with the
    * manager process. */
  def resource(cfg: MetricsConfig): Resource[IO, MetricsRegistry] =
    Resource.make(IO.delay(create(cfg)))(reg => IO.delay(reg.close()))

  /** A no-op registry — used when `metrics.enabled=false` so callers can keep
    * a single constructor signature. The composite has no children, so
    * counters/timers/gauges registered against it are silently discarded. */
  val dummy: MetricsRegistry =
    new MetricsRegistry(new CompositeMeterRegistry(), prometheus = None)

  private def create(cfg: MetricsConfig): MetricsRegistry =
    val composite = new CompositeMeterRegistry()

    // Common tags apply via a MeterFilter so they hit every meter
    // registered against the composite (including those registered by
    // cloud children attached later).
    if cfg.commonTags.nonEmpty then
      val tags = cfg.commonTags.toList.map { case (k, v) => Tag.of(k, v) }.asJava
      composite.config().meterFilter(MeterFilter.commonTags(tags))

    val prom: Option[PrometheusMeterRegistry] =
      if cfg.prometheus.enabled then
        val p = new PrometheusMeterRegistry(MmPrometheusConfig.DEFAULT)
        composite.add(p)
        Some(p)
      else None

    new MetricsRegistry(composite, prom)
