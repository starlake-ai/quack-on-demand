package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.{DistributionSummary, MeterRegistry}

/** The decisions the decide endpoint takes for nodes (decision 26):
  * `qod_authz_decisions_total{tenant,kind,outcome,mode}` (kind = plan | screen, outcome = allow |
  * refuse | unavailable, mode = qod | opa) and `qod_authz_decide_duration_ms{kind}`, how long each
  * took. The duration is a distribution summary in milliseconds with fixed buckets, not a timer: a
  * timer is exported in seconds, under `<name>_seconds`, where dashboards read this name as is
  * (`_bucket`, `_count`, `_sum`).
  */
final class AuthzInstruments(registry: MeterRegistry):
  private val durations =
    new java.util.concurrent.ConcurrentHashMap[String, DistributionSummary]()

  def record(
      tenant: String,
      kind: String,
      outcome: String,
      mode: String,
      durationMs: Double
  ): Unit =
    registry
      .counter(
        "qod_authz_decisions_total",
        "tenant",
        tenant,
        "kind",
        kind,
        "outcome",
        outcome,
        "mode",
        mode
      )
      .increment()
    durations
      .computeIfAbsent(
        kind,
        k =>
          DistributionSummary
            .builder("qod_authz_decide_duration_ms")
            .tag("kind", k)
            .serviceLevelObjectives(AuthzInstruments.BucketsMs*)
            .register(registry)
      )
      .record(durationMs)

object AuthzInstruments:

  /** The duration buckets' upper bounds, in milliseconds: a grant decision takes well under one, an
    * OPA tenant's up to the OPA timeout (2000 ms by default).
    */
  val BucketsMs: Seq[Double] =
    Seq(0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0)

  /** No-op twin backed by an empty CompositeMeterRegistry, as OpaInstruments.noop. */
  val noop: AuthzInstruments =
    new AuthzInstruments(new io.micrometer.core.instrument.composite.CompositeMeterRegistry())
