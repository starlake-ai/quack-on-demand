package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.MeterRegistry

/** Counters for the cache-aware routing layer. One instance per manager, called from
  * FlightSqlRouter after each routed statement. `recordLocality` publishes the phase-0 baseline
  * (repeat/switch rates on ANY routing policy); `recordDecision` labels each placement decision
  * with the outcome vocabulary
  * `claim | sticky-fresh | sticky-stale | overflow-new-home | overflow-evict-home | pinned-sticky | pinned-move | no-refs-fallback | not-eligible | flag-off`;
  * `recordLoadRatio` observes chosen-node inFlight over pool average, which should stay under the
  * configured loadCapFactor.
  */
final class RoutingInstruments(registry: MeterRegistry):

  def recordLocality(
      tenant: String,
      pool: String,
      newTables: Int,
      repeatTables: Int,
      stays: Int,
      switches: Int
  ): Unit =
    def bump(name: String, result: String, n: Int): Unit =
      if n > 0 then
        registry
          .counter(name, "tenant", tenant, "pool", pool, "result", result)
          .increment(n.toDouble)
    bump("routing_tables_total", "new", newTables)
    bump("routing_tables_total", "repeat", repeatTables)
    bump("routing_placements_total", "stay", stays)
    bump("routing_placements_total", "switch", switches)

  def recordDecision(tenant: String, pool: String, outcome: String): Unit =
    registry
      .counter("routing_decisions_total", "tenant", tenant, "pool", pool, "outcome", outcome)
      .increment()

  def recordLoadRatio(tenant: String, pool: String, ratio: Double): Unit =
    registry.summary("routing_load_ratio", "tenant", tenant, "pool", pool).record(ratio)

object RoutingInstruments:

  /** No-op twin backed by an empty CompositeMeterRegistry, mirroring StatementInstruments.noop. */
  val noop: RoutingInstruments =
    new RoutingInstruments(new io.micrometer.core.instrument.composite.CompositeMeterRegistry())
