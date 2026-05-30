package ai.starlake.quack.observability.metrics

import com.typesafe.scalalogging.Logger
import io.micrometer.core.instrument.composite.CompositeMeterRegistry

/** A single cloud-monitoring backend that may be attached to the
  * MetricsRegistry's CompositeMeterRegistry. Each backend reports whether
  * it's enabled for the given config and knows how to attach its own
  * Micrometer registry. Concrete implementations live as case objects in
  * the companion. */
private[metrics] sealed abstract class CloudMetricsBackend(val name: String):

  /** True when the operator has flagged this backend on. Required-field
    * presence (e.g. Azure's instrumentationKey) is checked inside `attach`
    * so we still emit a clear WARN for "enabled but misconfigured" cases. */
  def isEnabled(cfg: MetricsConfig): Boolean

  /** Construct and attach the backend's Micrometer registry. Logs WARN
    * and returns without attaching on init failure (missing creds, missing
    * required field, ClassNotFoundException on the cloud SDK). Never throws. */
  def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Unit

private[metrics] object CloudMetricsBackend:

  /** AWS CloudWatch via micrometer-registry-cloudwatch2. */
  case object CloudWatch extends CloudMetricsBackend("aws-cloudwatch"):
    def isEnabled(cfg: MetricsConfig): Boolean = cfg.aws.enabled

    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Unit =
      try
        import io.micrometer.cloudwatch2.{CloudWatchConfig, CloudWatchMeterRegistry}
        import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
        val awsCfg = new CloudWatchConfig:
          override def get(key: String): String = null
          override def namespace(): String = cfg.aws.namespace
          override def step(): java.time.Duration = java.time.Duration.ofSeconds(cfg.aws.stepSeconds)
        val client = CloudWatchAsyncClient.builder().build()
        val cw     = new CloudWatchMeterRegistry(awsCfg, io.micrometer.core.instrument.Clock.SYSTEM, client)
        composite.add(cw)
      catch case t: Throwable =>
        logger.warn(s"$name metrics disabled: ${t.getMessage}")

  /** Azure Monitor via micrometer-registry-azure-monitor. Requires a
    * non-empty `instrumentationKey`. */
  case object AzureMonitor extends CloudMetricsBackend("azure-monitor"):
    def isEnabled(cfg: MetricsConfig): Boolean = cfg.azure.enabled

    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Unit =
      cfg.azure.instrumentationKey match
        case None =>
          logger.warn(s"$name enabled but azure.instrumentationKey is empty; skipping.")
        case Some(key) =>
          try
            import io.micrometer.azuremonitor.{AzureMonitorConfig, AzureMonitorMeterRegistry}
            val azCfg = new AzureMonitorConfig:
              override def get(k: String): String = null
              override def instrumentationKey(): String = key
              override def step(): java.time.Duration = java.time.Duration.ofSeconds(cfg.azure.stepSeconds)
            val az = new AzureMonitorMeterRegistry(azCfg, io.micrometer.core.instrument.Clock.SYSTEM)
            composite.add(az)
          catch case t: Throwable =>
            logger.warn(s"$name metrics disabled: ${t.getMessage}")

  /** GCP Cloud Monitoring via micrometer-registry-stackdriver. Requires
    * a non-empty `projectId`. */
  case object Stackdriver extends CloudMetricsBackend("gcp-stackdriver"):
    def isEnabled(cfg: MetricsConfig): Boolean = cfg.gcp.enabled

    def attach(cfg: MetricsConfig, composite: CompositeMeterRegistry, logger: Logger): Unit =
      cfg.gcp.projectId match
        case None =>
          logger.warn(s"$name enabled but gcp.projectId is empty; skipping.")
        case Some(project) =>
          try
            import io.micrometer.stackdriver.{StackdriverConfig, StackdriverMeterRegistry}
            val sdCfg = new StackdriverConfig:
              override def get(k: String): String = null
              override def projectId(): String = project
              override def step(): java.time.Duration = java.time.Duration.ofSeconds(cfg.gcp.stepSeconds)
            val sd = StackdriverMeterRegistry.builder(sdCfg).build()
            composite.add(sd)
          catch case t: Throwable =>
            logger.warn(s"$name metrics disabled: ${t.getMessage}")

  /** Priority-ordered list of backends. `selectFirstEnabled` returns the
    * first one whose flag is on. Multiple flags simultaneously enabled is
    * treated as a config error; only the first wins. */
  val all: List[CloudMetricsBackend] = List(CloudWatch, AzureMonitor, Stackdriver)

  /** Pick the highest-priority backend whose flag is on, if any. */
  def selectFirstEnabled(cfg: MetricsConfig): Option[CloudMetricsBackend] =
    all.find(_.isEnabled(cfg))
