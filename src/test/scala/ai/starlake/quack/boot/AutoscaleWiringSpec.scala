package ai.starlake.quack.boot

import ai.starlake.quack.AutoscaleConfig
import ai.starlake.quack.model.{PoolKey, RoleDistribution}
import ai.starlake.quack.ondemand.autoscale.{AutoscaleAction, AutoscaleView}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AutoscaleWiringSpec extends AnyFlatSpec with Matchers:
  private val poolKey = PoolKey("t", "db", "p")
  private val cfg     = AutoscaleConfig(outStreak = 1) // act on the first hot sweep
  private def hotView =
    AutoscaleView(poolKey, 1, 3, RoleDistribution(0, 1, 0), false, false, Some(0.9))

  "sweep" should "flush local buckets on every replica but only decide as leader" in:
    var flushed = 0
    var scaled  = 0
    val w       = new AutoscaleWiring(
      cfg,
      views = () => List(hotView),
      flushLocal = () => flushed += 1,
      purge = () => (),
      scale = _ => IO { scaled += 1; Right(()) },
      isLeader = () => false
    )
    w.sweep().unsafeRunSync()
    flushed shouldBe 1
    scaled shouldBe 0

  it should "apply a scale-out decision as leader" in:
    var applied = List.empty[AutoscaleAction]
    val w       = new AutoscaleWiring(
      cfg,
      views = () => List(hotView),
      flushLocal = () => (),
      purge = () => (),
      scale = a => IO { applied = applied :+ a; Right(()) },
      isLeader = () => true
    )
    w.sweep().unsafeRunSync()
    applied shouldBe List(AutoscaleAction.ScaleOut(poolKey, 2, RoleDistribution(0, 2, 0)))

  it should "back off a pool after 3 consecutive failures and recover after the window" in:
    var calls = 0
    val w     = new AutoscaleWiring(
      cfg.copy(failureBackoffSweeps = 2, scaleOutCooldownSec = 0),
      views = () => List(hotView),
      flushLocal = () => (),
      purge = () => (),
      scale = _ => IO { calls += 1; Left("boom") },
      isLeader = () => true
    )
    w.sweep().unsafeRunSync() // fail 1
    w.sweep().unsafeRunSync() // fail 2
    w.sweep().unsafeRunSync() // fail 3 -> backoff starts
    calls shouldBe 3
    w.sweep().unsafeRunSync() // backoff sweep 1: skipped
    w.sweep().unsafeRunSync() // backoff sweep 2: skipped
    calls shouldBe 3
    w.sweep().unsafeRunSync() // retry
    calls shouldBe 4

  it should "not let a scale error kill the sweep" in:
    val w = new AutoscaleWiring(
      cfg.copy(scaleOutCooldownSec = 0),
      views = () => List(hotView),
      flushLocal = () => (),
      purge = () => (),
      scale = _ => IO.raiseError(new RuntimeException("quota")),
      isLeader = () => true
    )
    noException should be thrownBy w.sweep().unsafeRunSync()

  it should "flush even when the leader's decide pass throws" in:
    var flushed = 0
    val w       = new AutoscaleWiring(
      cfg,
      views = () => throw new RuntimeException("store down"),
      flushLocal = () => flushed += 1,
      purge = () => (),
      scale = _ => IO.pure(Right(())),
      isLeader = () => true
    )
    an[RuntimeException] should be thrownBy w.sweep().unsafeRunSync()
    flushed shouldBe 1

  it should "re-evaluate the leader gate on every run, not just once when sweep() is constructed" in:
    var leader  = false
    var applied = List.empty[AutoscaleAction]
    val w       = new AutoscaleWiring(
      cfg,
      views = () => List(hotView),
      flushLocal = () => (),
      purge = () => (),
      scale = a => IO { applied = applied :+ a; Right(()) },
      isLeader = () => leader
    )
    val s = w.sweep()
    s.unsafeRunSync() // not leader yet: no decision
    applied shouldBe Nil
    leader = true
    s.unsafeRunSync() // same bound IO, now leader: must decide fresh
    applied shouldBe List(AutoscaleAction.ScaleOut(poolKey, 2, RoleDistribution(0, 2, 0)))

  it should "survive a scale that throws synchronously and still count the failure toward backoff" in:
    val w = new AutoscaleWiring(
      cfg.copy(failureBackoffSweeps = 2, scaleOutCooldownSec = 0),
      views = () => List(hotView),
      flushLocal = () => (),
      purge = () => (),
      scale = _ => throw new RuntimeException("boom-sync"),
      isLeader = () => true
    )
    noException should be thrownBy w.sweep().unsafeRunSync() // fail 1
    noException should be thrownBy w.sweep().unsafeRunSync() // fail 2
    noException should be thrownBy w.sweep().unsafeRunSync() // fail 3 -> backoff starts
    w.backoffForTest.get(poolKey) shouldBe Some(2)

  it should "reset the failure streak after a success" in:
    var calls = 0
    val w     = new AutoscaleWiring(
      cfg.copy(failureBackoffSweeps = 5, scaleOutCooldownSec = 0),
      views = () => List(hotView),
      flushLocal = () => (),
      purge = () => (),
      scale = _ =>
        IO {
          calls += 1
          if calls % 3 == 0 then Right(()) else Left("boom")
        },
      isLeader = () => true
    )
    (1 to 6).foreach(_ => w.sweep().unsafeRunSync())
    // Two failures then a success on every third sweep: the counter never
    // reaches 3, so no pool ever enters backoff.
    calls shouldBe 6
    w.backoffForTest.isEmpty shouldBe true

  // The disabled fiber completes instead of looping. Main keys the PoolLoadStats
  // sink off the same flag, because this sweep is the sink's sole drainer.
  "fiber" should "start no loop when autoscale is disabled" in:
    var flushed = 0
    val w       = new AutoscaleWiring(
      cfg.copy(enabled = false),
      views = () => List(hotView),
      flushLocal = () => flushed += 1,
      purge = () => (),
      scale = _ => IO.pure(Right(())),
      isLeader = () => true
    )
    w.fiber.flatMap(_.join).unsafeRunSync().isSuccess shouldBe true
    flushed shouldBe 0
