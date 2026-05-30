package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.{MeterRegistry, Timer}
import java.time.Duration

/** Counter + timer for routed FlightSQL statements. One instance per manager;
  * called from FlightSqlRouter's post-process branch. The `status` label
  * mirrors the same enum already written to StatementHistoryStore so dashboards
  * and history queries agree.
  *
  * The timer publishes a percentile histogram with SLO buckets at 1ms, 10ms,
  * 100ms, 1s, 10s — those become `_bucket` series in Prometheus and roll up
  * cleanly in CloudWatch / Stackdriver / Azure as count+sum statistics. */
final class StatementInstruments(registry: MeterRegistry):

  private val timerCache = new java.util.concurrent.ConcurrentHashMap[(String, String, String), Timer]()

  private def resolveTimer(tenant: String, pool: String, status: String): Timer =
    timerCache.computeIfAbsent(
      (tenant, pool, status),
      _ => Timer.builder("statement_duration_seconds")
        .tag("tenant", tenant)
        .tag("pool",   pool)
        .tag("status", status)
        .publishPercentileHistogram()
        .serviceLevelObjectives(
          Duration.ofMillis(1),
          Duration.ofMillis(10),
          Duration.ofMillis(100),
          Duration.ofSeconds(1),
          Duration.ofSeconds(10)
        )
        .register(registry)
    )

  /** Record one completed statement: increment the counter, observe the timer. */
  def record(tenant: String, pool: String, status: String, durationMs: Long): Unit =
    registry.counter("statements_total",
        "tenant", tenant, "pool", pool, "status", status
      ).increment()
    resolveTimer(tenant, pool, status).record(Duration.ofMillis(durationMs))
