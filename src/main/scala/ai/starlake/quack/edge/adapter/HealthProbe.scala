package ai.starlake.quack.edge.adapter

import ai.starlake.quack.model.RunningNode
import ai.starlake.quack.route.NodeUnhealthy
import cats.effect.{Fiber, IO}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.FiniteDuration

/** `afterTick` receives, after every tick, the ids of the nodes that tick listed (and pinged): the
  * owner prunes its per-node maps to them ([[EngineStatsTracker.retain]],
  * [[SchemaInitFlags.retain]]). The probe is the only writer of those maps and writes them for
  * listed nodes only, so pruning to its own list cannot race a write, and it runs on every replica,
  * so a node removed by a peer leaves them too. Required: a probe whose maps are never pruned keeps
  * every node it ever saw.
  */
final class HealthProbe(
    tracker: NodeLoadTracker,
    pingFn: RunningNode => IO[ProbeVerdict],
    // Whether every node the backend starts carries a pid (QuackBackend.recordsPids): a failing node
    // whose row has none is then another runtime's leftover, and says so (NodeUnhealthy.leftover).
    recordsPids: Boolean,
    interval: FiniteDuration,
    afterTick: Set[String] => Unit,
    onHealthy: RunningNode => IO[Unit] = _ => IO.unit
) extends LazyLogging:

  /** Starts a background fiber that, every `interval`, queries `nodes()` and pings each, pushing
    * the result into the tracker: healthy, or unhealthy with why ([[ProbeVerdict.cause]]), and the
    * time of the answer. A node that pings healthy then gets `onHealthy` (e.g. the DuckDB
    * engine-stats scrape); its errors are logged and never affect the health flag. Errors from
    * `pingFn` are caught so a single bad node doesn't kill the loop, and so are `afterTick`'s.
    * Returns the fiber so callers can cancel during shutdown / scale-down.
    */
  def start(nodes: () => List[RunningNode]): IO[Fiber[IO, Throwable, Unit]] =
    val tick: IO[Unit] =
      IO.delay(nodes()).flatMap { ns =>
        logger.debug(s"HealthProbe tick: ${ns.size} nodes")
        ns.foldLeft(IO.unit) { (acc, n) =>
          acc *> pingFn(n).attempt.flatMap {
            case Right(verdict) =>
              val ok  = verdict.healthy
              val now = System.currentTimeMillis()
              logger.debug(s"HealthProbe ${n.nodeId} -> healthy=$ok")
              tracker.setProbedAt(n.nodeId, now)
              ProbeVerdict
                .cause(n, verdict)
                .map(c => NodeUnhealthy.leftover(n, recordsPids).getOrElse(c)) match
                case Some(cause) => tracker.setUnhealthy(n.nodeId, cause, now)
                case None        => tracker.setHealthy(n.nodeId, true)
              if ok then
                onHealthy(n).attempt.map {
                  case Left(t) =>
                    logger.debug(s"HealthProbe ${n.nodeId} onHealthy threw: ${t.getMessage}")
                  case Right(_) => ()
                }
              else IO.unit
            case Left(t) =>
              IO.delay {
                logger.warn(s"HealthProbe ${n.nodeId} pingFn threw: ${t.getMessage}")
                val now = System.currentTimeMillis()
                tracker.setProbedAt(n.nodeId, now)
                tracker.setUnhealthy(
                  n.nodeId,
                  NodeUnhealthy.leftover(n, recordsPids).getOrElse(NodeUnhealthy.ProbeError),
                  now
                )
              }
          }
        } *> IO.delay(afterTick(ns.iterator.map(_.nodeId).toSet)).attempt.map {
          case Left(t)  => logger.warn(s"HealthProbe afterTick threw: ${t.getMessage}")
          case Right(_) => ()
        }
      } *> IO.sleep(interval)
    tick.foreverM.void.start
