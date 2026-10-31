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
import ai.starlake.quack.ondemand.ha.PoolLocker
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.{Deferred, FiberIO, IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

class PoolSupervisorDrainSpec extends AnyFlatSpec with Matchers:
  private val key: PoolKey = PoolKey("acme", "acme_default", "sales")

  private def fixture: IO[(PoolSupervisor, NodeLoadTracker, String, Deferred[IO, String])] =
    fixtureWith(PoolSupervisor.DefaultDrainTimeout)

  private def fixtureWith(drainTimeout: FiniteDuration) =
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
      supervisor = new PoolSupervisor(backend, tracker, store, drainTimeout = drainTimeout)
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

  "A drain" should "stop the node once the drain timeout passes, whatever is still in flight" in:
    (for
      f <- fixtureWith(200.millis)
      (sup, tracker, id, stopped) = f
      _      <- IO(tracker.onStart(id))
      _      <- sup.stopPool(key, force = false).timeout(3.seconds)
      actual <- stopped.get
      _      <- IO(actual shouldBe id)
    yield ()).timeout(10.seconds).unsafeRunSync()

  // -- several nodes, suspend, and the leader loops ------------------------------------------------

  /** A pool of `size` dual nodes; `stops` lists the node ids the backend stopped, in order. */
  private final case class Pool(
      sup: PoolSupervisor,
      tracker: NodeLoadTracker,
      ids: List[String],
      stops: Ref[IO, List[String]]
  )

  private def pool(
      size: Int,
      drainTimeout: FiniteDuration = 10.seconds,
      locks: PoolLocker = PoolLocker.noop
  ): IO[Pool] =
    for
      stops <- Ref.of[IO, List[String]](Nil)
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
        def stop(key: PoolKey, id: String): IO[Unit]  = stops.update(_ :+ id)
        def isAlive(id: String): Boolean              = true
        def discoverExisting(): IO[List[RunningNode]] = IO.pure(Nil)
        def cleanup(): IO[Unit]                       = IO.unit
      tracker = new NodeLoadTracker
      sup     = new PoolSupervisor(
        backend,
        tracker,
        new InMemoryControlPlaneStore,
        drainTimeout = drainTimeout,
        locks = locks
      )
      _     <- sup.createTenant(Tenant("acme"))
      _     <- sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "")
      nodes <- sup.createPool(key, RoleDistribution(0, 0, size))
    yield Pool(sup, tracker, nodes.map(_.nodeId), stops)

  /** Polls `cond` every 5 ms for up to 3 s. */
  private def eventually(what: String)(cond: => Boolean): IO[Unit] =
    def loop: IO[Unit] = IO.defer(if cond then IO.unit else IO.sleep(5.millis) *> loop)
    loop.timeoutTo(3.seconds, IO(fail(s"never: $what")))

  /** Whether `fiber` is still running after `wait`. */
  private def running(fiber: FiberIO[?], wait: FiniteDuration): IO[Boolean] =
    IO.race(fiber.joinWithNever, IO.sleep(wait)).map(_.isRight)

  "A drain of several nodes" should "mark them all draining for one wait, and clear every flag when cancelled" in:
    (for
      p <- pool(2)
      _ <- IO(p.tracker.onStart(p.ids.head))
      f <- p.sup.stopPool(key, force = false).start
      _ <- eventually("both nodes draining")(p.ids.forall(p.tracker.snapshot(_).draining))
      // The idle node waits with the busy one: nothing is stopped yet.
      _ <- running(f, 200.millis).map(_ shouldBe true)
      _ <- p.stops.get.map(_ shouldBe Nil)
      _ <- f.cancel
      _ <- IO(p.ids.map(p.tracker.snapshot(_).draining) shouldBe List(false, false))
      _ <- p.stops.get.map(_ shouldBe Nil)
    yield ()).timeout(10.seconds).unsafeRunSync()

  "A scale-down" should "drain only the nodes it removes, the others serving on" in:
    (for
      p <- pool(2)
      _ <- IO(p.ids.foreach(p.tracker.onStart))
      f <- p.sup.scale(key, 1, RoleDistribution(0, 0, 1), force = false).start
      _ <- eventually("one node draining")(p.ids.exists(p.tracker.snapshot(_).draining))
      _ <- running(f, 200.millis).map(_ shouldBe true)
      draining = p.ids.filter(p.tracker.snapshot(_).draining)
      _ <- IO(draining should have size 1)
      kept = p.ids.filterNot(draining.contains).head
      _ <- IO(p.tracker.snapshot(kept).routable shouldBe true)
      _ <- IO(p.tracker.onFinish(draining.head, 5L))
      _ <- f.joinWithNever.timeout(3.seconds)
      _ <- p.stops.get.map(_ shouldBe draining)
      _ <- IO(p.sup.get(key).get.nodes.map(_.nodeId) shouldBe List(kept))
    yield ()).timeout(10.seconds).unsafeRunSync()

  "A suspend" should "not wait for a front-door hold, which only a resume could release" in:
    // A held link is an open native transaction or a result still being read. Its next statement
    // on a suspended pool wakes it, and the resume waits for the suspend's lock: waiting for the
    // hold would always run to the timeout.
    (for
      p <- pool(1)
      _ <- IO(p.tracker.tryHold(p.ids.head).getOrElse(fail("refused")))
      _ <- p.sup.suspendPool(key, "test").timeout(2.seconds)
      _ <- p.stops.get.map(_ shouldBe p.ids)
    yield ()).timeout(10.seconds).unsafeRunSync()

  it should "still wait for a statement in flight" in:
    (for
      p <- pool(1)
      _ <- IO(p.tracker.onStart(p.ids.head))
      f <- p.sup.suspendPool(key, "test").start
      _ <- eventually("draining")(p.tracker.snapshot(p.ids.head).draining)
      _ <- running(f, 200.millis).map(_ shouldBe true)
      _ <- IO(p.tracker.onFinish(p.ids.head, 5L))
      _ <- f.joinWithNever.timeout(3.seconds)
      _ <- p.stops.get.map(_ shouldBe p.ids)
    yield ()).timeout(10.seconds).unsafeRunSync()

  "The reconcile pass" should "skip a pool whose lock a drain holds, then queue for it after three skips" in:
    (for
      p <- pool(1, locks = PoolLocker.inProcess())
      _ <- IO(p.tracker.onStart(p.ids.head))
      f <- p.sup.stopPool(key, force = false).start
      _ <- eventually("draining")(p.tracker.snapshot(p.ids.head).draining)
      // Three passes skip the busy pool instead of queueing behind the drain...
      _ <- p.sup.reconcile().timeout(1.second)
      _ <- p.sup.reconcile().timeout(1.second)
      _ <- p.sup.reconcile().timeout(1.second)
      // ...and the next one waits for it, so a pool drained back to back is not skipped forever.
      r <- p.sup.reconcile().start
      _ <- running(r, 300.millis).map(_ shouldBe true)
      _ <- IO(p.tracker.onFinish(p.ids.head, 5L))
      _ <- f.joinWithNever.timeout(3.seconds)
      _ <- r.joinWithNever.timeout(3.seconds)
    yield ()).timeout(15.seconds).unsafeRunSync()

  "A leader duty" should "be refused at once with PoolBusy while a drain holds the pool, and run once it is free" in:
    (for
      p   <- pool(2, locks = PoolLocker.inProcess())
      _   <- IO(p.ids.foreach(p.tracker.onStart))
      f   <- p.sup.scale(key, 1, RoleDistribution(0, 0, 1), force = false).start
      _   <- eventually("draining")(p.ids.exists(p.tracker.snapshot(_).draining))
      out <- p.sup
        .scale(
          key,
          2,
          RoleDistribution(0, 0, 2),
          force = false,
          reason = "autoscale",
          onBusy = PoolSupervisor.OnBusy.Skip("autoscale")
        )
        .attempt
        .timeout(1.second)
      _   <- IO(out.left.toOption.get shouldBe a[PoolSupervisor.PoolBusy])
      sus <- p.sup
        .suspendPool(key, "idle", onBusy = PoolSupervisor.OnBusy.Skip("hibernation"))
        .attempt
        .timeout(1.second)
      _ <- IO(sus.left.toOption.get shouldBe a[PoolSupervisor.PoolBusy])
      _ <- IO(p.ids.foreach(id => p.tracker.onFinish(id, 5L)))
      _ <- f.joinWithNever.timeout(3.seconds)
      // Free again: the same duty runs.
      _ <- p.sup
        .suspendPool(key, "idle", onBusy = PoolSupervisor.OnBusy.Skip("hibernation"))
        .timeout(3.seconds)
        .map(_.isRight shouldBe true)
    yield ()).timeout(15.seconds).unsafeRunSync()
