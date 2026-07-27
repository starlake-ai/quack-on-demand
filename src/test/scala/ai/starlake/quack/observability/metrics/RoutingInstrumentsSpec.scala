package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoutingInstrumentsSpec extends AnyFlatSpec with Matchers:

  "RoutingInstruments" should "publish locality counters split by result" in:
    val reg = new SimpleMeterRegistry
    val ri  = new RoutingInstruments(reg)
    ri.recordLocality("acme", "sales", newTables = 2, repeatTables = 3, stays = 1, switches = 2)
    reg
      .counter("routing_tables_total", "tenant", "acme", "pool", "sales", "result", "new")
      .count() shouldBe 2.0
    reg
      .counter("routing_tables_total", "tenant", "acme", "pool", "sales", "result", "repeat")
      .count() shouldBe 3.0
    reg
      .counter("routing_placements_total", "tenant", "acme", "pool", "sales", "result", "stay")
      .count() shouldBe 1.0
    reg
      .counter("routing_placements_total", "tenant", "acme", "pool", "sales", "result", "switch")
      .count() shouldBe 2.0

  it should "publish decision outcomes" in:
    val reg = new SimpleMeterRegistry
    val ri  = new RoutingInstruments(reg)
    ri.recordDecision("acme", "sales", "sticky-fresh")
    reg
      .counter(
        "routing_decisions_total",
        "tenant",
        "acme",
        "pool",
        "sales",
        "outcome",
        "sticky-fresh"
      )
      .count() shouldBe 1.0

  it should "publish the load ratio summary" in:
    val reg = new SimpleMeterRegistry
    val ri  = new RoutingInstruments(reg)
    ri.recordLoadRatio("acme", "sales", 1.5)
    reg.summary("routing_load_ratio", "tenant", "acme", "pool", "sales").count() shouldBe 1L

  "RoutingInstruments.noop" should "swallow records silently" in:
    noException should be thrownBy
      RoutingInstruments.noop.recordLocality("t", "p", 1, 1, 1, 1)
