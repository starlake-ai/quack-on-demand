package ai.starlake.quack.security

import ai.starlake.quack.edge.EdgeHandshake
import ai.starlake.quack.edge.adapter.QuackTransport
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.quack.{QuackFrontDoor, QuackSessionRegistry}
import ai.starlake.quack.edge.quack.QuackWire.*
import ai.starlake.quack.ondemand.telemetry.{AuditEvent, HandshakeAudit}
import ai.starlake.quack.spi.ManagerEventSink
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.flight.{
  FlightCallHeaders,
  FlightDescriptor,
  FlightRuntimeException,
  FlightStatusCode,
  HeaderCallOption
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.util.Base64
import scala.collection.mutable

/** Issue #202: a refused FlightSQL or native Quack handshake writes `auth.login.failure`, as a
  * refused console login does: the user name as given, the tenant and pool, the client's address
  * and a reason, never the credential; at most one row a minute per (front door, address, user).
  */
class DataPlaneLoginAuditSpec extends AnyFlatSpec with Matchers:

  private final class Rows:
    private val buf           = mutable.ListBuffer.empty[AuditEvent]
    val audit                 = new HandshakeAudit(e => buf.synchronized { buf += e; () })
    def all: List[AuditEvent] = buf.synchronized(buf.toList)

  private def flightEdge[A](body: (FlightEdgeHarness.Harness, Rows) => A): A =
    val rows = new Rows
    val h    = FlightEdgeHarness.boot(
      SecurityFixtures.freshStore().store,
      wiring = FlightEdgeHarness.RouterWiring(
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      ),
      audit = rows.audit
    )
    try body(h, rows)
    finally h.shutdown()

  private def basic(user: String, password: String, superuser: Boolean = false) =
    val hdrs = new FlightCallHeaders()
    hdrs.insert("tenant", SecurityFixtures.TenantId)
    hdrs.insert("pool", SecurityFixtures.PoolName)
    if superuser then hdrs.insert("superuser", "true")
    val pair = Base64.getEncoder.encodeToString(s"$user:$password".getBytes("UTF-8"))
    hdrs.insert("authorization", s"Basic $pair")
    new HeaderCallOption(hdrs)

  private def call(h: FlightEdgeHarness.Harness, opt: HeaderCallOption): FlightStatusCode =
    val client = h.newClient()
    try
      try
        client.getInfo(FlightDescriptor.command("SELECT 1".getBytes("UTF-8")), opt)
        FlightStatusCode.OK
      catch case e: FlightRuntimeException => e.status().code()
    finally client.close()

  private def secrets(e: AuditEvent): String =
    (e.actor :: e.tenant.toList ++ e.target.toList ++ e.detail.values.toList).mkString(" ")

  "A FlightSQL handshake with a wrong password" should "write auth.login.failure" in
    flightEdge { (h, rows) =>
      call(h, basic(SecurityFixtures.AliceUsername, "not-her-password")) shouldBe
        FlightStatusCode.UNAUTHENTICATED
      val List(e) = rows.all
      e.family shouldBe "auth"
      e.action shouldBe "auth.login.failure"
      e.outcome shouldBe "denied"
      e.origin shouldBe "flightsql"
      e.actor shouldBe SecurityFixtures.AliceUsername
      e.actorRealm shouldBe "tenant"
      e.tenant shouldBe Some(SecurityFixtures.TenantId)
      e.detail("username") shouldBe SecurityFixtures.AliceUsername
      e.detail("pool") shouldBe SecurityFixtures.PoolName
      e.detail("source") shouldBe "127.0.0.1"
      e.detail("reason") shouldBe HandshakeAudit.InvalidCredentials
      secrets(e) should not include "not-her-password"
    }

  it should "write one row a minute for the same client and user" in
    flightEdge { (h, rows) =>
      for _ <- 1 to 3 do call(h, basic(SecurityFixtures.AliceUsername, "wrong"))
      call(h, basic(SecurityFixtures.BobUsername, "wrong"))
      rows.all.map(_.actor) shouldBe List(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.BobUsername
      )
    }

  "A FlightSQL handshake of an unknown user" should "write the name as given" in
    flightEdge { (h, rows) =>
      call(h, basic("mallory", "whatever")) shouldBe FlightStatusCode.UNAUTHENTICATED
      val List(e) = rows.all
      e.actor shouldBe "mallory"
      e.detail("reason") shouldBe HandshakeAudit.InvalidCredentials
    }

  "A superuser=true FlightSQL handshake by a tenant user" should "write a system-realm row" in
    flightEdge { (h, rows) =>
      call(h, basic(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword, true)) shouldBe
        FlightStatusCode.UNAUTHENTICATED
      val List(e) = rows.all
      e.actorRealm shouldBe "system"
      e.actor shouldBe SecurityFixtures.AliceUsername
      e.detail("reason") shouldBe HandshakeAudit.InvalidCredentials
    }

  "A FlightSQL handshake with no access to the pool" should "write reason no_pool_access" in
    flightEdge { (h, rows) =>
      call(h, basic(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)) shouldBe
        FlightStatusCode.UNAUTHORIZED
      val List(e) = rows.all
      e.actor shouldBe SecurityFixtures.BobUsername
      e.detail("reason") shouldBe HandshakeAudit.NoPoolAccess
      e.detail("pool") shouldBe SecurityFixtures.PoolName
    }

  "A FlightSQL handshake that succeeds" should "write no row" in
    flightEdge { (h, rows) =>
      call(h, basic(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword)) should not be
        FlightStatusCode.UNAUTHENTICATED
      rows.all shouldBe empty
    }

  "A FlightSQL bearer no provider accepts" should "write a row keyed by its address" in
    flightEdge { (h, rows) =>
      val hdrs = new FlightCallHeaders()
      hdrs.insert("tenant", SecurityFixtures.TenantId)
      hdrs.insert("pool", SecurityFixtures.PoolName)
      hdrs.insert("authorization", "Bearer not-a-token")
      call(h, new HeaderCallOption(hdrs)) shouldBe FlightStatusCode.UNAUTHENTICATED
      val List(e) = rows.all
      e.actor shouldBe "anonymous"
      e.detail.get("username") shouldBe None
      e.detail("source") shouldBe "127.0.0.1"
      secrets(e) should not include "not-a-token"
    }

  // ---------------------------------------------------------------------------------------------
  // Native Quack front door
  // ---------------------------------------------------------------------------------------------

  private object NoNode extends QuackTransport:
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] =
      IO.raiseError(new IllegalStateException("no node in this spec"))

  private def quackDoor(h: FlightEdgeHarness.Harness, rows: Rows) =
    val sup = h.supervisor
    new QuackFrontDoor(
      h.router,
      new EdgeHandshake(
        new InMemoryAuthService.Service(SecurityFixtures.freshStore().store, true),
        lookupPool = (tn, p) =>
          sup.findPoolKeyByTenantAndPoolName(tn, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
        resolveTenant = raw => sup.getTenant(raw),
        authorize = req => sup.authorizeHandshakeDetailed(req),
        versions = sup.policyVersions,
        sessionLookup = _ => ai.starlake.quack.ondemand.api.SessionTokenStore.LookupResult.Invalid,
        audit = rows.audit,
        edge = "quack"
      ),
      new QuackSessionRegistry(sessionTtlSec = 3600, maxHeartbeatSec = 3600),
      NoNode,
      ManagerEventSink.noop,
      "test"
    )

  "A native Quack handshake with a wrong password" should "write auth.login.failure" in
    flightEdge { (h, _) =>
      val rows  = new Rows
      val token = s"tenant=${SecurityFixtures.TenantId}&pool=${SecurityFixtures.PoolName}" +
        s"&user=${SecurityFixtures.AliceUsername}&password=not-her-password"
      val hello = ConnectionRequest(token, "v2.0.0", "osx_arm64", 3L, 3L, "qod-test", 0L)
      val resp  =
        quackDoor(h, rows)
          .handle(encodeConnectionRequest(hello, 1L), Some("10.1.2.3"))
          .unsafeRunSync()
      messageType(resp) shouldBe Type.ErrorResponse
      val List(e) = rows.all
      e.action shouldBe "auth.login.failure"
      e.origin shouldBe "quack"
      e.actor shouldBe SecurityFixtures.AliceUsername
      e.tenant shouldBe Some(SecurityFixtures.TenantId)
      e.detail("source") shouldBe "10.1.2.3"
      e.detail("reason") shouldBe HandshakeAudit.InvalidCredentials
      secrets(e) should not include "not-her-password"
    }
