package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.*
import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.edge.sql.{
  Allowed,
  Denied,
  StatementValidator,
  ValidationContext,
  ValidationResult
}
import ai.starlake.quack.model.*
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, EffectiveSet}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.EventJournal
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer

/** The front door against a scripted node transport: every message type, the statement lifecycle
  * around the relayed node connection, and the policy, audit and kill hooks the pipeline provides.
  */
class QuackFrontDoorSpec extends AnyFlatSpec with Matchers:

  import QuackWire.*

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")
  private val Token            = "tenant=acme&pool=sales&user=alice&password=pw"

  private def connResp(id: String)       = QuackTestFixtures.serializeSampleConnectionResponse(id)
  private def prepareResp(more: Boolean) =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.valueOf(5L), more, true)
  private def fetchResp(chunk: Boolean) = QuackTestFixtures.serializeSampleFetchResponse(chunk)
  private val success                   = encodeSuccess()

  /** Plays a node: records every request as a decoded frame and answers by message type. */
  private final class ScriptedTransport extends QuackTransport:
    @volatile var prepareNeedsMore: Boolean = false
    @volatile var fetchChunksBeforeEnd: Int = 1
    val posted: ArrayBuffer[Frame]          = ArrayBuffer.empty
    private val connections                 = new AtomicInteger(0)
    private var fetchesServed               = 0
    def types: List[Int]                    = posted.toList.map(_.header.msgType)

    /** Traffic after a successful handshake, which itself costs one probe connection (a
      * CONNECTION_REQUEST and a DISCONNECT) that takes node connection id NODE1.
      */
    def sinceHandshake: List[Frame]                        = posted.toList.drop(2)
    def typesSinceHandshake: List[Int]                     = sinceHandshake.map(_.header.msgType)
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      val f = frame(body).toOption.get
      posted.synchronized(posted += f)
      f.header.msgType match
        case Type.ConnectionRequest => connResp(s"NODE${connections.incrementAndGet()}")
        case Type.PrepareRequest    =>
          fetchesServed = 0
          prepareResp(prepareNeedsMore)
        case Type.FetchRequest =>
          fetchesServed += 1
          if fetchesServed <= fetchChunksBeforeEnd then fetchResp(true) else fetchResp(false)
        case _ => success
    }

  private final case class Fixture(
      door: QuackFrontDoor,
      router: FlightSqlRouter,
      transport: ScriptedTransport,
      sessions: QuackSessionRegistry,
      journal: EventJournal,
      store: RecordingTelemetryStore,
      events: ArrayBuffer[ManagerEvent],
      nodes: List[RunningNode]
  )

  private def setup(
      validator: StatementValidator = StatementValidator.allowAll,
      superuser: Boolean = false,
      withAdmin: Boolean = false,
      ttlSec: Long = 3600
  ): Fixture =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21800 + n.size,
          "nodetok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 2)).unsafeRunSync()
    val store                  = new RecordingTelemetryStore
    val journal                = new EventJournal(store)
    val events                 = ArrayBuffer.empty[ManagerEvent]
    val sink: ManagerEventSink = e => events.synchronized(events += e)
    val client                 =
      new QuackHttpClient(TestArrow.sharedAllocator, nativeClient = true, nodeDisableSsl = true)
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      journal = journal,
      events = sink,
      adminExecutor =
        if withAdmin then Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(sup))
        else None
    )
    val user      = RbacUser("u-1", if superuser then None else Some("t-1"), "alice", role = "user")
    val eff       = EffectiveSet(user, Nil, Nil, Nil, Nil)
    val handshake = new EdgeHandshake(
      new AuthenticationService(AuthenticationConfig.disabled, "x"),
      lookupPool = (t, p) =>
        sup.findPoolKeyByTenantAndPoolName(t, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = raw => sup.getTenant(raw),
      authorize = (t, p, u, _, _, _) => Right(AuthorizedHandshake(poolKey, "t-1", "p-1", user, eff))
    )
    val transport = new ScriptedTransport
    val sessions  = new QuackSessionRegistry(sessionTtlSec = ttlSec, maxHeartbeatSec = 3600)
    val door      = new QuackFrontDoor(router, handshake, sessions, transport, sink, "test")
    Fixture(door, router, transport, sessions, journal, store, events, sup.get(poolKey).get.nodes)

  private def hello(token: String) = ConnectionRequest(token, "v1.5.4", "osx_arm64", 1L, 1L, "", 0L)

  private def connect(fx: Fixture, token: String = Token): String =
    val resp = fx.door.handle(encodeConnectionRequest(hello(token), 1L)).unsafeRunSync()
    messageType(resp) shouldBe Type.ConnectionResponse
    decodeConnectionResponse(resp).toOption.get._1

  private def prepare(fx: Fixture, connId: String, sql: String): Array[Byte] =
    fx.door
      .handle(encodePrepareRequest(connId, 3L, PrepareRequest(sql, None, None)))
      .unsafeRunSync()

  private def fetch(fx: Fixture, connId: String): Array[Byte] =
    fx.door.handle(encodeFetchRequest(connId, 3L, Hugeint(0L, 5L))).unsafeRunSync()

  private def bare(msgType: Int, connId: String): Array[Byte] =
    encodeHeader(Header(msgType, connId, InvalidIndex)) ++ Array[Byte](-1, -1)

  private def appendBytes(connId: String, schema: String, table: String): Array[Byte] =
    val body                          = ArrayBuffer[Byte]()
    def str(id: Int, s: String): Unit =
      body ++= Array[Byte](id.toByte, 0)
      body += s.length.toByte
      body ++= s.getBytes("UTF-8")
    str(1, schema)
    str(2, table)
    body ++= Array[Byte](3, 0, 0) // field 3: empty chunk list
    body ++= Array[Byte](-1, -1)
    encodeHeader(Header(Type.DataRequest, connId, 6L)) ++ body.toArray

  private def errorText(resp: Array[Byte]): String =
    messageType(resp) shouldBe Type.ErrorResponse
    decodeErrorMessage(resp).toOption.get

  "CONNECTION_REQUEST" should "bind a session and answer the client's protocol version" in:
    val fx          = setup()
    val resp        = fx.door.handle(encodeConnectionRequest(hello(Token), 1L)).unsafeRunSync()
    val (connId, r) = decodeConnectionResponse(resp).toOption.get
    connId should have length 32
    r.quackVersion shouldBe 1L
    r.serverPlatform shouldBe "quack-on-demand"
    fx.sessions.get(connId).map(_.bound.user) shouldBe Some("alice")
    fx.events.toList should contain(ManagerEvent.SessionOpened("acme", "alice", "quack"))
    // The version probe opened and closed one node connection with the client's own hello.
    fx.transport.types shouldBe List(Type.ConnectionRequest, Type.Disconnect)
    decodeConnectionRequest(fx.transport.posted(0)).toOption.get shouldBe
      hello(Token).copy(authString = "nodetok")

  it should "refuse a malformed token without touching the auth chain" in:
    val fx = setup()
    errorText(fx.door.handle(encodeConnectionRequest(hello("garbage"), 1L)).unsafeRunSync()) should
      include("Authentication failed")
    fx.sessions.size shouldBe 0

  it should "refuse an unknown pool" in:
    val fx   = setup()
    val resp = fx.door
      .handle(encodeConnectionRequest(hello("tenant=acme&pool=nope&user=u&password=p"), 1L))
      .unsafeRunSync()
    errorText(resp) should include("not found")

  "PREPARE_REQUEST" should "relay the node's response bytes verbatim through the pipeline" in:
    val fx     = setup()
    val connId = connect(fx)
    val resp   = prepare(fx, connId, "SELECT 1")
    resp shouldBe prepareResp(false)
    val t = fx.transport
    t.typesSinceHandshake shouldBe List(
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.Disconnect
    )
    decodeConnectionRequest(t.sinceHandshake(0)).toOption.get.authString shouldBe "nodetok"
    val sent = decodePrepareRequest(t.sinceHandshake(1)).toOption.get.sql
    sent shouldBe "USE memory.main; SELECT 1"
    t.sinceHandshake(1).header.connectionId shouldBe "NODE2"
    fx.sessions.get(connId).get.current.get() shouldBe None
    val rec = fx.router.history.snapshot(10).head
    rec.status shouldBe "ok"
    rec.sql shouldBe "SELECT 1"
    rec.user shouldBe "alice"

  it should "keep the node connection while more batches are pending and release it on the terminal fetch" in:
    val fx = setup()
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT * FROM big") shouldBe prepareResp(true)
    fx.transport.types.last shouldBe Type.PrepareRequest
    fx.sessions.get(connId).get.current.get().isDefined shouldBe true
    fetch(fx, connId) shouldBe fetchResp(true)
    fx.transport.posted.last.header shouldBe Header(Type.FetchRequest, "NODE2", 3L)
    fetch(fx, connId) shouldBe fetchResp(false)
    // The link stays open past the terminal response: the generation 1 client fetches from
    // several threads, and a late fetch must still reach the node (which answers it empty)
    // rather than a closed link. The sweeper releases the drained statement after a grace.
    fx.transport.types.last shouldBe Type.FetchRequest
    fx.sessions.get(connId).get.current.get().isDefined shouldBe true
    fx.door.sweep(Instant.now().plusSeconds(10)).unsafeRunSync()
    fx.transport.types.last shouldBe Type.Disconnect
    fx.sessions.get(connId).get.current.get() shouldBe None

  it should "answer a fetch with no live statement like a node does" in:
    val fx     = setup()
    val connId = connect(fx)
    errorText(fetch(fx, connId)) shouldBe "Result has been closed"

  it should "supersede a still-open statement" in:
    val fx = setup()
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT 1")
    prepare(fx, connId, "SELECT 2")
    fx.transport.typesSinceHandshake shouldBe List(
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.Disconnect,
      Type.ConnectionRequest,
      Type.PrepareRequest
    )

  it should "report a manager-side denial as an ERROR_RESPONSE and never reach a node" in:
    val denying = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult =
        if ctx.statement.contains("secret") then Denied("no grant on secret", Set.empty)
        else Allowed
    val fx     = setup(validator = denying)
    val connId = connect(fx)
    errorText(prepare(fx, connId, "SELECT * FROM secret")) should include("no grant on secret")
    fx.transport.typesSinceHandshake shouldBe Nil
    fx.journal.drainNow()
    fx.store.events.map(_.origin).toList shouldBe List("quack")

  it should "answer an unknown connection id like a node does" in:
    val fx = setup()
    errorText(prepare(fx, "NOPE", "SELECT 1")) shouldBe "Invalid connection id"

  it should "render an admin-dialect result through a node instead of encoding chunks" in:
    val fx     = setup(superuser = true, withAdmin = true)
    val connId = connect(fx)
    prepare(fx, connId, "CREATE ROLE analysts") shouldBe prepareResp(false)
    val rendered = decodePrepareRequest(fx.transport.sinceHandshake(1)).toOption.get.sql
    rendered should include("VALUES")
    rendered should include("'ok'")
    rendered should include("status")

  "APPEND_REQUEST" should "be authorized as a write on the named table before it reaches a node" in:
    val denyWrites = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult =
        if ctx.statement.toUpperCase.startsWith("INSERT") then Denied("read only", Set.empty)
        else Allowed
    val fx     = setup(validator = denyWrites)
    val connId = connect(fx)
    errorText(fx.door.handle(appendBytes(connId, "main", "t")).unsafeRunSync()) should include(
      "read only"
    )
    fx.transport.types should not contain Type.DataRequest

  it should "forward the original bytes to a node under the node connection id" in:
    val fx     = setup()
    val connId = connect(fx)
    val bytes  = appendBytes(connId, "main", "t")
    fx.door.handle(bytes).unsafeRunSync() shouldBe success
    // The router's USE prefix runs on the link first: an append names schema.table and a fresh
    // node connection's default catalog is the transient memory db, not the pool's catalog.
    fx.transport.typesSinceHandshake shouldBe
      List(Type.ConnectionRequest, Type.PrepareRequest, Type.DataRequest, Type.Disconnect)
    decodePrepareRequest(fx.transport.sinceHandshake(1)).toOption.get.sql shouldBe "USE memory.main"
    val fwd = fx.transport.sinceHandshake(2)
    fwd.header.connectionId shouldBe "NODE2"
    fwd.bytes.drop(fwd.bodyStart).toSeq shouldBe bytes
      .drop(frame(bytes).toOption.get.bodyStart)
      .toSeq
    fx.router.history.snapshot(10).head.sql should include("INSERT INTO \"main\".\"t\"")

  "HEARTBEAT_REQUEST" should "renew the lease and answer SUCCESS" in:
    val fx     = setup()
    val connId = connect(fx)
    fx.door.handle(bare(Type.HeartbeatRequest, connId)).unsafeRunSync() shouldBe success

  "CANCEL_REQUEST and ACKNOWLEDGEMENT" should "answer SUCCESS when nothing is running" in:
    val fx     = setup()
    val connId = connect(fx)
    fx.door.handle(bare(Type.CancelRequest, connId)).unsafeRunSync() shouldBe success
    fx.door.handle(bare(Type.Acknowledgement, connId)).unsafeRunSync() shouldBe success
    fx.transport.typesSinceHandshake shouldBe Nil

  "DISCONNECT_MESSAGE" should "release the statement, unbind, and refuse a second disconnect" in:
    val fx = setup()
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT 1")
    fx.door.handle(bare(Type.Disconnect, connId)).unsafeRunSync() shouldBe success
    fx.transport.types.last shouldBe Type.Disconnect
    fx.sessions.get(connId) shouldBe None
    errorText(fx.door.handle(bare(Type.Disconnect, connId)).unsafeRunSync()) shouldBe
      "Invalid connection id"

  "an unsupported message" should "be refused like a node does" in:
    val fx     = setup()
    val connId = connect(fx)
    errorText(fx.door.handle(bare(Type.PrepareResponse, connId)).unsafeRunSync()) shouldBe
      "Unsupported message type for server"

  "sweep" should "expire idle sessions past the TTL and release their node connection" in:
    val fx = setup(ttlSec = 1)
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT 1")
    fx.door.sweep(Instant.now().plusSeconds(5)).unsafeRunSync()
    fx.transport.types.last shouldBe Type.Disconnect
    errorText(fetch(fx, connId)) shouldBe "Invalid connection id"

  "an admin kill" should "abort the relayed statement on the node" in:
    val fx = setup()
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT 1")
    val live = fx.router.registry.list()
    live should have size 1
    live.head.user shouldBe "alice"
    fx.router.registry.kill(live.head.id)
    fx.transport.types.last shouldBe Type.Disconnect
