package ai.starlake.quack.route

import ai.starlake.quack.model.{PoolKey, Role, RunningNode, StatementKind}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class RouterSpec extends AnyFlatSpec with Matchers:

  private val poolKey                                   = PoolKey("acme", "acme_default", "sales")
  private def node(id: String, role: Role): RunningNode =
    RunningNode(id, poolKey, role, "127.0.0.1", 21900, "tok", Some(1L), None, Instant.EPOCH)

  private val ro1 = node("ro1", Role.ReadOnly)
  private val ro2 = node("ro2", Role.ReadOnly)
  private val wo1 = node("wo1", Role.WriteOnly)
  private val d1  = node("d1", Role.Dual)

  private def snap(
      nodes: List[RunningNode],
      load: Map[String, NodeLoad] = Map.empty
  ): PoolSnapshot =
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
    Router.pick(snap(List(ro1, d1)), StatementKind.Dml, None) shouldBe RoutingDecision.Use("d1")

  it should "return Unavailable when no node compatible" in:
    Router.pick(snap(List(ro1)), StatementKind.Dml, None) shouldBe
      RoutingDecision.Unavailable("no node with role WRITEONLY or DUAL")

  it should "return Unavailable on empty pool" in:
    Router.pick(snap(Nil), StatementKind.Select, None) shouldBe
      RoutingDecision.Unavailable("pool is empty")

  it should "exclude draining and unhealthy nodes" in:
    val s = snap(
      List(ro1, ro2),
      Map(
        "ro1" -> NodeLoad(0, 0, draining = true),
        "ro2" -> NodeLoad(0, 0, healthy = false)
      )
    )
    Router.pick(s, StatementKind.Select, None) shouldBe
      RoutingDecision.Unavailable("no node with role READONLY or DUAL")

  it should "treat maxConcurrent = 0 as unlimited" in:
    val n     = ro1.copy(maxConcurrent = 0)
    val loads = Map("ro1" -> NodeLoad(inFlight = 9999, ewmaMs = 50))
    Router.pick(snap(List(n), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Use("ro1")

  it should "refuse a node at maxConcurrent capacity" in:
    val n     = ro1.copy(maxConcurrent = 2)
    val loads = Map("ro1" -> NodeLoad(inFlight = 2, ewmaMs = 50))
    Router.pick(snap(List(n), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Unavailable("all compatible nodes at capacity")

  it should "still route to a node below cap when others are full" in:
    val full  = ro1.copy(maxConcurrent = 1)
    val open  = ro2.copy(maxConcurrent = 4)
    val loads = Map(
      "ro1" -> NodeLoad(inFlight = 1, ewmaMs = 50),
      "ro2" -> NodeLoad(inFlight = 0, ewmaMs = 50)
    )
    Router.pick(snap(List(full, open), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Use("ro2")

  it should "ignore cap for a pinned node (in-tx session continues)" in:
    val n     = ro1.copy(maxConcurrent = 1)
    val loads = Map("ro1" -> NodeLoad(inFlight = 1, ewmaMs = 50))
    Router.pick(snap(List(n), loads), StatementKind.Select, pinned = Some("ro1")) shouldBe
      RoutingDecision.Use("ro1")

  it should "never pick a quarantined node even when it is least loaded" in:
    val loads = Map(
      "ro1" -> NodeLoad(inFlight = 0, ewmaMs = 0.0, quarantined = true),
      "ro2" -> NodeLoad(inFlight = 5, ewmaMs = 500)
    )
    Router.pick(snap(List(ro1, ro2), loads), StatementKind.Select, None) shouldBe
      RoutingDecision.Use("ro2")

  it should "report unavailable when every compatible node is quarantined" in:
    val loads = Map("ro1" -> NodeLoad(inFlight = 0, ewmaMs = 0.0, quarantined = true))
    Router.pick(snap(List(ro1), loads), StatementKind.Select, None) match
      case RoutingDecision.Unavailable(_) => succeed
      case other                          => fail(s"expected Unavailable, got $other")

  // --- placement-aware pick (cache-aware routing) ---

  private def req(
      tables: Set[String],
      assignments: Map[String, Assignment],
      c: Double = 2.0
  ) = Some(PlacementRequest(tables, assignments, c))

  private def home(nodeId: String, warm: Long = 0L, epoch: Long = 0L) =
    Assignment(List(HomeEntry(nodeId, warm)), epoch, 1L)

  it should "route to the fresh home over a less-loaded node" in:
    val loads = Map("ro1" -> NodeLoad(2, 100), "ro2" -> NodeLoad(0, 100))
    val a     = Map("db.main.t" -> home("ro1"))
    Router.pick(
      snap(List(ro1, ro2), loads),
      StatementKind.Select,
      None,
      req(Set("db.main.t"), a)
    ) shouldBe RoutingDecision.Use("ro1")

  it should "prefer a fresh home over a stale home on multi-table queries" in:
    val a = Map(
      "db.main.x" -> home("ro1", warm = 1L, epoch = 1L), // fresh on ro1: 2 points
      "db.main.y" -> home("ro2", warm = 1L, epoch = 2L)  // stale on ro2: 1 point
    )
    Router.pick(
      snap(List(ro1, ro2)),
      StatementKind.Select,
      None,
      req(Set("db.main.x", "db.main.y"), a)
    ) shouldBe RoutingDecision.Use("ro1")

  it should "prefer a stale home over a cold node" in:
    val a = Map("db.main.t" -> home("ro1", warm = 0L, epoch = 1L)) // stale: 1 point beats 0
    Router.pick(
      snap(List(ro1, ro2)),
      StatementKind.Select,
      None,
      req(Set("db.main.t"), a)
    ) shouldBe RoutingDecision.Use("ro1")

  it should "score any listed home, not only the MRU one" in:
    val a = Map(
      "db.main.t" -> Assignment(List(HomeEntry("ro2", 0L), HomeEntry("ro1", 0L)), 0L, 1L)
    )
    val loads = Map("ro1" -> NodeLoad(0, 100), "ro2" -> NodeLoad(2, 100))
    // Both are fresh homes (score 2 each); least-loaded tie-break picks ro1.
    Router.pick(
      snap(List(ro1, ro2), loads),
      StatementKind.Select,
      None,
      req(Set("db.main.t"), a)
    ) shouldBe RoutingDecision.Use("ro1")

  it should "bypass the home when it exceeds its load cap" in:
    // capFor(ro1) = 1.5 x max(1, avg of others = 1) = 1.5 < 9 -> excluded; ro2 admitted.
    val loads = Map("ro1" -> NodeLoad(9, 100), "ro2" -> NodeLoad(1, 100))
    val a     = Map("db.main.t" -> home("ro1"))
    Router.pick(
      snap(List(ro1, ro2), loads),
      StatementKind.Select,
      None,
      req(Set("db.main.t"), a, c = 1.5)
    ) shouldBe RoutingDecision.Use("ro2")

  it should "bind the cap even on a 2-node pool (candidate excluded from its own average)" in:
    // Pool-wide average would give cap = 2 x (3/2) = 3 and admit ro1 forever; excluding the
    // candidate gives capFor(ro1) = 2 x max(1, 0) = 2 < 3 -> overflow to ro2.
    val loads = Map("ro1" -> NodeLoad(3, 100), "ro2" -> NodeLoad(0, 100))
    val a     = Map("db.main.t" -> home("ro1"))
    Router.pick(
      snap(List(ro1, ro2), loads),
      StatementKind.Select,
      None,
      req(Set("db.main.t"), a)
    ) shouldBe RoutingDecision.Use("ro2")

  it should "fall back to least-loaded when no table has an assignment" in:
    val loads = Map("ro1" -> NodeLoad(3, 100), "ro2" -> NodeLoad(1, 100))
    Router.pick(
      snap(List(ro1, ro2), loads),
      StatementKind.Select,
      None,
      req(Set("db.main.t"), Map.empty)
    ) shouldBe RoutingDecision.Use("ro2")

  it should "break score ties by least-loaded" in:
    val loads = Map("ro1" -> NodeLoad(3, 100), "ro2" -> NodeLoad(1, 100))
    val a     = Map(
      "db.main.x" -> home("ro1"),
      "db.main.y" -> home("ro2")
    )
    Router.pick(
      snap(List(ro1, ro2), loads),
      StatementKind.Select,
      None,
      req(Set("db.main.x", "db.main.y"), a)
    ) shouldBe RoutingDecision.Use("ro2")

  it should "still respect maxConcurrent capacity in the placement arm" in:
    val capped = node("ro1", Role.ReadOnly).copy(maxConcurrent = 1)
    val loads  = Map("ro1" -> NodeLoad(1, 100), "ro2" -> NodeLoad(0, 100))
    val a      = Map("db.main.t" -> home("ro1"))
    Router.pick(
      snap(List(capped, ro2), loads),
      StatementKind.Select,
      None,
      req(Set("db.main.t"), a)
    ) shouldBe RoutingDecision.Use("ro2")

  it should "behave identically to the 3-arg pick when placement is None" in:
    val loads = Map("ro1" -> NodeLoad(3, 100), "ro2" -> NodeLoad(2, 100))
    val s     = snap(List(ro1, ro2), loads)
    Router.pick(s, StatementKind.Select, None, None) shouldBe
      Router.pick(s, StatementKind.Select, None)

  it should "let a pinned node win over any placement" in:
    val a = Map("db.main.t" -> home("ro1"))
    Router.pick(
      snap(List(ro1, ro2)),
      StatementKind.Select,
      Some("ro2"),
      req(Set("db.main.t"), a)
    ) shouldBe RoutingDecision.Use("ro2")
