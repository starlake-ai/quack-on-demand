package ai.starlake.quack.observability.metrics

import ai.starlake.quack.edge.SessionRegistry
import ai.starlake.quack.edge.adapter.{
  EngineStats,
  EngineStatsTracker,
  NodeAuthzStats,
  NodeAuthzStatsTracker,
  NodeLoadTracker
}
import ai.starlake.quack.model.{PoolKey, Role, RoleDistribution, RunningNode, StatementKind}
import ai.starlake.quack.ondemand.PoolState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant
import scala.jdk.CollectionConverters._

class MetricsBindingsSpec extends AnyFlatSpec with Matchers:

  private val poolKey                                   = PoolKey("acme", "acme_default", "sales")
  private def node(id: String, role: Role): RunningNode =
    RunningNode(id, poolKey, role, "127.0.0.1", 21900, "tok", Some(1L), None, Instant.EPOCH)

  /** Minimal PoolState snapshot factory -- the binder only reads the fields it needs (nodes list).
    */
  private def fakePool(nodes: List[RunningNode]): PoolState =
    PoolState(poolKey, nodes, RoleDistribution(0, 0, 0), Map.empty, Map.empty)

  // SessionRegistry needs at least one open() call to emit a non-zero gauge.
  private def fakeSessions(count: Int, txOpen: Int): SessionRegistry =
    val s = new SessionRegistry
    (1 to count).foreach { i =>
      s.open(s"conn-$i", "u", poolKey)
      if i <= txOpen then s.onStatement(s"conn-$i", StatementKind.Begin, "wo1")
    }
    s

  /** A single manager, or the HA leader: the replica that exports the node authz counters. */
  private val Leader: () => Boolean = () => true

  "MetricsBindings" should "expose node_healthy/draining/in_flight/ewma per (tenant,pool,nodeId,role)" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    val pools   = () => List(fakePool(List(node("n1", Role.ReadOnly))))
    val mb      = new MetricsBindings(
      reg,
      tracker,
      fakeSessions(0, 0),
      pools,
      new NodeAuthzStatsTracker,
      Leader
    )
    tracker.onStart("n1"); tracker.setHealthy("n1", true); tracker.setDraining("n1", false)
    mb.refresh()
    reg.find("node_healthy").tag("node_id", "n1").gauge.value() shouldBe 1.0
    reg.find("node_draining").tag("node_id", "n1").gauge.value() shouldBe 0.0
    reg.find("node_in_flight").tag("node_id", "n1").gauge.value() shouldBe 1.0

  it should "reflect tracker changes between refreshes" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    val pools   = () => List(fakePool(List(node("n1", Role.ReadOnly))))
    val mb      = new MetricsBindings(
      reg,
      tracker,
      fakeSessions(0, 0),
      pools,
      new NodeAuthzStatsTracker,
      Leader
    )
    tracker.onStart("n1"); mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n1").gauge.value() shouldBe 1.0
    tracker.onStart("n1"); tracker.onStart("n1"); mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n1").gauge.value() shouldBe 3.0

  it should "drop a node series when the pool no longer lists it" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    var current = List(fakePool(List(node("n1", Role.ReadOnly), node("n2", Role.Dual))))
    val mb      = new MetricsBindings(
      reg,
      tracker,
      fakeSessions(0, 0),
      () => current,
      new NodeAuthzStatsTracker,
      Leader
    )
    tracker.onStart("n1"); tracker.onStart("n2")
    mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n1").gauge should not be null
    reg.find("node_in_flight").tag("node_id", "n2").gauge should not be null
    current = List(fakePool(List(node("n1", Role.ReadOnly))))
    mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n2").gauge shouldBe null

  it should "expose pool_nodes by (tenant,pool,role)" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    val pools   = () =>
      List(
        fakePool(List(node("n1", Role.ReadOnly), node("n2", Role.ReadOnly), node("n3", Role.Dual)))
      )
    val mb = new MetricsBindings(
      reg,
      tracker,
      fakeSessions(0, 0),
      pools,
      new NodeAuthzStatsTracker,
      Leader
    )
    mb.refresh()
    reg.find("pool_nodes").tag("role", "ReadOnly").gauge.value() shouldBe 2.0
    reg.find("pool_nodes").tag("role", "Dual").gauge.value() shouldBe 1.0

  it should "expose flightsql_sessions_active + flightsql_sessions_in_transaction" in:
    val reg = new SimpleMeterRegistry()
    val mb  = new MetricsBindings(
      reg,
      new NodeLoadTracker,
      fakeSessions(3, 1),
      () => Nil,
      new NodeAuthzStatsTracker,
      Leader
    )
    mb.refresh()
    reg.find("flightsql_sessions_active").gauge.value() shouldBe 3.0
    reg.find("flightsql_sessions_in_transaction").gauge.value() shouldBe 1.0

  it should "expose node_duckdb_* gauges only for nodes with a scraped sample" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    val engine  = new EngineStatsTracker
    val pools   = () => List(fakePool(List(node("n1", Role.ReadOnly), node("n2", Role.Dual))))
    val mb      = new MetricsBindings(
      reg,
      tracker,
      fakeSessions(0, 0),
      pools,
      new NodeAuthzStatsTracker,
      Leader,
      engine
    )
    engine.update("n1", EngineStats(1024L, 2048L, 3L, 4096L))
    mb.refresh()
    reg.find("node_duckdb_memory_used_bytes").tag("node_id", "n1").gauge.value() shouldBe 1024.0
    reg.find("node_duckdb_temp_storage_bytes").tag("node_id", "n1").gauge.value() shouldBe 2048.0
    reg.find("node_duckdb_spill_files").tag("node_id", "n1").gauge.value() shouldBe 3.0
    reg.find("node_duckdb_spill_bytes").tag("node_id", "n1").gauge.value() shouldBe 4096.0
    // n2 never scraped -> no row at all (not a zero).
    reg.find("node_duckdb_memory_used_bytes").tag("node_id", "n2").gauge shouldBe null

  it should "refresh node_duckdb_* rows with the latest sample" in:
    val reg    = new SimpleMeterRegistry()
    val engine = new EngineStatsTracker
    val pools  = () => List(fakePool(List(node("n1", Role.ReadOnly))))
    val mb     = new MetricsBindings(
      reg,
      new NodeLoadTracker,
      fakeSessions(0, 0),
      pools,
      new NodeAuthzStatsTracker,
      Leader,
      engine
    )
    engine.update("n1", EngineStats(10L, 0L, 0L, 0L))
    mb.refresh()
    reg.find("node_duckdb_memory_used_bytes").tag("node_id", "n1").gauge.value() shouldBe 10.0
    engine.update("n1", EngineStats(999L, 1L, 2L, 3L))
    mb.refresh()
    reg.find("node_duckdb_memory_used_bytes").tag("node_id", "n1").gauge.value() shouldBe 999.0
    reg.find("node_duckdb_spill_bytes").tag("node_id", "n1").gauge.value() shouldBe 3.0

  it should "export qod_node_authz_*_total counters for the nodes the probe has read" in:
    val reg     = new SimpleMeterRegistry()
    val stats   = new NodeAuthzStatsTracker
    var current = List(fakePool(List(node("n1", Role.ReadOnly), node("n2", Role.Dual))))
    val mb      = new MetricsBindings(
      reg,
      new NodeLoadTracker,
      fakeSessions(0, 0),
      () => current,
      stats,
      Leader
    )
    stats.update("n1", NodeAuthzStats(5L, 2L, 3L, 0L, 1L, 4L, 9L))
    mb.refresh()
    def counter(name: String, id: String) =
      reg
        .find(s"qod_node_authz_${name}_total")
        .tags("tenant", "acme", "pool", "sales", "node", id)
        .functionCounter()
    NodeAuthzStats.Counters.foreach(c => withClue(c)(counter(c, "n1") should not be null))
    counter("plan_cache_hits", "n1").count() shouldBe 5.0
    counter("screen_calls", "n1").count() shouldBe 9.0
    // n2 never read: no series at all, not a zero.
    counter("plan_cache_hits", "n2") shouldBe null
    // The counters read the tracker's totals live, a node restart included.
    stats.update("n1", NodeAuthzStats(8L, 2L, 3L, 0L, 1L, 4L, 12L))
    counter("plan_cache_hits", "n1").count() shouldBe 8.0
    stats.update("n1", NodeAuthzStats(1L, 0L, 0L, 0L, 0L, 0L, 2L))
    counter("plan_cache_hits", "n1").count() shouldBe 9.0
    // A node the pools no longer list loses its series and its totals.
    current = List(fakePool(List(node("n2", Role.Dual))))
    mb.refresh()
    counter("plan_cache_hits", "n1") shouldBe null
    stats.total("n1") shouldBe None

  // Every replica probes every node, so each would export the same node's counters: only the HA
  // leader does (a single manager is always the leader), and a replica that stops leading stops
  // exporting at its next refresh.
  it should "export qod_node_authz_* only while this replica leads" in:
    val reg   = new SimpleMeterRegistry()
    val stats = new NodeAuthzStatsTracker
    val mb    = new MetricsBindings(
      reg,
      new NodeLoadTracker,
      fakeSessions(0, 0),
      () => List(fakePool(List(node("n1", Role.ReadOnly)))),
      stats,
      () => false
    )
    stats.update("n1", NodeAuthzStats(5L, 2L, 3L, 0L, 1L, 4L, 9L))
    mb.refresh()
    reg.getMeters.asScala.map(_.getId.getName).filter(_.startsWith("qod_node_authz_")) shouldBe
      empty
    // A follower keeps no totals either: a reading it took is dropped at its refresh.
    stats.total("n1") shouldBe None

  it should "remove the meters and totals at the next refresh once this replica stops leading" in:
    val reg    = new SimpleMeterRegistry()
    val stats  = new NodeAuthzStatsTracker
    val leader = new java.util.concurrent.atomic.AtomicBoolean(true)
    val mb     = new MetricsBindings(
      reg,
      new NodeLoadTracker,
      fakeSessions(0, 0),
      () => List(fakePool(List(node("n1", Role.ReadOnly)))),
      stats,
      () => leader.get
    )
    stats.update("n1", NodeAuthzStats(5L, 2L, 3L, 0L, 1L, 4L, 9L))
    mb.refresh()
    reg.find("qod_node_authz_plan_cache_hits_total").functionCounter().count() shouldBe 5.0
    leader.set(false)
    mb.refresh()
    reg.getMeters.asScala.map(_.getId.getName).filter(_.startsWith("qod_node_authz_")) shouldBe
      empty
    stats.total("n1") shouldBe None

  it should "register from the current reading once this replica becomes leader" in:
    val reg    = new SimpleMeterRegistry()
    val stats  = new NodeAuthzStatsTracker
    val leader = new java.util.concurrent.atomic.AtomicBoolean(false)
    val mb     = new MetricsBindings(
      reg,
      new NodeLoadTracker,
      fakeSessions(0, 0),
      () => List(fakePool(List(node("n1", Role.ReadOnly)))),
      stats,
      () => leader.get
    )
    stats.update("n1", NodeAuthzStats(5L, 2L, 3L, 0L, 1L, 4L, 9L))
    mb.refresh()
    // The node keeps counting while this replica follows; the reading after promotion is the
    // node's own count since it started, taken whole.
    stats.update("n1", NodeAuthzStats(7L, 2L, 4L, 0L, 1L, 4L, 15L))
    leader.set(true)
    mb.refresh()
    reg.find("qod_node_authz_plan_cache_hits_total").functionCounter().count() shouldBe 7.0
    reg.find("qod_node_authz_screen_calls_total").functionCounter().count() shouldBe 15.0
