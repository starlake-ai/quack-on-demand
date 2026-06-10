package ai.starlake.quack.edge.adapter

import ai.starlake.quack.model.RunningNode
import cats.effect.{Fiber, IO}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.FiniteDuration

final class HealthProbe(
    tracker: NodeLoadTracker,
    pingFn: RunningNode => IO[Boolean],
    interval: FiniteDuration
) extends LazyLogging:

  /** Starts a background fiber that, every `interval`, queries `nodes()` and pings each, pushing
    * the result into the tracker. Errors from `pingFn` are caught so a single bad node doesn't kill
    * the loop. Returns the fiber so callers can cancel during shutdown / scale-down.
    */
  def start(nodes: () => List[RunningNode]): IO[Fiber[IO, Throwable, Unit]] =
    val tick: IO[Unit] =
      IO.delay(nodes()).flatMap { ns =>
        logger.debug(s"HealthProbe tick: ${ns.size} nodes")
        ns.foldLeft(IO.unit) { (acc, n) =>
          acc *> pingFn(n).attempt.map {
            case Right(ok) =>
              logger.debug(s"HealthProbe ${n.nodeId} -> healthy=$ok")
              tracker.setHealthy(n.nodeId, ok)
            case Left(t) =>
              logger.warn(s"HealthProbe ${n.nodeId} pingFn threw: ${t.getMessage}")
              tracker.setHealthy(n.nodeId, false)
          }
        }
      } *> IO.sleep(interval)
    tick.foreverM.void.start
