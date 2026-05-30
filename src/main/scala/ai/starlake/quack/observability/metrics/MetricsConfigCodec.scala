package ai.starlake.quack.observability.metrics

import pureconfig._
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

object MetricsConfigCodec:
  private val camelMapping: ConfigFieldMapping = ConfigFieldMapping(CamelCase, CamelCase)

  given ProductHint[PrometheusConfig]   = ProductHint[PrometheusConfig](camelMapping)
  given ProductHint[GcpMetricsConfig]   = ProductHint[GcpMetricsConfig](camelMapping)
  given ProductHint[AzureMetricsConfig] = ProductHint[AzureMetricsConfig](camelMapping)
  given ProductHint[AwsMetricsConfig]   = ProductHint[AwsMetricsConfig](camelMapping)
  given ProductHint[MetricsConfig]      = ProductHint[MetricsConfig](camelMapping)

  given ConfigReader[PrometheusConfig]   = deriveReader[PrometheusConfig]
  given ConfigReader[GcpMetricsConfig]   = deriveReader[GcpMetricsConfig]
  given ConfigReader[AzureMetricsConfig] = deriveReader[AzureMetricsConfig]
  given ConfigReader[AwsMetricsConfig]   = deriveReader[AwsMetricsConfig]
  given ConfigReader[MetricsConfig]      = deriveReader[MetricsConfig]