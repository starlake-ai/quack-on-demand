package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MaintenanceMetricsSpec extends AnyFlatSpec with Matchers:

  "MaintenanceMetrics.Micrometer" should "increment qod_maint_runs_total tagged by tenant/tenant_db/result" in:
    val reg = new SimpleMeterRegistry()
    val m   = new MaintenanceMetrics.Micrometer(reg)
    m.recordRun("acme", "acme_tpch1", "succeeded", 12.5, 1024L, 3, 2)
    reg
      .find("qod_maint_runs_total")
      .tag("tenant", "acme")
      .tag("tenant_db", "acme_tpch1")
      .tag("result", "succeeded")
      .counter
      .count() shouldBe 1.0

  it should "accumulate qod_maint_bytes_reclaimed_total across runs" in:
    val reg = new SimpleMeterRegistry()
    val m   = new MaintenanceMetrics.Micrometer(reg)
    m.recordRun("acme", "acme_tpch1", "succeeded", 1.0, 500L, 0, 0)
    m.recordRun("acme", "acme_tpch1", "succeeded", 1.0, 250L, 0, 0)
    reg
      .find("qod_maint_bytes_reclaimed_total")
      .tag("tenant", "acme")
      .tag("tenant_db", "acme_tpch1")
      .counter
      .count() shouldBe 750.0

  it should "increment qod_maint_files_compacted_total and qod_maint_snapshots_expired_total" in:
    val reg = new SimpleMeterRegistry()
    val m   = new MaintenanceMetrics.Micrometer(reg)
    m.recordRun("acme", "acme_tpch1", "succeeded", 1.0, 0L, 4, 7)
    reg
      .find("qod_maint_files_compacted_total")
      .tag("tenant", "acme")
      .tag("tenant_db", "acme_tpch1")
      .counter
      .count() shouldBe 4.0
    reg
      .find("qod_maint_snapshots_expired_total")
      .tag("tenant", "acme")
      .tag("tenant_db", "acme_tpch1")
      .counter
      .count() shouldBe 7.0

  it should "record qod_maint_duration_seconds as a timer tagged by tenant/tenant_db" in:
    val reg = new SimpleMeterRegistry()
    val m   = new MaintenanceMetrics.Micrometer(reg)
    m.recordRun("acme", "acme_tpch1", "succeeded", 42.0, 0L, 0, 0)
    val timer = reg.find("qod_maint_duration_seconds").tag("tenant", "acme").timer
    timer.count() shouldBe 1
    timer.totalTime(java.util.concurrent.TimeUnit.SECONDS) shouldBe 42.0 +- 0.01

  "MaintenanceMetrics.noop" should "not throw and record nothing observable" in:
    noException should be thrownBy
      MaintenanceMetrics.noop.recordRun("acme", "acme_tpch1", "failed", 1.0, 0L, 0, 0)
