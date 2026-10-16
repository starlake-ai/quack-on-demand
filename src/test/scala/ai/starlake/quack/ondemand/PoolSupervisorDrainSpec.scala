package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

class PoolSupervisorDrainSpec extends AnyFlatSpec with Matchers:
  private val key: PoolKey = PoolKey("acme", "acme_default", "sales")

  private def fixture =
    for
      stopped <- Deferred[IO, String]
      backend = new QuackBackend:
        def start(spec: NodeSpec): IO[RunningNode] = IO.pure(
          RunningNode(
            spec.nodeId,
            spec.poolKey,
            spec.role,
            "localhost",
            1234,
            "token",
            None,
            None,
            Instant.EPOCH,
            maxConcurrent = spec.maxConcurrent
          )
        )
        def stop(key: PoolKey, id: String): IO[Unit]  = stopped.complete(id).void
        def isAlive(id: String): Boolean              = true
        def discoverExisting(): IO[List[RunningNode]] = IO.pure(Nil)
        def cleanup(): IO[Unit]                       = IO.unit
      tracker    = new NodeLoadTracker
      store      = new InMemoryControlPlaneStore
      supervisor = new PoolSupervisor(backend, tracker, store)
      _ <- supervisor.createTenant(Tenant("acme"))
      _ <- supervisor.createTenantDb(
        "acme",
        "default",
        TenantDbKind.InMemory,
        Map.empty,
        dataPath = ""
      )
      nodes <- supervisor.createPool(key, RoleDistribution(0, 0, 1))
    yield (supervisor, tracker, nodes.head.nodeId, stopped)

  private def awaitDraining(
      tracker: NodeLoadTracker,
      id: String,
      stopped: Deferred[IO, String]
  ): IO[Unit] =
    def waitForFlag: IO[Unit] = IO.defer {
      if tracker.snapshot(id).draining then IO.unit
      else IO.sleep(5.millis) *> waitForFlag
    }
    IO.race(stopped.get, waitForFlag)
      .flatMap {
        case Left(_)  => IO(fail("backend.stop was called while requests were still in flight"))
        case Right(_) => IO.unit
      }
      .timeout(3.seconds)

  "Non-force teardown" should "wait for tracked requests before stopping the node" in:
    val operations: List[(String, PoolSupervisor => IO[Unit])] = List(
      "scale"   -> (_.scale(key, 0, RoleDistribution(0, 0, 0), force = false).void),
      "stop"    -> (_.stopPool(key, force = false)),
      "delete"  -> (_.deletePool(key, force = false)),
      "suspend" -> (_.suspendPool(key, "test").void)
    )
    operations.foreach { case (name, teardown) =>
      withClue(s"$name: ") {
        (for
          f <- fixture
          (sup, tracker, id, stopped) = f
          _ <- IO(tracker.onStart(id))
          _ <- IO(tracker.onStart(id))
          _ <- teardown(sup).start.bracket { fiber =>
            for
              _            <- awaitDraining(tracker, id, stopped)
              _            <- IO.sleep(150.millis)
              earlyStop    <- stopped.tryGet
              _            <- IO(earlyStop shouldBe None)
              _            <- IO(tracker.onFinish(id, 10L))
              _            <- IO.sleep(150.millis)
              stillRunning <- stopped.tryGet
              _            <- IO(stillRunning shouldBe None)
              _            <- IO(tracker.onAbort(id))
              _            <- fiber.joinWithNever.timeout(3.seconds)
              actual       <- stopped.get
              _            <- IO(actual shouldBe id)
              _            <- IO(tracker.snapshotAll.keySet should not contain id)
            yield ()
          }(_.cancel)
        yield ()).timeout(10.seconds).unsafeRunSync()
      }
    }

  it should "leave a node routable if its drain is cancelled" in:
    (for
      f <- fixture
      (sup, tracker, id, stopped) = f
      _      <- IO(tracker.onStart(id))
      fiber  <- sup.stopPool(key, force = false).start
      _      <- awaitDraining(tracker, id, stopped)
      _      <- fiber.cancel
      _      <- IO(tracker.snapshot(id).draining shouldBe false)
      _      <- IO(tracker.snapshot(id).inFlight shouldBe 1)
      actual <- stopped.tryGet
      _      <- IO(actual shouldBe None)
      _      <- IO(sup.get(key).get.nodes.map(_.nodeId) shouldBe List(id))
    yield ()).timeout(10.seconds).unsafeRunSync()

  "Forced teardown" should "stop without waiting for tracked requests" in:
    (for
      f <- fixture
      (sup, tracker, id, stopped) = f
      _      <- IO(tracker.onStart(id))
      _      <- sup.stopPool(key, force = true).timeout(3.seconds)
      actual <- stopped.get
      _      <- IO(actual shouldBe id)
    yield ()).timeout(10.seconds).unsafeRunSync()

  "Idle teardown" should "stop immediately without a force flag" in:
    (for
      f <- fixture
      (sup, _, id, stopped) = f
      _      <- sup.stopPool(key, force = false).timeout(3.seconds)
      actual <- stopped.get
      _      <- IO(actual shouldBe id)
    yield ()).timeout(10.seconds).unsafeRunSync()
