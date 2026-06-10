package ai.starlake.quack.observability.metrics

import ai.starlake.quack.edge.SessionRegistry
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.PoolState
import io.micrometer.core.instrument.{MeterRegistry, MultiGauge, Tags}

import scala.jdk.CollectionConverters._

/** Wires NodeLoadTracker / PoolSupervisor / SessionRegistry into the Micrometer registry as
  * read-on-scrape gauges. `refresh()` is called from MetricsEndpoint before each scrape (and once
  * at startup); each call updates the row set of every MultiGauge so dynamic membership (createPool
  * / scale / stopPool) is reflected automatically without leaving stale series behind.
  */
final class MetricsBindings(
    registry: MeterRegistry,
    tracker: NodeLoadTracker,
    sessions: SessionRegistry,
    listPools: () => List[PoolState]
):

  // Per-node MultiGauges. Each row carries (tenant, pool, node_id, role) tags.
  private val nodeHealthy  = MultiGauge.builder("node_healthy").register(registry)
  private val nodeDraining = MultiGauge.builder("node_draining").register(registry)
  private val nodeInFlight = MultiGauge.builder("node_in_flight").register(registry)
  private val nodeEwma     = MultiGauge.builder("node_ewma_latency_seconds").register(registry)
  private val poolNodes    = MultiGauge.builder("pool_nodes").register(registry)

  // Free JVM metrics -- operators expect these and Micrometer ships them.
  new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(registry)
  new io.micrometer.core.instrument.binder.jvm.JvmGcMetrics().bindTo(registry)
  new io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics().bindTo(registry)
  new io.micrometer.core.instrument.binder.system.ProcessorMetrics().bindTo(registry)
  new io.micrometer.core.instrument.binder.system.UptimeMetrics().bindTo(registry)

  // Singletons. Registered once; values pulled live via the Supplier.
  registry.gauge(
    "flightsql_sessions_active",
    java.util.Collections.emptyList[io.micrometer.core.instrument.Tag](),
    sessions,
    (s: SessionRegistry) => s.size.toDouble
  )
  registry.gauge(
    "flightsql_sessions_in_transaction",
    java.util.Collections.emptyList[io.micrometer.core.instrument.Tag](),
    sessions,
    (s: SessionRegistry) => s.inTransactionCount.toDouble
  )

  /** Re-derive the per-node + per-pool row sets from the current snapshot. */
  def refresh(): Unit =
    val pools = listPools()
    val nodes = pools.flatMap(p => p.nodes.map(n => (p.key.tenant, p.key.pool, n)))

    val healthyRows = nodes.map { case (t, p, n) =>
      val l = tracker.snapshot(n.nodeId)
      MultiGauge.Row.of(
        Tags.of("tenant", t, "pool", p, "node_id", n.nodeId, "role", n.role.toString),
        if l.healthy then 1.0 else 0.0
      )
    }
    val drainingRows = nodes.map { case (t, p, n) =>
      val l = tracker.snapshot(n.nodeId)
      MultiGauge.Row.of(
        Tags.of("tenant", t, "pool", p, "node_id", n.nodeId, "role", n.role.toString),
        if l.draining then 1.0 else 0.0
      )
    }
    val inFlightRows = nodes.map { case (t, p, n) =>
      val l = tracker.snapshot(n.nodeId)
      MultiGauge.Row.of(
        Tags.of("tenant", t, "pool", p, "node_id", n.nodeId, "role", n.role.toString),
        l.inFlight.toDouble
      )
    }
    val ewmaRows = nodes.map { case (t, p, n) =>
      val l = tracker.snapshot(n.nodeId)
      MultiGauge.Row.of(
        Tags.of("tenant", t, "pool", p, "node_id", n.nodeId, "role", n.role.toString),
        l.ewmaMs / 1000.0
      )
    }

    // pool_nodes: one row per (tenant, pool, role) with the count.
    val poolRoleCounts = pools.flatMap { p =>
      p.nodes.groupBy(_.role).map { case (role, ns) =>
        MultiGauge.Row.of(
          Tags.of("tenant", p.key.tenant, "pool", p.key.pool, "role", role.toString),
          ns.size.toDouble
        )
      }
    }

    // `overwrite=true` replaces the row set wholesale -- old nodes vanish.
    nodeHealthy.register(healthyRows.asJava, true)
    nodeDraining.register(drainingRows.asJava, true)
    nodeInFlight.register(inFlightRows.asJava, true)
    nodeEwma.register(ewmaRows.asJava, true)
    poolNodes.register(poolRoleCounts.asJava, true)
