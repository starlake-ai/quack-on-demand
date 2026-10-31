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
      interval = 50.millis,
      afterTick = _ => ()
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
      afterTick = _ => (),
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
      afterTick = _ => (),
      onHealthy = _ => IO.raiseError(new RuntimeException("scrape blew up"))
    )
    val fiber = probe.start(() => List(node("ok"))).unsafeRunSync()
    Thread.sleep(200)
    fiber.cancel.unsafeRunSync()
    tracker.snapshot("ok").healthy shouldBe true

  it should "hand afterTick the ids it listed, after pinging them, and survive its failure" in:
    val tracker          = new NodeLoadTracker
    val pinged           = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    val ticks            = new java.util.concurrent.ConcurrentLinkedQueue[(Set[String], Boolean)]()
    @volatile var listed = List(node("a"), node("b"))
    val probe            = new HealthProbe(
      tracker = tracker,
      pingFn = n => IO.delay { pinged.add(n.nodeId); true },
      interval = 50.millis,
      afterTick = ids => {
        // Every id handed over was pinged first in the same tick.
        ticks.add((ids, ids.forall(pinged.contains)))
        throw new RuntimeException("prune blew up")
      }
    )
    val fiber = probe.start(() => listed).unsafeRunSync()
    Thread.sleep(150)
    listed = List(node("b"))
    Thread.sleep(200)
    fiber.cancel.unsafeRunSync()
    import scala.jdk.CollectionConverters.*
    val seen = ticks.asScala.toList
    seen.map(_._1) should contain(Set("a", "b"))
    seen.last._1 shouldBe Set("b")
    seen.forall(_._2) shouldBe true
    // The loop kept ticking although afterTick threw every time.
    seen.size should be >= 3
