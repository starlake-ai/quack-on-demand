package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.*
import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.authz.HandleInspector
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.model.*
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, EffectiveSet}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.EventJournal
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import ai.starlake.quack.security.InMemoryAuthService
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

/** The front door against a scripted node transport: every message type, the statement lifecycle
  * around the relayed node connection, and the policy, audit and kill hooks the pipeline provides.
  */
class QuackFrontDoorSpec extends AnyFlatSpec with Matchers:

  import QuackWire.*

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")
  private val Token            = "tenant=acme&pool=sales&user=alice&password=pw"

  /** The metastore keys a DuckLake tenant-db needs; the initializer is stubbed, so none is used. */
  private val LakeMetastore = Map(
    "pgHost"     -> "localhost",
    "pgPort"     -> "5432",
    "pgUser"     -> "qod",
    "pgPassword" -> "pw",
    "schemaName" -> "main"
  )

  private def connResp(id: String)       = QuackTestFixtures.serializeSampleConnectionResponse(id)
  private def prepareResp(more: Boolean) =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.valueOf(5L), more, true)
  private def fetchResp(chunk: Boolean) = QuackTestFixtures.serializeSampleFetchResponse(chunk)
  private val success                   = encodeSuccess()

  /** How a session handle prelude starts, and the author-stamping statement the router sends as a
    * write's first PREPARE (`FlightSqlRouter.stampPrelude`).
    */
  private val HandlePrefix = "CALL system.main.qod_session('"
  private val StampPrefix  = "BEGIN; CALL ducklake_set_commit_message("

  /** Stands for a session handle in [[ScriptedTransport.shape]]: the handle itself never appears in
    * an assertion, so never in a failure message.
    */
  private val HandleMarker = "<session handle>"

  /** DuckDB 2.0's answer to every statement but ROLLBACK and COMMIT, a session handle's CALL
    * included, inside a transaction a failed statement aborted (as the 2.0.0 CLI answers).
    */
  private val TxAbortedError =
    "TransactionContext Error: Current transaction is aborted (please ROLLBACK)"

  /** How [[ScriptedTransport.statementTransportFailures]] fails a statement: a 5xx, after the node
    * received it.
    */
  private val StatementTransportError = "HTTP 503 from Quack node"

  /** The answer to a statement lost that way on its client transaction's link, and the fence's
    * until the client rolls back.
    */
  private val StatementLostAnswer =
    "transaction aborted: a statement could not run on the transaction's node connection " +
      s"($StatementTransportError); ROLLBACK to continue"

  /** A PREPARE a scripted node received: its SQL, the node (URI) it reached and the node connection
    * it rode.
    */
  private final case class Prepared(sql: String, uri: URI, connectionId: String):
    def isHandle: Boolean = sql.startsWith(HandlePrefix)
    def label: String     = if isHandle then HandleMarker else sql
    def verb: String      = sql.trim.takeWhile(_.isLetter).toUpperCase(java.util.Locale.ROOT)

  /** Plays a node: records every request as a decoded frame and answers by message type. A session
    * handle prelude (`CALL system.main.qod_session(...)`) is answered apart and kept out of
    * `posted`, so the sequence of `posted` frames is what it was before handles existed; every
    * PREPARE, the handles included, is listed in arrival order in `prepared`. `preludeError`
    * refuses the next `preludeRefusals` handles (all of them by default) and the node accepts them
    * again after that; `stampError` fails the author-stamping PREPARE only;
    * `statementTransportFailures` fails that many statement PREPAREs (never a handle) in transport,
    * once the node received them. `abortsTx` plays DuckDB's transactions on each node connection:
    * BEGIN opens one, COMMIT, ROLLBACK and DISCONNECT end it, and once a PREPARE failed inside it,
    * every PREPARE but ROLLBACK and COMMIT (which DuckDB runs as a ROLLBACK), a session handle
    * included, answers [[TxAbortedError]].
    */
  /** How a respawned node answers a connection of the process it replaced. */
  private final class DeadConnection extends RuntimeException("Invalid connection id")

  private final class ScriptedTransport extends QuackTransport:
    @volatile var prepareNeedsMore: Boolean       = false
    @volatile var prepareError: Option[String]    = None
    @volatile var preludeError: Option[String]    = None
    @volatile var preludeRefusals: Int            = Int.MaxValue
    @volatile var stampError: Option[String]      = None
    @volatile var statementTransportFailures: Int = 0
    @volatile var abortsTx: Boolean               = false
    @volatile var fetchChunksBeforeEnd: Int       = 1
    val posted: ArrayBuffer[Frame]                = ArrayBuffer.empty
    val prepared: ArrayBuffer[Prepared]           = ArrayBuffer.empty
    val prepareUris: ArrayBuffer[URI]             = ArrayBuffer.empty
    @volatile var unreachablePorts: Set[Int]      = Set.empty
    private val connections                       = new AtomicInteger(0)
    private val transactions                      = TrieMap.empty[String, Boolean] // id -> aborted
    private var fetchesServed                     = 0
    def types: List[Int]                          = posted.toList.map(_.header.msgType)
    def preludes: List[String]                    = prepared.toList.filter(_.isHandle).map(_.sql)

    /** Every PREPARE in arrival order, a session handle shown as [[HandleMarker]]. */
    def shape: List[String] = prepared.toList.map(_.label)

    /** Traffic after a successful handshake, which itself costs one probe connection (a
      * CONNECTION_REQUEST and a DISCONNECT) that takes node connection id NODE1.
      */
    def sinceHandshake: List[Frame]    = posted.toList.drop(2)
    def typesSinceHandshake: List[Int] = sinceHandshake.map(_.header.msgType)

    /** Told every PREPARE as it arrives, before the node answers it. */
    @volatile var onPrepare: Prepared => Unit = _ => ()

    /** The first PREPARE it matches is recorded and never answered (`hung` counts down). */
    @volatile var hangWhen: Prepared => Boolean = _ => false
    val hung                                    = new java.util.concurrent.CountDownLatch(1)

    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO.defer {
      val hangs = frame(body).toOption
        .filter(_.header.msgType == Type.PrepareRequest)
        .flatMap(f => decodePrepareRequest(f).toOption.map(r => Prepared(r.sql, uri, "")))
        .exists(hangWhen)
      if hangs then
        IO {
          hangWhen = _ => false
          posted.synchronized(prepareUris += uri)
          hung.countDown()
        } *> IO.never
      else answer(uri, body)
    }

    /** Node connections whose process is gone: a respawned node refuses them. */
    @volatile var deadConnections: Set[String] = Set.empty

    /** Node connection ids handed out so far. */
    def connectionIds: Set[String] = (1 to connections.get()).map(i => s"NODE$i").toSet

    private def answer(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      if unreachablePorts.contains(uri.getPort) then
        throw new java.io.IOException(s"connection refused: ${uri.getPort}")
      val f    = frame(body).toOption.get
      val conn = f.header.connectionId
      if deadConnections.contains(conn) then
        posted.synchronized(posted += f)
        throw new DeadConnection
      val sent =
        if f.header.msgType != Type.PrepareRequest then None
        else decodePrepareRequest(f).toOption.map(r => Prepared(r.sql, uri, conn))
      posted.synchronized(sent.foreach(prepared += _))
      sent.foreach(onPrepare)
      // An aborted transaction refuses the statement before anything else looks at it.
      val aborted = sent
        .filter(p => abortsTx && transactions.get(conn).contains(true))
        .filterNot(p => p.verb == "ROLLBACK" || p.verb == "COMMIT")
        .map(_ => encodeError(TxAbortedError))
      val resp = sent.filter(_.isHandle) match
        case Some(_) =>
          aborted.getOrElse {
            val refusal = posted.synchronized {
              preludeError.filter(_ => preludeRefusals > 0).map { m =>
                preludeRefusals -= 1
                encodeError(m)
              }
            }
            refusal.getOrElse(prepareResp(false))
          }
        case None =>
          posted.synchronized(posted += f)
          f.header.msgType match
            case Type.ConnectionRequest => connResp(s"NODE${connections.incrementAndGet()}")
            case Type.PrepareRequest    =>
              posted.synchronized(prepareUris += uri)
              fetchesServed = 0
              val lost = posted.synchronized {
                val fails = statementTransportFailures > 0
                if fails then statementTransportFailures -= 1
                fails
              }
              if lost then throw QuackWireError.Transient(StatementTransportError)
              val failure =
                if sent.exists(_.sql.startsWith(StampPrefix)) then stampError.orElse(prepareError)
                else prepareError
              aborted.getOrElse(failure.map(encodeError).getOrElse(prepareResp(prepareNeedsMore)))
            case Type.FetchRequest =>
              fetchesServed += 1
              if fetchesServed <= fetchChunksBeforeEnd then fetchResp(true) else fetchResp(false)
            // The pinned node refuses CANCEL_REQUEST; a fake that accepted it would hide that.
            case Type.CancelRequest => encodeError("Unsupported message type for server")
            case _                  => success
      if abortsTx then track(f, sent, resp)
      resp
    }

    /** The transaction of the node connection `f` rode, after the node answered `resp`. */
    private def track(f: Frame, sent: Option[Prepared], resp: Array[Byte]): Unit =
      val conn = f.header.connectionId
      if f.header.msgType == Type.Disconnect then transactions.remove(conn)
      sent.foreach { p =>
        // A failure inside a transaction aborts it (DuckDB spares a parse error, which no case
        // here plays), a refused session handle included: the Permission Error qod-node raises
        // at bind aborts it too.
        if messageType(resp) == Type.ErrorResponse then
          transactions.updateWith(conn)(_.map(_ => true))
        else if p.verb == "BEGIN" then transactions.put(conn, false)
        else if p.verb == "COMMIT" || p.verb == "ROLLBACK" then transactions.remove(conn)
      }

  private final case class Fixture(
      door: QuackFrontDoor,
      router: FlightSqlRouter,
      transport: ScriptedTransport,
      sessions: QuackSessionRegistry,
      journal: EventJournal,
      store: RecordingTelemetryStore,
      events: ArrayBuffer[ManagerEvent],
      nodes: List[RunningNode],
      tracker: NodeLoadTracker
  )

  private def setup(
      superuser: Boolean = false,
      withAdmin: Boolean = false,
      ttlSec: Long = 3600,
      maxConcurrent: Int = 0,
      stamping: Boolean = false,
      auth: AuthenticationService = InMemoryAuthService.acceptingAnyPassword(),
      drainTimeout: FiniteDuration = 10.seconds,
      /** Runs once the router holds the pool snapshot it picks from, before it picks: a teardown
        * that starts draining there plays the race of a route chosen just before the drain.
        */
      beforePick: NodeLoadTracker => Unit = _ => (),
      /** Told every manager event as it is emitted, on the emitting fiber. */
      onEvent: ManagerEvent => Unit = _ => ()
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
    // The author stamp applies to DuckLake pools only. Creating a DuckLake tenant-db runs a catalog
    // initializer, which must never reach a Postgres from a unit spec.
    val sup = new PoolSupervisor(
      backend,
      tracker,
      new InMemoryControlPlaneStore(),
      duckLakeInitializer = (_, _) => (),
      drainTimeout = drainTimeout
    )
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    val (kind, metastore, dataPath) =
      if stamping then (TenantDbKind.DuckLake, LakeMetastore, "/data/acme_default")
      else (TenantDbKind.InMemory, Map.empty[String, String], "")
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, kind, metastore, dataPath)
      .unsafeRunSync()
    sup
      .createPool(poolKey, RoleDistribution(0, 0, 2), maxConcurrentPerNode = maxConcurrent)
      .unsafeRunSync()
    val store                  = new RecordingTelemetryStore
    val journal                = new EventJournal(store)
    val events                 = ArrayBuffer.empty[ManagerEvent]
    val sink: ManagerEventSink = e =>
      onEvent(e)
      events.synchronized(events += e)
    val client =
      new QuackHttpClient(TestArrow.sharedAllocator)
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral()),
      journal = journal,
      stampWrites = stamping,
      events = sink,
      adminExecutor =
        if withAdmin then Some(new ai.starlake.quack.edge.admin.AdminStatementExecutor(sup))
        else None,
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled,
      refsConfigFor = _ =>
        beforePick(tracker)
        ai.starlake.acl.model.Config.forDuckDB(None, None)
    )
    val user      = RbacUser("u-1", if superuser then None else Some("t-1"), "alice", kind = "user")
    val eff       = EffectiveSet(user, Nil, Nil, Nil, Nil)
    val handshake = new EdgeHandshake(
      auth,
      lookupPool = (t, p) =>
        sup.findPoolKeyByTenantAndPoolName(t, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = raw => sup.getTenant(raw),
      authorize = _ => Right(AuthorizedHandshake(poolKey, "t-1", "p-1", user, eff)),
      versions = sup.policyVersions
    )
    val transport = new ScriptedTransport
    val sessions  = new QuackSessionRegistry(sessionTtlSec = ttlSec, maxHeartbeatSec = 3600)
    val door      = new QuackFrontDoor(router, handshake, sessions, transport, sink, "test")
    Fixture(
      door,
      router,
      transport,
      sessions,
      journal,
      store,
      events,
      sup.get(poolKey).get.nodes,
      tracker
    )

  private def hello(token: String) =
    ConnectionRequest(token, "v2.0.0", "osx_arm64", 3L, 3L, "qod-test", 0L)

  private def connect(fx: Fixture, token: String = Token): String =
    val resp = fx.door.handle(encodeConnectionRequest(hello(token), 1L)).unsafeRunSync()
    messageType(resp) shouldBe Type.ConnectionResponse
    decodeConnectionResponse(resp).toOption.get._1

  private def prepare(fx: Fixture, connId: String, sql: String): Array[Byte] =
    fx.door
      .handle(encodePrepareRequest(connId, 3L, PrepareRequest(sql, None, None)))
      .unsafeRunSync()

  private def fetch(fx: Fixture, connId: String): Array[Byte] =
    fx.door.handle(encodeFetchRequest(connId, 3L, Hugeint(0L, 5L), 1L)).unsafeRunSync()

  private def bare(msgType: Int, connId: String): Array[Byte] =
    encodeHeader(Header(msgType, connId, InvalidIndex)) ++ Array[Byte](-1, -1)

  private def errorText(resp: Array[Byte]): String =
    messageType(resp) shouldBe Type.ErrorResponse
    decodeErrorMessage(resp).toOption.get

  "CONNECTION_REQUEST" should "bind a session and answer the client's protocol version" in:
    val fx          = setup()
    val resp        = fx.door.handle(encodeConnectionRequest(hello(Token), 1L)).unsafeRunSync()
    val (connId, r) = decodeConnectionResponse(resp).toOption.get
    connId should have length 32
    r.quackVersion shouldBe 3L
    r.serverPlatform shouldBe "quack-on-demand"
    fx.sessions.get(connId).map(_.bound.user) shouldBe Some("alice")
    fx.events.toList should contain(ManagerEvent.SessionOpened("acme", "alice", "quack"))
    // The version probe opened and closed one node connection with the client's own hello.
    fx.transport.types shouldBe List(Type.ConnectionRequest, Type.Disconnect)
    decodeConnectionRequest(fx.transport.posted(0)).toOption.get shouldBe
      hello(Token).copy(authString = "nodetok")

  it should "refuse every client when no authenticator is configured" in:
    val fx = setup(auth = new AuthenticationService(AuthenticationConfig.disabled, "x"))
    errorText(fx.door.handle(encodeConnectionRequest(hello(Token), 1L)).unsafeRunSync()) should
      include("no authenticator is configured")
    fx.sessions.size shouldBe 0
    fx.transport.types shouldBe Nil

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

  it should "refuse a DuckDB 1.5 client with a clear message" in:
    val fx  = setup()
    val old = ConnectionRequest(Token, "v1.5.6", "osx_arm64", 1L, 1L, "", 0L)
    errorText(fx.door.handle(encodeConnectionRequest(old, 1L)).unsafeRunSync()) should
      include("requires a DuckDB 2.0 client")
    fx.sessions.size shouldBe 0

  it should "refuse a client whose range stops short of Quack v3 before authenticating" in:
    val fx  = setup()
    val old = ConnectionRequest("garbage", "v1.5.6", "osx_arm64", 1L, 2L, "", 0L)
    val msg = errorText(fx.door.handle(encodeConnectionRequest(old, 1L)).unsafeRunSync())
    msg shouldBe QuackFrontDoor.clientVersionMessage(old)
    msg should include("v1.5.6")
    fx.transport.posted shouldBe empty

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
    // The link stays open past the terminal response: the client fetches ahead from several
    // threads, and a late fetch must still reach the node (which answers it empty)
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

  it should "report a node's authorization refusal as an ERROR_RESPONSE journaled as a denial" in:
    val fx     = setup()
    val connId = connect(fx)
    fx.transport.prepareError = Some("Permission Error: QoD: user:alice lacks grants on secret")
    errorText(prepare(fx, connId, "SELECT * FROM secret")) should include(
      "QoD: user:alice lacks grants on secret"
    )
    fx.transport.prepareError = None
    fx.journal.drainNow()
    fx.store.events.map(e => (e.origin, e.outcome)).toList shouldBe List(("quack", "denied"))

  it should "relay a client data scan, which only the front door can feed" in:
    // The front door is the one edge that relays SEND_DATA, so it alone lets a DuckDB 2.0
    // client's scan_data_from_quack_client INSERT reach a node (clientDataScanRefusal).
    val fx     = setup()
    val connId = connect(fx)
    val insert = "INSERT INTO orders SELECT * FROM scan_data_from_quack_client(" +
      "'0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', NULL::STRUCT(a INTEGER), ordered := true)"
    prepare(fx, connId, insert) shouldBe prepareResp(false)
    fx.transport.prepared.toList
      .map(_.sql)
      .exists(_.contains("scan_data_from_quack_client")) shouldBe
      true

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

  private def nonceOf(handleSql: String): String =
    HandleInspector.payload(handleSql).hcursor.get[String]("nonce").toOption.get

  "a relayed statement" should "follow a session handle for the session's principal on its node" in:
    val fx     = setup()
    val connId = connect(fx)
    messageType(prepare(fx, connId, "SELECT 1")) shouldBe Type.PrepareResponse
    fx.transport.preludes.size shouldBe 1
    val handle  = fx.transport.prepared.head
    val payload = HandleInspector.payload(handle.sql)
    payload.hcursor.downField("principal").get[String]("id") shouldBe Right("u-1")
    // Minted for the node the statement went to, not for just any node of the pool, and sent over
    // that node's connection.
    payload.hcursor.get[String]("node") shouldBe Right(lastPrepareNode(fx))
    handle.uri shouldBe fx.transport.prepareUris.last

  it should "answer an error, relaying nothing, when every node refuses the handle" in:
    val fx     = setup()
    val connId = connect(fx)
    fx.transport.preludeError = Some("qod-node: session handle refused: handle MAC does not verify")
    val resp = prepare(fx, connId, "SELECT 1")
    messageType(resp) shouldBe Type.ErrorResponse
    errorText(resp) should include("session handle")
    fx.transport.sinceHandshake.exists(_.header.msgType == Type.PrepareRequest) shouldBe false

  "a transaction's link" should "follow a fresh session handle with BEGIN, a statement and COMMIT" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)
    prepare(fx, connId, "COMMIT") shouldBe prepareResp(false)
    val t = fx.transport
    t.shape shouldBe List(
      HandleMarker,
      "BEGIN",
      HandleMarker,
      "USE memory.main; SELECT 1",
      HandleMarker,
      "COMMIT"
    )
    // One node connection carried all three, and each PREPARE had a handle of its own.
    t.typesSinceHandshake.count(_ == Type.ConnectionRequest) shouldBe 1
    t.prepared.toList.map(_.uri).distinct.size shouldBe 1
    val handles = t.prepared.toList.filter(_.isHandle).map(h => HandleInspector.payload(h.sql))
    handles.map(_.hcursor.get[String]("nonce")).distinct.size shouldBe 3
    handles.map(_.hcursor.get[String]("node")).distinct.size shouldBe 1
    handles.map(_.hcursor.downField("principal").get[String]("id")).distinct shouldBe
      List(Right("u-1"))

  /** Each frame posted since `from`, by message type and node connection id. */
  private def postedSince(fx: Fixture, from: Int): List[(Int, String)] =
    fx.transport.posted.toList.drop(from).map(p => (p.header.msgType, p.header.connectionId))

  /** The node holding the session's transaction link. */
  private def txNode(fx: Fixture, connId: String): String =
    fx.sessions.get(connId).flatMap(_.txLink.get()).map(_._1).getOrElse(fail("no transaction"))

  "a refused handle inside a transaction" should "relay nothing, end the transaction and leave the node healthy" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    val node     = txNode(fx, connId)
    val posted   = fx.transport.posted.size
    val prepared = fx.transport.prepared.size
    fx.transport.preludeError = Some("qod-node: session handle refused: handle MAC does not verify")
    fx.transport.preludeRefusals = 1
    val refusal = "transaction aborted: the node refused the statement's session handle " +
      "(qod-node: session handle refused: handle MAC does not verify); ROLLBACK to continue"
    errorText(prepare(fx, connId, "SELECT 1")) shouldBe refusal
    // The refused handle was the only PREPARE the node saw: the statement itself was not relayed,
    // and the transaction's link was DISCONNECTed, which rolls the transaction back.
    fx.transport.prepared.toList.drop(prepared).map(_.isHandle) shouldBe List(true)
    postedSince(fx, posted) shouldBe List((Type.Disconnect, "NODE2"))
    // A Permanent failure: nothing is booked against the node (its own health probe judges it),
    // and the router no longer holds the transaction.
    fx.tracker.snapshot(node).healthy shouldBe true
    fx.router.sessions.get(connId).get.txOpen shouldBe false
    fx.sessions.get(connId).get.txLink.get() shouldBe None
    // The session is fenced until the client rolls back, and runs on after it.
    errorText(prepare(fx, connId, "SELECT 2")) shouldBe refusal
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
    prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)

  "a refused handle outside a transaction" should "stay transient and go to another node" in:
    val fx     = setup()
    val connId = connect(fx)
    fx.transport.preludeError = Some("qod-node: session handle refused: handle expired")
    fx.transport.preludeRefusals = 1
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)
    val refusedOn = fx.transport.prepared.head.uri.getPort
    val refused   = fx.nodes.find(_.port == refusedOn).map(_.nodeId).get
    // A fresh connection is never inside an aborted transaction, so the refusal is the node's
    // problem: booked as a transient failure, and the router took the statement elsewhere.
    lastPrepareNode(fx) should not be refused
    fx.tracker.snapshot(refused).healthy shouldBe false
    fx.tracker.snapshot(lastPrepareNode(fx)).healthy shouldBe true
    // The refusing node's link was released with no statement sent on it.
    fx.transport.typesSinceHandshake shouldBe List(
      Type.ConnectionRequest,
      Type.Disconnect,
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.Disconnect
    )

  "a transport failure inside a transaction" should "end the transaction and send nothing elsewhere" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    val node     = txNode(fx, connId)
    val posted   = fx.transport.posted.size
    val prepared = fx.transport.prepared.size
    fx.transport.statementTransportFailures = 1
    val answer = StatementLostAnswer
    errorText(prepare(fx, connId, "INSERT INTO t VALUES (1)")) shouldBe answer
    // The handle and the write went out on the transaction's connection, and nothing went to
    // another node: the fence keeps every later statement off a fresh link, where it would run
    // outside the client's transaction. The link was DISCONNECTed, which rolls the node's
    // transaction back.
    val sent = fx.transport.prepared.toList.drop(prepared)
    sent.map(p => (p.connectionId, p.isHandle)) shouldBe List(("NODE2", true), ("NODE2", false))
    sent(1).sql should include("INSERT INTO t VALUES (1)")
    postedSince(fx, posted) shouldBe List(
      (Type.PrepareRequest, "NODE2"),
      (Type.Disconnect, "NODE2")
    )
    // Permanent: nothing booked against the node, and the router no longer holds the transaction.
    fx.tracker.snapshot(node).healthy shouldBe true
    fx.router.sessions.get(connId).get.txOpen shouldBe false
    fx.sessions.get(connId).get.txLink.get() shouldBe None
    // Fenced, with nothing sent, until the client rolls back; its ROLLBACK is a genuine response
    // from a fresh link, and the session runs on.
    val fenced = fx.transport.prepared.size
    errorText(prepare(fx, connId, "INSERT INTO t VALUES (2)")) shouldBe answer
    fx.transport.prepared.size shouldBe fenced
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
    fx.transport.shape.takeRight(4) shouldBe List(HandleMarker, "BEGIN", HandleMarker, "ROLLBACK")
    fx.sessions.get(connId).get.txAborted.get() shouldBe None
    prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)

  "a COMMIT lost in transport" should "be answered as of unknown outcome, not as aborted" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    prepare(fx, connId, "INSERT INTO t VALUES (1)") shouldBe prepareResp(false)
    val node     = txNode(fx, connId)
    val posted   = fx.transport.posted.size
    val prepared = fx.transport.prepared.size
    fx.transport.statementTransportFailures = 1
    // The COMMIT went out after its handle and its answer was lost: it may have run, so the client
    // is told neither that the transaction aborted nor that nothing was committed.
    errorText(prepare(fx, connId, "COMMIT")) shouldBe
      "the transaction's outcome is unknown: its node connection failed during COMMIT " +
      s"($StatementTransportError); check the data before retrying"
    val sent = fx.transport.prepared.toList.drop(prepared)
    sent.map(p => (p.connectionId, p.label)) shouldBe
      List(("NODE2", HandleMarker), ("NODE2", "COMMIT"))
    // Nothing went to another node, and the transaction's link was DISCONNECTed.
    postedSince(fx, posted) shouldBe List(
      (Type.PrepareRequest, "NODE2"),
      (Type.Disconnect, "NODE2")
    )
    // The COMMIT ended the transaction on the client's side whatever the node did: there is no
    // fence to roll back from, nothing is booked against the node, and the session runs on.
    fx.tracker.snapshot(node).healthy shouldBe true
    fx.router.sessions.get(connId).get.txOpen shouldBe false
    fx.sessions.get(connId).get.txLink.get() shouldBe None
    fx.sessions.get(connId).get.txAborted.get() shouldBe None
    prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)

  it should "keep the aborted answer when its own session handle fails" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    prepare(fx, connId, "INSERT INTO t VALUES (1)") shouldBe prepareResp(false)
    val prepared = fx.transport.prepared.size
    fx.transport.preludeError = Some("qod-node: session handle refused: handle MAC does not verify")
    fx.transport.preludeRefusals = 1
    errorText(prepare(fx, connId, "COMMIT")) shouldBe
      "transaction aborted: the node refused the statement's session handle " +
      "(qod-node: session handle refused: handle MAC does not verify); ROLLBACK to continue"
    // The refused handle was the only PREPARE: the COMMIT never went out, so nothing was
    // committed. The COMMIT ended the client's transaction, so no fence is left.
    fx.transport.prepared.toList.drop(prepared).map(_.label) shouldBe List(HandleMarker)
    fx.sessions.get(connId).get.txAborted.get() shouldBe None
    prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)

  it should "keep the aborted answer when it finds the fence up, sending nothing" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.transport.statementTransportFailures = 1
    errorText(prepare(fx, connId, "INSERT INTO t VALUES (1)")) shouldBe StatementLostAnswer
    // The statement before it had already lost the transaction: the COMMIT is never sent and gets
    // the fence's answer, and it ends the transaction on the client's side too.
    val fenced = fx.transport.prepared.size
    errorText(prepare(fx, connId, "COMMIT")) shouldBe StatementLostAnswer
    fx.transport.prepared.size shouldBe fenced
    fx.sessions.get(connId).get.txAborted.get() shouldBe None
    prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)

  "QuackFrontDoor.txEndedMessage" should "word a lost statement and an unmintable handle alike" in:
    val mintFailure = "requirement failed: no principal is bound to this statement"
    for failure <- List(
        LinkFailure.Transient(StatementTransportError),
        LinkFailure.Permanent(mintFailure)
      )
    do
      QuackFrontDoor.txEndedMessage(failure, commit = false) shouldBe
        "transaction aborted: a statement could not run on the transaction's node connection " +
        s"(${failure.message}); ROLLBACK to continue"

  // ---- DuckDB 2.0 aborts a transaction on a failed statement and then runs only ROLLBACK ----

  /** BEGIN, then a statement the node fails, on nodes that abort a transaction as DuckDB does. The
    * statement's own error keeps the transaction's link: whether the transaction survived (a parse
    * error does not abort it) is for the node to say on the next statement.
    */
  private def failedInTx(fx: Fixture): String =
    fx.transport.abortsTx = true
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.transport.prepareError = Some("Invalid Input Error: boom")
    errorText(prepare(fx, connId, "SELECT error('boom')")) should include("boom")
    fx.transport.prepareError = None
    fx.sessions.get(connId).get.txLink.get().isDefined shouldBe true
    connId

  "a failed statement inside a transaction" should "leave the client's ROLLBACK a genuine response" in:
    val fx       = setup()
    val connId   = failedInTx(fx)
    val posted   = fx.transport.posted.size
    val prepared = fx.transport.prepared.size
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
    // The aborted transaction refused the ROLLBACK's handle, as DuckDB refuses any CALL there; its
    // link was DISCONNECTed, and a fresh one ran BEGIN and the ROLLBACK, each after a handle.
    fx.transport.prepared.toList.drop(prepared).map(p => (p.connectionId, p.label)) shouldBe List(
      ("NODE2", HandleMarker),
      ("NODE3", HandleMarker),
      ("NODE3", "BEGIN"),
      ("NODE3", HandleMarker),
      ("NODE3", "ROLLBACK")
    )
    postedSince(fx, posted) shouldBe List(
      (Type.Disconnect, "NODE2"),
      (Type.ConnectionRequest, ""),
      (Type.PrepareRequest, "NODE3"),
      (Type.PrepareRequest, "NODE3"),
      (Type.Disconnect, "NODE3")
    )
    // Nothing was booked against a node, and the session runs on, transactions included.
    fx.nodes.map(n => fx.tracker.snapshot(n.nodeId).healthy).distinct shouldBe List(true)
    fx.sessions.get(connId).get.txAborted.get() shouldBe None
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)
    prepare(fx, connId, "COMMIT") shouldBe prepareResp(false)
    fx.transport.shape.takeRight(6) shouldBe List(
      HandleMarker,
      "BEGIN",
      HandleMarker,
      "USE memory.main; SELECT 1",
      HandleMarker,
      "COMMIT"
    )

  it should "answer every other statement with DuckDB's own error until ROLLBACK" in:
    val fx       = setup()
    val connId   = failedInTx(fx)
    val posted   = fx.transport.posted.size
    val prepared = fx.transport.prepared.size
    errorText(prepare(fx, connId, "SELECT 2")) shouldBe TxAbortedError
    // Only the statement's handle reached the node, which refused it; the link was DISCONNECTed.
    fx.transport.prepared.toList.drop(prepared).map(_.isHandle) shouldBe List(true)
    postedSince(fx, posted) shouldBe List((Type.Disconnect, "NODE2"))
    fx.nodes.map(n => fx.tracker.snapshot(n.nodeId).healthy).distinct shouldBe List(true)
    val routerSession = fx.router.sessions.get(connId).get
    routerSession.txOpen shouldBe false
    routerSession.pinnedNodeId shouldBe None
    // Fenced: the same answer, with nothing sent to a node, until ROLLBACK.
    val fenced = fx.transport.prepared.size
    errorText(prepare(fx, connId, "INSERT INTO t VALUES (1)")) shouldBe TxAbortedError
    fx.transport.prepared.size shouldBe fenced
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
    fx.transport.shape.takeRight(4) shouldBe List(HandleMarker, "BEGIN", HandleMarker, "ROLLBACK")
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)
    prepare(fx, connId, "COMMIT") shouldBe prepareResp(false)

  "an admin statement's rendering" should "follow a session handle of the system principal" in:
    val fx     = setup(superuser = true, withAdmin = true)
    val connId = connect(fx)
    prepare(fx, connId, "CREATE ROLE analysts") shouldBe prepareResp(false)
    val log = fx.transport.prepared.toList
    log.map(_.isHandle) shouldBe List(true, false)
    log(1).sql should startWith("SELECT * FROM (VALUES")
    // The result is the manager's own, rendered by a node: never run as the session's user (the
    // superuser u-1 here), or Phase C's verdicts would be asked about the manager's own SQL.
    val principal = HandleInspector.payload(log.head.sql).hcursor.downField("principal")
    principal.get[Boolean]("system") shouldBe Right(true)
    principal.get[String]("id") shouldBe Right("system")

  // ---- author stamping: the DuckLake pools' writes (BEGIN, stamp, statement, COMMIT) ----

  private val StampedWrite = "INSERT INTO t VALUES (1)"

  "a stamped write" should "send its stamp, the write and the COMMIT each after a session handle" in:
    val fx     = setup(stamping = true)
    val connId = connect(fx)
    prepare(fx, connId, StampedWrite) shouldBe prepareResp(false)
    val log = fx.transport.prepared.toList
    log.map(_.isHandle) shouldBe List(true, false, true, false, true, false)
    log(1).sql should startWith(StampPrefix)
    log(1).sql should include("tenant:acme/user:alice")
    log(3).sql should include(StampedWrite)
    log(5).sql shouldBe "COMMIT"
    // One node connection, three handles of their own.
    log.map(_.uri).distinct.size shouldBe 1
    log.filter(_.isHandle).map(h => nonceOf(h.sql)).distinct.size shouldBe 3

  it should "never go out unstamped when its stamp's handle is refused" in:
    val fx     = setup(stamping = true)
    val connId = connect(fx)
    // Only the first handle, the stamp's on the first node, is refused: the retry on the other
    // node gets its handles.
    fx.transport.preludeError = Some("qod-node: session handle refused: handle expired")
    fx.transport.preludeRefusals = 1
    prepare(fx, connId, StampedWrite) shouldBe prepareResp(false)
    // The write that reached a node is the stamped one. A refused handle is not the stamp SQL
    // failing: falling back to a fresh link would have sent the write alone, with no COMMIT.
    val statements = fx.transport.prepared.toList.filterNot(_.isHandle).map(_.sql)
    statements.size shouldBe 3
    statements.head should startWith(StampPrefix)
    statements(1) should include(StampedWrite)
    statements(2) shouldBe "COMMIT"
    // The first node's link was released without a statement; the second node served the write.
    fx.transport.typesSinceHandshake shouldBe List(
      Type.ConnectionRequest,
      Type.Disconnect,
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.PrepareRequest,
      Type.PrepareRequest,
      Type.Disconnect
    )

  it should "answer an error and send no statement when every node refuses its handles" in:
    val fx     = setup(stamping = true)
    val connId = connect(fx)
    fx.transport.preludeError = Some("qod-node: session handle refused: handle expired")
    val resp = prepare(fx, connId, StampedWrite)
    errorText(resp) should include("session handle")
    fx.transport.prepared.toList.exists(!_.isHandle) shouldBe false
    // One link per node the router tried, none reopened to send the write unstamped.
    fx.transport.typesSinceHandshake.count(_ == Type.ConnectionRequest) shouldBe 2
    fx.transport.typesSinceHandshake.count(_ == Type.Disconnect) shouldBe 2

  it should "still go out unstamped when the stamp itself fails" in:
    val fx     = setup(stamping = true)
    val connId = connect(fx)
    fx.transport.stampError = Some("Catalog Error: function ducklake_set_commit_message failed")
    prepare(fx, connId, StampedWrite) shouldBe prepareResp(false)
    // The stamp SQL failed on a good handle: the write proceeds on a fresh link, with its own
    // handle, and without the stamp's COMMIT.
    val log = fx.transport.prepared.toList
    log.map(_.isHandle) shouldBe List(true, false, true, false)
    log(1).sql should startWith(StampPrefix)
    log(3).sql should include(StampedWrite)
    fx.transport.typesSinceHandshake shouldBe List(
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.Disconnect,
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.Disconnect
    )

  "SEND_DATA_REQUEST" should "answer like a node when no statement is streaming" in:
    val fx     = setup()
    val connId = connect(fx)
    errorText(fx.door.handle(bare(Type.DataRequest, connId)).unsafeRunSync()) shouldBe
      "No active data stream"
    fx.transport.typesSinceHandshake shouldBe Nil

  "HEARTBEAT_REQUEST" should "renew the lease and answer SUCCESS" in:
    val fx     = setup()
    val connId = connect(fx)
    fx.door.handle(bare(Type.HeartbeatRequest, connId)).unsafeRunSync() shouldBe success

  it should "renew the node lease of an open transaction whose statement has drained" in:
    // The node checks its lease when the next message arrives: a client pausing inside a
    // transaction longer than the lease must keep the transaction's node connection alive.
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)
    fx.door.sweep(Instant.now().plusSeconds(10)).unsafeRunSync()
    fx.sessions.get(connId).get.current.get() shouldBe None
    fx.door.handle(bare(Type.HeartbeatRequest, connId)).unsafeRunSync() shouldBe success
    val beat = fx.transport.posted.last
    beat.header.msgType shouldBe Type.HeartbeatRequest
    beat.header.connectionId shouldBe "NODE2"

  it should "renew a link once when the statement rides the transaction's connection" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.transport.prepareNeedsMore = true
    prepare(fx, connId, "SELECT * FROM big") shouldBe prepareResp(true)
    fx.door.handle(bare(Type.HeartbeatRequest, connId)).unsafeRunSync() shouldBe success
    fx.transport.typesSinceHandshake.count(_ == Type.HeartbeatRequest) shouldBe 1

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

  "BEGIN" should "hand its node connection to the transaction instead of disconnecting it" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.sessions.get(connId).get.txLink.get().isDefined shouldBe true
    fx.transport.typesSinceHandshake should not contain Type.Disconnect
    // The next statement rides the same node connection: no second CONNECTION_REQUEST.
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)
    fx.transport.typesSinceHandshake.count(_ == Type.ConnectionRequest) shouldBe 1
    fx.transport.typesSinceHandshake should not contain Type.Disconnect
    prepare(fx, connId, "COMMIT") shouldBe prepareResp(false)
    fx.transport.typesSinceHandshake.count(_ == Type.Disconnect) shouldBe 1
    fx.sessions.get(connId).get.txLink.get() shouldBe None

  "an admin statement" should "keep its rendering link open while the result needs FETCH" in:
    val fx = setup(superuser = true, withAdmin = true)
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "CREATE ROLE analysts") shouldBe prepareResp(true)
    fx.sessions.get(connId).get.current.get().isDefined shouldBe true
    fetch(fx, connId) shouldBe fetchResp(true)

  "DISCONNECT_MESSAGE" should "close the router session as well as the front door's" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "SELECT 1")
    fx.router.sessions.size shouldBe 1
    fx.door.handle(bare(Type.Disconnect, connId)).unsafeRunSync() shouldBe success
    fx.sessions.size shouldBe 0
    fx.router.sessions.size shouldBe 0

  "sweep" should "read the session table when it runs, not when it is built" in:
    val fx   = setup(ttlSec = 1)
    val tick = fx.door.sweep(Instant.now().plusSeconds(5))
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT 1")
    tick.unsafeRunSync()
    fx.transport.types.last shouldBe Type.Disconnect
    fx.sessions.get(connId) shouldBe None

  "closeAll" should "release sessions opened after it was built" in:
    val fx       = setup()
    val shutdown = fx.door.closeAll()
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT 1")
    shutdown.unsafeRunSync()
    fx.transport.types.last shouldBe Type.Disconnect
    fx.sessions.size shouldBe 0

  "an admin kill" should "disconnect a transaction's connection and fence the session until ROLLBACK" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.transport.prepareNeedsMore = true
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(true)
    val live = fx.router.registry.list()
    live should have size 1
    fx.router.registry.kill(live.head.id)
    // The pinned node has no interrupt: DISCONNECT is the only kill, and it takes the
    // transaction with it.
    val last = fx.transport.posted.last
    last.header.msgType shouldBe Type.Disconnect
    last.header.connectionId shouldBe "NODE2"
    fx.sessions.get(connId).get.txLink.get() shouldBe None
    // The client still believes it is inside a transaction: nothing may run, and above all
    // nothing may silently auto-commit, until it rolls back.
    fx.transport.prepareNeedsMore = false
    val posted = fx.transport.posted.size
    errorText(prepare(fx, connId, "SELECT 2")) should include("killed by an administrator")
    errorText(prepare(fx, connId, "INSERT INTO t VALUES (1)")) should include("ROLLBACK")
    errorText(prepare(fx, connId, "COMMIT")) should include("killed by an administrator")
    fx.transport.posted.size shouldBe posted

  it should "let the client ROLLBACK after a kill and carry on outside the transaction" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.transport.prepareNeedsMore = true
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(true)
    fx.router.registry.kill(fx.router.registry.list().head.id)
    fx.transport.prepareNeedsMore = false
    val before = fx.transport.posted.size
    // The ROLLBACK must answer with a genuine node response: a fresh connection is opened, put
    // into a transaction, rolled back, and released.
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
    val recovery = fx.transport.posted.toList.drop(before)
    recovery.map(_.header.msgType) shouldBe List(
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.PrepareRequest,
      Type.Disconnect
    )
    decodePrepareRequest(recovery(1)).toOption.get.sql shouldBe "BEGIN"
    decodePrepareRequest(recovery(2)).toOption.get.sql should include("ROLLBACK")
    fx.sessions.get(connId).get.txLink.get() shouldBe None
    prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)

  /** BEGIN, a streaming SELECT, then an admin kill of that SELECT: the fenced session. */
  private def killedInTx(fx: Fixture): String =
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.transport.prepareNeedsMore = true
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(true)
    fx.router.registry.kill(fx.router.registry.list().head.id)
    fx.transport.prepareNeedsMore = false
    connId

  "ROLLBACK after a kill" should "keep the fence up when the recovery itself fails" in:
    val fx     = setup()
    val connId = killedInTx(fx)
    fx.transport.prepareError = Some("node went away")
    errorText(prepare(fx, connId, "ROLLBACK")) should include("node went away")
    fx.transport.prepareError = None
    errorText(prepare(fx, connId, "INSERT INTO t VALUES (1)")) should include(
      "killed by an administrator"
    )
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
    prepare(fx, connId, "INSERT INTO t VALUES (1)") shouldBe prepareResp(false)

  it should "send a session handle before the reopened BEGIN and before itself" in:
    val fx     = setup()
    val connId = killedInTx(fx)
    val before = fx.transport.prepared.size
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
    val recovery = fx.transport.prepared.toList.drop(before)
    recovery.map(_.isHandle) shouldBe List(true, false, true, false)
    recovery(1).sql shouldBe "BEGIN"
    recovery(3).sql should include("ROLLBACK")
    recovery.filter(_.isHandle).map(h => nonceOf(h.sql)).distinct.size shouldBe 2

  "a kill inside a transaction" should "clear the router's transaction state with the fence" in:
    val fx     = setup()
    val connId = killedInTx(fx)
    errorText(prepare(fx, connId, "COMMIT")) should include("killed by an administrator")
    val routerSession = fx.router.sessions.get(connId).get
    routerSession.txOpen shouldBe false
    routerSession.pinnedNodeId shouldBe None
    fx.router.sessions.inTransactionCount shouldBe 0

  "a transaction whose node leaves the pool" should
    "be fenced until ROLLBACK, never run on another node" in:
      val fx     = setup()
      val connId = connect(fx)
      // The transaction opens on the pool's last node (the first is made busier): the one a scale
      // down to one node removes.
      val first = fx.nodes.head.nodeId
      val last  = fx.nodes.last.nodeId
      fx.tracker.onStart(first)
      prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
      txNode(fx, connId) shouldBe last
      fx.router.supervisor
        .scale(poolKey, 1, RoleDistribution(0, 0, 1), force = true)
        .unsafeRunSync()
      fx.router.supervisor.get(poolKey).get.nodes.map(_.nodeId) shouldBe List(first)
      val posted   = fx.transport.posted.size
      val prepared = fx.transport.prepared.size
      errorText(prepare(fx, connId, "SELECT 1")) shouldBe FlightSqlRouter.PinLostAnswer
      // Nothing ran: the transaction's link was DISCONNECTed and no PREPARE went out.
      fx.transport.prepared.size shouldBe prepared
      postedSince(fx, posted) shouldBe List((Type.Disconnect, "NODE2"))
      fx.sessions.get(connId).get.txLink.get() shouldBe None
      fx.router.sessions.get(connId).get.txOpen shouldBe false
      // Fenced: the next statement gets the same answer instead of autocommitting on the survivor.
      errorText(prepare(fx, connId, "SELECT 2")) shouldBe FlightSqlRouter.PinLostAnswer
      fx.transport.prepared.size shouldBe prepared
      // ROLLBACK lifts the fence (on the surviving node), and the session runs on there.
      prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
      prepare(fx, connId, "SELECT 3") shouldBe prepareResp(false)
      lastPrepareNode(fx) shouldBe first

  "a computed location inside a transaction" should
    "be refused, never computed, when the transaction's link is on another node" in:
      // The router routes the evaluation to the transaction's pinned node, while the link the front
      // door holds says it is on another (as after the router dropped its pin). A statement of the
      // transaction is never computed outside it: refused before anything is sent (CONTRACT
      // Addendum 4 item 25a; Phase C review T19 N4).
      val fx     = setup()
      val connId = connect(fx)
      prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
      val node      = txNode(fx, connId)
      val session   = fx.sessions.get(connId).get
      val (_, link) = session.txLink.get().get
      session.txLink.set(Some(("quack-elsewhere", link)))
      val posted   = fx.transport.posted.size
      val prepared = fx.transport.prepared.size
      errorText(
        prepare(fx, connId, "SELECT * FROM read_csv('s3://acme-etl/in/' || 'x.csv')")
      ) shouldBe
        "computing the file location: permanent failure: the client transaction's node " +
        s"connection is on quack-elsewhere, not $node: a statement of the transaction is never " +
        "computed outside it"
      // Nothing reached a node: no evaluation, no statement, no fresh link.
      fx.transport.prepared.size shouldBe prepared
      fx.transport.posted.size shouldBe posted

  // The DuckDB 2.0 client's catalog sync (duckdb-quack QuackSchemaSet / QuackTableSet
  // GetLoadQuery, abridged): two statements the client joins on node-local oids.
  private val SchemaSync =
    "WITH RECURSIVE schema_tree AS (SELECT oid, database_name, [schema_name] AS schema_path " +
      "FROM duckdb_schemas() WHERE parent_schema_oid IS NULL) " +
      "SELECT oid, database_name, schema_path, current_database() FROM schema_tree"
  private val TableSync =
    "SELECT schema_oid, sql, 'table' FROM duckdb_tables() " +
      "UNION ALL SELECT schema_oid, view_name, 'view' FROM duckdb_views()"

  private def lastPrepareNode(fx: Fixture): String =
    val port = fx.transport.prepareUris.last.getPort
    fx.nodes.find(_.port == port).map(_.nodeId).getOrElse(fail(s"no node on port $port"))

  "a catalog sync" should "list schemas and tables on the same node even when load moves" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, SchemaSync) shouldBe prepareResp(false)
    val first = lastPrepareNode(fx)
    val other = fx.nodes.map(_.nodeId).find(_ != first).getOrElse(fail("need two nodes"))
    // Load moves off the node that listed the schemas, so a load-aware pick now prefers the
    // other node: an ordinary read goes there...
    fx.tracker.onStart(first)
    prepare(fx, connId, "SELECT 42") shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe other
    // ...but the table listing must not: its schema_oid values would match none of the oids the
    // client holds, and the attached catalog would show every schema and no table.
    prepare(fx, connId, TableSync) shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe first

  it should "move to another node when its node stops being routable, then stay there" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, SchemaSync) shouldBe prepareResp(false)
    val first = lastPrepareNode(fx)
    val other = fx.nodes.map(_.nodeId).find(_ != first).getOrElse(fail("need two nodes"))
    fx.tracker.setHealthy(first, false)
    prepare(fx, connId, SchemaSync) shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe other
    // The first node is back and now the less loaded one; the next listing still pairs with
    // the previous one.
    fx.tracker.setHealthy(first, true)
    fx.tracker.onStart(other)
    prepare(fx, connId, TableSync) shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe other

  it should "not tie one session's catalog node to another session" in:
    val fx = setup()
    val a  = connect(fx)
    prepare(fx, a, SchemaSync) shouldBe prepareResp(false)
    val first = lastPrepareNode(fx)
    val other = fx.nodes.map(_.nodeId).find(_ != first).getOrElse(fail("need two nodes"))
    fx.tracker.onStart(first)
    val b = connect(fx)
    prepare(fx, b, SchemaSync) shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe other

  it should "hold the pin over the node's maxConcurrent cap" in:
    val fx     = setup(maxConcurrent = 1)
    val connId = connect(fx)
    prepare(fx, connId, SchemaSync) shouldBe prepareResp(false)
    val first = lastPrepareNode(fx)
    val other = fx.nodes.map(_.nodeId).find(_ != first).getOrElse(fail("need two nodes"))
    fx.tracker.onStart(first) // at its cap of 1
    prepare(fx, connId, "SELECT 42") shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe other
    prepare(fx, connId, TableSync) shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe first

  it should "re-pin the session to the node a transient retry actually used" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, SchemaSync) shouldBe prepareResp(false)
    val first     = lastPrepareNode(fx)
    val other     = fx.nodes.map(_.nodeId).find(_ != first).getOrElse(fail("need two nodes"))
    val firstPort = fx.nodes.find(_.nodeId == first).get.port
    // The next sync starts on the pinned node, which refuses the connection: the router's
    // transient retry serves it on the other node, and the session's catalog node follows.
    fx.transport.unreachablePorts = Set(firstPort)
    prepare(fx, connId, SchemaSync) shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe other
    fx.sessions.get(connId).flatMap(_.catalogNode.get()) shouldBe Some(other)
    // The first node is back and the other one is now busier: the table listing still follows
    // the schema listing.
    fx.transport.unreachablePorts = Set.empty
    fx.tracker.setHealthy(first, true)
    fx.tracker.onStart(other)
    prepare(fx, connId, TableSync) shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe other

  "QuackFrontDoor.readsCatalog" should "spot a duckdb_* catalog function from lexer tokens" in:
    QuackFrontDoor.readsCatalog(SchemaSync) shouldBe true
    QuackFrontDoor.readsCatalog(TableSync) shouldBe true
    QuackFrontDoor.readsCatalog("select * from main.DUCKDB_TABLES ()") shouldBe true
    QuackFrontDoor.readsCatalog("SELECT * FROM \"duckdb_tables\"()") shouldBe true
    QuackFrontDoor.readsCatalog("SELECT * FROM system.main.\"DuckDB_Schemas\"()") shouldBe true
    QuackFrontDoor.readsCatalog("FROM duckdb_tables") shouldBe true
    QuackFrontDoor.readsCatalog("SELECT * FROM duckdb_views /* listing */ ()") shouldBe true

  it should "ignore literals, comments and names that only resemble one" in:
    QuackFrontDoor.readsCatalog("SELECT count(*) FROM tpch1.lineitem") shouldBe false
    QuackFrontDoor.readsCatalog("SELECT my_duckdb_tables() FROM t") shouldBe false
    QuackFrontDoor.readsCatalog("SELECT 'duckdb_tables()' AS s") shouldBe false
    QuackFrontDoor.readsCatalog("SELECT 1 -- FROM duckdb_tables()") shouldBe false
    QuackFrontDoor.readsCatalog("SELECT 1 /* duckdb_schemas() */") shouldBe false
    QuackFrontDoor.readsCatalog("SELECT duckdb_x.a FROM t") shouldBe false
    QuackFrontDoor.readsCatalog("") shouldBe false

  // -- drain: a non-force teardown waits for the front door's work on the node ------------------

  /** Polls `cond` for up to 3 s. */
  private def awaitTrue(what: String)(cond: => Boolean): Unit =
    val deadline = System.nanoTime() + 3.seconds.toNanos
    while !cond && System.nanoTime() < deadline do Thread.sleep(5)
    withClue(what)(cond shouldBe true)

  /** Whether `fiber` is still running after `wait`. */
  private def stillRunning(fiber: cats.effect.FiberIO[Unit], wait: FiniteDuration): Boolean =
    IO.race(fiber.joinWithNever, IO.sleep(wait)).unsafeRunSync().isRight

  "a non-force teardown" should "wait for a front-door statement whose result the client still reads" in:
    val fx = setup()
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    prepare(fx, connId, "SELECT * FROM big") shouldBe prepareResp(true)
    val served = lastPrepareNode(fx)
    val stop   = fx.router.supervisor.stopPool(poolKey, force = false).start.unsafeRunSync()
    awaitTrue("the statement's node is draining")(fx.tracker.snapshot(served).draining)
    // The PREPARE has answered, but the node still holds the result the client fetches: the
    // drain must not stop it under the client.
    stillRunning(stop, 300.millis) shouldBe true
    fetch(fx, connId) shouldBe fetchResp(true)
    fetch(fx, connId) shouldBe fetchResp(false)
    stillRunning(stop, 200.millis) shouldBe true
    // The client is done and the sweeper releases the drained statement: the stop goes on.
    fx.door.sweep(Instant.now().plusSeconds(10)).unsafeRunSync()
    stop.joinWithNever.timeout(3.seconds).unsafeRunSync()
    fx.router.supervisor.get(poolKey).get.nodes shouldBe empty

  it should "refuse, retryably and before any byte reaches it, a statement routed to a node that started draining" in:
    // Armed with the pool's nodes: every node starts draining after the router took the snapshot
    // it routes from, which still shows them routable.
    val draining = new java.util.concurrent.atomic.AtomicReference(List.empty[String])
    val fx     = setup(beforePick = tracker => draining.get().foreach(tracker.setDraining(_, true)))
    val connId = connect(fx)
    val before = fx.transport.posted.size
    val loads  = fx.nodes.map(n => n.nodeId -> fx.tracker.snapshot(n.nodeId)).toMap
    draining.set(fx.nodes.map(_.nodeId))
    val answer = errorText(prepare(fx, connId, "SELECT 1"))
    answer should include("no fallback node")
    fx.transport.posted.size shouldBe before
    fx.nodes.foreach { n =>
      val after = fx.tracker.snapshot(n.nodeId)
      after.copy(draining = false) shouldBe loads(n.nodeId)
    }
    fx.router.history.snapshot(10).head.error.getOrElse("") should include("is draining")
    // Nothing of the session was spent: once the drain is called off, the same statement runs.
    draining.set(Nil)
    fx.nodes.foreach(n => fx.tracker.setDraining(n.nodeId, false))
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)

  it should "keep running an open transaction's statements on its draining node and stop it after COMMIT" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    val txNode =
      fx.sessions.get(connId).flatMap(_.txLink.get()).map(_._1).getOrElse(fail("no transaction"))
    val stop = fx.router.supervisor.stopPool(poolKey, force = false).start.unsafeRunSync()
    awaitTrue("the transaction's node is draining")(fx.tracker.snapshot(txNode).draining)
    // The transaction was admitted before the drain: its statements still run, on its link.
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)
    lastPrepareNode(fx) shouldBe txNode
    stillRunning(stop, 300.millis) shouldBe true
    prepare(fx, connId, "COMMIT") shouldBe prepareResp(false)
    stop.joinWithNever.timeout(3.seconds).unsafeRunSync()
    fx.router.supervisor.get(poolKey).get.nodes shouldBe empty

  it should "stop an open transaction's node at the drain timeout, its next statement told the node left" in:
    val fx     = setup(drainTimeout = 300.millis)
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    fx.router.supervisor.stopPool(poolKey, force = false).timeout(5.seconds).unsafeRunSync()
    fx.router.supervisor.get(poolKey).get.nodes shouldBe empty
    errorText(prepare(fx, connId, "SELECT 1")) shouldBe FlightSqlRouter.PinLostAnswer

  /** Front-door holds on every node of the pool. */
  private def held(fx: Fixture): Int = fx.nodes.map(n => fx.tracker.snapshot(n.nodeId).held).sum

  "a statement refused by a node that started draining" should "run once, on the surviving node" in:
    val draining = new java.util.concurrent.atomic.AtomicReference(List.empty[String])
    val fx     = setup(beforePick = tracker => draining.get().foreach(tracker.setDraining(_, true)))
    val connId = connect(fx)
    val List(first, survivor) = fx.nodes
    // The survivor carries load, so the least-loaded pick is the node about to drain.
    fx.tracker.onStart(survivor.nodeId)
    draining.set(List(first.nodeId))
    prepare(fx, connId, "SELECT 1") shouldBe prepareResp(false)
    fx.transport.prepared.toList.filterNot(_.isHandle).map(p => (p.sql, p.uri.getPort)) shouldBe
      List(("USE memory.main; SELECT 1", survivor.port))
    // One node connection, the survivor's: the draining node got no byte.
    fx.transport.typesSinceHandshake shouldBe List(
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.Disconnect
    )
    held(fx) shouldBe 0

  "a statement cancelled before its node answers" should "give its node's hold back" in:
    val fx     = setup()
    val connId = connect(fx)
    fx.transport.hangWhen = p => p.sql.contains("SELECT 42")
    val f = fx.door
      .handle(encodePrepareRequest(connId, 3L, PrepareRequest("SELECT 42", None, None)))
      .start
      .unsafeRunSync()
    fx.transport.hung.await(3, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    held(fx) shouldBe 1
    f.cancel.timeout(3.seconds).unsafeRunSync()
    held(fx) shouldBe 0

  "a stamped write whose fallback link its draining node refuses" should "go once to the other node" in:
    val fx     = setup(stamping = true)
    val connId = connect(fx)
    fx.transport.stampError = Some("Catalog Error: function ducklake_set_commit_message failed")
    // The first stamp's node starts draining once that stamp failed: the fresh link the write
    // falls back to must be admitted again, and is refused there.
    val stampedOn = new java.util.concurrent.atomic.AtomicReference(Option.empty[Int])
    fx.transport.onPrepare = p =>
      if p.sql.startsWith(StampPrefix) && stampedOn.compareAndSet(None, Some(p.uri.getPort)) then
        fx.nodes.find(_.port == p.uri.getPort).foreach(n => fx.tracker.setDraining(n.nodeId, true))
    prepare(fx, connId, StampedWrite) shouldBe prepareResp(false)
    val writes = fx.transport.prepared.toList
      .filterNot(p => p.isHandle || p.sql.startsWith(StampPrefix))
      .filter(_.sql.contains(StampedWrite))
    writes should have size 1
    Some(writes.head.uri.getPort) should not be stampedOn.get()
    held(fx) shouldBe 0

  "a stamped write's fallback link" should "be closed, its hold given back, when the write is cancelled" in:
    val fx     = setup(stamping = true)
    val connId = connect(fx)
    fx.transport.stampError = Some("Catalog Error: function ducklake_set_commit_message failed")
    fx.transport.hangWhen = p =>
      !p.sql.startsWith(StampPrefix) && !p.sql.startsWith(HandlePrefix) &&
        p.sql.contains(StampedWrite)
    val f = fx.door
      .handle(encodePrepareRequest(connId, 3L, PrepareRequest(StampedWrite, None, None)))
      .start
      .unsafeRunSync()
    fx.transport.hung.await(3, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    held(fx) shouldBe 1
    f.cancel.timeout(3.seconds).unsafeRunSync()
    held(fx) shouldBe 0
    fx.transport.types.last shouldBe Type.Disconnect

  "a statement cancelled between its node's answer and its hand-off to the session" should "release its link" in:
    val armed   = new java.util.concurrent.atomic.AtomicBoolean(false)
    val entered = new java.util.concurrent.CountDownLatch(1)
    val gate    = new java.util.concurrent.CountDownLatch(1)
    val fx      = setup(onEvent = {
      case _: ManagerEvent.StatementExecuted if armed.get() =>
        entered.countDown()
        gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
        ()
      case _ => ()
    })
    fx.transport.prepareNeedsMore = true
    val connId = connect(fx)
    armed.set(true)
    val f = fx.door
      .handle(encodePrepareRequest(connId, 3L, PrepareRequest("SELECT 1", None, None)))
      .start
      .unsafeRunSync()
    // The node answered (its result needs FETCH, so the link must outlive the PREPARE) and the
    // router is reporting the statement: the session does not hold it yet.
    entered.await(3, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    held(fx) shouldBe 1
    val cancel = f.cancel.start.unsafeRunSync()
    armed.set(false)
    gate.countDown()
    cancel.joinWithNever.timeout(3.seconds).unsafeRunSync()
    held(fx) shouldBe 0
    fx.sessions.get(connId).get.current.get() shouldBe None
    fx.transport.types.last shouldBe Type.Disconnect

  "the evaluation of a value on a fresh link" should "be refused Transient by a draining node, sending nothing" in:
    val fx     = setup()
    val connId = connect(fx)
    val node   = fx.nodes.head
    fx.tracker.setDraining(node.nodeId, true)
    val before = fx.transport.posted.size
    val send   = fx.door.evaluateSend(fx.sessions.get(connId).get, 3L)
    send(
      node,
      "SELECT 1",
      None,
      true,
      ai.starlake.quack.edge.authz.NodePrincipal.System
    ).unsafeRunSync() match
      case NodeOutcome.Transient(m, _) => m should include("is draining")
      case other                       => fail(s"expected Transient, got $other")
    fx.transport.posted.size shouldBe before
    held(fx) shouldBe 0

  "an admin result's rendering" should "be refused, retryably, by a node that started draining" in:
    val fx     = setup(superuser = true, withAdmin = true)
    val connId = connect(fx)
    val node   = fx.nodes.head
    fx.tracker.setDraining(node.nodeId, true)
    val before = fx.transport.posted.size
    val resp   = fx.door
      .renderOn(
        fx.sessions.get(connId).get,
        node,
        PrepareRequest("CREATE ROLE analysts", None, None),
        3L,
        "SELECT 'ok' AS status"
      )
      .unsafeRunSync()
    errorText(resp) should include("is draining; retry")
    fx.transport.posted.size shouldBe before
    held(fx) shouldBe 0

  "a suspend" should "not wait for an open front-door transaction, whose next statement is told it was aborted" in:
    val fx     = setup()
    val connId = connect(fx)
    prepare(fx, connId, "BEGIN") shouldBe prepareResp(false)
    // The drain timeout is 10 s: the suspend must not wait for the transaction, whose COMMIT
    // would itself wait for the suspend to resume the pool.
    fx.router.supervisor
      .suspendPool(poolKey, "test")
      .timeout(3.seconds)
      .unsafeRunSync()
      .isRight shouldBe true
    // The COMMIT wakes the pool. A resume respawns the nodes under their node ids, so the pin
    // finds its node again, but the new process refuses the stopped one's connection: the client
    // is told its transaction was aborted, and its ROLLBACK runs on a fresh link.
    fx.transport.deadConnections = fx.transport.connectionIds
    val answer = errorText(prepare(fx, connId, "COMMIT"))
    answer should startWith("transaction aborted")
    answer should endWith("ROLLBACK to continue")
    prepare(fx, connId, "ROLLBACK") shouldBe prepareResp(false)
