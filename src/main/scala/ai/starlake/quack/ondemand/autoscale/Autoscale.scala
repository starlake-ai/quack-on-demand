package ai.starlake.quack.ondemand.autoscale

import ai.starlake.quack.AutoscaleConfig
import ai.starlake.quack.model.{PoolKey, RoleDistribution}

/** The sweep's projection of one elastic pool. utilization is Little's-law estimated concurrency /
  * capacity, None when capacity is unknown (no routable read-capable node): no decision is safe
  * then.
  */
final case class AutoscaleView(
    key: PoolKey,
    minNodes: Int,
    maxNodes: Int,
    distribution: RoleDistribution,
    suspended: Boolean,
    disabled: Boolean,
    utilization: Option[Double]
)

/** Leader-local per-pool decision state. Reset on failover errs conservative: streaks restart, so
  * the next action is delayed, never doubled.
  */
final case class PoolAutoState(
    outStreak: Int = 0,
    inStreak: Int = 0,
    lastActionAtMs: Option[Long] = None,
    wasSuspended: Boolean = false
)

enum AutoscaleAction:
  case ScaleOut(key: PoolKey, targetSize: Int, dist: RoleDistribution)
  case ScaleIn(key: PoolKey, targetSize: Int, dist: RoleDistribution)

object Autoscale:

  /** Pure decision core: snapshot in, actions + updated state out. Only the readonly count ever
    * changes; writers are invariant by construction. Four hysteresis layers: watermark gap,
    * streaks, cooldowns, step of 1.
    *
    * Invariant: scale-in never removes the last read-capable node, and never fires when there is no
    * readonly node to remove (scale-in only ever decrements readonly, never dual). A pool needs
    * readonly >= 1 AND readonly + dual > 1 to be scaled in -- an all-dual pool (readonly == 0) or a
    * pool with exactly one remaining reader is never scaled in, even below minNodes-derived floors,
    * because dropping it leaves no node able to serve SELECTs -- utilization then reads None
    * (unknown capacity) and the pool can never recover on its own.
    */
  def decide(
      nowMs: Long,
      views: List[AutoscaleView],
      state: Map[PoolKey, PoolAutoState],
      skip: Set[PoolKey],
      cfg: AutoscaleConfig
  ): (List[AutoscaleAction], Map[PoolKey, PoolAutoState]) =
    val actions   = List.newBuilder[AutoscaleAction]
    val nextState = views.map { v =>
      val prev = state.getOrElse(v.key, PoolAutoState())
      val next =
        if v.suspended then prev.copy(outStreak = 0, inStreak = 0, wasSuspended = true)
        else if prev.wasSuspended then
          // Resume transition: start a cooldown so the wake burst cannot grow
          // the pool before its baseline nodes have warmed.
          PoolAutoState(lastActionAtMs = Some(nowMs))
        else if v.disabled || skip.contains(v.key) then prev.copy(outStreak = 0, inStreak = 0)
        else
          // No sentinel default: unknown capacity (no routable read-capable node) is handled
          // explicitly by the None arm, not folded into the watermark math below.
          v.utilization match
            case None       => prev.copy(outStreak = 0, inStreak = 0)
            case Some(util) =>
              val size                              = v.distribution.total
              def cooled(cooldownSec: Int): Boolean =
                prev.lastActionAtMs.forall(t => nowMs - t >= cooldownSec * 1000L)
              // Scale-in only ever decrements readonly (never dual), so it needs at least
              // one readonly node to remove; and at least one read-capable node
              // (readonly + dual) must survive the decrement, or every SELECT starts
              // failing with no routable reader left to recover capacity visibility from.
              val canScaleIn =
                v.distribution.readonly > 0 && v.distribution.readonly + v.distribution.dual > 1
              if util >= cfg.highWatermark && size < v.maxNodes then
                val streak = prev.outStreak + 1
                if streak >= cfg.outStreak && cooled(cfg.scaleOutCooldownSec) then
                  actions += AutoscaleAction.ScaleOut(
                    v.key,
                    size + 1,
                    v.distribution.copy(readonly = v.distribution.readonly + 1)
                  )
                  PoolAutoState(lastActionAtMs = Some(nowMs))
                else prev.copy(outStreak = streak, inStreak = 0)
              else if util <= cfg.lowWatermark && size > v.minNodes && canScaleIn then
                val streak = prev.inStreak + 1
                if streak >= cfg.inStreak && cooled(cfg.scaleInCooldownSec) then
                  actions += AutoscaleAction.ScaleIn(
                    v.key,
                    size - 1,
                    v.distribution.copy(readonly = v.distribution.readonly - 1)
                  )
                  PoolAutoState(lastActionAtMs = Some(nowMs))
                else prev.copy(inStreak = streak, outStreak = 0)
              else prev.copy(outStreak = 0, inStreak = 0)
      v.key -> next
    }.toMap
    (actions.result(), nextState)
