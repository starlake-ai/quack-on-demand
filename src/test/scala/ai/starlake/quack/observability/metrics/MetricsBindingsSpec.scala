package ai.starlake.quack.observability.metrics

import ai.starlake.quack.edge.SessionRegistry
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, Role, RoleDistribution, RunningNode, StatementKind}
import ai.starlake.quack.ondemand.PoolState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class MetricsBindingsSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "sales")
  private def node(id: String, role: Role): RunningNode =
    RunningNode(id, poolKey, role, "127.0.0.1", 21900, "tok", Some(1L), None, Instant.EPOCH)

  /** Minimal PoolState snapshot factory -- the binder only reads the fields it
    * needs (nodes list). */
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

  "MetricsBindings" should "expose node_healthy/draining/in_flight/ewma per (tenant,pool,nodeId,role)" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    val pools   = () => List(fakePool(List(node("n1", Role.ReadOnly))))
    val mb      = new MetricsBindings(reg, tracker, fakeSessions(0, 0), pools)
    tracker.onStart("n1"); tracker.setHealthy("n1", true); tracker.setDraining("n1", false)
    mb.refresh()
    reg.find("node_healthy").tag("node_id", "n1").gauge.value()     shouldBe 1.0
    reg.find("node_draining").tag("node_id", "n1").gauge.value()    shouldBe 0.0
    reg.find("node_in_flight").tag("node_id", "n1").gauge.value()   shouldBe 1.0

  it should "reflect tracker changes between refreshes" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    val pools   = () => List(fakePool(List(node("n1", Role.ReadOnly))))
    val mb      = new MetricsBindings(reg, tracker, fakeSessions(0, 0), pools)
    tracker.onStart("n1"); mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n1").gauge.value() shouldBe 1.0
    tracker.onStart("n1"); tracker.onStart("n1"); mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n1").gauge.value() shouldBe 3.0

  it should "drop a node series when the pool no longer lists it" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    var current = List(fakePool(List(node("n1", Role.ReadOnly), node("n2", Role.Dual))))
    val mb      = new MetricsBindings(reg, tracker, fakeSessions(0, 0), () => current)
    tracker.onStart("n1"); tracker.onStart("n2")
    mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n1").gauge should not be (null)
    reg.find("node_in_flight").tag("node_id", "n2").gauge should not be (null)
    current = List(fakePool(List(node("n1", Role.ReadOnly))))
    mb.refresh()
    reg.find("node_in_flight").tag("node_id", "n2").gauge shouldBe null

  it should "expose pool_nodes by (tenant,pool,role)" in:
    val reg     = new SimpleMeterRegistry()
    val tracker = new NodeLoadTracker
    val pools   = () => List(fakePool(List(node("n1", Role.ReadOnly), node("n2", Role.ReadOnly), node("n3", Role.Dual))))
    val mb      = new MetricsBindings(reg, tracker, fakeSessions(0, 0), pools)
    mb.refresh()
    reg.find("pool_nodes").tag("role", "ReadOnly").gauge.value() shouldBe 2.0
    reg.find("pool_nodes").tag("role", "Dual").gauge.value()     shouldBe 1.0

  it should "expose flightsql_sessions_active + flightsql_sessions_in_transaction" in:
    val reg = new SimpleMeterRegistry()
    val mb  = new MetricsBindings(reg, new NodeLoadTracker, fakeSessions(3, 1), () => Nil)
    mb.refresh()
    reg.find("flightsql_sessions_active").gauge.value()        shouldBe 3.0
    reg.find("flightsql_sessions_in_transaction").gauge.value() shouldBe 1.0