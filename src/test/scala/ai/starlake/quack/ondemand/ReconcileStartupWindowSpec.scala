package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  Role,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.runtime.{
  LocalQuackBackend,
  LocalQuackBackendSpec,
  NodeProcess,
  QuackBackend
}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Regression for the pool-create spawn race: the local backend returns at fork time, so a node row
  * exists while its process is alive but not yet listening. A reconcile tick in that window used to
  * read the node as dead and respawn it under the same id without stopping the first process, which
  * then ran untracked (holding its port) past every stop path and manager shutdown.
  *
  * Real child processes (`sleep`, never listening) stand in for a node that is slow to listen; the
  * reconcile loop ticks far faster than the node could ever come up.
  */
class ReconcileStartupWindowSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  /** One start: the node and its process handle taken right after the fork (start-time-bound, so
    * checking or killing it later can never reach a process that reuses the pid).
    */
  private final case class Started(node: RunningNode, handle: Option[ProcessHandle]):
    def alive: Boolean = handle.exists(_.isAlive)

  /** The real local backend, recording every node it starts. */
  private final class RecordingLocal(min: Int, max: Int, cmd: List[String]) extends QuackBackend:
    private val inner = new LocalQuackBackend(
      min,
      max,
      commandFor = (_, _, _) => cmd,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig()
    )
    val started                                = new ConcurrentLinkedQueue[Started]()
    def start(spec: NodeSpec): IO[RunningNode] =
      inner.start(spec).flatTap(n => IO(started.add(Started(n, NodeProcess.of(n)))))
    def stop(k: PoolKey, id: String): IO[Unit]                 = inner.stop(k, id)
    def isAlive(id: String): Boolean                           = inner.isAlive(id)
    def discoverExisting(): IO[List[RunningNode]]              = inner.discoverExisting()
    def cleanup(): IO[Unit]                                    = inner.cleanup()
    override def adopt(n: RunningNode): IO[Unit]               = inner.adopt(n)
    override def stopStray(n: RunningNode): IO[Unit]           = inner.stopStray(n)
    override def reservePorts(ns: List[RunningNode]): IO[Unit] = inner.reservePorts(ns)
    def all: List[Started]                                     = started.asScala.toList

  private def kill(h: ProcessHandle): Unit =
    if h.isAlive then
      h.descendants().forEach { d => d.destroyForcibly(); () }
      h.destroyForcibly()
      ()

  /** A process the test owns, outside any backend. */
  private def foreignSleep(): Process =
    new java.lang.ProcessBuilder(LocalQuackBackendSpec.sleepCmd(60)*).start()

  private def withPool(
      min: Int,
      max: Int,
      grace: FiniteDuration,
      cmd: List[String] = LocalQuackBackendSpec.sleepCmd(60)
  )(body: (PoolSupervisor, RecordingLocal, InMemoryControlPlaneStore) => Unit): Unit =
    val backend = new RecordingLocal(min, max, cmd)
    val store   = new InMemoryControlPlaneStore()
    val sup     = new PoolSupervisor(backend, new NodeLoadTracker, store, nodeStartupGrace = grace)
    try
      sup.createTenant(Tenant("acme")).unsafeRunSync()
      sup
        .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
        .unsafeRunSync()
      sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
      body(sup, backend, store)
    finally
      backend.cleanup().unsafeRunSync()
      backend.all.flatMap(_.handle).foreach(kill)

  /** Run the reconcile loop every 20 ms until `done` or `forMs` elapses, then stop it. */
  private def tick(sup: PoolSupervisor, forMs: Long, done: () => Boolean = () => false): Unit =
    val deadline = System.currentTimeMillis() + forMs
    val fiber    = sup.reconcileLoop(20.millis).start.unsafeRunSync()
    while !done() && System.currentTimeMillis() < deadline do Thread.sleep(20)
    fiber.cancel.unsafeRunSync()

  /** Point the pool's only node row at another process, as a row a previous manager wrote. */
  private def rewriteRow(
      sup: PoolSupervisor,
      store: InMemoryControlPlaneStore,
      pid: Long,
      startedAt: Instant
  ): Unit =
    val n = sup.get(poolKey).get.nodes.head
    store.upsertNode(n.copy(pid = Some(pid), startedAt = startedAt), store.snapshot().pools.head.id)

  "reconcile" should "not respawn a local node that is alive but still inside its startup window" in:
    assume(!LocalQuackBackendSpec.isWindows)
    withPool(23140, 23149, grace = 1.minute) { (sup, backend, _) =>
      val first = backend.all
      first should have size 1
      tick(sup, 600)
      withClue("starts after ~30 reconcile ticks of a node still starting: ") {
        backend.all.map(_.node) shouldBe first.map(_.node)
      }
      sup.get(poolKey).get.nodes.flatMap(_.pid) shouldBe first.flatMap(_.node.pid)
      first.head.alive shouldBe true
    }

  it should "stop the old process before respawning a node past its startup window" in:
    assume(!LocalQuackBackendSpec.isWindows)
    withPool(23150, 23152, grace = 200.millis) { (sup, backend, _) =>
      // Each pass after the window finds the never-listening node dead and replaces it.
      tick(sup, 6000, () => backend.started.size >= 5)
      val all = backend.all
      all.size should be >= 2
      all.map(_.node.nodeId).distinct shouldBe List(PoolSupervisor.nodeId(poolKey, 1))
      // Every process but the newest was replaced. (The newest may be missing from the pool state
      // when the loop was cancelled between its start and its row write, so compare to `all`.)
      withClue(s"replaced nodes still running: ${all.init.filter(_.alive).flatMap(_.node.pid)}") {
        all.init.filter(_.alive) shouldBe empty
      }
      all.flatMap(_.node.pid) should contain allElementsOf sup.get(poolKey).get.nodes.flatMap(_.pid)
      // A range of three ports: a lease leaked per replacement would refuse the fourth start.
      all.size should be >= 5
    }

  it should "respawn a node whose process dies inside its startup window" in:
    // The window only shields a node whose process is still running: a wrapper exits as soon as its
    // duckdb does, so a crash during startup is replaced on the next pass, not after the window.
    assume(!LocalQuackBackendSpec.isWindows)
    withPool(23160, 23162, grace = 1.minute, cmd = List("sh", "-c", "exit 1")) {
      (sup, backend, _) =>
        tick(sup, 5000, () => backend.started.size >= 3)
        backend.started.size should be >= 3
        backend.all.init.filter(_.alive) shouldBe empty
    }

  it should "neither treat as starting nor signal an unrelated process under the row's pid" in:
    // After a manager or container restart a row's pid can name an unrelated process. Inside the
    // window a live pid alone used to make the node "starting": reconcile adopted it, and the
    // replacement or shutdown then SIGTERMed / SIGKILLed that process.
    assume(!LocalQuackBackendSpec.isWindows)
    withPool(23170, 23172, grace = 1.minute) { (sup, backend, store) =>
      val unrelated = foreignSleep()
      try
        // A node started 30 s ago (inside the 1 min window) whose pid now names a process that
        // started just now.
        rewriteRow(sup, store, unrelated.pid(), Instant.now().minusSeconds(30))
        sup.reconcile().unsafeRunSync()
        withClue("the node was treated as starting instead of dead: ") {
          backend.started.size shouldBe 2
        }
        unrelated.isAlive shouldBe true
        backend.cleanup().unsafeRunSync()
        unrelated.waitFor(500, TimeUnit.MILLISECONDS)
        unrelated.isAlive shouldBe true
      finally kill(unrelated.toHandle)
    }

  it should "stop a node process an earlier manager left running before replacing it" in:
    // A node that never listened outlives its manager (unclean exit), so this generation never
    // adopted it. Once past its window it is replaced, and the replacement must stop it first.
    assume(!LocalQuackBackendSpec.isWindows)
    withPool(23180, 23182, grace = 100.millis) { (sup, backend, store) =>
      val stray = foreignSleep()
      try
        val strayStart = stray.toHandle.info().startInstant().get()
        rewriteRow(sup, store, stray.pid(), strayStart)
        Thread.sleep(300) // past the window
        sup.reconcile().unsafeRunSync()
        backend.started.size shouldBe 2
        withClue("the earlier manager's process survived its replacement: ") {
          stray.waitFor(10, TimeUnit.SECONDS) shouldBe true
        }
      finally kill(stray.toHandle)
    }

  it should "keep an unverified leftover's port out of a new manager's replacement" in:
    // Linux, unclean manager exit plus a wall-clock step: a leftover node no longer matches its
    // row's start time, so it is neither adopted nor signalled. Its port must not go to the
    // replacement either, or the new node fails to bind while the leftover keeps answering there.
    assume(!LocalQuackBackendSpec.isWindows)
    val leftover = foreignSleep()
    val listener = new java.net.ServerSocket()
    // The leftover's port answers, as a still-listening leftover node would.
    listener.bind(new java.net.InetSocketAddress("127.0.0.1", 23190))
    try
      withPool(23185, 23187, grace = 1.minute) { (sup, backend, store) =>
        // The row the previous manager wrote: the leftover's pid and port, a start time the
        // leftover's own does not match.
        val n = sup.get(poolKey).get.nodes.head
        store.upsertNode(
          n.copy(
            pid = Some(leftover.pid()),
            port = 23190,
            startedAt = Instant.now().minusSeconds(30)
          ),
          store.snapshot().pools.head.id
        )
        // The next manager: a fresh backend whose range starts at the leftover's port.
        val next    = new RecordingLocal(23190, 23192, LocalQuackBackendSpec.sleepCmd(60))
        val nextSup =
          new PoolSupervisor(next, new NodeLoadTracker, store, nodeStartupGrace = 1.minute)
        try
          nextSup.restore()
          nextSup.reconcile().unsafeRunSync()
          next.all.map(_.node.port) should have size 1
          withClue("the replacement was started on the leftover's port: ") {
            next.all.head.node.port should not be 23190
          }
          leftover.isAlive shouldBe true
          nextSup.get(poolKey).get.nodes.map(_.port) should not contain 23190
          // The reservation ends with the leftover: its port is handed out again afterwards.
          kill(leftover.toHandle)
          leftover.waitFor(5, TimeUnit.SECONDS) shouldBe true
          listener.close()
          val deadline = System.currentTimeMillis() + 5000
          var port     = -1
          while port != 23190 && System.currentTimeMillis() < deadline do
            val probe = next
              .start(
                NodeSpec(poolKey, s"probe-${System.nanoTime()}", Role.Dual, Map.empty, Map.empty)
              )
              .unsafeRunSync()
            port = probe.port
            next.stop(poolKey, probe.nodeId).unsafeRunSync()
            if port != 23190 then Thread.sleep(50)
          port shouldBe 23190
        finally
          next.cleanup().unsafeRunSync()
          next.all.flatMap(_.handle).foreach(kill)
      }
    finally
      kill(leftover.toHandle)
      listener.close()
