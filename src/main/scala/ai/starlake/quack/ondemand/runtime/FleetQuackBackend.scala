package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.model.{NodeSpec, PoolKey, RunningNode}
import ai.starlake.quack.ondemand.fleet.{FleetLiveness, NodeResourceSql, ServerLiveness}
import ai.starlake.quack.ondemand.state.{
  ClaimMiss,
  FleetAssignment,
  FleetServerRow,
  FleetServerStore
}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.Quantity

import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try

final case class NoFreeServer(poolKey: PoolKey, nodeId: String, reason: String)
    extends RuntimeException(s"no fleet server for $poolKey/$nodeId ($reason)")
final case class FleetStartTimeout(server: String, nodeId: String, seconds: Int)
    extends RuntimeException(s"fleet server $server did not report $nodeId running in ${seconds}s")
final case class FleetNodeFailed(server: String, nodeId: String, error: String)
    extends RuntimeException(s"fleet server $server reports $nodeId failed: $error")

/** Quack nodes on a dynamically joining fleet of servers, one node per server. Servers are rows in
  * qodstate_fleet_server kept fresh by `qod agent` heartbeats in qodstate_fleet_heartbeat; this
  * backend only claims and releases assignments and waits for the agent's report. Liveness always
  * comes from the store's clock (`FleetServerRow.silentSeconds`), so HA replicas agree; the
  * injected `clock` is only for this JVM's own deadlines. Design:
  * docs/superpowers/specs/2026-09-25-fleet-backend-design.md.
  */
