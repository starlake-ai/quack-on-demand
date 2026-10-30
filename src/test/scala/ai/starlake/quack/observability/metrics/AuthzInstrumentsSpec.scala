package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.{
  PrometheusConfig as MmPrometheusConfig,
  PrometheusMeterRegistry
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AuthzInstrumentsSpec extends AnyFlatSpec with Matchers:

  "AuthzInstruments" should "count decisions by tenant, kind, outcome and mode" in:
    val reg = new SimpleMeterRegistry
    val ai  = new AuthzInstruments(reg)
    ai.record("acme", "plan", "allow", "qod", 0.3)
    ai.record("acme", "plan", "allow", "qod", 0.7)
    ai.record("acme", "screen", "refuse", "opa", 12.0)
    reg
      .counter(
        "qod_authz_decisions_total",
        "tenant",
        "acme",
        "kind",
        "plan",
        "outcome",
        "allow",
        "mode",
        "qod"
      )
      .count() shouldBe 2.0
    reg
      .counter(
        "qod_authz_decisions_total",
        "tenant",
        "acme",
        "kind",
        "screen",
        "outcome",
        "refuse",
        "mode",
        "opa"
      )
      .count() shouldBe 1.0

  it should "observe each decision's duration per kind, in milliseconds" in:
    val reg = new SimpleMeterRegistry
    val ai  = new AuthzInstruments(reg)
    ai.record("acme", "plan", "allow", "qod", 0.3)
    ai.record("globex", "plan", "unavailable", "opa", 0.7)
    ai.record("acme", "screen", "allow", "qod", 4.0)
    val plan = reg.find("qod_authz_decide_duration_ms").tag("kind", "plan").summary()
    plan.count() shouldBe 2L
    plan.totalAmount() shouldBe 1.0 +- 1e-9
    reg.find("qod_authz_decide_duration_ms").tag("kind", "screen").summary().count() shouldBe 1L

  it should "export the names dashboards read, the duration without a seconds suffix" in:
    val prom = new PrometheusMeterRegistry(MmPrometheusConfig.DEFAULT)
    new AuthzInstruments(prom).record("acme", "plan", "unavailable", "opa", 3.0)
    val lines = prom.scrape().linesIterator.toList
    lines.exists(l =>
      l.startsWith("qod_authz_decisions_total{") && l.contains("outcome=\"unavailable\"") &&
        l.contains("mode=\"opa\"") && l.contains("kind=\"plan\"") && l.contains("tenant=\"acme\"")
    ) shouldBe true
    lines.exists(l =>
      l.startsWith("qod_authz_decide_duration_ms_bucket{") && l.contains("kind=\"plan\"")
    ) shouldBe true
    lines.exists(_.startsWith("qod_authz_decide_duration_ms_count{")) shouldBe true
    lines.exists(_.contains("qod_authz_decide_duration_ms_seconds")) shouldBe false

  "AuthzInstruments.noop" should "swallow records silently" in:
    noException should be thrownBy AuthzInstruments.noop.record("t", "plan", "allow", "qod", 1.0)
