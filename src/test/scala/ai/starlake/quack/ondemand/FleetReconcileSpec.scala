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
        // A node id this double started is still held by its (possibly dead) server.
        IO.raiseError(
          NoFreeServer(
            spec.poolKey,
            spec.nodeId,
            "none_free",
            nodes.get(spec.nodeId).flatMap(_.serverName)
          )
        )
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

  it should "keep a dead node's row when nothing is free and move it once a server is free" in {
    val (b, sup, st, key) = fixture(); b.freeServers = 1
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val before = sup.get(key).get.nodes.head
    b.liveIds.clear() // server went dead past the grace
    sup.reconcile().unsafeRunSync()
    // Owner policy: with no free server the dead holder keeps its row (and its assignment).
    sup.get(key).get.nodes shouldBe List(before)
    storedIds(sup, st, key) shouldBe List(id(1))
    sup.pendingCount(key) shouldBe 0 // a node on an unreachable server, not a missing slot
    sup.pendingReason(key) shouldBe Some("none_free")
    b.freeServers = 1
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes.map(_.nodeId) shouldBe List(id(1))
    sup.get(key).get.nodes.head should not be theSameInstanceAs(before)
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

  it should "keep a dead node's id when scaling up and number new nodes above it" in {
    val (b, sup, st, key) = fixture(); b.freeServers = 3
    sup.createPool(key, RoleDistribution(0, 0, 3)).unsafeRunSync()
    b.liveIds -= id(2) // -2's server died; no server is free to respawn it
    sup.reconcile().unsafeRunSync()
    sup.get(key).get.nodes.map(_.nodeId) shouldBe List(id(1), id(2), id(3))
    b.freeServers = 2
    sup.scale(key, 4, RoleDistribution(0, 0, 4), force = true).unsafeRunSync()
    val ids = sup.get(key).get.nodes.map(_.nodeId)
    ids.sorted shouldBe List(id(1), id(2), id(3), id(4))
    ids.distinct.size shouldBe ids.size
    storedIds(sup, st, key) shouldBe List(id(1), id(2), id(3), id(4))
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

/** PoolSupervisor over the REAL FleetQuackBackend and InMemoryFleetServerStore, with fake agents
  * beating from a background ticker, so the supervisor/backend interactions (release-and-claim in
  * one step, a dead holder kept when nothing is free, drain, remove) run end to end without
  * Postgres or processes.
  */
class FleetReconcileRealBackendSpec extends AnyFlatSpec with Matchers:
  import ai.starlake.quack.FleetConfig
  import ai.starlake.quack.ondemand.api.{FleetHandlers, FleetServerOpRequest}
  import ai.starlake.quack.ondemand.ha.PoolLocker
  import ai.starlake.quack.ondemand.runtime.{FleetNodeFailed, FleetQuackBackend}
  import ai.starlake.quack.ondemand.state.{
    ClaimMiss,
    FleetAssignment,
    FleetServerRow,
    FleetServerStore,
    Heartbeat,
    InMemoryFleetServerStore,
    NodeReport
  }
  import scala.concurrent.Await
  import scala.concurrent.duration._

  /** Shared movable clock: the store's (silentSeconds, claimedAt) and the backend's deadlines. */
  @volatile private var clockNow: Instant = Instant.parse("2026-09-25T10:00:00Z")

  private val cfg = FleetConfig(
    joinToken = "j",
    heartbeatTimeoutSec = 30,
    reassignAfterSec = 60,
    startupTimeoutSec = 30,
    stopTimeoutSec = 30
  )

  /** Store wrapper with a hook run before `claimReplacing`, to land a heartbeat inside the window
    * between a reconcile pass's `liveNodeIds` read and the backend's claim.
    */
  private final class HookedStore(u: InMemoryFleetServerStore) extends FleetServerStore:
    export u.{claimReplacing as _, *}
    @volatile var beforeClaimReplacing: String => Unit = _ => ()
    override def claimReplacing(
        a: FleetAssignment,
        reachableWithinSec: Int,
        requiredMemoryBytes: Option[Long]
    ): Either[ClaimMiss, FleetServerRow] =
      beforeClaimReplacing(a.nodeId); u.claimReplacing(a, reachableWithinSec, requiredMemoryBytes)

  /** A fake `qod agent`: reports the assignment it holds as running; once unassigned, reports
    * `stopped` under the epoch and node id it last ran, like the real agent.
    */
  private final class Agent(store: FleetServerStore, val name: String, host: String):
    @volatile var paused: Boolean = false
    // When set, the agent reports its assignment `failed` instead of `running`.
    @volatile var failing: Boolean = false
    // When set, the agent reports its assignment `starting` forever (a slow node start).
    @volatile var starting: Boolean                        = false
    @volatile private var lastRun: Option[FleetAssignment] = None
    def beat(): Unit                                       =
      val node = store.get(name).flatMap(_.assignment) match
        case Some(a) if failing =>
          lastRun = Some(a)
          NodeReport(a.epoch, Some(a.nodeId), "failed", None, Some("boom"), None)
        case Some(a) if starting =>
          lastRun = Some(a)
          NodeReport(a.epoch, Some(a.nodeId), "starting", None, None, None)
        case Some(a) =>
          lastRun = Some(a)
          NodeReport(a.epoch, Some(a.nodeId), "running", Some(1L), None, Some(Instant.EPOCH))
        case None =>
          lastRun match
            case Some(a) => NodeReport(a.epoch, Some(a.nodeId), "stopped", None, None, None)
            case None    => NodeReport(0, None, "none", None, None, None)
      store.recordHeartbeat(
        Heartbeat(name, host, 21900, Some("t"), Some("linux"), Some("1.5"), Some(8), None, node)
      )

  private final class Fx(locker: PoolLocker = PoolLocker.noop):
    clockNow = Instant.parse("2026-09-25T10:00:00Z")
    val inner   = new InMemoryFleetServerStore(clock = () => clockNow)
    val store   = new HookedStore(inner)
    val backend = new FleetQuackBackend(store, cfg, clock = () => clockNow, 10.millis)
    val cp      = new InMemoryControlPlaneStore()
    val tracker = new NodeLoadTracker
    // What the federation blob resolver answers; tests change it between passes.
    @volatile var fedBlob: Option[String] = None
    val sup                               =
      new PoolSupervisor(
        backend,
        tracker,
        cp,
        lockdownEnabled = false,
        locks = locker,
        federationBlobOf = _ => IO(fedBlob)
      )
    val key    = PoolKey("acme", "acme_db", "bi")
    val agents = TrieMap.empty[String, Agent]
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "db", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()

    /** Joins a server (one immediate beat, one second apart, so join order is deterministic). */
    def join(name: String): Agent =
      val a = new Agent(store, name, s"10.0.0.${agents.size + 1}")
      agents.put(name, a); clockNow = clockNow.plusSeconds(1); a.beat(); a

    /** Every 10ms: one simulated second passes and every unpaused agent beats. */
    private val ticker: IO[Nothing] =
      (IO.sleep(10.millis) *> IO.blocking {
        clockNow = clockNow.plusSeconds(1)
        agents.values.filterNot(_.paused).foreach(_.beat())
      }).foreverM

    def run[A](body: => A): A =
      val cancel = ticker.unsafeRunCancelable()
      try body
      finally Await.result(cancel(), 5.seconds)

    def holder(nodeId: String): List[String] =
      inner.list().filter(_.assignedNodeId.contains(nodeId)).map(_.name)
    def rowIds: List[String] = cp.listNodes(sup.poolId(key).get).map(_.nodeId).sorted
    def rowServer(nodeId: String): Option[String] =
      cp.listNodes(sup.poolId(key).get).find(_.nodeId == nodeId).flatMap(_.serverName)

    /** Kills a server: its agent stops beating and its heartbeat is backdated past the grace. */
    def kill(name: String): Unit =
      agents(name).paused = true; inner.backdate(name, 3600)

    /** Waits (wall clock) for `cond`, at most 5s. */
    def eventually(cond: => Boolean): Unit =
      val deadline = System.nanoTime() + 5.seconds.toNanos
      while !cond && System.nanoTime() < deadline do Thread.sleep(10)
      cond shouldBe true

  private def id(n: Int) = s"quack-acme-acme-db-bi-$n"

  "reconcile (real fleet backend)" should "move a drained server's node to another server" in {
    val fx = new Fx
    fx.join("a"); fx.join("b")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      val h = new FleetHandlers(fx.store, cfg, backend = Some(fx.backend))
      h.drain(FleetServerOpRequest("a"), None)(_ => None).unsafeRunSync() shouldBe Right(())
      fx.sup.reconcile().unsafeRunSync()
      fx.holder(id(1)) shouldBe List("b")
      fx.rowIds shouldBe List(id(1))
      fx.rowServer(id(1)) shouldBe Some("b")
      fx.inner.get("a").get.assignedNodeId shouldBe None
    }
  }

  it should "drop the row and leave the slot pending when a drained server's node has nowhere to go" in {
    val fx = new Fx
    fx.join("a")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      val h = new FleetHandlers(fx.store, cfg, backend = Some(fx.backend))
      h.drain(FleetServerOpRequest("a"), None)(_ => None).unsafeRunSync() shouldBe Right(())
      fx.sup.reconcile().unsafeRunSync()
      // Nothing holds the node any more (the drain released it): no server to keep it for, so
      // the row goes and the slot is pending, unlike a dead server that still holds it.
      fx.rowIds shouldBe Nil
      fx.sup.pendingCount(fx.key) shouldBe 1
      fx.sup.pendingReason(fx.key) shouldBe Some("none_free")
      fx.inner.get("a").get.assignedNodeId shouldBe None
      h.undrain(FleetServerOpRequest("a"), None)(_ => None).unsafeRunSync() shouldBe Right(())
      fx.sup.reconcile().unsafeRunSync()
      fx.rowIds shouldBe List(id(1))
      fx.holder(id(1)) shouldBe List("a")
    }
  }

  it should "free a dead server that returns mid-respawn instead of leaving it an untracked slot" in {
    val fx = new Fx
    fx.join("a"); fx.join("b")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 2)).unsafeRunSync()
      (fx.holder(id(1)), fx.holder(id(2))) shouldBe (List("a"), List("b"))
      fx.kill("a")
      // `a` comes back inside the window between the pass's liveNodeIds read (which saw it
      // dead) and the respawn's start: its next beat lands right before the backend's claim.
      fx.store.beforeClaimReplacing = nodeId =>
        if nodeId == id(1) then
          fx.store.beforeClaimReplacing = _ => ()
          fx.agents("a").paused = false
          fx.agents("a").beat()
      fx.sup.reconcile().unsafeRunSync()
      fx.sup.reconcile().unsafeRunSync()
      // Before the fix: the returning `a` kept -1 (reachable at the respawn's lookup, so not
      // released), the respawn found no free server, the -1 row was dropped and the fill
      // renumbered the slot -3, which nobody could claim: `a` held an assignment no node row
      // tracks, forever. Now `a` is freed and serves the pool again, whichever slot id it gets.
      fx.rowIds.size shouldBe 2
      fx.sup.pendingCount(fx.key) shouldBe 0
      val assigned = fx.inner.list().flatMap(_.assignedNodeId).sorted
      assigned shouldBe fx.rowIds
      fx.inner.get("a").get.assignedNodeId.isDefined shouldBe true
    }
  }

  it should "re-claim a crash orphan (reachable stale holder) in place with a new epoch" in {
    val fx = new Fx
    fx.run {
      // No server yet: the pool is created all-pending.
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.sup.pendingCount(fx.key) shouldBe 1
      // A manager claimed -1 on `a` and died before writing the node row; the agent runs it.
      fx.join("a")
      val orphan = fx.inner
        .claim(
          FleetAssignment(0, id(1), fx.key, 0, "t", "memory", Map.empty, "", "", "", ""),
          30,
          None
        )
        .toOption
        .get
      fx.eventually(fx.inner.get("a").get.nodeState == "running")
      fx.join("b")
      noException should be thrownBy fx.sup.reconcile().unsafeRunSync()
      fx.rowIds shouldBe List(id(1))
      // Released and re-claimed in one transaction: `a` (oldest, reachable) keeps the slot under
      // a new epoch and token; `b` stays free.
      fx.holder(id(1)) shouldBe List("a")
      fx.inner.get("a").get.assignmentEpoch shouldBe orphan.assignmentEpoch + 2
      fx.inner.get("a").get.assignment.map(_.token) should not be Some("t")
      fx.inner.get("b").get.assignedNodeId shouldBe None
      fx.rowServer(id(1)) shouldBe Some("a")
      fx.sup.pendingCount(fx.key) shouldBe 0
    }
  }

  it should "no free server: the dead node keeps its row and assignment; when its server returns before capacity appears the node is adopted and serves again (same epoch, no restart)" in {
    val fx = new Fx
    fx.join("a")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      val epoch  = fx.inner.get("a").get.assignmentEpoch
      val before = fx.sup.get(fx.key).get.nodes.head
      fx.kill("a")
      fx.sup.reconcile().unsafeRunSync()
      fx.rowIds shouldBe List(id(1))
      fx.rowServer(id(1)) shouldBe Some("a")
      fx.holder(id(1)) shouldBe List("a")
      fx.inner.get("a").get.assignmentEpoch shouldBe epoch
      fx.sup.get(fx.key).get.nodes shouldBe List(before)
      fx.sup.pendingCount(fx.key) shouldBe 0
      fx.sup.pendingReason(fx.key) shouldBe Some("none_free")
      // A second pass while it is still dead changes nothing.
      fx.sup.reconcile().unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      fx.inner.get("a").get.assignmentEpoch shouldBe epoch
      // The server returns: its agent still runs the node under the same epoch.
      fx.agents("a").paused = false
      fx.agents("a").beat()
      fx.sup.reconcile().unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      fx.inner.get("a").get.assignmentEpoch shouldBe epoch
      fx.inner.get("a").get.nodeState shouldBe "running"
      val after = fx.sup.get(fx.key).get.nodes
      after.map(n => (n.nodeId, n.token, n.serverName)) shouldBe
        List((before.nodeId, before.token, before.serverName))
      fx.rowIds shouldBe List(id(1))
      fx.sup.pendingReason(fx.key) shouldBe None
    }
  }

  it should "keep a kept dead node's tracker state (unroutable, counters) across passes" in {
    val fx = new Fx
    fx.join("a")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.tracker.onStart(id(1)); fx.tracker.onFinish(id(1), 5L)
      fx.tracker.snapshot(id(1)).totalServed shouldBe 1L
      fx.kill("a")
      fx.tracker.setHealthy(id(1), false) // the health probe marked it down
      fx.sup.reconcile().unsafeRunSync()
      fx.sup.reconcile().unsafeRunSync()
      fx.sup.reconcile().unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      // Before the fix every pass reset the entry to NodeLoad.empty (healthy = true): routable
      // again until the next probe tick, counters lost.
      fx.tracker.snapshot(id(1)).healthy shouldBe false
      fx.tracker.snapshot(id(1)).routable shouldBe false
      fx.tracker.snapshot(id(1)).totalServed shouldBe 1L
    }
  }

  it should "no free server, then a server joins: the respawn moves the node there and the returning old server is told to stop" in {
    val fx = new Fx
    fx.join("a")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.kill("a")
      fx.sup.reconcile().unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      fx.sup.pendingReason(fx.key) shouldBe Some("none_free")
      // The federation blob changes while the node is kept: the move is a spawn and must carry
      // the blob resolved at the pass that moves it, not the one from when it was first kept.
      fx.fedBlob = Some("ATTACH 'fed_v2.db' AS fed_v2;")
      fx.join("b")
      fx.sup.reconcile().unsafeRunSync()
      fx.holder(id(1)) shouldBe List("b")
      fx.inner.get("b").get.assignment.map(_.extraSetupSql).get should include("fed_v2")
      fx.rowIds shouldBe List(id(1))
      fx.rowServer(id(1)) shouldBe Some("b")
      fx.inner.get("a").get.assignedNodeId shouldBe None
      fx.sup.pendingReason(fx.key) shouldBe None
      // The old server returns: no assignment any more, so its agent stops the orphan node.
      fx.agents("a").paused = false
      fx.eventually(fx.inner.get("a").exists(_.nodeState == "stopped"))
      fx.inner.get("a").get.assignedNodeId shouldBe None
      fx.sup.reconcile().unsafeRunSync()
      fx.holder(id(1)) shouldBe List("b")
      fx.rowIds shouldBe List(id(1))
    }
  }

  it should "stay consistent after removing an unreachable server that holds a node" in {
    val fx = new Fx
    fx.join("a"); fx.join("b")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      fx.agents("a").paused = true
      fx.inner.backdate("a", 40) // unreachable (> 30s), inside the 60s grace
      val h = new FleetHandlers(fx.store, cfg, backend = Some(fx.backend))
      h.remove(FleetServerOpRequest("a"), None)(_ => None).unsafeRunSync() shouldBe Right(())
      fx.sup.reconcile().unsafeRunSync()
      fx.rowIds shouldBe List(id(1))
      fx.holder(id(1)) shouldBe List("b")
      fx.rowServer(id(1)) shouldBe Some("b")
      // The removed agent comes back: it re-joins as a fresh, free server, and its old node is
      // not the pool's any more (a removed-but-running agent may rejoin).
      fx.agents("a").paused = false
      fx.eventually(fx.inner.get("a").exists(_.nodeState == "stopped"))
      fx.inner.get("a").get.assignedNodeId shouldBe None
      fx.sup.reconcile().unsafeRunSync()
      fx.rowIds shouldBe List(id(1))
      fx.holder(id(1)) shouldBe List("b")
    }
  }

  it should "roll back the second start when the third fails: no new assignment left on any server, rows and distribution unchanged" in {
    val fx = new Fx
    fx.join("a"); fx.join("b"); fx.join("c")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      // `c` (the last free server, so -3 lands there) reports every node it gets as failed.
      fx.agents("c").failing = true
      val err = intercept[FleetNodeFailed] {
        fx.sup.scale(fx.key, 3, RoleDistribution(0, 0, 3), force = true).unsafeRunSync()
      }
      err.nodeId shouldBe id(3)
      // -2 was started on `b` before -3 failed: it must be released, not left running on a
      // server that the fill would never ask for again (the distribution is still 1).
      fx.inner.list().flatMap(_.assignedNodeId) shouldBe List(id(1))
      fx.holder(id(1)) shouldBe List("a")
      fx.rowIds shouldBe List(id(1))
      fx.sup.get(fx.key).get.distribution shouldBe RoleDistribution(0, 0, 1)
      fx.sup.poolEntity(fx.sup.poolId(fx.key).get).get.distribution shouldBe
        RoleDistribution(0, 0, 1)
    }
  }

  it should "cancelling a scale after node 2 is running and while node 3 is starting releases node 2 and node 3, keeps node 1, rows and distribution unchanged" in {
    val fx = new Fx
    fx.join("a"); fx.join("b"); fx.join("c")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      // `c` (where -3 lands) never gets past `starting`.
      fx.agents("c").starting = true
      val scaling = fx.sup
        .scale(fx.key, 3, RoleDistribution(0, 0, 3), force = true)
        .start
        .unsafeRunSync()
      fx.eventually(
        fx.inner.get("b").exists(r => r.assignedNodeId.contains(id(2)) && r.nodeState == "running")
          && fx.holder(id(3)) == List("c")
      )
      // Request timeout, resume hold timeout, shutdown: the scale fiber is cancelled.
      scaling.cancel.unsafeRunSync()
      scaling.join.unsafeRunSync().isCanceled shouldBe true
      fx.inner.list().flatMap(_.assignedNodeId) shouldBe List(id(1))
      fx.holder(id(1)) shouldBe List("a")
      fx.rowIds shouldBe List(id(1))
      fx.sup.get(fx.key).get.nodes.map(_.nodeId) shouldBe List(id(1))
      fx.sup.get(fx.key).get.distribution shouldBe RoleDistribution(0, 0, 1)
      fx.sup.poolEntity(fx.sup.poolId(fx.key).get).get.distribution shouldBe
        RoleDistribution(0, 0, 1)
    }
  }

  it should "not resurrect a node a concurrent scale to 0 is stopping (in-process pool lock)" in {
    val fx = new Fx(PoolLocker.inProcess())
    fx.join("a")
    fx.run {
      fx.sup.createPool(fx.key, RoleDistribution(0, 0, 1)).unsafeRunSync()
      fx.holder(id(1)) shouldBe List("a")
      // The agent is slow to confirm the stop: the scale-down releases -1, then waits.
      fx.agents("a").paused = true
      val scaling = fx.sup
        .scale(fx.key, 0, RoleDistribution(0, 0, 0), force = true)
        .start
        .unsafeRunSync()
      fx.eventually(fx.holder(id(1)).isEmpty)
      // A reconcile tick lands in that window. Unserialized, it reads -1 as dead (no server
      // holds it) and respawns it on the pre-scale target of 1.
      val reconciling = fx.sup.reconcile().start.unsafeRunSync()
      Thread.sleep(200)
      fx.agents("a").paused = false // the agent confirms the stop (or runs a respawned -1)
      scaling.joinWithNever.unsafeRunSync()
      reconciling.joinWithNever.unsafeRunSync()
      fx.sup.reconcile().unsafeRunSync()
      fx.sup.get(fx.key).get.distribution.total shouldBe 0
      fx.rowIds shouldBe Nil
      fx.inner.list().flatMap(_.assignedNodeId) shouldBe Nil
    }
  }
