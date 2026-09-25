package ai.starlake.quack.ondemand.api

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.state.{
  ClaimMiss,
  FleetAssignment,
  FleetServerRow,
  FleetServerStore,
  Heartbeat,
  HeartbeatOutcome,
  InMemoryFleetServerStore
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
