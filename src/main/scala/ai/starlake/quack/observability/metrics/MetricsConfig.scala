package ai.starlake.quack.observability.metrics

final case class PrometheusConfig(enabled: Boolean = true)

final case class GcpMetricsConfig(
    enabled: Boolean = false,
    projectId: Option[String] = None,
    stepSeconds: Int = 60
)

final case class AzureMetricsConfig(
    enabled: Boolean = false,
    instrumentationKey: Option[String] = None,
    stepSeconds: Int = 60
)

final case class AwsMetricsConfig(
    enabled: Boolean = false,
    namespace: String = "quack-on-demand",
    stepSeconds: Int = 60
)

final case class MetricsConfig(
    enabled: Boolean = true,
    prometheus: PrometheusConfig = PrometheusConfig(),
    commonTags: Map[String, String] = Map.empty,
    gcp: GcpMetricsConfig = GcpMetricsConfig(),
    azure: AzureMetricsConfig = AzureMetricsConfig(),
    aws: AwsMetricsConfig = AwsMetricsConfig()
)