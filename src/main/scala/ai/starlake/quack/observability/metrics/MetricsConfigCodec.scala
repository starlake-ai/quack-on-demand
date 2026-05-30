package ai.starlake.quack.observability.metrics

import pureconfig._
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

object MetricsConfigCodec:
  private val camelMapping: ConfigFieldMapping = ConfigFieldMapping(CamelCase, CamelCase)

  given ProductHint[AwsSinkConfig]   = ProductHint[AwsSinkConfig](camelMapping)
  given ProductHint[AzureSinkConfig] = ProductHint[AzureSinkConfig](camelMapping)
  given ProductHint[GcpSinkConfig]   = ProductHint[GcpSinkConfig](camelMapping)
  given ProductHint[MetricsConfig]   = ProductHint[MetricsConfig](camelMapping)

  given ConfigReader[AwsSinkConfig]   = deriveReader[AwsSinkConfig]
  given ConfigReader[AzureSinkConfig] = deriveReader[AzureSinkConfig]
  given ConfigReader[GcpSinkConfig]   = deriveReader[GcpSinkConfig]
  given ConfigReader[MetricsConfig]   = deriveReader[MetricsConfig]