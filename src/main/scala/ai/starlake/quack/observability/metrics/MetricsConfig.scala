package ai.starlake.quack.observability.metrics

import ai.starlake.quack.config.ConfigField

import scala.annotation.meta.field

final case class AwsSinkConfig(
    @field @ConfigField(
      envVar = "QOD_METRICS_AWS_NAMESPACE",
      description = "CloudWatch namespace when metrics.sink=aws."
    )
    namespace: String = "quack-on-demand",
    @field @ConfigField(
      envVar = "QOD_METRICS_AWS_STEP_SEC",
      description = "CloudWatch publish step in seconds."
    )
    stepSeconds: Int = 60
)

final case class AzureSinkConfig(
    @field @ConfigField(
      envVar = "QOD_METRICS_AZURE_KEY",
      description = "Azure Monitor instrumentation key.",
      sensitive = true
    )
    instrumentationKey: Option[String] = None,
    @field @ConfigField(
      envVar = "QOD_METRICS_AZURE_STEP_SEC",
      description = "Azure Monitor publish step in seconds."
    )
    stepSeconds: Int = 60
)

final case class GcpSinkConfig(
    @field @ConfigField(
      envVar = "QOD_METRICS_GCP_PROJECT_ID",
      description = "GCP project ID when metrics.sink=gcp."
    )
    projectId: Option[String] = None,
    @field @ConfigField(
      envVar = "QOD_METRICS_GCP_STEP_SEC",
      description = "GCP Cloud Monitoring publish step in seconds."
    )
    stepSeconds: Int = 60
)

/** Single-knob metrics config. `sink` selects exactly one observability target; the per-sink
  * sub-configs (`aws`, `azure`, `gcp`) hold that sink's parameters. `"prometheus"` exposes a
  * `/metrics` pull endpoint; the cloud values push to that provider's monitoring API; `"none"`
  * disables metrics entirely.
  */
final case class MetricsConfig(
    @field @ConfigField(
      envVar = "QOD_METRICS_SINK",
      description = "Active metrics sink: prometheus | aws | azure | gcp | none."
    )
    sink: String = "prometheus",
    commonTags: Map[String, String] = Map.empty,
    aws: AwsSinkConfig = AwsSinkConfig(),
    azure: AzureSinkConfig = AzureSinkConfig(),
    gcp: GcpSinkConfig = GcpSinkConfig()
)
