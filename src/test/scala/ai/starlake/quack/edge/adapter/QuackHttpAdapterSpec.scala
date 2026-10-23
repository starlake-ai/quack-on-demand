package ai.starlake.quack.edge.adapter

import ai.starlake.quack.edge.authz.NodePrincipal
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class QuackHttpAdapterSpec extends AnyFlatSpec with Matchers:

  private val key: PoolKey                  = PoolKey("acme", "acme_default", "sales")
  private def node(id: String): RunningNode =
    RunningNode(id, key, Role.Dual, "127.0.0.1", 0, "tok-" + id, Some(1L), None, Instant.EPOCH)

  /** Stub returning canned responses keyed by endpoint URI. Each entry is a thunk because
    * ArrowReader instances are single-use.
    */
  final class FakeClient(
      canned: Map[String, () => QuackResponse],
      canary: () => QuackResponse = () =>
        QuackResponse.Failed(
          QuackError.Permanent("qod-node: this connection has no verified principal"),
          1
        )
  ) extends QuackHttpClient(TestArrow.sharedAllocator):
    val preludes = scala.collection.mutable.ListBuffer.empty[String]
    override def query(
        endpoint: String,
        token: String,
        sql: String,
        session: Option[String],
        authorize: () => String
    ): IO[QuackResponse] =
      IO {
        preludes += authorize()
        canned
          .getOrElse(endpoint, () => QuackResponse.Failed(QuackError.Permanent("no stub")))
          .apply()
      }
    override def queryWithoutHandle(endpoint: String, token: String, sql: String) =
      IO(canary())

  "QuackHttpAdapter" should "delegate to client, track load on success" in:
    val client  = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(42L))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)

    val out =
      adapter.send(node("n1"), "SELECT 1", NodePrincipal.System, session = None).unsafeRunSync()
    out should matchPattern { case QuackResponse.Ok(_, _, _) => }
    out match
      case QuackResponse.Ok(_, _, close) => close()
      case _                             => ()
    tracker.snapshot("n1").inFlight shouldBe 0
    tracker.snapshot("n1").ewmaMs should be > 0.0

  it should "mark node unhealthy on transient failure" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Transient("connection refused"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.send(node("n2"), "SELECT 1", NodePrincipal.System, None).unsafeRunSync()
    tracker.snapshot("n2").healthy shouldBe false

  it should "NOT mark unhealthy on permanent failure" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Permanent("400"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.send(node("n3"), "BAD", NodePrincipal.System, None).unsafeRunSync()
    tracker.snapshot("n3").healthy shouldBe true

  "probe" should "return true on Ok without touching tracker counters" in:
    val client  = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(42L))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.probe(node("p1")).unsafeRunSync() shouldBe true
    val snap = tracker.snapshot("p1")
    snap.inFlight shouldBe 0
    snap.totalServed shouldBe 0L
    snap.ewmaMs shouldBe 0.0

  it should "return false on transient failure without touching tracker counters" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Transient("connection refused"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.probe(node("p2")).unsafeRunSync() shouldBe false
    tracker.snapshot("p2").totalServed shouldBe 0L

  it should "return false on permanent failure without touching tracker counters" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Permanent("400"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.probe(node("p3")).unsafeRunSync() shouldBe false
    tracker.snapshot("p3").totalServed shouldBe 0L

  "send with stampPrelude" should "route to queryStamped when supplied and to query when not" in:
    val recorded = scala.collection.mutable.ListBuffer.empty[String]
    val client   =
      new QuackHttpClient(TestArrow.sharedAllocator):
        override def query(
            endpoint: String,
            token: String,
            sql: String,
            session: Option[String],
            authorize: () => String
        ) =
          IO { recorded += s"plain:$sql"; TestArrow.okResponse() }
        override def queryStamped(
            endpoint: String,
            token: String,
            authorize: () => String,
            prelude: String,
            sql: String
        ) =
          IO { recorded += s"stamped:$prelude|$sql"; TestArrow.okResponse() }
    val tracker  = new NodeLoadTracker
    val adapter  = new QuackHttpAdapter(client, tracker)
    val testNode = RunningNode(
      "n1",
      PoolKey("t", "t_db", "p"),
      Role.Dual,
      "127.0.0.1",
      21901,
      "tok",
      Some(1L),
      None,
      Instant.EPOCH,
      maxConcurrent = 4
    )
    adapter
      .send(
        testNode,
        "INSERT INTO t VALUES (1)",
        NodePrincipal.System,
        None,
        recordLoad = false,
        stampPrelude = Some("BEGIN; CALL stamp()")
      )
      .unsafeRunSync() match
      case QuackResponse.Ok(_, _, close) => close()
      case _                             => ()
    adapter
      .send(testNode, "SELECT 1", NodePrincipal.System, None, recordLoad = false)
      .unsafeRunSync() match
      case QuackResponse.Ok(_, _, close) => close()
      case _                             => ()
    recorded.toList shouldBe List(
      "stamped:BEGIN; CALL stamp()|INSERT INTO t VALUES (1)",
      "plain:SELECT 1"
    )

  "send with recordLoad=false" should "leave tracker counters and percentiles untouched" in:
    // FlightSQL Prepare-time LIMIT-0 probe contract: the probe must be invisible to the UI's
    // per-node Total Served / QPS / avg latency / p50,p95,p99 -- those should reflect only
    // user-visible Executes. We prime the tracker with a real send first, then verify a
    // prep-style send doesn't shift any of the counters.
    val client = new FakeClient(
      Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(42L)))
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)

    adapter
      .send(node("prep1"), "SELECT 1", NodePrincipal.System, session = None)
      .unsafeRunSync() match
      case QuackResponse.Ok(_, _, close) => close()
      case _                             => ()
    val baseline            = tracker.snapshot("prep1")
    val baselinePercentiles = tracker.latencyPercentiles("prep1")
    baseline.totalServed shouldBe 1L
    baseline.ewmaMs should be > 0.0

    adapter
      .send(
        node("prep1"),
        "SELECT * FROM t LIMIT 0",
        NodePrincipal.System,
        session = None,
        recordLoad = false
      )
      .unsafeRunSync() match
      case QuackResponse.Ok(_, _, close) => close()
      case _                             => ()

    val snap = tracker.snapshot("prep1")
    snap.inFlight shouldBe 0
    snap.totalServed shouldBe baseline.totalServed
    snap.ewmaMs shouldBe baseline.ewmaMs
    tracker.latencyPercentiles("prep1") shouldBe baselinePercentiles

  it should "not flip the healthy flag on transient failure when recordLoad=false" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Transient("connection refused"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter
      .send(node("prep2"), "SELECT 1", NodePrincipal.System, None, recordLoad = false)
      .unsafeRunSync()
    val snap = tracker.snapshot("prep2")
    snap.healthy shouldBe true
    snap.totalServed shouldBe 0L

  "engineStats" should "decode the scrape result without touching tracker counters" in:
    val client = new FakeClient(
      Map("quack:127.0.0.1:0" -> (() => {
        val reader = TestArrow.readerFor(EngineStats.sql)
        QuackResponse.Ok(reader, 3L, () => ())
      }))
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    val stats   = adapter.engineStats(node("es1")).unsafeRunSync()
    stats should not be empty
    stats.get.memoryUsedBytes should be >= 0L
    val snap = tracker.snapshot("es1")
    snap.totalServed shouldBe 0L
    snap.ewmaMs shouldBe 0.0

  it should "return None on failure without flipping the healthy flag" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Transient("connection refused"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.engineStats(node("es2")).unsafeRunSync() shouldBe None
    tracker.snapshot("es2").healthy shouldBe true

  "tracked" should "release inFlight when the call is cancelled" in:
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(new FakeClient(Map.empty), tracker)
    val n       = node("n-cancel")
    cats.effect
      .Deferred[IO, Unit]
      .flatMap { started =>
        adapter
          .tracked[Unit](n, recordLoad = true)(started.complete(()) *> IO.never)
          .start
          .flatMap(f => started.get *> f.cancel)
      }
      .unsafeRunSync()
    tracker.snapshot(n.nodeId).inFlight shouldBe 0
    tracker.snapshot(n.nodeId).totalServed shouldBe 0L

  it should "release inFlight when the call raises" in:
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(new FakeClient(Map.empty), tracker)
    val n       = node("n-raise")
    val boom    = new RuntimeException("boom")
    adapter
      .tracked[Unit](n, recordLoad = true)(IO.raiseError(boom))
      .attempt
      .unsafeRunSync() shouldBe
      Left(boom)
    tracker.snapshot(n.nodeId).inFlight shouldBe 0

  "send" should "release inFlight when the client raises" in:
    val boom   = new RuntimeException("socket reset")
    val client =
      new QuackHttpClient(TestArrow.sharedAllocator):
        override def query(
            e: String,
            t: String,
            s: String,
            ss: Option[String],
            authorize: () => String
        ): IO[QuackResponse] =
          IO.raiseError(boom)
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    val n       = node("n-send-raise")
    adapter.send(n, "SELECT 1", NodePrincipal.System, None).attempt.unsafeRunSync() shouldBe Left(
      boom
    )
    tracker.snapshot(n.nodeId).inFlight shouldBe 0

  "send" should "mint a session handle for the principal on the node it calls" in:
    val client  = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(1L))))
    val adapter = new QuackHttpAdapter(client, new NodeLoadTracker)
    val alice   = NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
    adapter.send(node("n-h"), "SELECT 1", alice, None).unsafeRunSync() match
      case QuackResponse.Ok(_, _, close) => close()
      case other                         => fail(s"expected Ok, got $other")
    val payload = ai.starlake.quack.edge.authz.HandleInspector.payload(client.preludes.head)
    payload.hcursor.get[String]("node") shouldBe Right("n-h")
    payload.hcursor.downField("principal").get[String]("id") shouldBe Right("u-1")

  "probe" should "fail a node that runs a statement without a session handle" in:
    val client = new FakeClient(
      Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(1L))),
      canary = () => TestArrow.okResponse(1L)
    )
    new QuackHttpAdapter(client, new NodeLoadTracker).probe(node("p-open")).unsafeRunSync() shouldBe
      false

  it should "pass a node that refuses it, as the system principal" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(1L))))
    new QuackHttpAdapter(client, new NodeLoadTracker).probe(node("p-ok")).unsafeRunSync() shouldBe
      true
    ai.starlake.quack.edge.authz.HandleInspector
      .payload(client.preludes.head)
      .hcursor
      .downField("principal")
      .get[Boolean]("system") shouldBe Right(true)
