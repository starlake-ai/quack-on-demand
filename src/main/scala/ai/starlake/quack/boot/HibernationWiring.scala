package ai.starlake.quack.boot

import ai.starlake.quack.HibernationConfig
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.hibernate.{Hibernation, HibernationView}
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.{FiberIO, IO}
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant

/** The idle-pool hibernation loop, mirroring AutoscaleWiring's split of duties: `flushLocal` runs
  * on EVERY replica (each edge only sees the statements it served, so its activity timestamps must
  * reach Postgres or the leader's view is blind to them); the purge, the decision pass and the
  * suspends are leader-only. The first-seen baseline for never-active pools is leader-local and
  * in-heap: a failover resets it, which delays a suspend by one idle window rather than doubling
  * it.
  */
final class HibernationWiring(
    cfg: HibernationConfig,
    views: () => List[HibernationView],
    activityByKey: () => Map[PoolKey, Instant],
    flushLocal: () => Unit,
    purge: () => Unit,
    suspend: PoolKey => IO[Either[String, Unit]],
    isLeader: () => Boolean,
    audit: AuditRecorder = AuditRecorder.noop,
    now: () => Instant = () => Instant.now()
) extends LazyLogging:

  @volatile private var firstSeen: Map[PoolKey, Instant] = Map.empty

  private[boot] def firstSeenForTest: Map[PoolKey, Instant] = firstSeen

  def sweep(): IO[Unit] =
    // IO.defer around the leader check for the same reason as AutoscaleWiring: sweep()
    // is bound once into foreverM, so leadership must be re-evaluated on every run.
    IO.blocking(flushLocal()) *> IO.defer {
      if !isLeader() then IO.unit
      else
        IO.blocking(purge()) *> IO.defer {
          val vs                   = views()
          val activity             = activityByKey()
          val sweptAt              = now()
          val (toSuspend, updated) =
            Hibernation.candidates(sweptAt, vs, activity, firstSeen)
          toSuspend
            .foldLeft(IO.pure(Set.empty[PoolKey])) { (acc, key) =>
              acc.flatMap { succeeded =>
                suspend(key).attempt.map {
                  case Right(Right(())) =>
                    val idleFor = activity
                      .get(key)
                      .map(last => s"${(sweptAt.toEpochMilli - last.toEpochMilli) / 60000}m idle")
                      .getOrElse("never active")
                    logger.info(s"hibernation: suspended $key ($idleFor)")
                    auditSuspend(key, idleFor, outcome = "success", error = None)
                    succeeded + key
                  case Right(Left(err)) =>
                    if err.contains("not found") then
                      logger.debug(s"hibernation: $key vanished before suspend: $err")
                    else
                      logger.warn(
                        s"hibernation: suspend failed for $key, will retry next sweep: $err"
                      )
                      auditSuspend(key, "", outcome = "error", error = Some(err))
                    succeeded
                  case Left(t) =>
                    logger.warn(
                      s"hibernation: suspend threw for $key, will retry next sweep: ${t.getMessage}"
                    )
                    succeeded
                }
              }
            }
            .flatMap { succeeded =>
              // A suspended pool is no longer a running candidate; dropping it here (rather
              // than waiting for the next sweep's prune) keeps the baseline map exact.
              firstSeen = updated -- succeeded
              IO.whenA(toSuspend.nonEmpty)(
                IO.delay(
                  logger.info(
                    s"hibernation sweep: examined=${vs.size} suspended=${succeeded.size} " +
                      s"failed=${toSuspend.size - succeeded.size}"
                  )
                )
              )
            }
        }
    }

  private def auditSuspend(
      key: PoolKey,
      idleFor: String,
      outcome: String,
      error: Option[String]
  ): Unit =
    audit.restAs(
      actor = "hibernation",
      actorRealm = "system",
      family = "control-plane",
      action = AuditActions.PoolSuspend,
      outcome = outcome,
      tenant = Some(key.tenant),
      target = Some(s"${key.tenantDb}/${key.pool}"),
      detail = Map("trigger" -> "idle")
        ++ Option.when(idleFor.nonEmpty)("idleFor" -> idleFor)
        ++ error.map("error" -> _)
    )

  /** One sweep every `cfg.sweepInterval` (60s floor). `enabled = false` starts no loop at all: the
    * sweep's flushLocal is the sole drainer of the edge's activity map, so Main must not feed
    * `PoolActivity.sink` when this returns a no-op fiber (see the events wiring there). With no
    * manager-wide default and no per-pool `idleTimeoutSec`, the sweep examines nothing and is inert
    * on a fresh install, like autoscale without a band.
    */
  def fiber: IO[FiberIO[Unit]] =
    if !cfg.enabled then IO.unit.start
    else
      (sweep().handleErrorWith(t =>
        IO.delay(logger.warn(s"hibernation sweep: pass failed, continuing: ${t.getMessage}"))
      ) *> IO.sleep(cfg.sweepInterval)).foreverM.void.start

object HibernationWiringSupport:

  /** Effective idle window for one pool: `idleTimeoutSec = 0` is an explicit opt-out that beats the
    * manager-wide default, a positive value participates (floored at 5 minutes, since activity
    * flushes lag by up to one sweep interval), and an unset value falls back to
    * `defaultIdleMinutes` (0 = no default, per-pool opt-in only).
    */
  def idleFor(
      idleTimeoutSec: Option[Int],
      cfg: HibernationConfig
  ): Option[scala.concurrent.duration.FiniteDuration] =
    import scala.concurrent.duration.DurationInt
    idleTimeoutSec match
      case Some(s) if s <= 0 => None
      case Some(s)           => Some(math.max(300, s).seconds)
      case None              => cfg.defaultIdle

  /** Build the sweep's views: participants only (a pool with no effective idle window is not
    * examined). Suspended and disabled pools are filtered by the decision core, not here, so the
    * running-candidate prune of the first-seen map stays in one place.
    */
  def views(sup: PoolSupervisor, cfg: HibernationConfig): List[HibernationView] =
    sup.list().flatMap { st =>
      idleFor(sup.idleTimeoutSec(st.key), cfg).map { idle =>
        HibernationView(st.key, st.suspended, st.disabled, st.distribution.total, idle)
      }
    }

  /** The durable activity rows, translated from pool ids back to keys via the supervisor. */
  def activityByKey(sup: PoolSupervisor, store: ControlPlaneStore): Map[PoolKey, Instant] =
    val byId = store.poolActivity()
    sup
      .list()
      .flatMap(st => sup.poolId(st.key).flatMap(byId.get).map(st.key -> _))
      .toMap
