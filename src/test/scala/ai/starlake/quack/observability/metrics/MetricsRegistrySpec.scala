package ai.starlake.quack.observability.metrics

import cats.effect.unsafe.implicits.global
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class MetricsRegistrySpec extends AnyFlatSpec with Matchers:

  "MetricsRegistry" should "attach a Prometheus child when sink=prometheus" in:
    MetricsRegistry.resource(MetricsConfig(sink = "prometheus")).use { reg =>
      cats.effect.IO {
        reg.prometheus shouldBe defined
        reg.prometheus.get shouldBe a [PrometheusMeterRegistry]
        reg.composite.getRegistries.size shouldBe 1
      }
    }.unsafeRunSync()

  it should "have no children when sink=none" in:
    MetricsRegistry.resource(MetricsConfig(sink = "none")).use { reg =>
      cats.effect.IO {
        reg.prometheus shouldBe None
        reg.composite.getRegistries.size shouldBe 0
      }
    }.unsafeRunSync()

  it should "warn and fall back to none for an unknown sink name" in:
    MetricsRegistry.resource(MetricsConfig(sink = "wat")).use { reg =>
      cats.effect.IO {
        reg.prometheus shouldBe None
        reg.composite.getRegistries.size shouldBe 0
      }
    }.unsafeRunSync()

  it should "apply commonTags to every registered meter under sink=prometheus" in:
    val cfg = MetricsConfig(
      sink = "prometheus",
      commonTags = Map("deployment" -> "test", "region" -> "local")
    )
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.composite.counter("probe_total").increment()
        val scrape = reg.prometheus.get.scrape()
        scrape should include ("deployment=\"test\"")
        scrape should include ("region=\"local\"")
      }
    }.unsafeRunSync()

  it should "attach a CloudWatch child when sink=aws" in:
    // Fake AWS env so DefaultCredentialsProvider/Region chains don't fail.
    System.setProperty("aws.accessKeyId", "test")
    System.setProperty("aws.secretAccessKey", "test")
    System.setProperty("aws.region", "us-east-1")
    val cfg = MetricsConfig(sink = "aws", aws = AwsSinkConfig(namespace = "test-ns", stepSeconds = 60))
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.composite.getRegistries.size shouldBe 1
        reg.composite.getRegistries.iterator.next.getClass.getSimpleName should include ("CloudWatch")
      }
    }.unsafeRunSync()

  it should "attach Azure Monitor when sink=azure and instrumentationKey present" in:
    val cfg = MetricsConfig(
      sink = "azure",
      azure = AzureSinkConfig(instrumentationKey = Some("00000000-0000-0000-0000-000000000000"), stepSeconds = 60)
    )
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.composite.getRegistries.iterator.next.getClass.getSimpleName should include ("AzureMonitor")
      }
    }.unsafeRunSync()

  it should "skip Azure Monitor and have no children when instrumentationKey is missing" in:
    val cfg = MetricsConfig(sink = "azure", azure = AzureSinkConfig(instrumentationKey = None))
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.composite.getRegistries.asScala.exists(_.getClass.getSimpleName.contains("AzureMonitor")) shouldBe false
      }
    }.unsafeRunSync()

  it should "attach Stackdriver when sink=gcp and projectId present" in:
    val cfg = MetricsConfig(sink = "gcp", gcp = GcpSinkConfig(projectId = Some("test-proj"), stepSeconds = 60))
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.composite.getRegistries.iterator.next.getClass.getSimpleName should include ("Stackdriver")
      }
    }.unsafeRunSync()

  it should "skip Stackdriver and have no children when projectId is missing" in:
    val cfg = MetricsConfig(sink = "gcp", gcp = GcpSinkConfig(projectId = None))
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.composite.getRegistries.asScala.exists(_.getClass.getSimpleName.contains("Stackdriver")) shouldBe false
      }
    }.unsafeRunSync()