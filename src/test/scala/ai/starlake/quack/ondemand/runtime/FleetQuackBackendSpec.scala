package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import ai.starlake.quack.ondemand.fleet.ServerLiveness
import ai.starlake.quack.ondemand.state.{
  FleetAssignment,
  FleetServerRow,
  FleetServerStore,
  Heartbeat,
  InMemoryFleetServerStore,
  NodeReport
}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

class FleetQuackBackendSpec extends AnyFlatSpec with Matchers:

  /** The one movable instant behind both the store's clock and the backend's deadline clock. A
    * FakeAgent beat moves it one second forward first, so a heartbeat is always strictly later than
    * the claim it answers (the backend only trusts reports from after the claim).
    */
  @volatile private var clockNow: Instant = Instant.EPOCH

  private val pk               = PoolKey("acme", "db", "bi")
  private def spec(id: String) =
    NodeSpec(
      pk,
      id,
      Role.Dual,
      Map("pgHost" -> "h", "pgPassword" -> "pw"),
      Map.empty,
      kindWire = "memory"
    )

  /** A fake agent: heartbeats for `name`, and once assigned reports the state the test dictates. */
  private final class FakeAgent(
      store: InMemoryFleetServerStore,
      name: String,
      host: String = "10.0.0.1",
      memoryBytes: Option[Long] = Some(64L << 30)
  ):
    @volatile var reportAs: String = "running"
    // Like the real agent: once unassigned, it keeps reporting `stopped` with the epoch and node
    // id of the assignment it last ran (never the server row's bumped epoch).
    @volatile private var lastRun: Option[FleetAssignment] = None
    def beat(): Unit                                       =
      clockNow = clockNow.plusSeconds(1)
      val node = store.get(name).flatMap(_.assignment) match
        case Some(a) =>
          lastRun = Some(a)
          NodeReport(
            a.epoch,
            Some(a.nodeId),
            reportAs,
            Some(1L),
            if reportAs == "failed" then Some("boom") else None,
            Some(Instant.EPOCH)
          )
        case None =>
          lastRun match
            case Some(a) => NodeReport(a.epoch, Some(a.nodeId), "stopped", None, None, None)
            case None    => NodeReport(0, None, "none", None, None, None)
      store.recordHeartbeat(
        Heartbeat(
          name,
          host,
          21900,
          Some("t"),
          Some("linux"),
          Some("1.5"),
          Some(8),
          memoryBytes,
          node
        )
      )

  /** Both the store's clock and the backend's deadline clock are the same movable instant. */
  private def fixture(
      cfg: FleetConfig = FleetConfig(joinToken = "j", startupTimeoutSec = 2, stopTimeoutSec = 1)
  ) =
    clockNow = Instant.parse("2026-09-25T10:00:00Z")
    val store   = new InMemoryFleetServerStore(clock = () => clockNow)
    val backend =
      new FleetQuackBackend(store, cfg, clock = () => clockNow, pollInterval = 20.millis)
    (store, backend, (t: Instant) => clockNow = t, () => clockNow)

  /** Advances the shared clock one second every 50ms while it runs. */
  private val ticker: IO[Nothing] =
    (IO.sleep(50.millis) *> IO.delay { clockNow = clockNow.plusSeconds(1) }).foreverM

  /** Start `id` in a fiber, wait for its claim to land, then have `agent` beat exactly once. */
  private def startThenBeatOnce(
      store: InMemoryFleetServerStore,
      backend: FleetQuackBackend,
      agent: FakeAgent,
      server: String,
      id: String
  ): IO[ai.starlake.quack.model.RunningNode] =
    val claimed             = IO.blocking(store.get(server).flatMap(_.assignedNodeId).contains(id))
    def waitClaim: IO[Unit] =
      claimed.flatMap(c => if c then IO.unit else IO.sleep(10.millis) *> waitClaim)
    backend.start(spec(id)).start.flatMap { fib =>
      waitClaim *> IO.blocking(agent.beat()) *> fib.joinWithNever
    }

  /** Run `io` while a fake agent keeps beating every 30ms in the background. */
  private def withAgent[A](agent: FakeAgent)(io: IO[A]): A =
    val beats = IO.blocking(agent.beat()).flatMap(_ => IO.sleep(30.millis)).foreverM
    beats.background.use(_ => io).unsafeRunSync()

  "start" should "claim a free server, wait for running and return the server's address" in {
    val (store, backend, _, _) = fixture()
    val agent                  = new FakeAgent(store, "srv-1", host = "10.0.0.7"); agent.beat()
    val n                      = withAgent(agent)(backend.start(spec("quack-acme-db-bi-1")))
    (n.host, n.port, n.serverName, n.pid) shouldBe ("10.0.0.7", 21900, Some("srv-1"), None)
    store.get("srv-1").get.assignment.map(a => (a.nodeId, a.env("pgPassword"), a.port)) shouldBe
      Some(("quack-acme-db-bi-1", "pw", 21900))
    n.token should not be empty
  }

  it should "prepend the pool's cpu/memory as DuckDB SETs to dbInitSql and pass memory to the claim" in {
    val (store, backend, _, _) = fixture()
    val small = new FakeAgent(store, "small", memoryBytes = Some(512L << 20)); small.beat()
    val big   = new FakeAgent(store, "big", memoryBytes = Some(64L << 30)); big.beat()
    val sized =
      spec("n1").copy(cpu = Some("2"), memory = Some("1Gi"), dbInitSql = "SET threads = 8;")
    val n = withAgent(big)(backend.start(sized))
    n.serverName shouldBe Some("big")
    store.get("big").get.assignment.map(_.dbInitSql) shouldBe
      Some("SET threads = 2;\nSET memory_limit = '1024MiB';\nSET threads = 8;")
  }

  it should "raise NoFreeServer with a reason" in {
    val (store, backend, _, _) = fixture()
    the[NoFreeServer] thrownBy backend.start(spec("n1")).unsafeRunSync() should have message
      "no fleet server for acme/db/bi/n1 (none_free)"
    val small = new FakeAgent(store, "small", memoryBytes = Some(512L << 20)); small.beat()
    (the[NoFreeServer] thrownBy backend
      .start(spec("n1").copy(memory = Some("8Gi")))
      .unsafeRunSync()).reason shouldBe "none_fits"
  }

  it should "release the claim and raise FleetStartTimeout when the agent never reports running" in {
    val (store, backend, setNow, now) = fixture()
    val agent = new FakeAgent(store, "srv-1"); agent.reportAs = "starting"; agent.beat()
    // The deadline clock must advance while the poll loop runs; tick it from a background fiber.
    val ticker = (IO.sleep(50.millis) *> IO.delay(setNow(now().plusSeconds(1)))).foreverM
    a[FleetStartTimeout] should be thrownBy ticker.background
      .use(_ => IO.delay(withAgent(agent)(backend.start(spec("n1")))))
      .unsafeRunSync()
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  it should "fail fast with the agent's error on a failed report" in {
    val (store, backend, _, _) = fixture()
    val agent = new FakeAgent(store, "srv-1"); agent.reportAs = "failed"; agent.beat()
    val e     = the[FleetNodeFailed] thrownBy withAgent(agent)(backend.start(spec("n1")))
    e.error shouldBe "boom"
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  it should "release a stale assignment of the same node id before claiming another server" in {
    val (store, backend, setNow, now) =
      fixture(FleetConfig(joinToken = "j", startupTimeoutSec = 2, reassignAfterSec = 60))
    val old = new FakeAgent(store, "old"); old.beat()
    withAgent(old)(backend.start(spec("n1"))).serverName shouldBe Some("old")
    setNow(now().plusSeconds(120)) // old is now Dead (silent 120s > 60s grace)
    val fresh = new FakeAgent(store, "fresh"); fresh.beat()
    withAgent(fresh)(backend.start(spec("n1"))).serverName shouldBe Some("fresh")
    store.get("old").get.assignedNodeId shouldBe None
  }

  it should "release a drained holder of the same node id before claiming another server" in {
    val (store, backend, _, _) = fixture()
    val a                      = new FakeAgent(store, "a"); a.beat()
    withAgent(a)(backend.start(spec("n1")))
    store.setUnschedulable("a", true)
    val b = new FakeAgent(store, "b"); b.beat()
    withAgent(b)(backend.start(spec("n1"))).serverName shouldBe Some("b")
    store.get("a").get.assignedNodeId shouldBe None
  }

  it should "release a reachable running holder of the same node id before claiming" in {
    val (store, backend, _, _) = fixture()
    val a                      = new FakeAgent(store, "a"); a.beat()
    val b                      = new FakeAgent(store, "b"); b.beat()
    // A crash orphan: claimed and running on `a`, no node row, `a` reachable and schedulable.
    withAgent(a)(backend.start(spec("n1"))).serverName shouldBe Some("a")
    store.get("a").get.nodeState shouldBe "running"
    val beats = (IO.blocking { a.beat(); b.beat() } *> IO.sleep(30.millis)).foreverM
    val n     = beats.background.use(_ => backend.start(spec("n1"))).unsafeRunSync()
    store.list().count(_.assignedNodeId.contains("n1")) shouldBe 1
    n.serverName.flatMap(store.get).flatMap(_.assignedNodeId) shouldBe Some("n1")
  }

  it should "fail fast with FleetClaimLost when the claim is taken away while waiting" in {
    val (store, backend, _, _) =
      fixture(FleetConfig(joinToken = "j", startupTimeoutSec = 600, stopTimeoutSec = 1))
    val agent   = new FakeAgent(store, "srv-1"); agent.reportAs = "starting"; agent.beat()
    val claimed = IO.blocking(store.get("srv-1").flatMap(_.assignedNodeId).isDefined)
    def waitClaim: IO[Unit] =
      claimed.flatMap(c => if c then IO.unit else IO.sleep(10.millis) *> waitClaim)
    // A drain releases the assignment under the waiting start. Without the fast fail the wait
    // runs to the 600s deadline (18s of wall time at one beat per 30ms); 5s is the ceiling.
    val run = backend.start(spec("n1")).start.flatMap { fib =>
      waitClaim *> IO.blocking {
        store.setUnschedulable("srv-1", true); store.release("n1")
      } *> fib.joinWithNever
    }
    val e = the[FleetClaimLost] thrownBy withAgent(agent)(run.timeout(5.seconds))
    (e.server, e.nodeId) shouldBe ("srv-1", "n1")
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  "stop" should "release and return even when the server is unreachable" in {
    val (store, backend, setNow, now) = fixture()
    val agent                         = new FakeAgent(store, "srv-1"); agent.beat()
    withAgent(agent)(backend.start(spec("n1")))
    setNow(now().plusSeconds(3600))
    backend.stop(pk, "n1").unsafeRunSync()
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  it should "return as soon as the agent reports stopped" in {
    val (store, backend, _, now) =
      fixture(FleetConfig(joinToken = "j", startupTimeoutSec = 2, stopTimeoutSec = 600))
    val agent = new FakeAgent(store, "srv-1"); agent.beat()
    withAgent(agent)(backend.start(spec("n1")))
    val before = now()
    val wall0  = System.nanoTime()
    // The agent beats every 30ms (one simulated second each): running the 600s deadline out
    // would take 18s of wall time and 600 simulated seconds.
    withAgent(agent)(backend.stop(pk, "n1"))
    val wallMs = (System.nanoTime() - wall0) / 1000000
    java.time.Duration.between(before, now()).getSeconds should be < 10L
    wallMs should be < 3000L
    store.get("srv-1").get.nodeState shouldBe "stopped"
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  "liveNodeIds" should "include nodes on reachable and grace-window servers, exclude dead ones, filter on the pool key" in {
    val (store, backend, setNow, now) =
      fixture(FleetConfig(joinToken = "j", startupTimeoutSec = 2, reassignAfterSec = 60))
    val a = new FakeAgent(store, "a"); a.beat()
    withAgent(a)(backend.start(spec("quack-acme-db-bi-1")))
    val b = new FakeAgent(store, "b"); b.beat()
    withAgent(b)(backend.start(spec("quack-acme-db-bi-2")))
    setNow(now().plusSeconds(45)); a.beat() // b silent 45s: unreachable, inside grace
    backend.liveNodeIds(pk).unsafeRunSync() shouldBe Some(
      Set("quack-acme-db-bi-1", "quack-acme-db-bi-2")
    )
    setNow(now().plusSeconds(30)); a.beat() // b silent 75s: dead
    backend.liveNodeIds(pk).unsafeRunSync() shouldBe Some(Set("quack-acme-db-bi-1"))
    backend.liveNodeIds(PoolKey("acme", "db", "bi-x")).unsafeRunSync() shouldBe Some(Set.empty)
  }

  "discoverExisting" should "release claims older than the startup timeout that never reached running and have no node row" in {
    val (store, backend, setNow, now) = fixture()
    val agent = new FakeAgent(store, "srv-1"); agent.reportAs = "starting"; agent.beat()
    store.claim(
      FleetAssignment(0, "orphan", pk, 21900, "t", "memory", Map.empty, "", "", "", ""),
      30,
      None
    )
    setNow(now().plusSeconds(10)); agent.beat()
    // Until Main wires nodeRowExists, every claim counts as backed by a node row: release nothing.
    backend.discoverExisting().unsafeRunSync() shouldBe Nil
    store.get("srv-1").get.assignedNodeId shouldBe Some("orphan")
    backend.nodeRowExists = _ => false
    backend.discoverExisting().unsafeRunSync() shouldBe Nil
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  it should "measure claim age on the store clock, not on a manager clock running ahead" in {
    val (store, _, setNow, now) = fixture()
    val agent = new FakeAgent(store, "srv-1"); agent.reportAs = "starting"; agent.beat()
    store.claim(
      FleetAssignment(0, "fresh", pk, 21900, "t", "memory", Map.empty, "", "", "", ""),
      30,
      None
    )
    // A newly promoted manager whose JVM clock runs an hour ahead of the database: the claim is
    // one second old on the store clock, so another replica may still be starting it.
    val ahead = new FleetQuackBackend(
      store,
      FleetConfig(joinToken = "j", startupTimeoutSec = 2, stopTimeoutSec = 1),
      clock = () => now().plusSeconds(3600),
      pollInterval = 20.millis
    )
    ahead.nodeRowExists = _ => false
    setNow(now().plusSeconds(1))
    ahead.discoverExisting().unsafeRunSync() shouldBe Nil
    store.get("srv-1").get.assignedNodeId shouldBe Some("fresh")
    // Past the startup timeout on the store clock it is an orphan, whatever the JVM clock says.
    setNow(now().plusSeconds(10))
    ahead.discoverExisting().unsafeRunSync() shouldBe Nil
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  it should "leave a running claim with no node row alone" in {
    val (store, backend, setNow, now) = fixture()
    val agent                         = new FakeAgent(store, "srv-1"); agent.beat()
    // An ephemeral maintenance/merge node: claimed and running, never given a node row.
    withAgent(agent)(backend.start(spec("quack-acme-db-bi__maint-1")))
    setNow(now().plusSeconds(3600)); agent.beat() // far past the startup timeout, still reachable
    backend.nodeRowExists = _ => false
    backend.discoverExisting().unsafeRunSync() shouldBe Nil
    store.get("srv-1").get.assignedNodeId shouldBe Some("quack-acme-db-bi__maint-1")
  }

  "start (stale reports)" should "ignore a failed state left by the previous assignment" in {
    val (store, backend, _, _) = fixture()
    val agent                  = new FakeAgent(store, "srv-1"); agent.reportAs = "failed"
    agent.beat()
    a[FleetNodeFailed] should be thrownBy
      startThenBeatOnce(store, backend, agent, "srv-1", "n1").unsafeRunSync()
    // Precondition: the server row still carries the old failure, and nobody beats again.
    store.get("srv-1").get.nodeState shouldBe "failed"
    a[FleetStartTimeout] should be thrownBy
      ticker.background.use(_ => backend.start(spec("n2"))).unsafeRunSync()
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  it should "not return on a running state left by the previous assignment" in {
    val (store, backend, _, _) = fixture()
    val agent                  = new FakeAgent(store, "srv-1"); agent.beat()
    startThenBeatOnce(store, backend, agent, "srv-1", "n1").unsafeRunSync()
    // stop runs out its deadline: no agent confirms, the state stays "running".
    ticker.background.use(_ => backend.stop(pk, "n1")).unsafeRunSync()
    store.get("srv-1").get.nodeState shouldBe "running"
    val result = (for
      done  <- Ref.of[IO, Option[String]](None)
      fib   <- backend.start(spec("n2")).flatTap(n => done.set(n.serverName)).start
      _     <- IO.sleep(300.millis)
      early <- done.get
      _     <- IO.blocking(store.get("srv-1").get.assignedNodeId shouldBe Some("n2"))
      _     <- IO.blocking(agent.beat())
      n     <- fib.joinWithNever
    yield (early, n.serverName)).unsafeRunSync()
    result shouldBe (None, Some("srv-1"))
  }

  "start (claim safety)" should "release the claim when the poll fails after the claim" in {
    val (inner, _, _, _) = fixture()
    final class ThrowingGet(underlying: InMemoryFleetServerStore) extends FleetServerStore:
      export underlying.{get as _, *}
      def get(name: String): Option[FleetServerRow] = throw new RuntimeException("pg down")
    val backend = new FleetQuackBackend(
      new ThrowingGet(inner),
      FleetConfig(joinToken = "j", startupTimeoutSec = 2, stopTimeoutSec = 1),
      clock = () => clockNow,
      pollInterval = 20.millis
    )
    new FakeAgent(inner, "srv-1").beat()
    (the[RuntimeException] thrownBy backend.start(spec("n1")).unsafeRunSync()).getMessage shouldBe
      "pg down"
    inner.get("srv-1").get.assignedNodeId shouldBe None
  }

  it should "release the claim when the start is cancelled" in {
    val (store, backend, _, _) =
      fixture(FleetConfig(joinToken = "j", startupTimeoutSec = 600, stopTimeoutSec = 1))
    val agent = new FakeAgent(store, "srv-1"); agent.reportAs = "starting"; agent.beat()
    a[TimeoutException] should be thrownBy
      withAgent(agent)(backend.start(spec("n1")).timeout(200.millis))
    store.get("srv-1").get.assignedNodeId shouldBe None
  }

  "stop" should "never fail, even when the store does" in {
    val (inner, _, _, _) = fixture()
    final class ThrowingRelease(underlying: InMemoryFleetServerStore) extends FleetServerStore:
      export underlying.{release as _, *}
      def release(nodeId: String): Option[String] = throw new RuntimeException("pg down")
    val backend = new FleetQuackBackend(new ThrowingRelease(inner), FleetConfig(joinToken = "j"))
    noException should be thrownBy backend.stop(pk, "n1").unsafeRunSync()
  }