final class FleetQuackBackend(
    store: FleetServerStore,
    cfg: FleetConfig,
    clock: () => Instant = () => Instant.now(),
    pollInterval: FiniteDuration = 1.second
) extends QuackBackend
    with LazyLogging:

  def livenessOf(row: FleetServerRow): ServerLiveness =
    FleetLiveness.classify(row.silentSeconds, cfg.heartbeatTimeoutSec, cfg.reassignAfterSec)

  /** Injected by Main so the backend does not depend on the whole ControlPlaneStore. */
  var nodeRowExists: String => Boolean = _ => false

  private def assignmentFor(spec: NodeSpec, token: String): FleetAssignment =
    FleetAssignment(
      epoch = 0L, // the store bumps and writes the real epoch
      nodeId = spec.nodeId,
      poolKey = spec.poolKey,
      port = 0, // stamped from the claimed row below
      token = token,
      kind = spec.kindWire,
      env = spec.metastore,
      // Pool cpu/memory as DuckDB SETs, ahead of the tenant-db's own dbInitSql so an explicit
      // operator SET there still wins (later SET overrides).
      dbInitSql = NodeResourceSql.render(spec.cpu, spec.memory) + spec.dbInitSql,
      objectStoreSql = spec.objectStoreSql,
      extraSetupSql = spec.extraSetupSql,
      lockdownSql = spec.lockdownSql
    )

  private def requiredMemoryBytes(spec: NodeSpec): Option[Long] =
    spec.memory
      .filter(_.nonEmpty)
      .flatMap(m => Try(Quantity.getAmountInBytes(new Quantity(m)).longValue).toOption)

  def start(spec: NodeSpec): IO[RunningNode] =
    val token = LocalQuackBackend.randomToken()
    // A stale holder of the same node id (a server past the grace window, or drained) must be
    // released first, otherwise the unique assigned_node_id refuses the new claim.
    val releaseStale: IO[Unit] = IO.blocking {
      store.byNodeId(spec.nodeId).foreach { row =>
        if livenessOf(row) == ServerLiveness.Dead || row.unschedulable then
          logger.warn(s"fleet: releasing stale assignment of ${spec.nodeId} on ${row.name}")
          store.release(spec.nodeId)
      }
    }
    releaseStale *>
      IO.blocking(
        store.claim(assignmentFor(spec, token), cfg.heartbeatTimeoutSec, requiredMemoryBytes(spec))
      ).flatMap {
        case Left(ClaimMiss.NoneFree) =>
          IO.raiseError(NoFreeServer(spec.poolKey, spec.nodeId, "none_free"))
        case Left(ClaimMiss.NoneFits) =>
          IO.raiseError(NoFreeServer(spec.poolKey, spec.nodeId, "none_fits"))
        case Right(row) =>
          // The claim wrote port=0; stamp the server's own node_port so the agent never has to
          // remember what it advertised.
          val withPort = row.assignment.get.copy(port = row.nodePort)
          IO.blocking(store.setAssignment(row.name, withPort)) *>
            awaitRunning(row.name, spec.nodeId).map { _ =>
              RunningNode(
                nodeId = spec.nodeId,
                poolKey = spec.poolKey,
                role = spec.role,
                host = row.advertiseHost,
                port = row.nodePort,
                token = token,
                pid = None,
                podName = None,
                startedAt = clock(),
                maxConcurrent = spec.maxConcurrent,
                serverName = Some(row.name)
              )
            }
      }

  private def awaitRunning(server: String, nodeId: String): IO[Unit] =
    val deadline       = clock().plusSeconds(cfg.startupTimeoutSec.toLong)
    def loop: IO[Unit] =
      IO.blocking(store.get(server)).flatMap {
        case Some(row) if row.assignedNodeId.contains(nodeId) && row.nodeState == "running" =>
          IO.unit // a report from a previous epoch reads as "stale", never as "running"
        case Some(row) if row.assignedNodeId.contains(nodeId) && row.nodeState == "failed" =>
          IO.blocking(store.release(nodeId)) *>
            IO.raiseError(FleetNodeFailed(server, nodeId, row.nodeError.getOrElse("unknown")))
        case _ if !clock().isBefore(deadline) =>
          IO.blocking(store.release(nodeId)) *>
            IO.raiseError(FleetStartTimeout(server, nodeId, cfg.startupTimeoutSec))
        case _ => IO.sleep(pollInterval) *> loop
      }
    loop

  def stop(key: PoolKey, nodeId: String): IO[Unit] =
    IO.blocking(store.release(nodeId)).flatMap {
      case None         => IO.unit
      case Some(server) =>
        val deadline       = clock().plusSeconds(cfg.stopTimeoutSec.toLong)
        def loop: IO[Unit] =
          IO.blocking(store.get(server)).flatMap {
            case Some(row) if row.nodeState == "stopped" || row.nodeState == "none" => IO.unit
            case Some(row) if livenessOf(row) != ServerLiveness.Reachable           => IO.unit
            case _ if !clock().isBefore(deadline)                                   =>
              IO.delay(
                logger
                  .warn(s"fleet: $server did not confirm stop of $nodeId in ${cfg.stopTimeoutSec}s")
              )
            case _ => IO.sleep(pollInterval) *> loop
          }
        loop
    }

  def isAlive(nodeId: String): Boolean =
    store
      .byNodeId(nodeId)
      .exists(r => livenessOf(r) == ServerLiveness.Reachable && r.nodeState == "running")

  /** Filtered on the pool key inside the assignment only; node-id prefixes are ambiguous. */
  override def liveNodeIds(key: PoolKey): IO[Option[Set[String]]] = IO.blocking {
    Some(
      store
        .list()
        .collect {
          case r if r.assignment.exists(_.poolKey == key) && livenessOf(r) != ServerLiveness.Dead =>
            r.assignedNodeId.get
        }
        .toSet
    )
  }

  /** Leader duty at boot / promotion. Nothing to adopt (node rows live in the store already); the
    * useful work is releasing claims a crashed manager left behind before the node row was written.
    */
  def discoverExisting(): IO[List[RunningNode]] = IO.blocking {
    val cutoff = clock().minusSeconds(cfg.startupTimeoutSec.toLong)
    store.list().foreach { r =>
      r.assignedNodeId.foreach { id =>
        val orphan = r.nodeState != "running" && r.claimedAt.exists(_.isBefore(cutoff))
        if orphan && !nodeRowExists(id) then
          logger.warn(s"fleet: releasing orphan assignment $id on ${r.name} (state=${r.nodeState})")
          store.release(id)
      }
    }
    Nil
  }

  def cleanup(): IO[Unit] = IO.unit
