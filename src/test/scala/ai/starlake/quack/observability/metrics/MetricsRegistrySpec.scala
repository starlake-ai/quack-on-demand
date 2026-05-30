package ai.starlake.quack.observability.metrics

import cats.effect.unsafe.implicits.global
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class MetricsRegistrySpec extends AnyFlatSpec with Matchers:

  "MetricsRegistry" should "expose a Prometheus child when prometheus.enabled=true" in:
    val cfg = MetricsConfig(prometheus = PrometheusConfig(enabled = true))
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.prometheus shouldBe defined
        reg.prometheus.get shouldBe a [PrometheusMeterRegistry]
        // CompositeMeterRegistry contains exactly the Prometheus child for now.
        reg.composite.getRegistries.size shouldBe 1
      }
    }.unsafeRunSync()

  it should "omit the Prometheus child when prometheus.enabled=false" in:
    val cfg = MetricsConfig(prometheus = PrometheusConfig(enabled = false))
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.prometheus            shouldBe None
        reg.composite.getRegistries.size shouldBe 0
      }
    }.unsafeRunSync()

  it should "apply commonTags to every registered meter" in:
    val cfg = MetricsConfig(
      prometheus = PrometheusConfig(enabled = true),
      commonTags = Map("deployment" -> "test", "region" -> "local")
    )
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        val counter = reg.composite.counter("probe_total")
        counter.increment()
        val scrape = reg.prometheus.get.scrape()
        scrape should include ("deployment=\"test\"")
        scrape should include ("region=\"local\"")
      }
    }.unsafeRunSync()

  it should "attach a CloudWatch child when aws.enabled=true" in:
    System.setProperty("aws.accessKeyId", "test")
    System.setProperty("aws.secretAccessKey", "test")
    System.setProperty("aws.region", "us-east-1")
    val cfg = MetricsConfig(
      prometheus = PrometheusConfig(enabled = false),
      aws        = AwsMetricsConfig(enabled = true, namespace = "test-ns", stepSeconds = 60)
    )
    MetricsRegistry.resource(cfg).use { reg =>
      cats.effect.IO {
        reg.composite.getRegistries.size shouldBe 1
        reg.composite.getRegistries.iterator.next.getClass.getSimpleName should include ("CloudWatch")
      }
    }.unsafeRunSync()

  it should "skip CloudWatch when aws.enabled=false" in:
    MetricsRegistry.resource(MetricsConfig(aws = AwsMetricsConfig(enabled = false))).use { reg =>
      cats.effect.IO {
        reg.composite.getRegistries.asScala.exists(_.getClass.getSimpleName.contains("CloudWatch")) shouldBe false
      }
    }.unsafeRunSync()
