package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StatementInstrumentsSpec extends AnyFlatSpec with Matchers:

  "StatementInstruments.record" should "increment statements_total by 1 with the given labels" in:
    val reg = new SimpleMeterRegistry()
    val si  = new StatementInstruments(reg)
    si.record("acme", "sales", "ok", 12L)
    val counter = reg.counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok")
    counter.count() shouldBe 1.0

  it should "record one observation in statement_duration_seconds" in:
    val reg = new SimpleMeterRegistry()
    val si  = new StatementInstruments(reg)
    si.record("acme", "sales", "ok", 12L)
    val timer =
      reg.timer("statement_duration_seconds", "tenant", "acme", "pool", "sales", "status", "ok")
    timer.count() shouldBe 1L
    timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe (12.0 +- 1.0)

  it should "keep different status labels as distinct series" in:
    val reg = new SimpleMeterRegistry()
    val si  = new StatementInstruments(reg)
    si.record("acme", "sales", "ok", 5L)
    si.record("acme", "sales", "denied", 1L)
    si.record("acme", "sales", "ok", 7L)
    reg
      .counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok")
      .count() shouldBe 2.0
    reg
      .counter("statements_total", "tenant", "acme", "pool", "sales", "status", "denied")
      .count() shouldBe 1.0

  "StatementInstruments.recordMetadataFilter" should "count outcomes by label and time the pass" in:
    val reg = new SimpleMeterRegistry()
    val si  = new StatementInstruments(reg)
    si.recordMetadataFilter("acme", "bi", "rewritten")
    si.recordMetadataFilter("acme", "bi", "rewritten")
    si.recordMetadataFilter("acme", "bi", "denied")
    si.recordMetadataFilterDuration("acme", "bi", 9L)
    reg
      .counter(
        "metadata_filter_rewrites_total",
        "tenant",
        "acme",
        "pool",
        "bi",
        "outcome",
        "rewritten"
      )
      .count() shouldBe 2.0
    reg
      .counter(
        "metadata_filter_rewrites_total",
        "tenant",
        "acme",
        "pool",
        "bi",
        "outcome",
        "denied"
      )
      .count() shouldBe 1.0
    reg
      .timer("metadata_filter_duration_seconds", "tenant", "acme", "pool", "bi")
      .count() shouldBe 1L

  "StatementInstruments.recordProtectedWrite" should "count allow/deny by label and time the check" in:
    val reg = new SimpleMeterRegistry()
    val si  = new StatementInstruments(reg)
    si.recordProtectedWrite("acme", "bi", "allow")
    si.recordProtectedWrite("acme", "bi", "deny")
    si.recordProtectedWrite("acme", "bi", "deny")
    si.recordProtectedWriteDuration("acme", "bi", 3L)
    reg
      .counter("protected_write_checks_total", "tenant", "acme", "pool", "bi", "outcome", "allow")
      .count() shouldBe 1.0
    reg
      .counter("protected_write_checks_total", "tenant", "acme", "pool", "bi", "outcome", "deny")
      .count() shouldBe 2.0
    reg
      .timer("protected_write_check_duration_seconds", "tenant", "acme", "pool", "bi")
      .count() shouldBe 1L
