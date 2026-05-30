package ai.starlake.quack.observability.metrics

final case class AwsSinkConfig(
    namespace: String = "quack-on-demand",
    stepSeconds: Int  = 60
)

final case class AzureSinkConfig(
    instrumentationKey: Option[String] = None,
    stepSeconds: Int                   = 60
)

final case class GcpSinkConfig(
    projectId: Option[String] = None,
    stepSeconds: Int          = 60
)

/** Single-knob metrics config. `sink` selects exactly one observability
  * target; the per-sink sub-configs (`aws`, `azure`, `gcp`) hold that
  * sink's parameters. `"prometheus"` exposes a `/metrics` pull endpoint;
  * the cloud values push to that provider's monitoring API; `"none"`
  * disables metrics entirely. */
final case class MetricsConfig(
    sink: String = "prometheus",
    commonTags: Map[String, String] = Map.empty,
    aws:   AwsSinkConfig   = AwsSinkConfig(),
    azure: AzureSinkConfig = AzureSinkConfig(),
    gcp:   GcpSinkConfig   = GcpSinkConfig()
)
