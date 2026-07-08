package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.{MeterRegistry, Timer}

import java.time.Duration

/** Metrics hook for one finished managed-maintenance run (EPIC Spec 09). Kept as a trait (rather
  * than a concrete class injected everywhere) so [[MaintenanceRunner]] and its spec can depend on
  * [[MaintenanceMetrics.noop]] without wiring a real [[MeterRegistry]] through every test fixture.
  *
  * `result` mirrors [[ai.starlake.quack.model.MaintenanceRun.status]] on completion: `succeeded` |
  * `failed` | `partial`.
  */
trait MaintenanceMetrics:
  def recordRun(
      tenant: String,
      tenantDb: String,
      result: String,
      durationSeconds: Double,
      bytesReclaimed: Long,
      filesCompacted: Int,
      snapshotsExpired: Int
  ): Unit

object MaintenanceMetrics:

  /** Silent fallback -- used by [[MaintenanceRunner]]'s default constructor param so
    * `MaintenanceRunnerSpec` (Task 5) never needs to know metrics exist.
    */
  val noop: MaintenanceMetrics =
    new MaintenanceMetrics:
      def recordRun(
          tenant: String,
          tenantDb: String,
          result: String,
          durationSeconds: Double,
          bytesReclaimed: Long,
          filesCompacted: Int,
          snapshotsExpired: Int
      ): Unit = ()

  /** Real implementation, wired in [[ai.starlake.quack.Main]] from the shared Micrometer composite
    * registry -- same registry [[StatementInstruments]] writes to, so every `qod_maint_*` series
    * shows up on the same `/metrics` scrape. Mirrors [[StatementInstruments]]'s idiom: counters via
    * `registry.counter(name, "k", v, ...).increment()`, a cached [[Timer]] per label tuple for the
    * duration histogram.
    */
  final class Micrometer(registry: MeterRegistry) extends MaintenanceMetrics:

    private val timerCache =
      new java.util.concurrent.ConcurrentHashMap[(String, String), Timer]()

    private def resolveTimer(tenant: String, tenantDb: String): Timer =
      timerCache.computeIfAbsent(
        (tenant, tenantDb),
        _ =>
          Timer
            .builder("qod_maint_duration_seconds")
            .tag("tenant", tenant)
            .tag("tenant_db", tenantDb)
            .publishPercentileHistogram()
            .serviceLevelObjectives(
              Duration.ofSeconds(1),
              Duration.ofSeconds(10),
              Duration.ofSeconds(60),
              Duration.ofMinutes(5),
              Duration.ofMinutes(30)
            )
            .register(registry)
      )

    def recordRun(
        tenant: String,
        tenantDb: String,
        result: String,
        durationSeconds: Double,
        bytesReclaimed: Long,
        filesCompacted: Int,
        snapshotsExpired: Int
    ): Unit =
      registry
        .counter(
          "qod_maint_runs_total",
          "tenant",
          tenant,
          "tenant_db",
          tenantDb,
          "result",
          result
        )
        .increment()
      registry
        .counter("qod_maint_bytes_reclaimed_total", "tenant", tenant, "tenant_db", tenantDb)
        .increment(bytesReclaimed.toDouble)
      registry
        .counter("qod_maint_files_compacted_total", "tenant", tenant, "tenant_db", tenantDb)
        .increment(filesCompacted.toDouble)
      registry
        .counter("qod_maint_snapshots_expired_total", "tenant", tenant, "tenant_db", tenantDb)
        .increment(snapshotsExpired.toDouble)
      resolveTimer(tenant, tenantDb).record(Duration.ofNanos((durationSeconds * 1e9).round))
