package ai.starlake.quack.observability.metrics

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class MetricsConfigSpec extends AnyFlatSpec with Matchers:

  // Mirror Main.scala's product-hint wiring so the test loads via the same path.
  import MetricsConfigCodec.given

  private def load(hocon: String): MetricsConfig =
    ConfigSource.fromConfig(ConfigFactory.parseString(hocon).resolve())
      .at("metrics").loadOrThrow[MetricsConfig]

  "MetricsConfig" should "default to prometheus on, all cloud backends off, no common tags" in:
    val cfg = load("metrics {}")
    cfg.enabled              shouldBe true
    cfg.prometheus.enabled   shouldBe true
    cfg.gcp.enabled          shouldBe false
    cfg.azure.enabled        shouldBe false
    cfg.aws.enabled          shouldBe false
    cfg.commonTags           shouldBe Map.empty

  it should "load camelCase keys end-to-end (commonTags, projectId, instrumentationKey, stepSeconds)" in:
    val cfg = load(
      """metrics {
        |  enabled = true
        |  prometheus { enabled = false }
        |  commonTags { deployment = "prod-eu", region = "europe-west1" }
        |  gcp   { enabled = true,  projectId = "p1",                       stepSeconds = 30 }
        |  azure { enabled = true,  instrumentationKey = "00000000-aaaa",   stepSeconds = 45 }
        |  aws   { enabled = true,  namespace = "qod-prod",                 stepSeconds = 90 }
        |}""".stripMargin)
    cfg.prometheus.enabled            shouldBe false
    cfg.commonTags                    shouldBe Map("deployment" -> "prod-eu", "region" -> "europe-west1")
    cfg.gcp.enabled                   shouldBe true
    cfg.gcp.projectId                 shouldBe Some("p1")
    cfg.gcp.stepSeconds               shouldBe 30
    cfg.azure.instrumentationKey      shouldBe Some("00000000-aaaa")
    cfg.azure.stepSeconds              shouldBe 45
    cfg.aws.namespace                 shouldBe "qod-prod"
    cfg.aws.stepSeconds               shouldBe 90