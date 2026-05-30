package ai.starlake.quack.observability.metrics

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class MetricsConfigSpec extends AnyFlatSpec with Matchers:

  import MetricsConfigCodec.given

  private def load(hocon: String): MetricsConfig =
    ConfigSource.fromConfig(ConfigFactory.parseString(hocon).resolve())
      .at("metrics").loadOrThrow[MetricsConfig]

  "MetricsConfig" should "default to sink=prometheus, no common tags, default sub-config values" in:
    val cfg = load("metrics {}")
    cfg.sink                shouldBe "prometheus"
    cfg.commonTags          shouldBe Map.empty
    cfg.aws.namespace       shouldBe "quack-on-demand"
    cfg.aws.stepSeconds     shouldBe 60
    cfg.azure.instrumentationKey shouldBe None
    cfg.azure.stepSeconds   shouldBe 60
    cfg.gcp.projectId       shouldBe None
    cfg.gcp.stepSeconds     shouldBe 60

  it should "load all sub-config fields when sink and per-sink blocks are populated" in:
    val cfg = load(
      """metrics {
        |  sink = "aws"
        |  commonTags { deployment = "prod-eu", region = "europe-west1" }
        |  aws   { namespace = "qod-prod", stepSeconds = 90 }
        |  azure { instrumentationKey = "00000000-aaaa", stepSeconds = 45 }
        |  gcp   { projectId = "p1", stepSeconds = 30 }
        |}""".stripMargin)
    cfg.sink                       shouldBe "aws"
    cfg.commonTags                 shouldBe Map("deployment" -> "prod-eu", "region" -> "europe-west1")
    cfg.aws.namespace              shouldBe "qod-prod"
    cfg.aws.stepSeconds            shouldBe 90
    cfg.azure.instrumentationKey   shouldBe Some("00000000-aaaa")
    cfg.azure.stepSeconds          shouldBe 45
    cfg.gcp.projectId              shouldBe Some("p1")
    cfg.gcp.stepSeconds            shouldBe 30