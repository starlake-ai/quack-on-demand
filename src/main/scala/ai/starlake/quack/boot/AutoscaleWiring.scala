package ai.starlake.quack.boot

import ai.starlake.quack.AutoscaleConfig
import ai.starlake.quack.model.{PoolKey, Role}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.autoscale.{
  Autoscale,
  AutoscaleAction,
  AutoscaleView,
  PoolAutoState
}
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging

/** The demand scale-out loop. `flushLocal` runs on EVERY replica (each edge only sees the
  * statements it served, so its buckets must reach Postgres or the leader's window is blind to
  * them); `purge`, the decision pass and the actions are leader-only.
  *
  * Failure bookkeeping lives here so the decision core stays pure: 3 consecutive scale failures put
  * a pool into `cfg.failureBackoffSweeps` sweeps of skip (fed to `decide` as `skip`), and any
  * success resets the counter. State is leader-local and in-heap: a failover restarts streaks,
  * which delays the next action rather than doubling it.
  */
final class AutoscaleWiring(
    cfg: AutoscaleConfig,
    views: () => List[AutoscaleView],
    flushLocal: () => Unit,
    purge: () => Unit,
    scale: AutoscaleAction => IO[Either[String, Unit]],
    isLeader: () => Boolean,
    audit: AuditRecorder = AuditRecorder.noop,
    now: () => Long = () => System.currentTimeMillis()
) extends LazyLogging:

  // Only the sweep fiber mutates these, but a failover-driven read from another
  // fiber must not see a stale map, hence @volatile on the whole-map swaps.
  @volatile private var state: Map[PoolKey, PoolAutoState] = Map.empty
  @volatile private var failures: Map[PoolKey, Int]        = Map.empty
  @volatile private var backoff: Map[PoolKey, Int]         = Map.empty

  private[boot] def stateForTest: Map[PoolKey, PoolAutoState] = state
  private[boot] def backoffForTest: Map[PoolKey, Int]         = backoff

  private def keyOf(a: AutoscaleAction): PoolKey = a match
    case AutoscaleAction.ScaleOut(k, _, _) => k
    case AutoscaleAction.ScaleIn(k, _, _)  => k

  def sweep(): IO[Unit] =
    // IO.defer around the leader check: sweep() is bound to a single IO value once
    // (see `fiber`'s foreverM), so the leadership branch must be re-evaluated on every
    // run rather than baked in at construction time -- otherwise a replica that boots
    // before winning the advisory lock latches "not leader" forever.
    IO.blocking(flushLocal()) *> IO.defer {
      if !isLeader() then IO.unit
      else
        IO.blocking(purge()) *> IO.defer {
          val vs    = views()
          val byKey = vs.map(v => v.key -> v).toMap
          // Snapshot the skip set BEFORE decrementing: a pool that entered backoff with
          // n sweeps is skipped on exactly n sweeps, and the counter it just failed on
          // is not one of them.
          val skip               = backoff.keySet
          val (actions, updated) = Autoscale.decide(now(), vs, state, skip, cfg)
          state = updated
          backoff = backoff.collect { case (k, n) if n > 1 => k -> (n - 1) }
          actions.foldLeft(IO.unit) { (acc, a) =>
            // IO.blocking on the bookkeeping: noteSuccess/noteFailure write an audit
            // row synchronously (same contract as MaintenanceRunner.auditRun).
            // IO.defer around scale(a): a synchronous throw from the action (e.g. a
            // `require` inside PoolSupervisor.scale) must be deferred until this step of
            // the fold actually runs, so `.attempt` can catch it -- otherwise it escapes
            // while the fold is still being built and kills the whole sweep before
            // noteFailure ever sees it.
            acc *> IO.defer(scale(a)).attempt.flatMap { outcome =>
              IO.blocking {
                outcome match
                  case Right(Right(())) => noteSuccess(a, byKey.get(keyOf(a)))
                  case Right(Left(err)) => noteFailure(a, err)
                  case Left(t) => noteFailure(a, Option(t.getMessage).getOrElse(t.toString))
              }
            }
          }
        }
    }

  private def describe(a: AutoscaleAction, view: Option[AutoscaleView]): String =
    val (key, dir, target) = a match
      case AutoscaleAction.ScaleOut(k, t, _) => (k, "out", t)
      case AutoscaleAction.ScaleIn(k, t, _)  => (k, "in", t)
    val from = view.map(_.distribution.total).getOrElse(-1)
    val util = view.flatMap(_.utilization).map(u => f"$u%.2f").getOrElse("?")
    s"${key.tenant}/${key.tenantDb}/${key.pool} $dir $from -> $target util=$util"

  private def noteSuccess(a: AutoscaleAction, view: Option[AutoscaleView]): Unit =
    val key = keyOf(a)
    failures -= key
    logger.info(s"autoscale: ${describe(a, view)}")
    auditAction(a, view, "success", None)

  private def noteFailure(a: AutoscaleAction, err: String): Unit =
    val key = keyOf(a)
    val n   = failures.getOrElse(key, 0) + 1
    failures += key -> n
    if n >= 3 then
      backoff += key -> cfg.failureBackoffSweeps
      failures -= key
      logger.warn(
        s"autoscale: $key failed $n times ($err); " +
          s"backing off ${cfg.failureBackoffSweeps} sweeps"
      )
    else logger.warn(s"autoscale: scale failed for $key, will retry next sweep: $err")
    auditAction(a, None, "failure", Some(err))

  private def auditAction(
      a: AutoscaleAction,
      view: Option[AutoscaleView],
      outcome: String,
      error: Option[String]
  ): Unit =
    val (key, dir, target) = a match
      case AutoscaleAction.ScaleOut(k, t, _) => (k, "out", t)
      case AutoscaleAction.ScaleIn(k, t, _)  => (k, "in", t)
    audit.restAs(
      actor = "autoscale",
      actorRealm = "system",
      family = "control-plane",
      action = AuditActions.PoolScale,
      outcome = outcome,
      tenant = Some(key.tenant),
      target = Some(s"${key.tenantDb}/${key.pool}"),
      detail = Map(
        "trigger"    -> "autoscale",
        "direction"  -> dir,
        "targetSize" -> target.toString
      )
        ++ view.flatMap(_.utilization).map(u => "utilization" -> f"$u%.3f")
        ++ error.map("error" -> _)
    )

  /** One sweep every `cfg.sweepInterval` (30s floor). `enabled = false` starts no loop at all: the
    * sweep is the sole drainer of the edge's load buckets, so Main must not feed
    * `PoolLoadStats.sink` when this returns a no-op fiber (see the events wiring there). A pool
    * with no band is not a participant, and a pool with `minNodes == maxNodes` is held at that
    * size.
    */
  def fiber: IO[FiberIO[Unit]] =
    if !cfg.enabled then IO.unit.start
    else
      (sweep().handleErrorWith(t =>
        IO.delay(logger.warn(s"autoscale sweep: pass failed, continuing: ${t.getMessage}"))
      ) *> IO.sleep(cfg.sweepInterval)).foreverM.void.start

