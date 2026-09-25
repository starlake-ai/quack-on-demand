package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import ai.starlake.quack.ondemand.fleet.ServerLiveness
import ai.starlake.quack.ondemand.state.{
  FleetAssignment,
  Heartbeat,
  InMemoryFleetServerStore,
  NodeReport
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

class FleetQuackBackendSpec extends AnyFlatSpec with Matchers:

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
    def beat(): Unit               =
      val node = store.get(name).flatMap(_.assignment) match
        case Some(a) =>
          NodeReport(
            a.epoch,
            Some(a.nodeId),
            reportAs,
            Some(1L),
            if reportAs == "failed" then Some("boom") else None,
            Some(Instant.EPOCH)
          )
        case None => NodeReport(0, None, "none", None, None, None)
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
    var now     = Instant.parse("2026-09-25T10:00:00Z")
    val store   = new InMemoryFleetServerStore(clock = () => now)
    val backend = new FleetQuackBackend(store, cfg, clock = () => now, pollInterval = 20.millis)
    (store, backend, (t: Instant) => now = t, () => now)

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

  "stop" should "release and return even when the server is unreachable" in {
    val (store, backend, setNow, now) = fixture()
    val agent                         = new FakeAgent(store, "srv-1"); agent.beat()
    withAgent(agent)(backend.start(spec("n1")))
    setNow(now().plusSeconds(3600))
    backend.stop(pk, "n1").unsafeRunSync()
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
    backend.nodeRowExists = _ => false
    backend.discoverExisting().unsafeRunSync() shouldBe Nil
    store.get("srv-1").get.assignedNodeId shouldBe None
  }
