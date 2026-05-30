package ai.starlake.quack.observability.metrics

import com.typesafe.scalalogging.Logger
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.prometheusmetrics.{PrometheusConfig as MmPrometheusConfig, PrometheusMeterRegistry}

/** A single metrics sink: Prometheus pull, one of the cloud-monitoring
  * pushes, or `Disabled` (no sink). At most one is attached per process.
  *
  * `attach` returns the PrometheusMeterRegistry only when the sink is
  * Prometheus — that handle lets `MetricsEndpoint` serve `/metrics`. All
  * other sinks return `None`. Failures (missing required config, init
  * exceptions) are logged at WARN and yield `None`. */
private[metrics] sealed abstract class MetricsSink(val name: String):
  def attach(
      cfg: MetricsConfig,
      composite: CompositeMeterRegistry,
      logger: Logger
  ): Option[PrometheusMeterRegistry]

private[metrics] object MetricsSink:

  case object Prometheus extends MetricsSink("prometheus"):
    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Option[PrometheusMeterRegistry] =
      val p = new PrometheusMeterRegistry(MmPrometheusConfig.DEFAULT)
      composite.add(p)
      Some(p)

  case object CloudWatch extends MetricsSink("aws"):
    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Option[PrometheusMeterRegistry] =
      try
        import io.micrometer.cloudwatch2.{CloudWatchConfig, CloudWatchMeterRegistry}
        import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
        val awsCfg = new CloudWatchConfig:
          override def get(key: String): String = null
          override def namespace(): String      = cfg.aws.namespace
          override def step(): java.time.Duration = java.time.Duration.ofSeconds(cfg.aws.stepSeconds)
        val client = CloudWatchAsyncClient.builder().build()
        val cw     = new CloudWatchMeterRegistry(awsCfg, io.micrometer.core.instrument.Clock.SYSTEM, client)
        composite.add(cw)
      catch case t: Throwable =>
        logger.warn(s"$name metrics disabled: ${t.getMessage}")
      None

  case object AzureMonitor extends MetricsSink("azure"):
    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Option[PrometheusMeterRegistry] =
      cfg.azure.instrumentationKey match
        case None =>
          logger.warn(s"$name selected but azure.instrumentationKey is empty; skipping.")
        case Some(key) =>
          try
            import io.micrometer.azuremonitor.{AzureMonitorConfig, AzureMonitorMeterRegistry}
            val azCfg = new AzureMonitorConfig:
              override def get(k: String): String          = null
              override def instrumentationKey(): String    = key
              override def step(): java.time.Duration      = java.time.Duration.ofSeconds(cfg.azure.stepSeconds)
            val az = new AzureMonitorMeterRegistry(azCfg, io.micrometer.core.instrument.Clock.SYSTEM)
            composite.add(az)
          catch case t: Throwable =>
            logger.warn(s"$name metrics disabled: ${t.getMessage}")
      None

  case object Stackdriver extends MetricsSink("gcp"):
    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Option[PrometheusMeterRegistry] =
      cfg.gcp.projectId match
        case None =>
          logger.warn(s"$name selected but gcp.projectId is empty; skipping.")
        case Some(project) =>
          try
            import io.micrometer.stackdriver.{StackdriverConfig, StackdriverMeterRegistry}
            val sdCfg = new StackdriverConfig:
              override def get(k: String): String          = null
              override def projectId(): String             = project
              override def step(): java.time.Duration      = java.time.Duration.ofSeconds(cfg.gcp.stepSeconds)
            val sd = StackdriverMeterRegistry.builder(sdCfg).build()
            composite.add(sd)
          catch case t: Throwable =>
            logger.warn(s"$name metrics disabled: ${t.getMessage}")
      None

  case object Disabled extends MetricsSink("none"):
    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Option[PrometheusMeterRegistry] =
      None

  val all: List[MetricsSink] = List(Prometheus, CloudWatch, AzureMonitor, Stackdriver, Disabled)

  val byName: Map[String, MetricsSink] = all.map(s => s.name -> s).toMap

  /** Look up the configured sink by name. Unknown values log a WARN and
    * fall through to `Disabled` so the manager doesn't crash. */
  def fromConfig(cfg: MetricsConfig, logger: Logger): MetricsSink =
    byName.getOrElse(cfg.sink, {
      logger.warn(s"unknown metrics.sink '${cfg.sink}'; expected one of ${byName.keys.toList.sorted.mkString(", ")}; falling back to 'none'")
      Disabled
    })