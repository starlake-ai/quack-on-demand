package ai.starlake.quack.boot

import ai.starlake.quack.HibernationConfig
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.hibernate.HibernationView
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.DurationInt

class HibernationWiringSpec extends AnyFlatSpec with Matchers:
  private val poolKey  = PoolKey("t", "db", "p")
  private val cfg  = HibernationConfig()
  private val now  = Instant.parse("2026-09-03T12:00:00Z")
  private def idleView = HibernationView(poolKey, false, false, 1, 30.minutes)
  private def staleActivity = Map(poolKey -> now.minusSeconds(31 * 60))

  private def wiring(
      leader: Boolean,
      suspend: PoolKey => IO[Either[String, Unit]],
      onFlush: () => Unit = () => ()
  ) =
    new HibernationWiring(
      cfg,
      views = () => List(idleView),
      activityByKey = () => staleActivity,
      flushLocal = onFlush,
      purge = () => (),
      suspend = suspend,
      isLeader = () => leader,
      now = () => now
    )

  "sweep" should "flush local activity on every replica but only suspend as leader" in:
    var flushed   = 0
    var suspended = 0
    val w = wiring(leader = false, suspend = _ => IO { suspended += 1; Right(()) }, () => flushed += 1)
    w.sweep().unsafeRunSync()
    flushed shouldBe 1
    suspended shouldBe 0

  it should "suspend an idle pool as leader" in:
    var suspended = List.empty[PoolKey]
    val w         = wiring(leader = true, suspend = k => IO { suspended = suspended :+ k; Right(()) })
    w.sweep().unsafeRunSync()
    suspended shouldBe List(poolKey)

  it should "retry a failed suspend on the next sweep" in:
    var calls = 0
    val w     = wiring(leader = true, suspend = _ => IO { calls += 1; Left("boom") })
    w.sweep().unsafeRunSync()
    w.sweep().unsafeRunSync()
    calls shouldBe 2

  it should "drop a suspended pool's first-seen baseline" in:
    // Never-active pool: first sweep baselines, a later sweep suspends and clears.
    var t         = now
    var suspended = 0
    val w         = new HibernationWiring(
      cfg,
      views = () => List(idleView),
      activityByKey = () => Map.empty,
      flushLocal = () => (),
      purge = () => (),
      suspend = _ => IO { suspended += 1; Right(()) },
      isLeader = () => true,
      now = () => t
    )
    w.sweep().unsafeRunSync()
    suspended shouldBe 0
    w.firstSeenForTest shouldBe Map(poolKey -> now)
    t = now.plusSeconds(31 * 60)
    w.sweep().unsafeRunSync()
    suspended shouldBe 1
    w.firstSeenForTest shouldBe empty

object HibernationWiringSupportSpec

class HibernationIdleForSpec extends AnyFlatSpec with Matchers:
  "idleFor" should "treat 0 as an explicit opt-out even with a manager-wide default" in:
    HibernationWiringSupport.idleFor(Some(0), HibernationConfig(defaultIdleMinutes = 30)) shouldBe
      None

  it should "floor a per-pool window at 5 minutes" in:
    HibernationWiringSupport.idleFor(Some(60), HibernationConfig()) shouldBe Some(300.seconds)

  it should "fall back to the manager-wide default when unset" in:
    HibernationWiringSupport.idleFor(None, HibernationConfig(defaultIdleMinutes = 30)) shouldBe
      Some(30.minutes)

  it should "yield no participant when unset and no default" in:
    HibernationWiringSupport.idleFor(None, HibernationConfig()) shouldBe None

  it should "floor the manager-wide default at 5 minutes" in:
    HibernationWiringSupport.idleFor(None, HibernationConfig(defaultIdleMinutes = 1)) shouldBe
      Some(5.minutes)
