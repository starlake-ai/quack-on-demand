package ai.starlake.quack.ondemand.api

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.runtime.FleetQuackBackend
import ai.starlake.quack.ondemand.state.{
  ClaimMiss,
  FleetAssignment,
  FleetServerRow,
  FleetServerStore,
  Heartbeat,
  HeartbeatOutcome,
  InMemoryFleetServerStore,
  NodeReport
}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant

class FleetHandlersSpec extends AnyFlatSpec with Matchers:
  private val t0        = Instant.parse("2026-09-25T10:00:00Z")
  private def fixture() =
    val store = new InMemoryFleetServerStore(clock = () => t0)
    (store, new FleetHandlers(store, FleetConfig(joinToken = "secret", heartbeatSec = 7)))
  private def req(name: String = "srv-1", host: String = "10.0.0.1") =
    FleetHeartbeatRequest(
      name,
      host,
      21900,
      Some("0.9.7"),
      Some("linux"),
      Some("1.5.5"),
      Some(8),
      Some(64L << 30),
      FleetNodeReportDto(0, None, "none", None, None, None)
    )

  "heartbeat" should "401 on a wrong or missing token" in {
    val (_, h) = fixture()
    h.heartbeat(req(), Some("nope")).unsafeRunSync().left.map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.Unauthorized, "fleet_unauthorized"))
    h.heartbeat(req(), None).unsafeRunSync().left.map(_._1) shouldBe Left(StatusCode.Unauthorized)
    h.heartbeat(req(), Some("secret")).unsafeRunSync().isRight shouldBe true
  }

  it should "insert on first contact and answer with no assignment and the interval" in {
    val (store, h) = fixture()
    h.heartbeat(req(), Some("secret")).unsafeRunSync() shouldBe
      Right(FleetHeartbeatResponse(7, None))
    store.get("srv-1").map(r => (r.joinedAt, r.cpus, r.memoryBytes)) shouldBe
      Some((t0, Some(8), Some(64L << 30)))
  }

  it should "return the current assignment" in {
    val (store, h) = fixture()
    h.heartbeat(req(), Some("secret")).unsafeRunSync()
    store.claim(
      FleetAssignment(
        0,
        "n1",
        PoolKey("acme", "db", "bi"),
        21900,
        "tok",
        "memory",
        Map("pgHost" -> "h"),
        "",
        "",
        "",
        ""
      ),
      30,
      None
    )
    val r = h.heartbeat(req(), Some("secret")).unsafeRunSync().toOption.get
    r.assignment.map(a => (a.nodeId, a.epoch, a.poolKey.tenant, a.env("pgHost"))) shouldBe
      Some(("n1", 1L, "acme", "h"))
  }

  it should "409 an address change on a known name even when idle (no takeover)" in {
    val (store, h) = fixture()
    h.heartbeat(req(), Some("secret")).unsafeRunSync()
    val e = h.heartbeat(req(host = "10.0.0.2"), Some("secret")).unsafeRunSync().left.toOption.get
    (e._1, e._2.error) shouldBe (StatusCode.Conflict, "address_change_refused")
    store.setUnschedulable("srv-1", true)
    h.heartbeat(req(host = "10.0.0.2"), Some("secret")).unsafeRunSync().isRight shouldBe true
    store.get("srv-1").map(_.advertiseHost) shouldBe Some("10.0.0.2")
  }

  it should "400 an unknown node state" in {
    val (_, h) = fixture()
    val bad    = req().copy(node = FleetNodeReportDto(0, None, "sleeping", None, None, None))
    h.heartbeat(bad, Some("secret")).unsafeRunSync().left.map(_._2.error) shouldBe
      Left("invalid_node_state")
  }

  it should "400 a malformed startedAt instead of failing the request" in {
    val (_, h) = fixture()
    val bad    =
      req().copy(node = FleetNodeReportDto(0, None, "none", None, None, Some("yesterday")))
    h.heartbeat(bad, Some("secret")).unsafeRunSync().left.map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.BadRequest, "invalid_started_at"))
  }

  it should "map a raised store error to 502 backend_error (the agent retries next beat)" in {
    val failing = new FleetServerStore:
      def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome =
        throw new IllegalStateException("server row vanished")
      def claim(
          assignment: FleetAssignment,
          reachableWithinSec: Int,
          requiredMemoryBytes: Option[Long]
      ): Either[ClaimMiss, FleetServerRow] = Left(ClaimMiss.NoneFree)
      def setAssignment(name: String, a: FleetAssignment): Unit   = ()
      def release(nodeId: String): Option[String]                 = None
      def get(name: String): Option[FleetServerRow]               = None
      def list(): List[FleetServerRow]                            = Nil
      def byNodeId(nodeId: String): Option[FleetServerRow]        = None
      def setUnschedulable(name: String, value: Boolean): Boolean = false
      def delete(name: String): Boolean                           = false
    val h = new FleetHandlers(failing, FleetConfig(joinToken = "secret", heartbeatSec = 7))
    val e = h.heartbeat(req(), Some("secret")).unsafeRunSync().left.toOption.get
    (e._1, e._2.error) shouldBe (StatusCode.BadGateway, "backend_error")
    e._2.message should include("server row vanished")
  }

  // --- Admin surface (Task 8) ----------------------------------------------------------------

  private def adminFixture() =
    val store   = new InMemoryFleetServerStore(clock = () => t0)
    val cfg     = FleetConfig(joinToken = "secret", reassignAfterSec = 60)
    val backend = new FleetQuackBackend(store, cfg, clock = () => t0)
    val h       =
      new FleetHandlers(store, cfg, backend = Some(backend), publish = StateChangePublisher.noop)
    (store, h)
  private def beat(store: InMemoryFleetServerStore, name: String, host: String) =
    store.recordHeartbeat(
      Heartbeat(
        name,
        host,
        21900,
        None,
        None,
        None,
        Some(4),
        Some(32L << 30),
        NodeReport(0, None, "none", None, None, None)
      )
    )
  private def assignment(nodeId: String) =
    FleetAssignment(
      0,
      nodeId,
      PoolKey("acme", "db", "bi"),
      21900,
      "t",
      "memory",
      Map.empty,
      "",
      "",
      "",
      ""
    )
  private val superuser: String => Option[SessionScope]   = _ => Some(SessionScope.Superuser)
  private val tenantAdmin: String => Option[SessionScope] =
    _ => Some(SessionScope(false, Set("acme")))

  "listServers" should "classify liveness and carry the pool key of the assignment" in {
    val (store, h) = adminFixture()
    beat(store, "silent", "10.0.0.2"); store.backdate("silent", 100)
    beat(store, "fresh", "10.0.0.1")
    store.claim(assignment("n1"), 30, None)
    val servers =
      h.listServers(Some("k"))(superuser).unsafeRunSync().toOption.get.servers.sortBy(_.name)
    servers.map(s => (s.name, s.liveness, s.assignedNodeId, s.pool, s.cpus)) shouldBe List(
      ("fresh", "reachable", Some("n1"), Some("bi"), Some(4)),
      ("silent", "dead", None, None, Some(4))
    )
    servers.find(_.name == "silent").get.silentSeconds should be >= 100L
  }

  it should "refuse a tenant admin" in {
    val (_, h) = adminFixture()
    h.listServers(Some("k"))(tenantAdmin).unsafeRunSync().left.map(_._1) shouldBe
      Left(StatusCode.Forbidden)
  }

  "drain" should "mark unschedulable and release the assignment" in {
    val (store, h) = adminFixture()
    beat(store, "a", "10.0.0.1")
    store.claim(assignment("n1"), 30, None)
    h.drain(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    val row = store.get("a").get
    (row.unschedulable, row.assignedNodeId) shouldBe (true, None)
    h.undrain(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    store.get("a").get.unschedulable shouldBe false
    h.drain(FleetServerOpRequest("ghost"), Some("k"))(superuser)
      .unsafeRunSync()
      .left
      .map(_._1) shouldBe Left(StatusCode.NotFound)
  }

  "remove" should "409 while reachable and not drained, then delete" in {
    val (store, h) = adminFixture()
    beat(store, "a", "10.0.0.1")
    h.remove(FleetServerOpRequest("a"), Some("k"))(superuser)
      .unsafeRunSync()
      .left
      .map(_._2.error) shouldBe Left("server_active")
    h.drain(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    h.remove(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    store.get("a") shouldBe None
  }

  it should "400 fleet_disabled when the backend is not the fleet one" in {
    val h = new FleetHandlers(
      new InMemoryFleetServerStore(),
      FleetConfig(joinToken = "s"),
      backend = None,
      publish = StateChangePublisher.noop
    )
    h.listServers(Some("k"))(superuser).unsafeRunSync().left.map(_._2.error) shouldBe
      Left("fleet_disabled")
  }
