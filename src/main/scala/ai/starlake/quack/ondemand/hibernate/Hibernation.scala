package ai.starlake.quack.ondemand.hibernate

import ai.starlake.quack.model.PoolKey

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** The hibernation sweep's projection of one pool. Only participants reach the decision core:
  * building the view already resolved the per-pool `idleTimeoutSec` override against the
  * manager-wide default (see `HibernationWiringSupport.idleFor`), so `idleAfter` is always the
  * effective window for THIS pool. `size` is the desired distribution total, not a live node
  * census, mirroring the autoscale views.
  */
final case class HibernationView(
    key: PoolKey,
    suspended: Boolean,
    disabled: Boolean,
    size: Int,
    idleAfter: FiniteDuration
)

/** Idle-pool hibernation: the policy half of scale-to-zero (the mechanism, suspend/resume with
  * wake-on-first-statement, lives in PoolSupervisor and the FlightSQL edge). Ported from the hosted
  * module, where it was proven in production; the signal is `qodstate_pool_activity`, a per-pool
  * last-statement timestamp flushed by every replica and bumped on both StatementExecuted and
  * PoolResumed, so a pool woken by REST gets a full idle window instead of being re-suspended on
  * its stale pre-suspend timestamp.
  */
object Hibernation:

  /** Pure decision core. Returns the pools to suspend and the updated first-seen map: running pools
    * with no activity row are baselined at `now` (so a never-queried pool still hibernates once its
    * window elapses from first sight); entries are pruned once the pool gains an activity row or
    * stops being a running candidate (suspended, disabled, stopped, deleted). The map is
    * leader-local and resets on failover, which errs conservative: one extra idle window.
    */
  def candidates(
      now: Instant,
      views: List[HibernationView],
      activity: Map[PoolKey, Instant],
      firstSeen: Map[PoolKey, Instant]
  ): (List[PoolKey], Map[PoolKey, Instant]) =
    val running     = views.filter(v => !v.suspended && !v.disabled && v.size > 0)
    val runningKeys = running.map(_.key).toSet
    val pruned      = firstSeen.filter((k, _) => runningKeys.contains(k) && !activity.contains(k))
    val updated     = running.foldLeft(pruned) { (acc, v) =>
      if activity.contains(v.key) || acc.contains(v.key) then acc else acc.updated(v.key, now)
    }
    def elapsed(since: Instant, idle: FiniteDuration): Boolean =
      now.toEpochMilli - since.toEpochMilli >= idle.toMillis
    val toSuspend = running.flatMap { v =>
      activity.get(v.key) match
        case Some(last) => Option.when(elapsed(last, v.idleAfter))(v.key)
        case None       =>
          updated.get(v.key) match
            case Some(seen) => Option.when(elapsed(seen, v.idleAfter))(v.key)
            case None       => None
    }
    (toSuspend, updated)
