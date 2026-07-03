package ai.starlake.quack.edge.adapter

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

class HealthProbeSpec extends AnyFlatSpec with Matchers:

  private val key: PoolKey                  = PoolKey("acme", "acme_default", "sales")
  private def node(id: String): RunningNode =
    RunningNode(id, key, Role.Dual, "127.0.0.1", 0, "tok", Some(1L), None, Instant.EPOCH)

  "HealthProbe" should "mark a healthy node healthy and a failing node unhealthy" in:
    val tracker = new NodeLoadTracker
    val results = Map("ok" -> true, "bad" -> false)
    val probe   = new HealthProbe(
      tracker = tracker,
      pingFn = n => IO.pure(results(n.nodeId)),
      interval = 50.millis
    )
    val nodes = () => List(node("ok"), node("bad"))
    val fiber = probe.start(nodes).unsafeRunSync()
    Thread.sleep(200)
    fiber.cancel.unsafeRunSync()
    tracker.snapshot("ok").healthy shouldBe true
    tracker.snapshot("bad").healthy shouldBe false

  it should "run onHealthy only for nodes that ping healthy" in:
    val tracker = new NodeLoadTracker
    val results = Map("ok" -> true, "bad" -> false)
    val seen    = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    val probe   = new HealthProbe(
      tracker = tracker,
      pingFn = n => IO.pure(results(n.nodeId)),
      interval = 50.millis,
      onHealthy = n => IO.delay { seen.add(n.nodeId); () }
    )
    val fiber = probe.start(() => List(node("ok"), node("bad"))).unsafeRunSync()
    Thread.sleep(200)
    fiber.cancel.unsafeRunSync()
    seen.contains("ok") shouldBe true
    seen.contains("bad") shouldBe false

  it should "keep the node healthy when onHealthy throws" in:
    val tracker = new NodeLoadTracker
    val probe   = new HealthProbe(
      tracker = tracker,
      pingFn = _ => IO.pure(true),
      interval = 50.millis,
      onHealthy = _ => IO.raiseError(new RuntimeException("scrape blew up"))
    )
    val fiber = probe.start(() => List(node("ok"))).unsafeRunSync()
    Thread.sleep(200)
    fiber.cancel.unsafeRunSync()
    tracker.snapshot("ok").healthy shouldBe true
