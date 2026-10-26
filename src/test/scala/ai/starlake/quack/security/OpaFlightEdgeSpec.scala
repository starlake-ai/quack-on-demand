package ai.starlake.quack.security

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
  * [[FlightEdgeHarness]]) with the production validator ([[BootFactories.aclValidator]]) and a real
  * [[OpaAuthorizer]] talking HTTP to a WireMock OPA. Tenant `acme` is in opa mode and its user
  * `bob` holds NO QoD grant of any kind, so every admission below is OPA's.
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
      aclEnabled: Boolean = true,
      ttlSec: Int = 5
  ): Booted =
    val fix   = seed(url)
    val audit = new RecordingTelemetryStore
    val opa   = authorizer(ttlSec)
    val h     = FlightEdgeHarness.boot(
      fix.store,
      wiring = FlightEdgeHarness.RouterWiring(
        validator = validator(opa, aclEnabled),
        opa = Some(opa),
        nodeQuery = duckNode(OrdersSetup),
        journal = new EventJournal(audit),
        spawnNodes = true
      )
    )
    Booted(h, audit)

  private def withBoot[A](
      url: String = opaUrl,
      aclEnabled: Boolean = true,
      ttlSec: Int = 5
  )(body: Booted => A): A =
    val b = boot(url, aclEnabled, ttlSec)
    try body(b)
    finally b.h.shutdown()

  private def flightError(body: => Any): FlightRuntimeException =
    intercept[FlightRuntimeException](body)

  private val bob = headers(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

  "the FlightSQL edge" should "admit a grant-less user when OPA allows connect and statement" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)
    withBoot() { b =>
      val rows = query(b.h, bob, "SELECT * FROM orders")
      rows.map(_("id")) shouldBe List("1", "2")
      // One connect and one statement question reach OPA. The edge asks twice per kind (one
      // handshake per RPC, and the GetFlightInfo schema probe before DoGet); the second,
      // identical question is answered by the decision cache.
      wm.verify(1, connectCalls)
      wm.verify(1, statementCalls)
      wm.verify(
        statementCalls
          .withRequestBody(matchingJsonPath("$.input.client.edge", equalTo("flightsql")))
          .withRequestBody(matchingJsonPath("$.input.user.name", equalTo("bob")))
          .withRequestBody(matchingJsonPath("$.input.accesses[0].table", equalTo("orders")))
          .withRequestBody(matchingJsonPath("$.input.accesses[0].verb", equalTo("read")))
      )
    }

  it should "answer UNAUTHORIZED naming the denied table and audit authz_source=opa" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, deny("orders is off limits"))
    withBoot() { b =>
      val e = flightError(query(b.h, bob, "SELECT * FROM orders"))
      e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
      e.getMessage should include("orders")
      e.getMessage should include("denied by policy")

      // GetFlightInfo's schema probe is not journaled (recordExecution = false); DoGet is.
      val e2 = flightError(doGet(b.h, bob, "SELECT * FROM orders"))
      e2.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
      b.journal.drainNow()
      val denials = b.audit.events.toList.filter(_.detail.get("authz_source").contains("opa"))
      denials should have size 1
      denials.head.detail.get("opa_decision_id") shouldBe Some("d-1")
      denials.head.detail.getOrElse("denied", "") should include("orders")
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

  it should "answer UNAVAILABLE when OPA errors at statement time" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, serverError())
    withBoot() { b =>
      val e = flightError(query(b.h, bob, "SELECT * FROM orders"))
      e.status().code() shouldBe FlightStatusCode.UNAVAILABLE
      e.getMessage should include("authorization service unavailable")
      e.getMessage should include(OpaAuthorizer.UnavailableMessage)
      e.getMessage.contains("HTTP 500") shouldBe false
    }

  it should "send a 3-table join to OPA as ONE statement call carrying 3 accesses" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)
    withBoot() { b =>
      val sql =
        "SELECT o.id FROM orders o JOIN customers c ON o.id = c.id JOIN items i ON i.id = o.id"
      // The stub node has no customers/items: the node error is irrelevant, OPA was asked first.
      scala.util.Try(query(b.h, bob, sql))
      wm.verify(1, statementCalls)
      wm.verify(
        1,
        statementCalls.withRequestBody(
          matchingJsonPath("$.input.accesses.length()", equalTo("3"))
        )
      )
      wm.verify(
        statementCalls
          .withRequestBody(matchingJsonPath("$.input.accesses[?(@.table == 'customers')]"))
          .withRequestBody(matchingJsonPath("$.input.accesses[?(@.table == 'items')]"))
          .withRequestBody(matchingJsonPath("$.input.accesses[?(@.table == 'orders')]"))
      )
    }

  "with acl.enabled=false" should "still deny the opa tenant when OPA denies" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, deny("no"))
    withBoot(aclEnabled = false) { b =>
      val e = flightError(query(b.h, bob, "SELECT * FROM orders"))
      e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
      e.getMessage should include("denied by policy")
      wm.verify(1, statementCalls)
    }

  it should "admit the qod tenant globex without table grants and never ask OPA" in:
    wm.resetAll()
    stubConnect(wm, deny("must not be asked"))
    stubStatement(wm, deny("must not be asked"))
    withBoot(aclEnabled = false) { b =>
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
