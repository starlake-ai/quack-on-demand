package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.runtime.{NoFreeServer, QuackBackend}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

class FleetReconcileSpec extends AnyFlatSpec with Matchers:

  /** Fleet-like double: a finite count of free servers; `start` raises NoFreeServer when none is
    * left. Nodes are pid-less, so liveness comes from `liveNodeIds` as for the fleet backend.
    */
  private final class FleetLikeBackend extends QuackBackend:
    val nodes                      = TrieMap.empty[String, RunningNode]
    @volatile var freeServers: Int = 0
    // When set, `start` raises this instead of claiming (a non-NoFreeServer failure).
    @volatile var failWith: Option[Throwable]  = None
    val liveIds                                = scala.collection.mutable.Set.empty[String]
    def start(spec: NodeSpec): IO[RunningNode] = IO.defer {
      if failWith.isDefined then IO.raiseError(failWith.get)
      else if freeServers <= 0 then
        IO.raiseError(NoFreeServer(spec.poolKey, spec.nodeId, "none_free"))
      else
        freeServers -= 1
        val n = RunningNode(
          spec.nodeId,
          spec.poolKey,
          spec.role,
          "10.0.0." + nodes.size,
          21900,
          "tok",
          None,
          None,
          Instant.EPOCH,
          maxConcurrent = spec.maxConcurrent,
          serverName = Some("srv-" + nodes.size)
        )
        nodes.put(spec.nodeId, n); liveIds += spec.nodeId; IO.pure(n)
    }
    def stop(key: PoolKey, id: String): IO[Unit] =
      IO { nodes.remove(id); liveIds -= id; freeServers += 1 }
    def isAlive(id: String): Boolean                                = nodes.contains(id)
    def discoverExisting(): IO[List[RunningNode]]                   = IO.pure(Nil)
    def cleanup(): IO[Unit]                                         = IO.unit
    override def liveNodeIds(key: PoolKey): IO[Option[Set[String]]] = IO.pure(Some(liveIds.toSet))

  /** Tenant `acme` + in-memory tenant-db `db` (slug `acme_db`), pool `bi`. */
  private def fixture(): (FleetLikeBackend, PoolSupervisor, InMemoryControlPlaneStore, PoolKey) =
    val b   = new FleetLikeBackend
    val st  = new InMemoryControlPlaneStore()
    val sup = new PoolSupervisor(b, new NodeLoadTracker, st, lockdownEnabled = false)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "db", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    (b, sup, st, PoolKey("acme", "acme_db", "bi"))

  private def id(n: Int) = s"quack-acme-acme-db-bi-$n"

  private def storedIds(sup: PoolSupervisor, st: InMemoryControlPlaneStore, key: PoolKey) =
    st.listNodes(sup.poolId(key).get).map(_.nodeId).sorted

  "createPool" should "accept a size the fleet cannot satisfy and leave the rest pending" in {
    val (b, sup, st, key) = fixture(); b.freeServers = 1
    sup.createPool(key, RoleDistribution(0, 0, 3)).unsafeRunSync()
    sup.get(key).get.nodes.map(_.nodeId) shouldBe List(id(1))
    storedIds(sup, st, key) shouldBe List(id(1))
    sup.pendingCount(key) shouldBe 2
    sup.pendingReason(key) shouldBe Some("none_free")
  }

  "reconcile" should "fill pending slots as servers join" in {
    val (b, sup, st, key) = fixture(); b.freeServers = 1
    sup.createPool(key, RoleDistribution(0, 0, 3)).unsafeRunSync()
    b.freeServers = 5
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes.map(_.nodeId).sorted shouldBe List(id(1), id(2), id(3))
    storedIds(sup, st, key) shouldBe List(id(1), id(2), id(3))
    sup.pendingCount(key) shouldBe 0
    sup.pendingReason(key) shouldBe None
  }

  it should "respawn a dead node elsewhere and keep it pending when nothing is free" in {
    val (b, sup, st, key) = fixture(); b.freeServers = 1
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    b.liveIds.clear() // server went dead past the grace
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes shouldBe Nil
    storedIds(sup, st, key) shouldBe Nil
    sup.pendingCount(key) shouldBe 1
    sup.pendingReason(key) shouldBe Some("none_free")
    b.freeServers = 1
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes.map(_.nodeId) shouldBe List(id(1))
    storedIds(sup, st, key) shouldBe List(id(1))
    sup.pendingCount(key) shouldBe 0
    sup.pendingReason(key) shouldBe None
  }

  it should "leave a local-style pool at target untouched" in {
    val (b, sup, _, key) = fixture(); b.freeServers = 2
    sup.createPool(key, RoleDistribution(0, 0, 2)).unsafeRunSync()
    val before = sup.get(key).get.nodes
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes shouldBe before
    sup.pendingCount(key) shouldBe 0
    sup.pendingReason(key) shouldBe None
  }

  "stopPool" should "zero the distribution of an all-pending pool so reconcile spawns nothing" in {
    val (b, sup, st, key) = fixture(); b.freeServers = 0
    sup.createPool(key, RoleDistribution(0, 0, 3)).unsafeRunSync()
    sup.get(key).get.nodes shouldBe Nil
    sup.pendingCount(key) shouldBe 3
    sup.stopPool(key, force = true).unsafeRunSync()
    sup.get(key).get.distribution.total shouldBe 0
    sup.poolEntity(sup.poolId(key).get).get.distribution.total shouldBe 0
    sup.pendingCount(key) shouldBe 0
    b.freeServers = 5
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes shouldBe Nil
    storedIds(sup, st, key) shouldBe Nil
  }

  "scale" should "persist a scale-down that moves no node when slots are pending" in {
    val (b, sup, _, key) = fixture(); b.freeServers = 1
    sup.createPool(key, RoleDistribution(0, 0, 3)).unsafeRunSync()
    sup.pendingCount(key) shouldBe 2
    sup.scale(key, 1, RoleDistribution(0, 0, 1), force = true).unsafeRunSync()
    sup.get(key).get.distribution shouldBe RoleDistribution(0, 0, 1)
    val row = sup.poolEntity(sup.poolId(key).get).get
    row.distribution shouldBe RoleDistribution(0, 0, 1)
    row.size shouldBe 1
    sup.pendingCount(key) shouldBe 0
    sup.pendingReason(key) shouldBe None
    b.freeServers = 5
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes.map(_.nodeId) shouldBe List(id(1))
  }

  it should "number new nodes above the highest present index, never reusing a live id" in {
    val (b, sup, st, key) = fixture(); b.freeServers = 3
    sup.createPool(key, RoleDistribution(0, 0, 3)).unsafeRunSync()
    b.liveIds -= id(2) // -2's server died; no server is free to respawn it
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes.map(_.nodeId) shouldBe List(id(1), id(3))
    b.freeServers = 2
    sup.scale(key, 4, RoleDistribution(0, 0, 4), force = true).unsafeRunSync()
    val ids = sup.get(key).get.nodes.map(_.nodeId)
    ids.sorted shouldBe List(id(1), id(3), id(4), id(5))
    ids.distinct.size shouldBe ids.size
    storedIds(sup, st, key) shouldBe List(id(1), id(3), id(4), id(5))
  }

  "pendingCount" should "be 0 for a suspended pool" in {
    val (b, sup, _, key) = fixture(); b.freeServers = 2
    sup.createPool(key, RoleDistribution(0, 0, 2)).unsafeRunSync()
    sup.suspendPool(key, "rest").unsafeRunSync().isRight shouldBe true
    sup.get(key).get.nodes shouldBe Nil
    sup.pendingCount(key) shouldBe 0
    sup.pendingReason(key) shouldBe None
  }

  "createPool" should "still fail on a start error that is not NoFreeServer" in {
    val (b, sup, _, key) = fixture(); b.freeServers = 3
    b.failWith = Some(new RuntimeException("boom"))
    val err = intercept[RuntimeException] {
      sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    }
    err.getMessage should include("boom")
  }
