package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.adapter.{NodeHold, QuackTestFixtures, QuackTransport, QuackWireError}
import ai.starlake.quack.edge.authz.{NodeKeys, NodePrincipal}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class QuackNodeLinkSpec extends AnyFlatSpec with Matchers:

  import QuackWire.*

  /** Records every POST and answers from a queue; a queued Throwable is raised instead. */
  private final class FakeTransport(script: Any*) extends QuackTransport:
    private val queue                                      = script.iterator
    val posted: ArrayBuffer[(URI, Array[Byte])]            = ArrayBuffer.empty
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO {
      posted += ((uri, body.clone()))
      queue.next() match
        case t: Throwable   => throw t
        case b: Array[Byte] => b
        case other          => throw new IllegalStateException(s"bad script entry $other")
    }

  private val node = RunningNode(
    "n1",
    PoolKey("acme", "acme_db", "bi"),
    Role.Dual,
    "127.0.0.1",
    21999,
    "nodetok1",
    None,
    None,
    Instant.EPOCH
  )
  private val hello =
    ConnectionRequest("client-secret", "v2.0.0", "osx_arm64", 3L, 3L, "qod-test", 0L)
  private val handleSql               = "CALL system.main.qod_session('test.handle')"
  private val authorize: () => String = () => handleSql

  /** The SQL of every PREPARE posted so far, in order: the handles and the statements. */
  private def preparedSql(t: FakeTransport): List[String] =
    t.posted.toList
      .map(p => frame(p._2).toOption.get)
      .filter(_.header.msgType == Type.PrepareRequest)
      .flatMap(f => decodePrepareRequest(f).toOption.map(_.sql))

  private def connResp(id: String)       = QuackTestFixtures.serializeSampleConnectionResponse(id)
  private def errorResp(msg: String)     = QuackTestFixtures.serializeSampleErrorResponse(msg)
  private def prepareResp(more: Boolean) =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.valueOf(77L), more, true)
  private def fetchResp(withChunk: Boolean) =
    QuackTestFixtures.serializeSampleFetchResponse(withChunk)

  "open" should "post the client's hello with the node token and adopt the node connection id" in:
    val t   = new FakeTransport(connResp("NODE1"))
    val out = QuackNodeLink
      .open(t, node, hello, 5L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
    val (link, resp) = out.toOption.get
    link.nodeConnectionId shouldBe "NODE1"
    link.url shouldBe URI.create("http://127.0.0.1:21999/quack")
    resp.quackVersion should be > 0L
    val sent = frame(t.posted.head._2).toOption.get
    sent.header shouldBe Header(Type.ConnectionRequest, "", 5L)
    decodeConnectionRequest(sent).toOption.get shouldBe hello.copy(authString = "nodetok1")

  it should "surface a node ERROR_RESPONSE as Permanent" in:
    val out = QuackNodeLink
      .open(
        new FakeTransport(errorResp("Authentication failed")),
        node,
        hello,
        1L,
        authorize,
        NodeHold.none,
        QuackNodeLink.AlwaysServes
      )
      .unsafeRunSync()
    out match
      case Left(LinkFailure.Permanent(m)) => m should include("Authentication failed")
      case other                          => fail(s"expected Permanent, got $other")

  it should "surface a transport failure as Transient" in:
    val out = QuackNodeLink
      .open(
        new FakeTransport(QuackWireError.Transient("HTTP 503")),
        node,
        hello,
        1L,
        authorize,
        NodeHold.none,
        QuackNodeLink.AlwaysServes
      )
      .unsafeRunSync()
    out shouldBe Left(LinkFailure.Transient("HTTP 503"))

  it should "keep its node held until the link closes, and release it when no link comes of it" in:
    val tracker = new ai.starlake.quack.edge.adapter.NodeLoadTracker
    def hold    = tracker.tryHold(node.nodeId).getOrElse(fail("refused"))
    val t       = new FakeTransport(connResp("NODE1"), encodeSuccess())
    val link    =
      QuackNodeLink
        .open(t, node, hello, 1L, authorize, hold, QuackNodeLink.AlwaysServes)
        .unsafeRunSync()
        .toOption
        .get
        ._1
    tracker.snapshot(node.nodeId).held shouldBe 1
    link.close().unsafeRunSync()
    tracker.snapshot(node.nodeId).held shouldBe 0
    // A DISCONNECT that fails still releases the hold, and a second close releases nothing more.
    val t2    = new FakeTransport(connResp("NODE2"), QuackWireError.Transient("HTTP 503"))
    val link2 =
      QuackNodeLink
        .open(t2, node, hello, 1L, authorize, hold, QuackNodeLink.AlwaysServes)
        .unsafeRunSync()
        .toOption
        .get
        ._1
    val other = hold
    tracker.snapshot(node.nodeId).held shouldBe 2
    link2.close().unsafeRunSync()
    link2.close().attempt.unsafeRunSync()
    tracker.snapshot(node.nodeId).held shouldBe 1
    other.release()
    // Refused or failed in transport, the open leaves no link: its hold is released at once.
    QuackNodeLink
      .open(
        new FakeTransport(errorResp("Authentication failed")),
        node,
        hello,
        1L,
        authorize,
        hold,
        QuackNodeLink.AlwaysServes
      )
      .unsafeRunSync()
      .isLeft shouldBe true
    QuackNodeLink
      .open(
        new FakeTransport(QuackWireError.Transient("HTTP 503")),
        node,
        hello,
        1L,
        authorize,
        hold,
        QuackNodeLink.AlwaysServes
      )
      .unsafeRunSync()
      .isLeft shouldBe true
    tracker.snapshot(node.nodeId).held shouldBe 0

  it should "send nothing once its node's process was replaced, and still give its hold back" in:
    val tracker = new ai.starlake.quack.edge.adapter.NodeLoadTracker
    val serves  = new java.util.concurrent.atomic.AtomicBoolean(true)
    val t       = new FakeTransport(connResp("NODE1"))
    val link    = QuackNodeLink
      .open(
        t,
        node,
        hello,
        1L,
        authorize,
        tracker.tryHold(node.nodeId).getOrElse(fail("refused")),
        () => serves.get()
      )
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.node shouldBe node
    serves.set(false)
    val fetch = frame(encodeFetchRequest("CLIENT", 3L, Hugeint(1L, 2L), 4L)).toOption.get
    link.forward(fetch).unsafeRunSync() shouldBe
      Left(LinkFailure.Permanent(QuackNodeLink.ProcessReplaced))
    link.prepare(PrepareRequest("SELECT 1", None, None), 3L).unsafeRunSync().isLeft shouldBe true
    link.close().unsafeRunSync()
    // No statement and no handle went out: only the CONNECTION_REQUEST and a best-effort DISCONNECT,
    // whose random id means nothing to another process.
    t.posted.map(p => frame(p._2).toOption.get.header.msgType).toList shouldBe
      List(Type.ConnectionRequest, Type.Disconnect)
    tracker.snapshot(node.nodeId).held shouldBe 0

  it should "give its hold back at once on close, whatever its DISCONNECT does" in:
    val tracker = new ai.starlake.quack.edge.adapter.NodeLoadTracker
    val hangs   = new QuackTransport:
      private var first                                      = true
      def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] =
        if first then IO { first = false; connResp("NODE1") } else IO.never
    val link = QuackNodeLink
      .open(
        hangs,
        node,
        hello,
        1L,
        authorize,
        tracker.tryHold(node.nodeId).getOrElse(fail("refused")),
        QuackNodeLink.AlwaysServes
      )
      .unsafeRunSync()
      .toOption
      .get
      ._1
    val closing  = link.close().start.unsafeRunSync()
    val deadline = System.nanoTime() + 3_000_000_000L
    while tracker.snapshot(node.nodeId).held != 0 && System.nanoTime() < deadline do Thread.sleep(5)
    tracker.snapshot(node.nodeId).held shouldBe 0
    closing.cancel.unsafeRunSync()
    // A DISCONNECT that fails is swallowed.
    val fails = new FakeTransport(connResp("NODE2"), QuackWireError.Transient("HTTP 503"))
    val l2    = QuackNodeLink
      .open(fails, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    l2.close().attempt.unsafeRunSync() shouldBe Right(())

  "forward" should "rewrite the header to the node connection id and return the node's bytes" in:
    val t    = new FakeTransport(connResp("NODE1"), fetchResp(true))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    val clientFetch = frame(encodeFetchRequest("CLIENT", 3L, Hugeint(1L, 2L), 4L)).toOption.get
    val out         = link.forward(clientFetch).unsafeRunSync()
    out.toOption.get shouldBe fetchResp(true)
    val relayed = frame(t.posted(1)._2).toOption.get
    relayed.header shouldBe Header(Type.FetchRequest, "NODE1", 3L)
    decodeFetchRequest(relayed) shouldBe Right(FetchRequest(Hugeint(1L, 2L), 4L, 3L))

  "forward" should "never relay a PREPARE" in:
    val t    = new FakeTransport(connResp("NODE1"))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    val prep = frame(
      encodePrepareRequest("CLIENT", 3L, PrepareRequest("SELECT 1", None, None))
    ).toOption.get
    link.forward(prep).unsafeRunSync().isLeft shouldBe true
    t.posted should have size 1

  "prepare" should "relay the request after a session handle on the same connection" in:
    val t    = new FakeTransport(connResp("NODE1"), prepareResp(false), prepareResp(false))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    val out =
      link.prepare(PrepareRequest("SELECT 1", Some(Hugeint(0L, 9L)), None), 4L).unsafeRunSync()
    out.isRight shouldBe true
    val handle = frame(t.posted(1)._2).toOption.get
    decodePrepareRequest(handle).toOption.get.sql shouldBe
      "CALL system.main.qod_session('test.handle')"
    val relayed = frame(t.posted(2)._2).toOption.get
    // Both PREPAREs go out on the node's connection, not the client's.
    handle.header.connectionId shouldBe "NODE1"
    relayed.header.connectionId shouldBe "NODE1"
    val request = decodePrepareRequest(relayed).toOption.get
    request.sql shouldBe "SELECT 1"
    request.queryUuid shouldBe Some(Hugeint(0L, 9L))

  it should "send nothing and fail HandleFailed when the node refuses the handle" in:
    val t    = new FakeTransport(connResp("NODE1"), errorResp("session handle refused: expired"))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.prepare(PrepareRequest("SELECT 1", None, None), 4L).unsafeRunSync() match
      case Left(h: LinkFailure.HandleFailed) =>
        // The node's own text, kept as is (it holds no statement excerpt): the front door may
        // answer a client with it verbatim.
        h.refused shouldBe true
        h.detail shouldBe "session handle refused: expired"
        h.message should include("refused the statement's session handle")
        h.message should include("session handle refused: expired")
      case other => fail(s"expected HandleFailed, got $other")
    // The connection request and the refused handle; the statement itself never went out.
    preparedSql(t) shouldBe List(handleSql)
    t.posted should have size 2

  it should "send nothing and fail HandleFailed when the handle never reaches the node" in:
    val t    = new FakeTransport(connResp("NODE1"), QuackWireError.Transient("HTTP 503"))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.prepare(PrepareRequest("SELECT 1", None, None), 4L).unsafeRunSync() match
      case Left(h: LinkFailure.HandleFailed) =>
        h.refused shouldBe false
        h.detail shouldBe "HTTP 503"
        h.message should include("did not reach the node")
        h.message should include("HTTP 503")
        // A 503 is not a refusal, and the message must not claim one.
        h.message should not include "refused"
      case other => fail(s"expected HandleFailed, got $other")
    preparedSql(t) shouldBe List(handleSql)
    t.posted should have size 2

  it should "drop DuckDB's excerpt of the statement from the node's refusal" in:
    // An excerpt would quote the prelude, handle and all, to whoever reads the answer.
    val refusal = "Permission Error: qod-node: session handle refused: handle MAC does not verify"
    val excerpt = "\n\nLINE 1: CALL system.main.qod_session('eyJ2IjoxfQ.0123abcd')\n  ^"
    val t       = new FakeTransport(connResp("NODE1"), errorResp(refusal + excerpt))
    val link    = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.prepare(PrepareRequest("SELECT 1", None, None), 4L).unsafeRunSync() match
      case Left(h: LinkFailure.HandleFailed) =>
        h.detail shouldBe refusal
        h.message should not include "eyJ2IjoxfQ"
        h.message should not include "LINE 1"
      case other => fail(s"expected HandleFailed, got $other")

  it should "send nothing and fail Permanent when there is no principal to mint a handle for" in:
    val t       = new FakeTransport(connResp("NODE1"))
    val unbound = () => NodeKeys.ephemeral().prelude(node, NodePrincipal.Unbound)
    val link    =
      QuackNodeLink
        .open(t, node, hello, 1L, unbound, NodeHold.none, QuackNodeLink.AlwaysServes)
        .unsafeRunSync()
        .toOption
        .get
        ._1
    link.prepare(PrepareRequest("SELECT 1", None, None), 4L).unsafeRunSync() match
      case Left(LinkFailure.Permanent(m)) => m should include("no principal is bound")
      case other                          => fail(s"expected Permanent, got $other")
    // Only the connection request: minting failed before anything was sent.
    t.posted should have size 1

  it should "send a fresh session handle before each PREPARE of a link" in:
    val minted = new AtomicInteger(0)
    val fresh  = () => s"CALL system.main.qod_session('h${minted.incrementAndGet()}')"
    val t      = new FakeTransport((connResp("NODE1") +: List.fill(6)(prepareResp(false)))*)
    val link   =
      QuackNodeLink
        .open(t, node, hello, 1L, fresh, NodeHold.none, QuackNodeLink.AlwaysServes)
        .unsafeRunSync()
        .toOption
        .get
        ._1
    link.runDiscard("BEGIN", 1L).unsafeRunSync() shouldBe Right(())
    link.prepare(PrepareRequest("SELECT 1", None, None), 2L).unsafeRunSync().isRight shouldBe true
    link.runDiscard("COMMIT", 3L).unsafeRunSync() shouldBe Right(())
    // BEGIN, the statement in between and COMMIT each follow a handle of their own.
    preparedSql(t) shouldBe List(
      "CALL system.main.qod_session('h1')",
      "BEGIN",
      "CALL system.main.qod_session('h2')",
      "SELECT 1",
      "CALL system.main.qod_session('h3')",
      "COMMIT"
    )
    minted.get shouldBe 3

  "runDiscard" should "prepare, drain every fetch and stop at the terminal response" in:
    val t = new FakeTransport(
      connResp("NODE1"),
      prepareResp(false),
      prepareResp(true),
      fetchResp(true),
      fetchResp(false)
    )
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.runDiscard("COMMIT", 9L).unsafeRunSync() shouldBe Right(())
    t.posted.map(p => frame(p._2).toOption.get.header.msgType).toList shouldBe List(
      Type.ConnectionRequest,
      Type.PrepareRequest,
      Type.PrepareRequest,
      Type.FetchRequest,
      Type.FetchRequest
    )
    decodePrepareRequest(frame(t.posted(1)._2).toOption.get).toOption.get.sql shouldBe
      "CALL system.main.qod_session('test.handle')"
    val prep = frame(t.posted(2)._2).toOption.get
    prep.header shouldBe Header(Type.PrepareRequest, "NODE1", 9L)
    decodePrepareRequest(prep).toOption.get.sql shouldBe "COMMIT"
    // Quack v3 names every query: a node refuses a PREPARE without a query uuid.
    val uuid = decodePrepareRequest(prep).toOption.get.queryUuid
    uuid.isDefined shouldBe true
    uuid should not be Some(Hugeint(0L, 0L))
    // Each FETCH names its 1-based batch and acks the ones before it.
    decodeFetchRequest(frame(t.posted(3)._2).toOption.get) shouldBe Right(
      FetchRequest(Hugeint(0L, 77L), 1L, 0L)
    )
    decodeFetchRequest(frame(t.posted(4)._2).toOption.get) shouldBe Right(
      FetchRequest(Hugeint(0L, 77L), 2L, 1L)
    )

  it should "name each of its queries with a fresh uuid" in:
    val t = new FakeTransport(
      connResp("NODE1"),
      prepareResp(false),
      prepareResp(false),
      prepareResp(false),
      prepareResp(false)
    )
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.runDiscard("BEGIN", 1L).unsafeRunSync() shouldBe Right(())
    link.runDiscard("COMMIT", 2L).unsafeRunSync() shouldBe Right(())
    val uuids = List(2, 4).map(i => decodePrepareRequest(frame(t.posted(i)._2).toOption.get))
    uuids(0).toOption.get.queryUuid should not be uuids(1).toOption.get.queryUuid

  it should "not fetch when the prepare response is complete" in:
    val t    = new FakeTransport(connResp("NODE1"), prepareResp(false), prepareResp(false))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.runDiscard("BEGIN", 1L).unsafeRunSync() shouldBe Right(())
    t.posted should have size 3

  it should "surface a node error as Permanent" in:
    val t    = new FakeTransport(connResp("NODE1"), prepareResp(false), errorResp("conflict"))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.runDiscard("COMMIT", 1L).unsafeRunSync() shouldBe Left(LinkFailure.Permanent("conflict"))

  it should "send nothing and fail HandleFailed when the node refuses the handle" in:
    val t    = new FakeTransport(connResp("NODE1"), errorResp("session handle refused: expired"))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.runDiscard("COMMIT", 9L).unsafeRunSync() match
      case Left(h: LinkFailure.HandleFailed) =>
        h.refused shouldBe true
        h.detail shouldBe "session handle refused: expired"
        h.message should include("refused the statement's session handle")
        h.message should include("session handle refused: expired")
      case other => fail(s"expected HandleFailed, got $other")
    // Only the refused handle was prepared; the COMMIT never went out.
    preparedSql(t) shouldBe List(handleSql)
    t.posted should have size 2

  it should "send nothing and fail HandleFailed when the handle never reaches the node" in:
    val t    = new FakeTransport(connResp("NODE1"), QuackWireError.Transient("HTTP 503"))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.runDiscard("COMMIT", 9L).unsafeRunSync() match
      case Left(h: LinkFailure.HandleFailed) =>
        h.refused shouldBe false
        h.detail shouldBe "HTTP 503"
        h.message should include("did not reach the node")
        h.message should include("HTTP 503")
        h.message should not include "refused"
      case other => fail(s"expected HandleFailed, got $other")
    preparedSql(t) shouldBe List(handleSql)
    t.posted should have size 2

  "close" should "post a DISCONNECT and swallow failures" in:
    val t    = new FakeTransport(connResp("NODE1"), QuackWireError.Transient("gone"))
    val link = QuackNodeLink
      .open(t, node, hello, 1L, authorize, NodeHold.none, QuackNodeLink.AlwaysServes)
      .unsafeRunSync()
      .toOption
      .get
      ._1
    link.close().unsafeRunSync()
    frame(t.posted(1)._2).toOption.get.header shouldBe Header(
      Type.Disconnect,
      "NODE1",
      InvalidIndex
    )
