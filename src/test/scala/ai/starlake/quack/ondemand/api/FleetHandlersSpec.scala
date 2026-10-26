package ai.starlake.quack.ondemand.api

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.edge.authz.NodeKeys
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.fleet.ServerLiveness
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.runtime.FleetQuackBackend
import ai.starlake.quack.ondemand.telemetry.{AuditRecorder, NoopTelemetryStore}
import ai.starlake.quack.ondemand.state.{
  ApproveResult,
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

import java.net.InetAddress
import java.time.Instant

class FleetHandlersSpec extends AnyFlatSpec with Matchers:
  private val t0                            = Instant.parse("2026-09-25T10:00:00Z")
  private val hbCfg                         = FleetConfig(joinToken = "secret", heartbeatSec = 7)
  private val loopback: Option[InetAddress] = Some(InetAddress.getByName("127.0.0.1"))
  private def addr(s: String): Option[InetAddress]               = Some(InetAddress.getByName(s))
  private def fixture(nodeKeys: NodeKeys = NodeKeys.ephemeral()) =
    val store   = new InMemoryFleetServerStore(clock = () => t0)
    val backend = new FleetQuackBackend(store, hbCfg, clock = () => t0)
    (store, new FleetHandlers(store, hbCfg, backend = Some(backend), nodeKeys = nodeKeys))
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
    h.heartbeat(req(), Some("nope"), loopback, None)
      .unsafeRunSync()
      .left
      .map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.Unauthorized, "fleet_unauthorized"))
    h.heartbeat(req(), None, loopback, None).unsafeRunSync().left.map(_._1) shouldBe Left(
      StatusCode.Unauthorized
    )
    h.heartbeat(req(), Some("secret"), loopback, None).unsafeRunSync().isRight shouldBe true
  }

  it should "tell a server with the wrong token how to fix it, echoing no token" in {
    val (_, h) = fixture()
    val e = h.heartbeat(req(), Some("nope"), loopback, None).unsafeRunSync().left.toOption.get._2
    e.retryable shouldBe Some(false)
    e.actions.toList.flatten.map(_.command) shouldBe
      List("export QOD_FLEET_JOIN_TOKEN=<the manager's QOD_FLEET_JOIN_TOKEN>")
    e.toString should not include "secret"
    e.toString should not include "nope"
  }

  it should "insert on first contact and answer with no assignment and the interval" in {
    val (store, h) = fixture()
    h.heartbeat(req(), Some("secret"), loopback, None).unsafeRunSync() shouldBe
      Right(FleetHeartbeatResponse(7, None, "approved"))
    store.get("srv-1").map(r => (r.joinedAt, r.cpus, r.memoryBytes)) shouldBe
      Some((t0, Some(8), Some(64L << 30)))
  }

  it should "return the current assignment" in {
    val keys       = NodeKeys.ephemeral()
    val (store, h) = fixture(keys)
    h.heartbeat(req(), Some("secret"), loopback, None).unsafeRunSync()
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
    val r = h.heartbeat(req(), Some("secret"), loopback, None).unsafeRunSync().toOption.get
    r.assignment.map(a => (a.nodeId, a.epoch, a.poolKey.tenant, a.env("pgHost"))) shouldBe
      Some(("n1", 1L, "acme", "h"))
    // The node's key is derived from the assignment's node id on every heartbeat, never stored
    // (the check does not echo it).
    r.assignment.map(_.authzKey == keys.keyHex("n1")) shouldBe Some(true)
  }

  it should "409 an address change on a known name even when idle (no takeover)" in {
    val (store, h) = fixture()
    h.heartbeat(req(), Some("secret"), loopback, None).unsafeRunSync()
    val e = h
      .heartbeat(req(host = "10.0.0.2"), Some("secret"), loopback, None)
      .unsafeRunSync()
      .left
      .toOption
      .get
    (e._1, e._2.error) shouldBe (StatusCode.Conflict, "address_change_refused")
    e._2.message shouldBe "server 'srv-1' is registered at 10.0.0.1:21900; this heartbeat " +
      "reports 10.0.0.2:21900 (not drained, holds no node)"
    e._2.details.map(d => (d("registered"), d("reported"), d("drained"))) shouldBe
      Some(("10.0.0.1:21900", "10.0.0.2:21900", "false"))
    e._2.actions.toList.flatten.map(a => (a.where, a.command)) shouldBe List(
      ("server", "qod fleet join ... --name <another-name>"),
      ("manager", "qod fleet drain srv-1"),
      ("manager", "qod fleet remove srv-1")
    )
    e._2.retryable shouldBe Some(false)
    store.setUnschedulable("srv-1", true)
    h.heartbeat(req(host = "10.0.0.2"), Some("secret"), loopback, None)
      .unsafeRunSync()
      .isRight shouldBe true
    store.get("srv-1").map(_.advertiseHost) shouldBe Some("10.0.0.2")
  }

  it should "400 an unknown node state" in {
    val (_, h) = fixture()
    val bad    = req().copy(node = FleetNodeReportDto(0, None, "sleeping", None, None, None))
    h.heartbeat(bad, Some("secret"), loopback, None).unsafeRunSync().left.map(_._2.error) shouldBe
      Left("invalid_node_state")
  }

  it should "400 a malformed startedAt instead of failing the request" in {
    val (_, h) = fixture()
    val bad    =
      req().copy(node = FleetNodeReportDto(0, None, "none", None, None, Some("yesterday")))
    h.heartbeat(bad, Some("secret"), loopback, None)
      .unsafeRunSync()
      .left
      .map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.BadRequest, "invalid_started_at"))
  }

  it should "map a raised store error to 502 backend_error (the server retries next beat)" in {
    val failing = new FleetServerStore:
      def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome =
        throw new IllegalStateException("server row vanished")
      def claim(
          assignment: FleetAssignment,
          reachableWithinSec: Int,
          requiredMemoryBytes: Option[Long]
      ): Either[ClaimMiss, FleetServerRow] = Left(ClaimMiss.NoneFree)
      def claimReplacing(
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
      def approve(name: String, by: String): ApproveResult        = ApproveResult.NotFound
    val h = new FleetHandlers(
      failing,
      hbCfg,
      backend = Some(new FleetQuackBackend(failing, hbCfg, clock = () => t0))
    )
    val e = h.heartbeat(req(), Some("secret"), loopback, None).unsafeRunSync().left.toOption.get
    (e._1, e._2.error) shouldBe (StatusCode.BadGateway, "backend_error")
    // A fixed message: the cause goes to the manager log, never to the caller.
    e._2.message shouldBe "fleet store error, see manager log"
    e._2.retryable shouldBe Some(true)
    e._2.message should not include "vanished"
  }

  it should "400 fleet_disabled and write no server row when the manager is not in fleet mode" in {
    val store = new InMemoryFleetServerStore(clock = () => t0)
    val h     = new FleetHandlers(store, hbCfg, backend = None)
    h.heartbeat(req(), Some("secret"), loopback, None)
      .unsafeRunSync()
      .left
      .map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.BadRequest, "fleet_disabled"))
    h.heartbeat(req(), Some("secret"), loopback, None)
      .unsafeRunSync()
      .left
      .map(_._2.retryable) shouldBe Left(Some(false))
    store.list() shouldBe Nil
  }

  private def approvalFixture(autoApprove: String, trustedProxies: String = "") =
    val cfg = FleetConfig(
      joinToken = "secret",
      heartbeatSec = 7,
      autoApprove = autoApprove,
      trustedProxies = trustedProxies
    )
    val store   = new InMemoryFleetServerStore(clock = () => t0)
    val backend = new FleetQuackBackend(store, cfg, clock = () => t0)
    (store, new FleetHandlers(store, cfg, backend = Some(backend)))

  "heartbeat approval" should "leave a server joined from outside the list pending" in {
    val (store, h) = approvalFixture("10.0.0.0/8")
    val r          = h.heartbeat(req(), Some("secret"), addr("192.168.1.5"), None).unsafeRunSync()
    r shouldBe Right(FleetHeartbeatResponse(7, None, "pending"))
    val row = store.get("srv-1").get
    (row.approved, row.sourceAddr) shouldBe (false, Some("192.168.1.5"))
  }

  it should "approve a server joined from inside the list" in {
    val (store, h) = approvalFixture("10.0.0.0/8")
    h.heartbeat(req(), Some("secret"), addr("10.1.2.3"), None)
      .unsafeRunSync()
      .map(_.approval) shouldBe
      Right("approved")
    store.get("srv-1").get.approvedBy shouldBe Some("auto")
  }

  it should "approve nobody automatically with an empty list" in {
    val (store, h) = approvalFixture("")
    h.heartbeat(req(), Some("secret"), loopback, None).unsafeRunSync().map(_.approval) shouldBe
      Right("pending")
  }

  it should "ignore X-Forwarded-For from a peer that is not a trusted proxy" in {
    val (store, h) = approvalFixture("10.0.0.0/8")
    h.heartbeat(req(), Some("secret"), addr("192.168.1.5"), Some("10.0.0.9"))
      .unsafeRunSync()
      .map(_.approval) shouldBe Right("pending")
    store.get("srv-1").get.sourceAddr shouldBe Some("192.168.1.5")
  }

  it should "believe X-Forwarded-For behind a trusted proxy" in {
    val (store, h) = approvalFixture("10.0.0.0/8", trustedProxies = "192.168.1.0/24")
    h.heartbeat(req(), Some("secret"), addr("192.168.1.1"), Some("10.0.0.9"))
      .unsafeRunSync()
      .map(_.approval) shouldBe Right("approved")
    store.get("srv-1").get.sourceAddr shouldBe Some("10.0.0.9")
  }

  it should "leave an unknown address pending behind a trusted proxy" in {
    val (store, h) = approvalFixture("0.0.0.0/0,::/0", trustedProxies = "192.168.1.0/24")
    h.heartbeat(req(), Some("secret"), addr("192.168.1.1"), Some("garbage"))
      .unsafeRunSync()
      .map(_.approval) shouldBe Right("pending")
    store.get("srv-1").get.sourceAddr shouldBe None
  }

  it should "approve a waiting server once the list is widened (manager restart)" in {
    val (store, strict) = approvalFixture("10.0.0.0/8")
    strict.heartbeat(req(), Some("secret"), addr("192.168.1.5"), None).unsafeRunSync()
    val widened = new FleetHandlers(
      store,
      FleetConfig(joinToken = "secret", heartbeatSec = 7),
      backend = Some(new FleetQuackBackend(store, hbCfg, clock = () => t0))
    )
    widened
      .heartbeat(req(), Some("secret"), addr("192.168.1.5"), None)
      .unsafeRunSync()
      .map(_.approval) shouldBe Right("approved")
  }

  it should "never hand an assignment to a pending server, even one that holds it" in {
    val (store, h) = approvalFixture("10.0.0.0/8")
    h.heartbeat(req(), Some("secret"), addr("192.168.1.5"), None).unsafeRunSync()
    store.forceAssignment(
      "srv-1",
      FleetAssignment(
        1,
        "n1",
        PoolKey("acme", "db", "bi"),
        21900,
        "tok",
        "memory",
        Map("pgPassword" -> "p"),
        "",
        "",
        "",
        ""
      )
    )
    h.heartbeat(req(), Some("secret"), addr("192.168.1.5"), None).unsafeRunSync() shouldBe
      Right(FleetHeartbeatResponse(7, None, "pending"))
  }

  it should "409 source_change_refused for a heartbeat impersonating an approved server" in {
    val (store, h) = approvalFixture("10.0.0.0/8")
    h.heartbeat(req(), Some("secret"), addr("10.1.2.3"), None).unsafeRunSync()
    store.claim(
      FleetAssignment(
        0,
        "n1",
        PoolKey("acme", "db", "bi"),
        21900,
        "tok",
        "memory",
        Map("pgPassword" -> "p"),
        "",
        "",
        "",
        ""
      ),
      30,
      None
    )
    // Same name, advertised host and port, from a source outside the list.
    val e = h
      .heartbeat(req(), Some("secret"), addr("192.168.1.5"), None)
      .unsafeRunSync()
      .left
      .toOption
      .get
    (e._1, e._2.error) shouldBe (StatusCode.Conflict, "source_change_refused")
    e._2.message shouldBe "server 'srv-1' is approved from 10.1.2.3; this heartbeat comes from " +
      "192.168.1.5, outside QOD_FLEET_AUTO_APPROVE"
    e._2.details.map(d => (d("approvedSource"), d("source"), d("autoApprove"))) shouldBe
      Some(("10.1.2.3", "192.168.1.5", "10.0.0.0/8"))
    e._2.actions.toList.flatten.map(_.command).head shouldBe "qod fleet drain srv-1"
    e._2.retryable shouldBe Some(false)
    val row = store.get("srv-1").get
    (row.approved, row.approvedSource, row.sourceAddr) shouldBe
      (true, Some("10.1.2.3"), Some("10.1.2.3"))
    // The genuine server keeps its assignment.
    h.heartbeat(req(), Some("secret"), addr("10.1.2.3"), None)
      .unsafeRunSync()
      .map(_.assignment.map(_.nodeId)) shouldBe Right(Some("n1"))
  }

  it should "409 approval_unbound for a heartbeat from outside the list on an unbound approval" in {
    val (store, h) = approvalFixture("10.0.0.0/8")
    h.heartbeat(req(), Some("secret"), addr("10.1.2.3"), None).unsafeRunSync()
    store.claim(
      FleetAssignment(
        0,
        "n1",
        PoolKey("acme", "db", "bi"),
        21900,
        "tok",
        "memory",
        Map("pgPassword" -> "p"),
        "",
        "",
        "",
        ""
      ),
      30,
      None
    )
    // As the 0042 upgrade leaves a server that joined before the source was recorded.
    store.unbind("srv-1")
    // Same name, advertised host and port, from a source outside the list.
    val e = h
      .heartbeat(req(), Some("secret"), addr("192.168.1.5"), None)
      .unsafeRunSync()
      .left
      .toOption
      .get
    (e._1, e._2.error) shouldBe (StatusCode.Conflict, "approval_unbound")
    e._2.message shouldBe "server 'srv-1' was approved before its source address was recorded, " +
      "and this heartbeat comes from 192.168.1.5, outside QOD_FLEET_AUTO_APPROVE"
    val first = e._2.actions.toList.flatten.head
    first.command shouldBe "qod fleet drain srv-1"
    first.note.get should include("qod fleet undrain srv-1")
    e._2.retryable shouldBe Some(false)
    e._2.message should not include "\u2014"
    val row = store.get("srv-1").get
    (row.approved, row.approvedSource, row.sourceAddr) shouldBe (true, None, Some("10.1.2.3"))
    // A heartbeat from inside the list binds the approval and gets the assignment.
    h.heartbeat(req(), Some("secret"), addr("10.1.2.3"), None)
      .unsafeRunSync()
      .map(_.assignment.map(_.nodeId)) shouldBe Right(Some("n1"))
    store.get("srv-1").get.approvedSource shouldBe Some("10.1.2.3")
  }

  // --- Admin surface (Task 8) ----------------------------------------------------------------

  // The approver an approval records is the calling user, resolved like the audit actor: the token
  // "k" every admin case sends is a superuser session for "root" here.
  private val rootAudit = new AuditRecorder(
    NoopTelemetryStore,
    t =>
      Option.when(t == "k")(
        SessionTokenStore.Session(
          AuthenticatedProfile("root", "admin", Set.empty, Map.empty, "db", None),
          SessionScope.Superuser,
          t0
        )
      )
  )
  private def adminFixture() =
    val store   = new InMemoryFleetServerStore(clock = () => t0)
    val cfg     = FleetConfig(joinToken = "secret", reassignAfterSec = 60)
    val backend = new FleetQuackBackend(store, cfg, clock = () => t0)
    val h       = new FleetHandlers(
      store,
      cfg,
      backend = Some(backend),
      publish = StateChangePublisher.noop,
      audit = rootAudit
    )
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
        NodeReport(0, None, "none", None, None, None),
        // This helper builds an already-approved row for admin-surface tests unrelated to
        // approval binding; the source must be known for an auto-approval to be constructible.
        sourceAddr = Some(host),
        autoApprove = true
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
  private val superuser: SessionScope.Resolver   = _ => SessionScope.Superuser
  private val tenantAdmin: SessionScope.Resolver =
    _ => SessionScope(false, Set("acme"))

  "listServers" should "carry the node's suggested action from the heartbeat" in {
    val (_, h) = adminFixture()
    val failed = req("a").copy(node =
      FleetNodeReportDto(0, None, "failed", None, Some("boom"), None, action = Some("fix it"))
    )
    h.heartbeat(failed, Some("secret"), loopback, None).unsafeRunSync().isRight shouldBe true
    h.listServers(Some("k"))(superuser)
      .unsafeRunSync()
      .toOption
      .get
      .servers
      .map(_.nodeAction) shouldBe List(Some("fix it"))
  }

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

  it should "name a missing server and point at the listing" in {
    val (_, h) = adminFixture()
    val e      = h
      .drain(FleetServerOpRequest("ghost"), Some("k"))(superuser)
      .unsafeRunSync()
      .left
      .toOption
      .get
    (e._1, e._2.message) shouldBe (StatusCode.NotFound, "no such server 'ghost'")
    e._2.actions.toList.flatten.map(_.command) shouldBe List("qod fleet servers")
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

  it should "explain why an active server cannot be removed and how to retire it" in {
    val (store, h) = adminFixture()
    beat(store, "a", "10.0.0.1")
    val e = h
      .remove(FleetServerOpRequest("a"), Some("k"))(superuser)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._2
    e.message shouldBe "server 'a' is active (reachable, schedulable, holds no node, last " +
      "heartbeat 0s ago): its qod fleet join would re-register it"
    e.actions.toList.flatten.map(a => (a.where, a.command, a.note)) shouldBe List(
      (
        "manager",
        "qod fleet drain a",
        Some("then stop qod fleet join on a, then qod fleet remove a")
      )
    )
    e.retryable shouldBe Some(false)
  }

  it should "remove a reachable, schedulable server that is still pending" in {
    val (store, h) = adminFixture()
    store.recordHeartbeat(
      Heartbeat(
        "p",
        "10.0.0.1",
        21900,
        None,
        None,
        None,
        None,
        None,
        NodeReport(0, None, "none", None, None, None),
        sourceAddr = Some("10.0.0.1"),
        autoApprove = false
      )
    )
    store.get("p").get.unschedulable shouldBe false
    h.remove(FleetServerOpRequest("p"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    store.get("p") shouldBe None
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

  "approve" should "approve a pending server, idempotently, superuser only, recording the approving user" in {
    val (store, h) = adminFixture()
    store.recordHeartbeat(
      Heartbeat(
        "p",
        "10.0.0.1",
        21900,
        None,
        None,
        None,
        None,
        None,
        NodeReport(0, None, "none", None, None, None),
        sourceAddr = Some("10.0.0.1"),
        autoApprove = false
      )
    )
    h.approve(FleetServerOpRequest("p"), Some("k"))(tenantAdmin)
      .unsafeRunSync()
      .left
      .map(_._1) shouldBe
      Left(StatusCode.Forbidden)
    store.get("p").get.approved shouldBe false
    h.approve(FleetServerOpRequest("p"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    val row = store.get("p").get
    row.approved shouldBe true
    row.approvedBy shouldBe Some("root")
    h.approve(FleetServerOpRequest("p"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    h.approve(FleetServerOpRequest("ghost"), Some("k"))(superuser)
      .unsafeRunSync()
      .left
      .map(e => (e._1, e._2.error)) shouldBe Left((StatusCode.NotFound, "not_found"))
  }

  it should "409 source_unknown for a pending server whose source is unknown" in {
    val (store, h) = adminFixture()
    store.recordHeartbeat(
      Heartbeat(
        "p",
        "10.0.0.1",
        21900,
        None,
        None,
        None,
        None,
        None,
        NodeReport(0, None, "none", None, None, None),
        sourceAddr = None,
        autoApprove = false
      )
    )
    val e =
      h.approve(FleetServerOpRequest("p"), Some("k"))(superuser).unsafeRunSync().left.toOption.get
    (e._1, e._2.error) shouldBe (StatusCode.Conflict, "source_unknown")
    e._2.message shouldBe "server 'p' has no known source address yet (last heartbeat 0s ago); " +
      "approve it after its next heartbeat"
    e._2.actions.toList.flatten.map(_.command) shouldBe List(
      "qod fleet approve p",
      "set QOD_FLEET_TRUSTED_PROXIES to the proxy's address"
    )
    e._2.retryable shouldBe Some(true)
    val row = store.get("p").get
    (row.approved, row.approvedBy, row.approvedSource) shouldBe (false, None, None)
  }

  "listServers" should "carry approval, approver, time and source address" in {
    val (store, h) = adminFixture()
    store.recordHeartbeat(
      Heartbeat(
        "p",
        "10.0.0.1",
        21900,
        None,
        None,
        None,
        None,
        None,
        NodeReport(0, None, "none", None, None, None),
        sourceAddr = Some("192.168.1.5"),
        autoApprove = false
      )
    )
    beat(store, "a", "10.0.0.2")
    val servers =
      h.listServers(Some("k"))(superuser).unsafeRunSync().toOption.get.servers.sortBy(_.name)
    servers.map(s => (s.name, s.approval)) shouldBe List("a" -> "approved", "p" -> "pending")
    servers.find(_.name == "p").get.sourceAddr shouldBe Some("192.168.1.5")
    servers.find(_.name == "a").get.approvedBy shouldBe Some("auto")
    servers.find(_.name == "a").get.approvedAt should not be empty
    servers.find(_.name == "p").get.approvedSource shouldBe None
    h.approve(FleetServerOpRequest("p"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    h.listServers(Some("k"))(superuser)
      .unsafeRunSync()
      .toOption
      .get
      .servers
      .find(_.name == "p")
      .get
      .approvedSource shouldBe Some("192.168.1.5")
  }

  /** Delegating store that records releases and can run a hook just before setUnschedulable. */
  private final class ProbeStore(inner: InMemoryFleetServerStore) extends FleetServerStore:
    var beforeSetUnschedulable: () => Unit = () => ()
    // When set, recordHeartbeat answers this without touching the store (a race stand-in).
    var heartbeatOutcome: Option[HeartbeatOutcome] = None
    val released = scala.collection.mutable.ListBuffer.empty[String]
    def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome =
      heartbeatOutcome.getOrElse(inner.recordHeartbeat(hb))
    def claim(
        assignment: FleetAssignment,
        reachableWithinSec: Int,
        requiredMemoryBytes: Option[Long]
    ): Either[ClaimMiss, FleetServerRow] =
      inner.claim(assignment, reachableWithinSec, requiredMemoryBytes)
    def claimReplacing(
        assignment: FleetAssignment,
        reachableWithinSec: Int,
        requiredMemoryBytes: Option[Long]
    ): Either[ClaimMiss, FleetServerRow] =
      inner.claimReplacing(assignment, reachableWithinSec, requiredMemoryBytes)
    def setAssignment(name: String, a: FleetAssignment): Unit = inner.setAssignment(name, a)
    def release(nodeId: String): Option[String]               =
      released += nodeId
      inner.release(nodeId)
    def get(name: String): Option[FleetServerRow]               = inner.get(name)
    def list(): List[FleetServerRow]                            = inner.list()
    def byNodeId(nodeId: String): Option[FleetServerRow]        = inner.byNodeId(nodeId)
    def setUnschedulable(name: String, value: Boolean): Boolean =
      beforeSetUnschedulable()
      inner.setUnschedulable(name, value)
    def delete(name: String): Boolean                    = inner.delete(name)
    def approve(name: String, by: String): ApproveResult = inner.approve(name, by)

  private final class CountingPublisher extends StateChangePublisher:
    var topology                = 0
    def topologyChanged(): Unit = topology += 1
    def rbacChanged(): Unit     = ()

  private def probeFixture() =
    val inner   = new InMemoryFleetServerStore(clock = () => t0)
    val probe   = new ProbeStore(inner)
    val cfg     = FleetConfig(joinToken = "secret", reassignAfterSec = 60)
    val backend = new FleetQuackBackend(probe, cfg, clock = () => t0)
    val pub     = new CountingPublisher
    val h       = new FleetHandlers(probe, cfg, backend = Some(backend), publish = pub)
    (inner, probe, pub, h)

  "heartbeat" should "not reply with an assignment bound to another source, whatever the store said" in {
    val (inner, probe, _, h) = probeFixture()
    h.heartbeat(req(), Some("secret"), addr("10.1.2.3"), None).unsafeRunSync()
    inner.claim(assignment("n1"), 30, None).isRight shouldBe true
    // A store answer racing a concurrent rebind: the reply is read afresh and bound elsewhere.
    probe.heartbeatOutcome = Some(HeartbeatOutcome.Updated)
    h.heartbeat(req(), Some("secret"), addr("10.9.9.9"), None)
      .unsafeRunSync()
      .map(_.assignment) shouldBe Right(None)
    h.heartbeat(req(), Some("secret"), addr("10.1.2.3"), None)
      .unsafeRunSync()
      .map(_.assignment.map(_.nodeId)) shouldBe Right(Some("n1"))
  }

  "drain" should "release an assignment claimed between its read and the unschedulable flip" in {
    val (inner, probe, _, h) = probeFixture()
    beat(inner, "a", "10.0.0.1")
    // The row handed to drain is idle; the claim lands just before the flip.
    probe.beforeSetUnschedulable = () =>
      probe.beforeSetUnschedulable = () => ()
      inner.claim(assignment("n-late"), 30, None)
      ()
    h.drain(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    inner.get("a").map(r => (r.unschedulable, r.assignedNodeId)) shouldBe Some((true, None))
    probe.released.toList shouldBe List("n-late")
  }

  it should "succeed on an idle server and release nothing" in {
    val (inner, probe, pub, h) = probeFixture()
    beat(inner, "a", "10.0.0.1")
    h.drain(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    inner.get("a").map(_.unschedulable) shouldBe Some(true)
    probe.released shouldBe empty
    pub.topology shouldBe 1
  }

  "undrain" should "publish topology like drain" in {
    val (inner, _, pub, h) = probeFixture()
    beat(inner, "a", "10.0.0.1")
    h.drain(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    h.undrain(FleetServerOpRequest("a"), Some("k"))(superuser).unsafeRunSync() shouldBe Right(())
    pub.topology shouldBe 2
  }

  "livenessString" should "name all three liveness classes" in {
    FleetHandlers.livenessString(ServerLiveness.Reachable) shouldBe "reachable"
    FleetHandlers.livenessString(ServerLiveness.Unreachable(42)) shouldBe "unreachable"
    FleetHandlers.livenessString(ServerLiveness.Dead) shouldBe "dead"
  }

  "livenessByName" should "map every server to its liveness in one listing" in {
    val store   = new InMemoryFleetServerStore(clock = () => t0)
    val cfg     = FleetConfig(joinToken = "secret", heartbeatTimeoutSec = 15, reassignAfterSec = 60)
    val backend = new FleetQuackBackend(store, cfg, clock = () => t0)
    beat(store, "up", "10.0.0.1")
    beat(store, "late", "10.0.0.2"); store.backdate("late", 30)
    beat(store, "gone", "10.0.0.3"); store.backdate("gone", 100)
    FleetHandlers.livenessByName(store, backend) shouldBe
      Map("up" -> "reachable", "late" -> "unreachable", "gone" -> "dead")
  }
