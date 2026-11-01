package ai.starlake.quack.edge.adapter

import ai.starlake.quack.edge.authz.NodePrincipal
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class QuackHttpAdapterSpec extends AnyFlatSpec with Matchers:

  "A draining node" should "reject a previously selected request without calling the client" in:
    var called = false
    val client = new FakeClient(Map("quack:127.0.0.1:0" -> (() => {
      called = true
      QuackResponse.Failed(QuackError.Permanent("should not be sent"))
    })))
    val tracker = new NodeLoadTracker
    tracker.onStart("draining")
    tracker.setDraining("draining", true)
    val before  = tracker.snapshot("draining")
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())

    adapter
      .send(node("draining"), "SELECT 1", NodePrincipal.System, None)
      .unsafeRunSync() should matchPattern {
      case QuackResponse.Failed(QuackError.Transient(_, _), _) =>
    }
    called shouldBe false
    tracker.snapshot("draining") shouldBe before

  it should "reject a native relay call without changing load or health" in:
    val tracker = new NodeLoadTracker
    tracker.setDraining("draining", true)
    val before  = tracker.snapshot("draining")
    val adapter = new QuackHttpAdapter(
      new FakeClient(Map.empty),
      tracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    )
    var called = false
    val out    = adapter
      .tracked[String](node("draining"), recordLoad = true, admitDraining = false)(IO {
        called = true
        NodeOutcome.Permanent("should not be sent", 1L)
      })
      .unsafeRunSync()

    out should matchPattern { case NodeOutcome.Transient(_, _, _) => }
    called shouldBe false
    tracker.snapshot("draining") shouldBe before

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
    val preludes                = scala.collection.mutable.ListBuffer.empty[String]
    @volatile var canaries: Int = 0
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
      IO { canaries += 1; canary() }

  "QuackHttpAdapter" should "delegate to client, track load on success" in:
    val client  = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(42L))))
    val tracker = new NodeLoadTracker
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())

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
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
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
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    adapter.send(node("n3"), "BAD", NodePrincipal.System, None).unsafeRunSync()
    tracker.snapshot("n3").healthy shouldBe true

  "probe" should "be Ok without touching tracker counters" in:
    val client  = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(42L))))
    val tracker = new NodeLoadTracker
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    adapter.probe(node("p1")).unsafeRunSync() shouldBe ProbeVerdict.Ok
    val snap = tracker.snapshot("p1")
    snap.inFlight shouldBe 0
    snap.totalServed shouldBe 0L
    snap.ewmaMs shouldBe 0.0

  it should "fail on a transient failure without touching tracker counters" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Transient("connection refused"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    // A transport failure, even of the probe's handle, is not the node refusing it.
    adapter.probe(node("p2")).unsafeRunSync() shouldBe
      ProbeVerdict.Failed(QuackError.Transient("connection refused").toString)
    tracker.snapshot("p2").totalServed shouldBe 0L

  it should "fail on a permanent failure without touching tracker counters" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() => QuackResponse.Failed(QuackError.Permanent("400"), 1))
      )
    )
    val tracker = new NodeLoadTracker
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    adapter.probe(node("p3")).unsafeRunSync() shouldBe
      ProbeVerdict.Failed(QuackError.Permanent("400").toString)
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
    val tracker = new NodeLoadTracker
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
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
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())

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
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
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
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val stats = adapter.engineStats(node("es1")).unsafeRunSync()
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
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    adapter.engineStats(node("es2")).unsafeRunSync() shouldBe None
    tracker.snapshot("es2").healthy shouldBe true

  private val CountersRow =
    "SELECT 1::BIGINT, 2::BIGINT, 3::BIGINT, 4::BIGINT, 5::BIGINT, 6::BIGINT, 7::BIGINT, " +
      "8::BIGINT, 9::BIGINT, 10::BIGINT"

  "authzStats" should "read the node's counters as the system principal, without tracker bookkeeping" in:
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" -> (() =>
          QuackResponse.Ok(TestArrow.readerFor(CountersRow), 3L, () => ())
        )
      )
    )
    val tracker = new NodeLoadTracker
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    adapter.authzStats(node("as1")).unsafeRunSync() shouldBe
      Some(NodeAuthzStats(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L))
    val payload = ai.starlake.quack.edge.authz.HandleInspector.payload(client.preludes.head)
    payload.hcursor.downField("principal").get[Boolean]("system") shouldBe Right(true)
    tracker.snapshot("as1").totalServed shouldBe 0L
    tracker.snapshot("as1").ewmaMs shouldBe 0.0

  it should "return None, never raise, when the node refuses the read or the client raises" in:
    val refusing = new FakeClient(
      Map(
        "quack:127.0.0.1:0" ->
          (() =>
            QuackResponse.Failed(
              QuackError.Permanent("QoD: function qod_authz_stats is superuser-only"),
              1
            )
          )
      )
    )
    val tracker = new NodeLoadTracker
    new QuackHttpAdapter(refusing, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
      .authzStats(node("as2"))
      .unsafeRunSync() shouldBe None
    tracker.snapshot("as2").healthy shouldBe true
    val raising =
      new QuackHttpClient(TestArrow.sharedAllocator):
        override def query(
            e: String,
            t: String,
            s: String,
            ss: Option[String],
            authorize: () => String
        ): IO[QuackResponse] =
          IO.raiseError(new RuntimeException("socket reset"))
    new QuackHttpAdapter(
      raising,
      new NodeLoadTracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    )
      .authzStats(node("as3"))
      .unsafeRunSync() shouldBe None

  "tracked" should "release inFlight when the call is cancelled" in:
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(
      new FakeClient(Map.empty),
      tracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    )
    val n = node("n-cancel")
    cats.effect
      .Deferred[IO, Unit]
      .flatMap { started =>
        adapter
          .tracked[Unit](n, recordLoad = true, admitDraining = false)(
            started.complete(()) *> IO.never
          )
          .start
          .flatMap(f => started.get *> f.cancel)
      }
      .unsafeRunSync()
    tracker.snapshot(n.nodeId).inFlight shouldBe 0
    tracker.snapshot(n.nodeId).totalServed shouldBe 0L

  it should "release inFlight when the call raises" in:
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(
      new FakeClient(Map.empty),
      tracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    )
    val n    = node("n-raise")
    val boom = new RuntimeException("boom")
    adapter
      .tracked[Unit](n, recordLoad = true, admitDraining = false)(IO.raiseError(boom))
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
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val n = node("n-send-raise")
    adapter.send(n, "SELECT 1", NodePrincipal.System, None).attempt.unsafeRunSync() shouldBe Left(
      boom
    )
    tracker.snapshot(n.nodeId).inFlight shouldBe 0

  "send" should "mint a session handle for the principal on the node it calls" in:
    val client  = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(1L))))
    val adapter = new QuackHttpAdapter(
      client,
      new NodeLoadTracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    )
    val alice = NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
    adapter.send(node("n-h"), "SELECT 1", alice, None).unsafeRunSync() match
      case QuackResponse.Ok(_, _, close) => close()
      case other                         => fail(s"expected Ok, got $other")
    val payload = ai.starlake.quack.edge.authz.HandleInspector.payload(client.preludes.head)
    payload.hcursor.get[String]("node") shouldBe Right("n-h")
    payload.hcursor.downField("principal").get[String]("id") shouldBe Right("u-1")

  "probe" should "find a node that runs a statement without a session handle unenforced" in:
    val client = new FakeClient(
      Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(1L))),
      canary = () => TestArrow.okResponse(1L)
    )
    new QuackHttpAdapter(
      client,
      new NodeLoadTracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    ).probe(node("p-open")).unsafeRunSync() shouldBe
      ProbeVerdict.Unenforced

  it should "pass a node that refuses it, as the system principal" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(1L))))
    new QuackHttpAdapter(
      client,
      new NodeLoadTracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    ).probe(node("p-ok")).unsafeRunSync() shouldBe
      ProbeVerdict.Ok
    client.canaries shouldBe 1
    ai.starlake.quack.edge.authz.HandleInspector
      .payload(client.preludes.head)
      .hcursor
      .downField("principal")
      .get[Boolean]("system") shouldBe Right(true)

  it should "pass a node whose canary fails for another reason: the probe statement ran" in:
    val client = new FakeClient(
      Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(1L))),
      canary = () => QuackResponse.Failed(QuackError.Transient("HTTP 503 from Quack node"), 1)
    )
    new QuackHttpAdapter(
      client,
      new NodeLoadTracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    ).probe(node("p-503")).unsafeRunSync() shouldBe
      ProbeVerdict.Ok
    client.canaries shouldBe 1

  it should "carry the node's answer when it refuses the probe's own session handle" in:
    val answer = "qod-node: session handle refused: handle MAC does not verify"
    val client = new FakeClient(
      Map(
        "quack:127.0.0.1:0" -> (() =>
          QuackResponse
            .Failed(QuackError.Transient(QuackHttpClient.HandleRefused(answer).getMessage), 1)
        )
      )
    )
    new QuackHttpAdapter(
      client,
      new NodeLoadTracker,
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    ).probe(node("p-key")).unsafeRunSync() shouldBe
      ProbeVerdict.HandleRefused(answer)
    // The probe statement never ran, so there was nothing for the canary to tell.
    client.canaries shouldBe 0

  "HandleRefusalWarnings" should "name another node-key secret once, until the node passes again" in:
    val warnings = new HandleRefusalWarnings
    val refused  =
      ProbeVerdict.HandleRefused("qod-node: session handle refused: handle MAC does not verify")
    val first = warnings.warning("n-1", refused)
    first.isDefined shouldBe true
    first.get should include("node n-1 refuses the manager's session handles")
    first.get should include("handle MAC does not verify")
    // Restarting the node alone only moves the refusal when the replicas disagree, so the advice
    // is one secret on every replica first.
    first.get should include(
      "make QOD_NODE_KEY_SECRET the same on every replica (or unset on all), then restart the node"
    )
    // Once per refusal: not again on the next tick, nor after a tick that failed otherwise.
    warnings.warning("n-1", refused) shouldBe None
    warnings.warning("n-1", ProbeVerdict.Failed("down")) shouldBe None
    warnings.warning("n-1", refused) shouldBe None
    // Another node is named on its own.
    warnings.warning("n-2", refused).isDefined shouldBe true
    // A pass ends the refusal: the next one is named again.
    warnings.warning("n-1", ProbeVerdict.Ok) shouldBe None
    warnings.warning("n-1", refused) shouldBe first

  it should "name the clocks for a handle the node finds expired or valid too long" in:
    for answer <- List(
        "qod-node: session handle refused: handle expired",
        "qod-node: session handle refused: handle is valid too long"
      )
    do
      val w = new HandleRefusalWarnings().warning("n-1", ProbeVerdict.HandleRefused(answer)).get
      w should include(answer)
      w should include("clocks disagree by about the handle lifetime (60 s) or more")
      w should include("check clock sync")
      w should not include "QOD_NODE_KEY_SECRET"

  it should "pass any other refusal on as the node words it" in:
    val answer = "qod-node: session handle refused: it was already used"
    new HandleRefusalWarnings().warning("n-1", ProbeVerdict.HandleRefused(answer)) shouldBe Some(
      s"node n-1 refuses the manager's session handles, so no statement runs on it and it stays " +
        s"unhealthy ($answer)"
    )

  it should "say nothing for a node that is down or does not enforce" in:
    val warnings = new HandleRefusalWarnings
    warnings.warning("n-1", ProbeVerdict.Failed("connection refused")) shouldBe None
    warnings.warning("n-1", ProbeVerdict.Unenforced) shouldBe None
