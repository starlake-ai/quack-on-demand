package ai.starlake.quack.security

import ai.starlake.quack.edge.EdgeHandshake
import ai.starlake.quack.edge.adapter.{QuackTestFixtures, QuackTransport}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.quack.{QuackFrontDoor, QuackSessionRegistry, QuackWire}
import ai.starlake.quack.spi.ManagerEventSink
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.flight.auth2.{ClientBearerHeaderHandler, ClientIncomingAuthHeaderMiddleware}
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.flight.{
  CallOption,
  FlightClient,
  FlightRuntimeException,
  FlightStatusCode,
  Location
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** A session held open across a grant change, on both data-plane doors: its grants follow the
  * tenant's policy version, so a revoke reaches it on its next statement and a re-grant restores
  * it, without a reconnect. Alice holds the pool through a direct pool permission and `*.*.* ALL`
  * through her role; the in-memory store moves the version as the Liquibase triggers do.
  */
class SessionGrantsDoorSpec extends AnyFlatSpec with Matchers:

  import QuackWire.*
  import SecurityFixtures.*

  private def withHarness[A](body: (FlightEdgeHarness.Harness, Fixture) => A): A =
    val fix = freshStore()
    val h   = FlightEdgeHarness.boot(
      fix.store,
      wiring = FlightEdgeHarness.RouterWiring(
        spawnNodes = true,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true)
      )
    )
    try body(h, fix)
    finally h.shutdown()

  /** Revokes Alice's pool permission, as the REST revoke does (the version moves with it). */
  private def revokePool(h: FlightEdgeHarness.Harness, fix: Fixture): Unit =
    fix.store.bumpPolicyVersion(TenantId)
    h.supervisor.revokePoolPermission(PoolPermId).unsafeRunSync().isRight shouldBe true

  private def regrantPool(h: FlightEdgeHarness.Harness, fix: Fixture): Unit =
    fix.store.bumpPolicyVersion(TenantId)
    h.supervisor
      .grantPoolPermission(TenantId, Some(PoolId), Some(fix.aliceUserId), None)
      .unsafeRunSync()
      .isRight shouldBe true

  // ---------------------------------------------------------------------------------------------
  // FlightSQL: one client, one handshake, the session's bearer replayed on every later call
  // ---------------------------------------------------------------------------------------------

  private final class HeldFlight(h: FlightEdgeHarness.Harness):
    private val bearer =
      new ClientIncomingAuthHeaderMiddleware.Factory(new ClientBearerHeaderHandler())
    private val raw: FlightClient =
      FlightClient
        .builder(h.allocator, Location.forGrpcInsecure(h.host, h.port))
        .intercept(bearer)
        .build()
    private val fsql = new FlightSqlClient(raw)
    private def exec(sql: String, opt: CallOption): Either[FlightRuntimeException, Unit] =
      try
        val info   = fsql.execute(sql, opt)
        val stream = fsql.getStream(info.getEndpoints.get(0).getTicket, opt)
        try while stream.next() do ()
        finally stream.close()
        Right(())
      catch case e: FlightRuntimeException => Left(e)

    /** The handshake: the first statement, with Alice's password. */
    exec("SELECT 1", OpaEdgeFixtures.headers(AliceUsername, AlicePassword)) shouldBe Right(())
    private val session = bearer.getCredentialCallOption

    def run(sql: String): Either[FlightRuntimeException, Unit] = exec(sql, session)
    def close(): Unit                                          = raw.close()

  "a FlightSQL session held open" should
    "be refused on its next statement once its pool is revoked, and allowed once re-granted" in
    withHarness { (h, fix) =>
      val held = new HeldFlight(h)
      try
        held.run("SELECT 2") shouldBe Right(())
        revokePool(h, fix)
        held.run("SELECT 3") match
          case Left(e) =>
            e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
            e.getMessage should include(s"has no access to pool '$TenantId/$PoolName'")
          case Right(_) => fail("a revoked session ran its next statement")
        regrantPool(h, fix)
        held.run("SELECT 4") shouldBe Right(())
      finally held.close()
    }

  it should "be refused once its user is disabled" in withHarness { (h, fix) =>
    val held = new HeldFlight(h)
    try
      held.run("SELECT 2") shouldBe Right(())
      // The account switch the REST user update writes, then the version move it comes with.
      val hash = fix.store.getPasswordHash(Some(TenantId), AliceUsername).get
      fix.store.upsertUserWithHash(Some(TenantId), AliceUsername, hash, "admin", enabled = false)
      fix.store.bumpPolicyVersion(TenantId)
      h.supervisor.restore()
      held.run("SELECT 3") match
        case Left(e) =>
          e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
          e.getMessage should include(s"user '$AliceUsername' is disabled")
        case Right(_) => fail("a disabled user's session ran its next statement")
    finally held.close()
  }

  // ---------------------------------------------------------------------------------------------
  // Native Quack front door: one CONNECTION_REQUEST, every statement on its connection id
  // ---------------------------------------------------------------------------------------------

  /** Plays a node: connects, answers a one-row PREPARE_RESPONSE, acknowledges everything else.
    * `contexts` lists the context digest (`ctx`) of every session handle the front door sent, in
    * order: what the node's decisions would be asked under.
    */
  private final class NodeTransport extends QuackTransport:
    @volatile var contexts: Vector[String] = Vector.empty

    /** Every statement (handles left out) with the node connection it ran on, in order. */
    @volatile var statements: Vector[(String, String)] = Vector.empty

    /** The node connections the front door DISCONNECTed, in order. */
    @volatile var disconnected: Vector[String] = Vector.empty
    private val opened                         = new java.util.concurrent.atomic.AtomicInteger(0)

    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      val f = frame(body).toOption.get
      f.header.msgType match
        case Type.ConnectionRequest =>
          QuackTestFixtures.serializeSampleConnectionResponse(s"NODE${opened.incrementAndGet()}")
        case Type.PrepareRequest =>
          val sql = decodePrepareRequest(f).toOption.map(_.sql).getOrElse("")
          if sql.startsWith(Prelude) then contexts = contexts :+ contextOf(sql)
          else statements = statements :+ (f.header.connectionId -> sql)
          QuackTestFixtures.serializeSamplePrepareResponse(
            java.math.BigInteger.valueOf(5L),
            false,
            true
          )
        case Type.Disconnect =>
          disconnected = disconnected :+ f.header.connectionId
          encodeSuccess()
        case _ => encodeSuccess()
    }

    /** The node connection the last statement whose text contains `sql` ran on. */
    def connectionOf(sql: String): String =
      statements.reverseIterator
        .collectFirst { case (c, text) if text.contains(sql) => c }
        .getOrElse(fail(s"no statement containing '$sql' reached a node: $statements"))

  private val Prelude = "CALL system.main.qod_session('"

  /** The `ctx` field of the handle in `prelude`, read without verifying it. */
  private def contextOf(prelude: String): String =
    val handle  = prelude.stripPrefix(Prelude).takeWhile(_ != '\'')
    val payload = java.util.Base64.getUrlDecoder.decode(handle.takeWhile(_ != '.'))
    io.circe.parser
      .parse(new String(payload, java.nio.charset.StandardCharsets.UTF_8))
      .flatMap(_.hcursor.get[String]("ctx"))
      .fold(e => fail(s"unreadable handle: ${e.getMessage}"), identity)

  private def door(
      h: FlightEdgeHarness.Harness,
      fix: Fixture,
      t: NodeTransport = new NodeTransport
  ): QuackFrontDoor =
    val sup       = h.supervisor
    val handshake = new EdgeHandshake(
      new InMemoryAuthService.Service(fix.store, providersEnabled = true),
      lookupPool = (tn, p) =>
        sup.findPoolKeyByTenantAndPoolName(tn, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = raw => sup.getTenant(raw),
      authorize = req => sup.authorizeHandshakeDetailed(req),
      versions = sup.policyVersions,
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

  private def connect(d: QuackFrontDoor): String =
    val token =
      s"tenant=$TenantId&pool=$PoolName&user=$AliceUsername&password=$AlicePassword"
    val hello = ConnectionRequest(token, "v2.0.0", "osx_arm64", 3L, 3L, "qod-test", 0L)
    val resp  = d.handle(encodeConnectionRequest(hello, 1L)).unsafeRunSync()
    withClue(decodeErrorMessage(resp)) {
      messageType(resp) shouldBe Type.ConnectionResponse
    }
    decodeConnectionResponse(resp).toOption.get._1

  private def prepare(d: QuackFrontDoor, connId: String, sql: String): Array[Byte] =
    d.handle(encodePrepareRequest(connId, 3L, PrepareRequest(sql, None, None))).unsafeRunSync()

  "a native front door session held open" should
    "be refused on its next statement once its pool is revoked, and allowed once re-granted" in
    withHarness { (h, fix) =>
      val d      = door(h, fix)
      val connId = connect(d)
      messageType(prepare(d, connId, "SELECT 1")) shouldBe Type.PrepareResponse
      revokePool(h, fix)
      val refused = prepare(d, connId, "SELECT 2")
      messageType(refused) shouldBe Type.ErrorResponse
      decodeErrorMessage(refused).toOption.get should
        include(s"has no access to pool '$TenantId/$PoolName'")
      regrantPool(h, fix)
      messageType(prepare(d, connId, "SELECT 3")) shouldBe Type.PrepareResponse
    }

  it should "judge a statement inside its open transaction under the grants of now" in
    withHarness { (h, fix) =>
      val t      = new NodeTransport
      val d      = door(h, fix, t)
      val connId = connect(d)
      messageType(prepare(d, connId, "BEGIN")) shouldBe Type.PrepareResponse
      messageType(prepare(d, connId, "SELECT 1")) shouldBe Type.PrepareResponse
      val before = t.contexts.last
      // Alice keeps the pool; her role loses its table grant.
      fix.store.bumpPolicyVersion(TenantId)
      h.supervisor.revokeRolePermission(AdminPermId).unsafeRunSync().isRight shouldBe true
      messageType(prepare(d, connId, "SELECT 2")) shouldBe Type.PrepareResponse
      withClue("the handle of the statement after the revoke, on the transaction's link: ") {
        t.contexts.last should not be before
      }
    }

  it should "end its open transaction when a refused COMMIT ends the client's, so a re-granted " +
    "statement never runs on it" in withHarness { (h, fix) =>
      val t      = new NodeTransport
      val d      = door(h, fix, t)
      val connId = connect(d)
      messageType(prepare(d, connId, "BEGIN")) shouldBe Type.PrepareResponse
      messageType(prepare(d, connId, "SELECT 11")) shouldBe Type.PrepareResponse
      val txConn = t.connectionOf("SELECT 11")
      revokePool(h, fix)
      val commit = prepare(d, connId, "COMMIT")
      messageType(commit) shouldBe Type.ErrorResponse
      decodeErrorMessage(commit).toOption.get should
        include(s"has no access to pool '$TenantId/$PoolName'")
      withClue("the refused COMMIT ended the node transaction: ") {
        t.disconnected should contain(txConn)
      }
      regrantPool(h, fix)
      // The client ended its transaction with the failed COMMIT: this statement is autocommit.
      messageType(prepare(d, connId, "SELECT 12")) shouldBe Type.PrepareResponse
      withClue("the statement after the re-grant, outside the ended transaction: ") {
        t.connectionOf("SELECT 12") should not be txConn
      }
      t.statements
        .collect { case (c, sql) if c == txConn => sql }
        .exists(_.contains("COMMIT")) shouldBe
        false
    }

  it should "admit a ROLLBACK its refused grants would refuse, and end the transaction with it" in
    withHarness { (h, fix) =>
      val t      = new NodeTransport
      val d      = door(h, fix, t)
      val connId = connect(d)
      messageType(prepare(d, connId, "BEGIN")) shouldBe Type.PrepareResponse
      messageType(prepare(d, connId, "SELECT 21")) shouldBe Type.PrepareResponse
      val txConn = t.connectionOf("SELECT 21")
      revokePool(h, fix)
      val rollback = prepare(d, connId, "ROLLBACK")
      withClue(decodeErrorMessage(rollback)) {
        messageType(rollback) shouldBe Type.PrepareResponse
      }
      t.connectionOf("ROLLBACK") shouldBe txConn
      withClue("the ROLLBACK released the transaction's connection: ") {
        t.disconnected should contain(txConn)
      }
      // Anything else is still refused.
      messageType(prepare(d, connId, "SELECT 22")) shouldBe Type.ErrorResponse
    }

  it should "fence a transaction whose statement the grants refused, and lift the fence with a " +
    "ROLLBACK the grants still refuse" in withHarness { (h, fix) =>
      val t      = new NodeTransport
      val d      = door(h, fix, t)
      val connId = connect(d)
      messageType(prepare(d, connId, "BEGIN")) shouldBe Type.PrepareResponse
      messageType(prepare(d, connId, "SELECT 31")) shouldBe Type.PrepareResponse
      val txConn = t.connectionOf("SELECT 31")
      revokePool(h, fix)
      val refused = prepare(d, connId, "SELECT 32")
      messageType(refused) shouldBe Type.ErrorResponse
      decodeErrorMessage(refused).toOption.get should
        include(s"has no access to pool '$TenantId/$PoolName'")
      decodeErrorMessage(refused).toOption.get should include("ROLLBACK to continue")
      t.disconnected should contain(txConn)
      // The client still believes it is inside its transaction: the fence answers until ROLLBACK.
      val fenced = prepare(d, connId, "SELECT 33")
      messageType(fenced) shouldBe Type.ErrorResponse
      decodeErrorMessage(fenced).toOption.get should include("ROLLBACK to continue")
      val rollback = prepare(d, connId, "ROLLBACK")
      withClue(decodeErrorMessage(rollback)) {
        messageType(rollback) shouldBe Type.PrepareResponse
      }
      t.connectionOf("ROLLBACK") should not be txConn
      regrantPool(h, fix)
      messageType(prepare(d, connId, "SELECT 34")) shouldBe Type.PrepareResponse
      t.connectionOf("SELECT 34") should not be txConn
      t.statements.map(_._2).exists(_.contains("SELECT 33")) shouldBe false
    }
