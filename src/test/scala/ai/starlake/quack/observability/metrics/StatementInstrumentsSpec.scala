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
    val timer = reg.timer("statement_duration_seconds", "tenant", "acme", "pool", "sales", "status", "ok")
    timer.count()                shouldBe 1L
    timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe (12.0 +- 1.0)

  it should "keep different status labels as distinct series" in:
    val reg = new SimpleMeterRegistry()
    val si  = new StatementInstruments(reg)
    si.record("acme", "sales", "ok", 5L)
    si.record("acme", "sales", "denied", 1L)
    si.record("acme", "sales", "ok", 7L)
    reg.counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok").count()     shouldBe 2.0
    reg.counter("statements_total", "tenant", "acme", "pool", "sales", "status", "denied").count() shouldBe 1.0