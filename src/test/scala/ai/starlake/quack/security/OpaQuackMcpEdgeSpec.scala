package ai.starlake.quack.security

import ai.starlake.quack.boot.RoutedExecutor
import ai.starlake.quack.edge.{EdgeHandshake, QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.{QuackTestFixtures, QuackTransport}
import ai.starlake.quack.edge.quack.{QuackFrontDoor, QuackSessionRegistry, QuackWire}
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.api.ExecCaller
import ai.starlake.quack.route.StatementClassifier
import ai.starlake.quack.spi.ManagerEventSink
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** OPA allow and deny round-trips on the two other data-plane edges, on the SAME stack the
  * FlightSQL specs use (the harness's supervisor and OPA wiring; statements are the node's to
  * decide):
  *
  *   - the native Quack front door, with the real [[EdgeHandshake]] (in-memory Basic auth, the
  *     supervisor's handshake gate, edge `quack`) in front of a scripted node transport;
  *   - the MCP data tools' routed executor ([[RoutedExecutor]], bound to the router exactly as Main
  *     binds it, edge `mcp`).
  */
class OpaQuackMcpEdgeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*
  import QuackWire.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private val poolKey =
    PoolKey(SecurityFixtures.TenantId, SecurityFixtures.TenantDbName, SecurityFixtures.PoolName)

  private def withHarness[A](body: (FlightEdgeHarness.Harness, SecurityFixtures.Fixture) => A): A =
    withPreparedHarness(_ => ())(body)

  private def withPreparedHarness[A](prep: SecurityFixtures.Fixture => Unit)(
      body: (FlightEdgeHarness.Harness, SecurityFixtures.Fixture) => A
  ): A =
    val fix = seed(s"http://localhost:${wm.port()}")
    prep(fix)
    val opa = authorizer()
    val h   = FlightEdgeHarness.boot(
      fix.store,
      wiring = FlightEdgeHarness.RouterWiring(
        opa = Some(opa),
        nodeQuery = duckNode(OrdersSetup),
        spawnNodes = true
      )
    )
    try body(h, fix)
    finally h.shutdown()

  // ---------------------------------------------------------------------------------------------
  // Native Quack front door
  // ---------------------------------------------------------------------------------------------

  /** Plays a node: connects, answers a one-row PREPARE_RESPONSE, acknowledges everything else. A
    * session handle prelude (`CALL system.main.qod_session(...)`) is answered apart: `preludes`
    * counts those and `prepares` only the statements the front door relays, and `order` lists both
    * in arrival order.
    */
  private final class NodeTransport extends QuackTransport:
    @volatile var prepares: Int                            = 0
    @volatile var preludes: Int                            = 0
    @volatile var order: Vector[String]                    = Vector.empty
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      val f = frame(body).toOption.get
      f.header.msgType match
        case Type.ConnectionRequest => QuackTestFixtures.serializeSampleConnectionResponse("NODE1")
        case Type.PrepareRequest    =>
          val prelude = decodePrepareRequest(f).toOption
            .exists(_.sql.startsWith("CALL system.main.qod_session('"))
          if prelude then
            preludes += 1
            order = order :+ "prelude"
          else
            prepares += 1
            order = order :+ "statement"
          QuackTestFixtures.serializeSamplePrepareResponse(
            java.math.BigInteger.valueOf(5L),
            false,
            true
          )
        case _ => encodeSuccess()
    }

  private def door(h: FlightEdgeHarness.Harness, fix: SecurityFixtures.Fixture, t: NodeTransport) =
    val sup       = h.supervisor
    val handshake = new EdgeHandshake(
      new InMemoryAuthService.Service(fix.store, providersEnabled = true),
      lookupPool = (tn, p) =>
        sup.findPoolKeyByTenantAndPoolName(tn, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = raw => sup.getTenant(raw),
      authorize = req => sup.authorizeHandshakeDetailed(req),
      edge = "quack"
    )
    new QuackFrontDoor(
      h.router,
      handshake,
      new QuackSessionRegistry(sessionTtlSec = 3600, maxHeartbeatSec = 3600),
      t,
      ManagerEventSink.noop,
      "test"
    )

  private val bobToken =
    s"tenant=${SecurityFixtures.TenantId}&pool=${SecurityFixtures.PoolName}" +
      s"&user=${SecurityFixtures.BobUsername}&password=${SecurityFixtures.BobPassword}"

  private def connect(d: QuackFrontDoor): String =
    val hello = ConnectionRequest(bobToken, "v2.0.0", "osx_arm64", 3L, 3L, "qod-test", 0L)
    val resp  = d.handle(encodeConnectionRequest(hello, 1L)).unsafeRunSync()
    withClue(decodeErrorMessage(resp)) {
      messageType(resp) shouldBe Type.ConnectionResponse
    }
    decodeConnectionResponse(resp).toOption.get._1

  private def prepare(d: QuackFrontDoor, connId: String, sql: String): Array[Byte] =
    d.handle(encodePrepareRequest(connId, 3L, PrepareRequest(sql, None, None))).unsafeRunSync()

  "the native Quack front door" should "relay a statement of a user OPA lets connect" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)
    withHarness { (h, fix) =>
      val t    = new NodeTransport
      val d    = door(h, fix, t)
      val resp = prepare(d, connect(d), "SELECT * FROM orders")
      messageType(resp) shouldBe Type.PrepareResponse
      t.prepares shouldBe 1
      // Exactly one session handle, sent right before the relayed statement.
      t.preludes shouldBe 1
      t.order shouldBe Vector("prelude", "statement")
      wm.verify(
        1,
        connectCalls.withRequestBody(matchingJsonPath("$.input.client.edge", equalTo("quack")))
      )
      wm.verify(0, statementCalls)
    }

  it should "refuse the session when OPA denies the connect" in:
    wm.resetAll()
    stubConnect(wm, deny("not this pool"))
    withHarness { (h, fix) =>
      val d     = door(h, fix, new NodeTransport)
      val hello = ConnectionRequest(bobToken, "v2.0.0", "osx_arm64", 3L, 3L, "qod-test", 0L)
      val resp  = d.handle(encodeConnectionRequest(hello, 1L)).unsafeRunSync()
      messageType(resp) shouldBe Type.ErrorResponse
      decodeErrorMessage(resp).toOption.get should include("denied by policy")
      wm.verify(0, statementCalls)
    }

  // ---------------------------------------------------------------------------------------------
  // MCP data tools (routed executor)
  // ---------------------------------------------------------------------------------------------

  private def executor(h: FlightEdgeHarness.Harness) =
    RoutedExecutor(
      h.supervisor,
      StatementClassifier.default,
      (caller, key, sql, eff, rec) =>
        h.router.execute(
          caller.connectionId,
          caller.identity,
          key,
          sql,
          effectiveSet = eff,
          recordExecution = rec,
          patId = caller.patId,
          adminDispatch = false,
          edge = "mcp"
        )
    )(recordExecution = true)

  private def mcpRun(
      h: FlightEdgeHarness.Harness,
      sql: String
  ): Either[RouterFailure, QueryResult] =
    // Bob is a tenant user: his session is not superuser-admissible.
    val bob = ExecCaller.unrestricted(
      "mcp-acme",
      SecurityFixtures.BobUsername,
      superuserAdmissible = false
    )
    executor(h)(bob, poolKey, sql).unsafeRunSync()

  "the MCP routed executor" should "run a statement of a user OPA lets connect" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)
    withHarness { (h, _) =>
      val out = mcpRun(h, "SELECT * FROM orders")
      out.isRight shouldBe true
      val r = out.toOption.get
      try
        r.rows.loadNextBatch() shouldBe true
        r.rows.getVectorSchemaRoot.getRowCount shouldBe 2
      finally r.close()
      wm.verify(
        1,
        connectCalls.withRequestBody(matchingJsonPath("$.input.client.edge", equalTo("mcp")))
      )
      wm.verify(0, statementCalls)
    }

  it should "map an OPA outage to a retryable Unavailable, not a denial" in:
    wm.resetAll()
    stubConnect(wm, serverError())
    withHarness { (h, _) =>
      mcpRun(h, "SELECT * FROM orders") match
        case Left(RouterFailure.Unavailable(reason)) =>
          reason should include("authorization service unavailable")
        case other => fail(s"expected Unavailable, got $other")
    }

  // ---------------------------------------------------------------------------------------------
  // PAT attenuation on an opa tenant
  // ---------------------------------------------------------------------------------------------

  private def patRun(
      h: FlightEdgeHarness.Harness,
      restriction: ai.starlake.quack.ondemand.auth.TokenRestriction,
      sql: String
  ): Either[RouterFailure, QueryResult] =
    executor(h)(
      ExecCaller(
        "mcp-acme",
        SecurityFixtures.BobUsername,
        restriction,
        superuserAdmissible = false,
        patId = Some("pat-1")
      ),
      poolKey,
      sql
    ).unsafeRunSync()

  private val roCeiling =
    ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted.copy(verbCeiling = Some("RO"))

  "a read-only PAT on an opa tenant" should "run a read, asking OPA nothing per statement" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)
    withHarness { (h, _) =>
      val out = patRun(h, roCeiling, "SELECT * FROM orders")
      out.isRight shouldBe true
      out.toOption.get.close()
      wm.verify(0, statementCalls)
    }

  "a role-narrowed PAT on an opa tenant" should "send OPA only the token's roles on connect" in:
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)
    withPreparedHarness { fix =>
      val s = fix.store
      s.upsertRole(
        ai.starlake.quack.ondemand.state
          .RbacRole(id = "r-analyst1", tenantId = SecurityFixtures.TenantId, name = "analyst")
      )
      s.upsertRole(
        ai.starlake.quack.ondemand.state
          .RbacRole(id = "r-writer01", tenantId = SecurityFixtures.TenantId, name = "writer")
      )
      s.addUserRole(fix.bobUserId, "r-analyst1")
      s.addUserRole(fix.bobUserId, "r-writer01")
    } { (h, _) =>
      val narrowed = ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted
        .copy(roles = Some(Set("analyst")))
      val out = patRun(h, narrowed, "SELECT * FROM orders")
      out.isRight shouldBe true
      out.toOption.get.close()
      wm.verify(
        1,
        connectCalls.withRequestBody(
          matchingJsonPath("$.input.user.roles", equalToJson("""["analyst"]"""))
        )
      )
      wm.verify(0, statementCalls)
    }
