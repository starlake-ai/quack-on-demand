package ai.starlake.quack.observability.metrics

import ai.starlake.quack.edge.SessionRegistry
import ai.starlake.quack.edge.adapter.{
  EngineStatsTracker,
  NodeAuthzStats,
  NodeAuthzStatsTracker,
  NodeLoadTracker
}
import ai.starlake.quack.model.RunningNode
import ai.starlake.quack.ondemand.PoolState
import io.micrometer.core.instrument.{FunctionCounter, MeterRegistry, MultiGauge, Tags}

import scala.jdk.CollectionConverters._

/** Wires NodeLoadTracker / PoolSupervisor / SessionRegistry into the Micrometer registry as
  * read-on-scrape gauges. `refresh()` is called from MetricsEndpoint before each scrape (and once
  * at startup); each call updates the row set of every MultiGauge so dynamic membership (createPool
  * / scale / stopPool) is reflected automatically without leaving stale series behind. qod-node's
  * authorization counters (NodeAuthzStatsTracker, from the probe's reads) are exported as counters,
  * one series set per node the probe has read, by the HA leader only (`isLeader`): every replica
  * probes every node, so N replicas exporting would multiply each node's counts by N. Required, so
  * no construction exports from every replica by omission; a single manager passes always-leader.
  */
final class MetricsBindings(
    registry: MeterRegistry,
    tracker: NodeLoadTracker,
    sessions: SessionRegistry,
    listPools: () => List[PoolState],
    authzStats: NodeAuthzStatsTracker,
    isLeader: () => Boolean,
    engineStats: EngineStatsTracker = new EngineStatsTracker
):

  // Per-node MultiGauges. Each row carries (tenant, pool, node_id, role) tags.
  private val nodeHealthy  = MultiGauge.builder("node_healthy").register(registry)
  private val nodeDraining = MultiGauge.builder("node_draining").register(registry)
  private val nodeInFlight = MultiGauge.builder("node_in_flight").register(registry)
  private val nodeEwma     = MultiGauge.builder("node_ewma_latency_seconds").register(registry)
  private val poolNodes    = MultiGauge.builder("pool_nodes").register(registry)

  // DuckDB engine internals per node, scraped by the HealthProbe (EngineStats). Nodes without a
  // successful sample yet publish no row at all rather than a misleading zero.
  private val nodeDuckMem  = MultiGauge.builder("node_duckdb_memory_used_bytes").register(registry)
  private val nodeDuckTemp =
    MultiGauge.builder("node_duckdb_temp_storage_bytes").register(registry)
  private val nodeDuckSpillFiles =
    MultiGauge.builder("node_duckdb_spill_files").register(registry)
  private val nodeDuckSpillBytes =
    MultiGauge.builder("node_duckdb_spill_bytes").register(registry)

  // qod-node's authorization counters per node (decision 26): one FunctionCounter per counter over
  // the tracker's totals, registered once the probe has read the node and removed with it.
  private val authzCounters = scala.collection.mutable.Map.empty[String, List[FunctionCounter]]

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

    // Engine-internal rows exist only for nodes with a scraped sample.
    val sampled = nodes.flatMap { case (t, p, n) =>
      engineStats.snapshot(n.nodeId).map { s =>
        (Tags.of("tenant", t, "pool", p, "node_id", n.nodeId, "role", n.role.toString), s)
      }
    }
    val duckMemRows  = sampled.map((tags, s) => MultiGauge.Row.of(tags, s.memoryUsedBytes.toDouble))
    val duckTempRows =
      sampled.map((tags, s) => MultiGauge.Row.of(tags, s.tempStorageBytes.toDouble))
    val spillFileRows = sampled.map((tags, s) => MultiGauge.Row.of(tags, s.spillFiles.toDouble))
    val spillByteRows = sampled.map((tags, s) => MultiGauge.Row.of(tags, s.spillBytes.toDouble))

    // `overwrite=true` replaces the row set wholesale -- old nodes vanish.
    nodeHealthy.register(healthyRows.asJava, true)
    nodeDraining.register(drainingRows.asJava, true)
    nodeInFlight.register(inFlightRows.asJava, true)
    nodeEwma.register(ewmaRows.asJava, true)
    poolNodes.register(poolRoleCounts.asJava, true)
    nodeDuckMem.register(duckMemRows.asJava, true)
    nodeDuckTemp.register(duckTempRows.asJava, true)
    nodeDuckSpillFiles.register(spillFileRows.asJava, true)
    nodeDuckSpillBytes.register(spillByteRows.asJava, true)
    refreshAuthzCounters(nodes)

  /** `qod_node_authz_<counter>_total{tenant,pool,node}` for every listed node the probe has read,
    * while this replica leads: counters, since the tracker's totals only grow. A node no longer
    * listed loses its series and its totals. A replica that does not lead (a follower, or a leader
    * demoted since the last refresh) removes every series and forgets every total, so it exports
    * none, and once it leads its first export is the node's current reading, taken whole (the
    * tracker's first reading of a node). Synchronized: two scrapes may refresh at once.
    */
  private def refreshAuthzCounters(nodes: List[(String, String, RunningNode)]): Unit =
    synchronized {
      val leads = isLeader()
      val live  = if leads then nodes.map(_._3.nodeId).toSet else Set.empty[String]
      authzCounters.keySet.toList.filterNot(live).foreach { id =>
        authzCounters.remove(id).foreach(_.foreach(c => registry.remove(c)))
      }
      authzStats.retain(live)
      if leads then
        nodes.foreach { case (t, p, n) =>
          val id = n.nodeId
          if !authzCounters.contains(id) && authzStats.total(id).isDefined then
            val counters = NodeAuthzStats.Counters.zipWithIndex.map { (name, i) =>
              FunctionCounter
                .builder(
                  s"qod_node_authz_${name}_total",
                  authzStats,
                  (s: NodeAuthzStatsTracker) => s.total(id).fold(0.0)(_.values(i).toDouble)
                )
                .tags("tenant", t, "pool", p, "node", id)
                .register(registry)
            }
            authzCounters.update(id, counters)
        }
    }
