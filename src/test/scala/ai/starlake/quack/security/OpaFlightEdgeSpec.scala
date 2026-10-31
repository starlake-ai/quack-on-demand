package ai.starlake.quack.security

import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.opa.OpaAuthorizer
import ai.starlake.quack.ondemand.telemetry.EventJournal
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.arrow.flight.{FlightRuntimeException, FlightStatusCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End-to-end OPA guarantees on the real Arrow FlightSQL wire: a real [[FlightEdgeServer]] (via
  * [[FlightEdgeHarness]]) and a real [[OpaAuthorizer]] talking HTTP to a WireMock OPA at handshake
  * (gate 4). Tenant `acme` is in opa mode and its user `bob` holds NO QoD grant of any kind, so
  * every connect admission below is OPA's; statements are the node's to decide (spec decision 29),
  * so the gateway asks OPA nothing per statement.
  */
class OpaFlightEdgeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private def opaUrl = s"http://localhost:${wm.port()}"

  private def closedPortUrl: String =
    val ss = new java.net.ServerSocket(0)
    try s"http://127.0.0.1:${ss.getLocalPort}"
    finally ss.close()

  private final case class Booted(h: FlightEdgeHarness.Harness, audit: RecordingTelemetryStore):
    val journal: EventJournal = h.router.journal

  private def boot(
      url: String = opaUrl,
      ttlSec: Int = 5
  ): Booted =
    val fix   = seed(url)
    val audit = new RecordingTelemetryStore
    val opa   = authorizer(ttlSec)
    val h     = FlightEdgeHarness.boot(
      fix.store,
      wiring = FlightEdgeHarness.RouterWiring(
        opa = Some(opa),
        nodeQuery = duckNode(OrdersSetup),
        journal = new EventJournal(audit),
        spawnNodes = true,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      )
    )
    Booted(h, audit)

  private def withBoot[A](
      url: String = opaUrl,
      ttlSec: Int = 5
  )(body: Booted => A): A =
    val b = boot(url, ttlSec = ttlSec)
    try body(b)
    finally b.h.shutdown()

  private def flightError(body: => Any): FlightRuntimeException =
    intercept[FlightRuntimeException](body)

  private val bob = headers(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

  "the FlightSQL edge" should "admit a grant-less user OPA lets connect, asking nothing per statement" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, deny("the gateway must not ask"))
    withBoot() { b =>
      val rows = query(b.h, bob, "SELECT * FROM orders")
      rows.map(_("id")) shouldBe List("1", "2")
      // One connect question reaches OPA (the RPC pair's second handshake is answered by the
      // decision cache); no statement question does.
      wm.verify(1, connectCalls)
      wm.verify(0, statementCalls)
    }

  it should "answer UNAVAILABLE when OPA is unreachable at handshake" in:
    wm.resetAll()
    val url = closedPortUrl
    withBoot(url = url) { b =>
      val e = flightError(query(b.h, bob, "SELECT * FROM orders"))
      e.status().code() shouldBe FlightStatusCode.UNAVAILABLE
      e.getMessage should include("authorization service unavailable")
      e.getMessage should include(OpaAuthorizer.UnavailableMessage)
      val port = url.substring(url.lastIndexOf(':') + 1)
      e.getMessage.contains(port) shouldBe false
    }

  it should "answer UNAVAILABLE when OPA errors at handshake" in:
    wm.resetAll()
    stubConnect(wm, serverError())
    withBoot() { b =>
      flightError(query(b.h, bob, "SELECT * FROM orders"))
        .status()
        .code() shouldBe FlightStatusCode.UNAVAILABLE
    }

  "the qod tenant globex" should "be admitted without table grants, never asking OPA" in:
    wm.resetAll()
    stubConnect(wm, deny("must not be asked"))
    stubStatement(wm, deny("must not be asked"))
    withBoot() { b =>
      val carol = headers(CarolUsername, CarolPassword, tenant = SecurityFixtures.GlobexTenantId)
      query(b.h, carol, "SELECT * FROM orders").map(_("id")) shouldBe List("1", "2")
      wm.verify(0, anyRequestedFor(anyUrl()))
    }

  "a superuser on the opa tenant" should "never reach OPA, at handshake or statement time" in:
    wm.resetAll()
    stubConnect(wm, deny("must not be asked"))
    stubStatement(wm, deny("must not be asked"))
    withBoot() { b =>
      val root = headers(
        SecurityFixtures.RootUsername,
        SecurityFixtures.RootPassword,
        superuser = true
      )
      query(b.h, root, "SELECT * FROM orders").map(_("id")) shouldBe List("1", "2")
      wm.verify(0, anyRequestedFor(anyUrl()))
    }
