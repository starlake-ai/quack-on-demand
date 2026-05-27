package ai.starlake.quack.route

import ai.starlake.quack.model.{PoolKey, Role, RunningNode, StatementKind}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class RouterSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "sales")
  private def node(id: String, role: Role): RunningNode =
    RunningNode(id, poolKey, role, "127.0.0.1", 21900, "tok", Some(1L), None, Instant.EPOCH)

  private val ro1 = node("ro1", Role.ReadOnly)
  private val ro2 = node("ro2", Role.ReadOnly)
  private val wo1 = node("wo1", Role.WriteOnly)
  private val d1  = node("d1",  Role.Dual)

  private def snap(nodes: List[RunningNode], load: Map[String, NodeLoad] = Map.empty): PoolSnapshot =
    PoolSnapshot(poolKey, nodes, load.withDefaultValue(NodeLoad.empty))

  "Router" should "honor pinned node regardless of role/load" in:
    val s = snap(List(ro1, wo1))
    Router.pick(s, StatementKind.Select, pinned = Some("wo1")) shouldBe
      RoutingDecision.Use("wo1")

  it should "reject pinned node not in snapshot" in:
    val s = snap(List(ro1))
    Router.pick(s, StatementKind.Select, pinned = Some("gone")) shouldBe
      RoutingDecision.PinnedNodeGone("gone")

  it should "pick the least-loaded role-compatible node" in:
    val loads = Map("ro1" -> NodeLoad(inFlight = 3, ewmaMs = 100), "ro2" -> NodeLoad(2, 100))
    Router.pick(snap(List(ro1, ro2), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Use("ro2")

  it should "break in-flight ties by EWMA latency" in:
    val loads = Map("ro1" -> NodeLoad(2, 200), "ro2" -> NodeLoad(2, 50))
    Router.pick(snap(List(ro1, ro2), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Use("ro2")

  it should "fall back from RO to DUAL when RO is empty" in:
    Router.pick(snap(List(d1)), StatementKind.Select, None) shouldBe RoutingDecision.Use("d1")

  it should "route DML to WO or DUAL" in:
    Router.pick(snap(List(ro1, wo1)), StatementKind.Dml, None) shouldBe RoutingDecision.Use("wo1")
    Router.pick(snap(List(ro1, d1)),  StatementKind.Dml, None) shouldBe RoutingDecision.Use("d1")

  it should "return Unavailable when no node compatible" in:
    Router.pick(snap(List(ro1)), StatementKind.Dml, None) shouldBe
      RoutingDecision.Unavailable("no node with role WRITEONLY or DUAL")

  it should "return Unavailable on empty pool" in:
    Router.pick(snap(Nil), StatementKind.Select, None) shouldBe
      RoutingDecision.Unavailable("pool is empty")

  it should "exclude draining and unhealthy nodes" in:
    val s = snap(List(ro1, ro2), Map(
      "ro1" -> NodeLoad(0, 0, draining = true),
      "ro2" -> NodeLoad(0, 0, healthy  = false)
    ))
    Router.pick(s, StatementKind.Select, None) shouldBe
      RoutingDecision.Unavailable("no node with role READONLY or DUAL")

  it should "treat maxConcurrent = 0 as unlimited" in:
    val n = ro1.copy(maxConcurrent = 0)
    val loads = Map("ro1" -> NodeLoad(inFlight = 9999, ewmaMs = 50))
    Router.pick(snap(List(n), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Use("ro1")

  it should "refuse a node at maxConcurrent capacity" in:
    val n = ro1.copy(maxConcurrent = 2)
    val loads = Map("ro1" -> NodeLoad(inFlight = 2, ewmaMs = 50))
    Router.pick(snap(List(n), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Unavailable("all compatible nodes at capacity")

  it should "still route to a node below cap when others are full" in:
    val full   = ro1.copy(maxConcurrent = 1)
    val open   = ro2.copy(maxConcurrent = 4)
    val loads  = Map(
      "ro1" -> NodeLoad(inFlight = 1, ewmaMs = 50),
      "ro2" -> NodeLoad(inFlight = 0, ewmaMs = 50)
    )
    Router.pick(snap(List(full, open), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Use("ro2")

  it should "ignore cap for a pinned node (in-tx session continues)" in:
    val n = ro1.copy(maxConcurrent = 1)
    val loads = Map("ro1" -> NodeLoad(inFlight = 1, ewmaMs = 50))
    Router.pick(snap(List(n), loads), StatementKind.Select, pinned = Some("ro1")) shouldBe
      RoutingDecision.Use("ro1")