object AutoscaleWiringSupport:

  /** Build the sweep's views: pools with an owner-declared band only (no band = fixed size = not a
    * participant). Utilization is the Little's-law concurrency estimate over the durable window
    * (sum of statement durations / window length) divided by capacity, where capacity is the sum of
    * `maxConcurrent` over the pool's routable read-capable nodes (`maxConcurrent = 0` meaning
    * unlimited contributes `cfg.assumedConcurrencyPerNode`). Capacity <= 0 (no routable reader, or
    * an assumedConcurrencyPerNode of 0) yields None: no decision is safe without known capacity.
    *
    * Suspended pools are included on purpose. The decision core needs to see the suspended -> live
    * transition to arm its post-resume cooldown; filtering them here would make that branch dead
    * code and let a wake burst scale the pool before its baseline nodes have warmed.
    *
    * `distribution` is the pool's DESIRED distribution (what scale/reconcile maintain), never a
    * live node census: deciding off a census would compound a transient shortfall into a scale-out.
    */
  def views(
      sup: PoolSupervisor,
      store: ControlPlaneStore,
      cfg: AutoscaleConfig
  ): List[AutoscaleView] =
    val from   = java.time.Instant.now().minusSeconds(cfg.windowMinutes * 60L)
    val window = store.poolLoadWindow(from)
    sup.list().flatMap { st =>
      sup.autoscaleBand(st.key).flatMap { case (mn, mx) =>
        sup.poolId(st.key).map { id =>
          val snap     = sup.snapshot(st.key)
          val capacity = snap.fold(0) { s =>
            s.nodes.collect {
              case n if n.role != Role.WriteOnly && s.loadOf(n.nodeId).routable =>
                if n.maxConcurrent > 0 then n.maxConcurrent else cfg.assumedConcurrencyPerNode
            }.sum
          }
          val est  = window.get(id).map(_._2.toDouble / (cfg.windowMinutes * 60_000.0))
          val util =
            if capacity <= 0 then None else Some(est.getOrElse(0.0) / capacity.toDouble)
          AutoscaleView(st.key, mn, mx, st.distribution, st.suspended, st.disabled, util)
        }
      }
    }
