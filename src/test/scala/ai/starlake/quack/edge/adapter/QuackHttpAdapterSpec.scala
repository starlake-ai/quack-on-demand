package ai.starlake.quack.edge.adapter

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class QuackHttpAdapterSpec extends AnyFlatSpec with Matchers:

  private val key: PoolKey = PoolKey("acme", "sales")
  private def node(id: String): RunningNode =
    RunningNode(id, key, Role.Dual, "127.0.0.1", 0, "tok-" + id,
                Some(1L), None, Instant.EPOCH)

  /** Stub returning canned responses keyed by endpoint URI. Each entry is a
    * thunk because ArrowReader instances are single-use. */
  final class FakeClient(canned: Map[String, () => QuackResponse])
      extends QuackHttpClient(
        TestArrow.sharedAllocator,
        nativeClient   = true,
        nodeDisableSsl = true
      ):
    override def query(endpoint: String, token: String, sql: String, session: Option[String]): IO[QuackResponse] =
      IO.pure(canned.getOrElse(endpoint, () =>
        QuackResponse.Failed(QuackError.Permanent("no stub"))).apply())

  "QuackHttpAdapter" should "delegate to client, track load on success" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(42L))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)

    val out = adapter.send(node("n1"), "SELECT 1", session = None).unsafeRunSync()
    out should matchPattern { case QuackResponse.Ok(_, _, _) => }
    out match
      case QuackResponse.Ok(_, _, close) => close()
      case _ => ()
    tracker.snapshot("n1").inFlight shouldBe 0
    tracker.snapshot("n1").ewmaMs   should be > 0.0

  it should "mark node unhealthy on transient failure" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" ->
      (() => QuackResponse.Failed(QuackError.Transient("connection refused"), 1))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.send(node("n2"), "SELECT 1", None).unsafeRunSync()
    tracker.snapshot("n2").healthy shouldBe false

  it should "NOT mark unhealthy on permanent failure" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" ->
      (() => QuackResponse.Failed(QuackError.Permanent("400"), 1))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.send(node("n3"), "BAD", None).unsafeRunSync()
    tracker.snapshot("n3").healthy shouldBe true

  "probe" should "return true on Ok without touching tracker counters" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" -> (() => TestArrow.okResponse(42L))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.probe(node("p1")).unsafeRunSync() shouldBe true
    val snap = tracker.snapshot("p1")
    snap.inFlight    shouldBe 0
    snap.totalServed shouldBe 0L
    snap.ewmaMs      shouldBe 0.0

  it should "return false on transient failure without touching tracker counters" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" ->
      (() => QuackResponse.Failed(QuackError.Transient("connection refused"), 1))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.probe(node("p2")).unsafeRunSync() shouldBe false
    tracker.snapshot("p2").totalServed shouldBe 0L

  it should "return false on permanent failure without touching tracker counters" in:
    val client = new FakeClient(Map("quack:127.0.0.1:0" ->
      (() => QuackResponse.Failed(QuackError.Permanent("400"), 1))))
    val tracker = new NodeLoadTracker
    val adapter = new QuackHttpAdapter(client, tracker)
    adapter.probe(node("p3")).unsafeRunSync() shouldBe false
    tracker.snapshot("p3").totalServed shouldBe 0L
