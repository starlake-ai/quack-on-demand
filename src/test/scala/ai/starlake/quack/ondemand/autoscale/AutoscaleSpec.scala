package ai.starlake.quack.ondemand.autoscale

import ai.starlake.quack.AutoscaleConfig
import ai.starlake.quack.model.{PoolKey, RoleDistribution}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AutoscaleSpec extends AnyFlatSpec with Matchers:
  private val key: PoolKey = PoolKey("t", "db", "p")
  private val cfg          = AutoscaleConfig() // outStreak 2, inStreak 10, cooldowns 180/600s
  private def view(
      util: Option[Double],
      dist: RoleDistribution = RoleDistribution(0, 1, 0),
      min: Int = 1,
      max: Int = 3,
      suspended: Boolean = false,
      disabled: Boolean = false
  ) = AutoscaleView(key, min, max, dist, suspended, disabled, util)
  private val now = 1_000_000L

  "decide" should "not act on the first hot sweep (streak below outStreak)" in:
    val (actions, st) = Autoscale.decide(now, List(view(Some(0.9))), Map.empty, Set.empty, cfg)
    actions shouldBe Nil
    st(key).outStreak shouldBe 1

  it should "add one reader after outStreak hot sweeps" in:
    val warm          = Map(key -> PoolAutoState(outStreak = 1))
    val (actions, st) = Autoscale.decide(now, List(view(Some(0.9))), warm, Set.empty, cfg)
    actions shouldBe List(AutoscaleAction.ScaleOut(key, 2, RoleDistribution(0, 2, 0)))
    st(key).outStreak shouldBe 0
    st(key).lastActionAtMs shouldBe Some(now)

  it should "respect the ceiling" in:
    val warm         = Map(key -> PoolAutoState(outStreak = 1))
    val (actions, _) =
      Autoscale.decide(now, List(view(Some(0.9), RoleDistribution(0, 3, 0))), warm, Set.empty, cfg)
    actions shouldBe Nil

  it should "respect the scale-out cooldown" in:
    val recent       = Map(key -> PoolAutoState(outStreak = 1, lastActionAtMs = Some(now - 1_000)))
    val (actions, _) = Autoscale.decide(now, List(view(Some(0.9))), recent, Set.empty, cfg)
    actions shouldBe Nil

  it should "scale out once the cooldown has elapsed" in:
    val old = Map(key -> PoolAutoState(outStreak = 1, lastActionAtMs = Some(now - 181_000)))
    val (actions, _) = Autoscale.decide(now, List(view(Some(0.9))), old, Set.empty, cfg)
    actions should have size 1

  it should "remove one reader after inStreak quiet sweeps, never below the floor" in:
    val quiet        = Map(key -> PoolAutoState(inStreak = 9))
    val (actions, _) = Autoscale.decide(
      now,
      List(view(Some(0.1), RoleDistribution(0, 2, 0))),
      quiet,
      Set.empty,
      cfg
    )
    actions shouldBe List(AutoscaleAction.ScaleIn(key, 1, RoleDistribution(0, 1, 0)))
    // at the floor: no action even with a full streak
    val (atFloor, _) =
      Autoscale.decide(now, List(view(Some(0.1))), quiet, Set.empty, cfg)
    atFloor shouldBe Nil

  it should "never strip the last read-capable node on scale-in" in:
    val dist         = RoleDistribution(writeonly = 1, readonly = 1, dual = 0)
    val full         = Map(key -> PoolAutoState(inStreak = 9))
    val (actions, _) =
      Autoscale.decide(now, List(view(Some(0.1), dist, min = 1, max = 4)), full, Set.empty, cfg)
    actions shouldBe Nil // last reader protected

  it should "never scale in a writer-only pool" in:
    val dist         = RoleDistribution(writeonly = 2, readonly = 0, dual = 0)
    val full         = Map(key -> PoolAutoState(inStreak = 9))
    val (actions, _) =
      Autoscale.decide(now, List(view(Some(0.1), dist, min = 1, max = 4)), full, Set.empty, cfg)
    actions shouldBe Nil

  it should "never touch writers" in:
    val dist         = RoleDistribution(writeonly = 1, readonly = 1, dual = 1)
    val quiet        = Map(key -> PoolAutoState(inStreak = 9))
    val (actions, _) =
      Autoscale.decide(now, List(view(Some(0.1), dist, min = 3, max = 5)), quiet, Set.empty, cfg)
    actions shouldBe Nil // size 3 == floor 3; and the floor >= writeonly + dual by validation

  it should "reset streaks in the hysteresis dead band" in:
    val warm          = Map(key -> PoolAutoState(outStreak = 1, inStreak = 5))
    val (actions, st) = Autoscale.decide(now, List(view(Some(0.5))), warm, Set.empty, cfg)
    actions shouldBe Nil
    st(key).outStreak shouldBe 0
    st(key).inStreak shouldBe 0

  it should "treat a resume transition as a cooldown-starting action" in:
    val (a1, s1) =
      Autoscale.decide(now, List(view(Some(0.9), suspended = true)), Map.empty, Set.empty, cfg)
    a1 shouldBe Nil
    s1(key).wasSuspended shouldBe true
    val (a2, s2) = Autoscale.decide(now + 1_000, List(view(Some(0.9))), s1, Set.empty, cfg)
    a2 shouldBe Nil // resume cooldown just started
    s2(key).wasSuspended shouldBe false
    s2(key).lastActionAtMs shouldBe Some(now + 1_000)

  it should "skip pools in failure backoff, disabled pools and unknown-capacity pools" in:
    val warm = Map(key -> PoolAutoState(outStreak = 1))
    Autoscale.decide(now, List(view(Some(0.9))), warm, Set(key), cfg)._1 shouldBe Nil
    Autoscale
      .decide(now, List(view(Some(0.9), disabled = true)), warm, Set.empty, cfg)
      ._1 shouldBe Nil
    Autoscale.decide(now, List(view(None)), warm, Set.empty, cfg)._1 shouldBe Nil

  it should "never scale in an all-dual pool below one node (no negative readonly)" in:
    val dist         = RoleDistribution(writeonly = 0, readonly = 0, dual = 2)
    val full         = Map(key -> PoolAutoState(inStreak = 9))
    val (actions, _) =
      Autoscale.decide(now, List(view(Some(0.1), dist, min = 1, max = 2)), full, Set.empty, cfg)
    actions shouldBe Nil

  it should "still scale in a mixed pool with a spare dual node" in:
    val dist         = RoleDistribution(writeonly = 0, readonly = 1, dual = 1)
    val full         = Map(key -> PoolAutoState(inStreak = 9))
    val (actions, _) =
      Autoscale.decide(now, List(view(Some(0.1), dist, min = 1, max = 2)), full, Set.empty, cfg)
    actions shouldBe List(AutoscaleAction.ScaleIn(key, 1, RoleDistribution(0, 0, 1)))

  it should "never scale a pinned band (minNodes == maxNodes) in either direction" in:
    val dist   = RoleDistribution(writeonly = 0, readonly = 2, dual = 0)
    val pinned = view(Some(0.9), dist, min = 2, max = 2)
    val hot    = Map(key -> PoolAutoState(outStreak = 1))
    Autoscale.decide(now, List(pinned), hot, Set.empty, cfg)._1 shouldBe Nil
    val quiet = Map(key -> PoolAutoState(inStreak = 9))
    Autoscale
      .decide(now, List(view(Some(0.1), dist, min = 2, max = 2)), quiet, Set.empty, cfg)
      ._1 shouldBe Nil

  it should "prune state for pools that vanished" in:
    val (_, st) =
      Autoscale.decide(now, Nil, Map(key -> PoolAutoState(outStreak = 1)), Set.empty, cfg)
    st shouldBe Map.empty
