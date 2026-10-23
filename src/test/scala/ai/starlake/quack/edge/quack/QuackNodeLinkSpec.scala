package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.adapter.{QuackTestFixtures, QuackTransport, QuackWireError}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.time.Instant
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
  private val authorize: () => String = () => "CALL system.main.qod_session('test.handle')"

  private def connResp(id: String)       = QuackTestFixtures.serializeSampleConnectionResponse(id)
  private def errorResp(msg: String)     = QuackTestFixtures.serializeSampleErrorResponse(msg)
  private def prepareResp(more: Boolean) =
    QuackTestFixtures.serializeSamplePrepareResponse(java.math.BigInteger.valueOf(77L), more, true)
  private def fetchResp(withChunk: Boolean) =
    QuackTestFixtures.serializeSampleFetchResponse(withChunk)

  "open" should "post the client's hello with the node token and adopt the node connection id" in:
    val t            = new FakeTransport(connResp("NODE1"))
    val out          = QuackNodeLink.open(t, node, hello, 5L, authorize).unsafeRunSync()
    val (link, resp) = out.toOption.get
    link.nodeConnectionId shouldBe "NODE1"
    link.url shouldBe URI.create("http://127.0.0.1:21999/quack")
    resp.quackVersion should be > 0L
    val sent = frame(t.posted.head._2).toOption.get
    sent.header shouldBe Header(Type.ConnectionRequest, "", 5L)
    decodeConnectionRequest(sent).toOption.get shouldBe hello.copy(authString = "nodetok1")

  it should "surface a node ERROR_RESPONSE as Permanent" in:
    val out = QuackNodeLink
      .open(new FakeTransport(errorResp("Authentication failed")), node, hello, 1L, authorize)
      .unsafeRunSync()
    out match
      case Left(LinkFailure.Permanent(m)) => m should include("Authentication failed")
      case other                          => fail(s"expected Permanent, got $other")

  it should "surface a transport failure as Transient" in:
    val out = QuackNodeLink
      .open(new FakeTransport(QuackWireError.Transient("HTTP 503")), node, hello, 1L, authorize)
      .unsafeRunSync()
    out shouldBe Left(LinkFailure.Transient("HTTP 503"))

  "forward" should "rewrite the header to the node connection id and return the node's bytes" in:
    val t    = new FakeTransport(connResp("NODE1"), fetchResp(true))
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    val clientFetch = frame(encodeFetchRequest("CLIENT", 3L, Hugeint(1L, 2L), 4L)).toOption.get
    val out         = link.forward(clientFetch).unsafeRunSync()
    out.toOption.get shouldBe fetchResp(true)
    val relayed = frame(t.posted(1)._2).toOption.get
    relayed.header shouldBe Header(Type.FetchRequest, "NODE1", 3L)
    decodeFetchRequest(relayed) shouldBe Right(FetchRequest(Hugeint(1L, 2L), 4L, 3L))

  "forward" should "never relay a PREPARE" in:
    val t    = new FakeTransport(connResp("NODE1"))
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    val prep = frame(
      encodePrepareRequest("CLIENT", 3L, PrepareRequest("SELECT 1", None, None))
    ).toOption.get
    link.forward(prep).unsafeRunSync().isLeft shouldBe true
    t.posted should have size 1

  "prepare" should "relay the request after a session handle on the same connection" in:
    val t    = new FakeTransport(connResp("NODE1"), prepareResp(false), prepareResp(false))
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    val out  =
      link.prepare(PrepareRequest("SELECT 1", Some(Hugeint(0L, 9L)), None), 4L).unsafeRunSync()
    out.isRight shouldBe true
    decodePrepareRequest(frame(t.posted(1)._2).toOption.get).toOption.get.sql shouldBe
      "CALL system.main.qod_session('test.handle')"
    val relayed = decodePrepareRequest(frame(t.posted(2)._2).toOption.get).toOption.get
    relayed.sql shouldBe "SELECT 1"
    relayed.queryUuid shouldBe Some(Hugeint(0L, 9L))

  it should "send nothing and fail Transient when the node refuses the handle" in:
    val t    = new FakeTransport(connResp("NODE1"), errorResp("session handle refused: expired"))
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    link.prepare(PrepareRequest("SELECT 1", None, None), 4L).unsafeRunSync() match
      case Left(LinkFailure.Transient(m)) =>
        m should include("refused the statement's session handle")
      case other => fail(s"expected Transient, got $other")
    t.posted should have size 2

  "runDiscard" should "prepare, drain every fetch and stop at the terminal response" in:
    val t = new FakeTransport(
      connResp("NODE1"),
      prepareResp(false),
      prepareResp(true),
      fetchResp(true),
      fetchResp(false)
    )
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
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
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    link.runDiscard("BEGIN", 1L).unsafeRunSync() shouldBe Right(())
    link.runDiscard("COMMIT", 2L).unsafeRunSync() shouldBe Right(())
    val uuids = List(2, 4).map(i => decodePrepareRequest(frame(t.posted(i)._2).toOption.get))
    uuids(0).toOption.get.queryUuid should not be uuids(1).toOption.get.queryUuid

  it should "not fetch when the prepare response is complete" in:
    val t    = new FakeTransport(connResp("NODE1"), prepareResp(false), prepareResp(false))
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    link.runDiscard("BEGIN", 1L).unsafeRunSync() shouldBe Right(())
    t.posted should have size 3

  it should "surface a node error as Permanent" in:
    val t    = new FakeTransport(connResp("NODE1"), prepareResp(false), errorResp("conflict"))
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    link.runDiscard("COMMIT", 1L).unsafeRunSync() shouldBe Left(LinkFailure.Permanent("conflict"))

  "close" should "post a DISCONNECT and swallow failures" in:
    val t    = new FakeTransport(connResp("NODE1"), QuackWireError.Transient("gone"))
    val link = QuackNodeLink.open(t, node, hello, 1L, authorize).unsafeRunSync().toOption.get._1
    link.close().unsafeRunSync()
    frame(t.posted(1)._2).toOption.get.header shouldBe Header(
      Type.Disconnect,
      "NODE1",
      InvalidIndex
    )
