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
import cats.effect.{IO, Outcome}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.Quantity

import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try

/** No server is free (or none fits) for `nodeId`. `heldBy` names the server that still holds the
  * node id's assignment (kept, since the replacement claim rolled back), None when nothing holds
  * it.
  */
final case class NoFreeServer(
    poolKey: PoolKey,
    nodeId: String,
    reason: String,
    heldBy: Option[String] = None
) extends RuntimeException(s"no fleet server for $poolKey/$nodeId ($reason)")
final case class FleetStartTimeout(server: String, nodeId: String, seconds: Int)
    extends RuntimeException(s"fleet server $server did not report $nodeId running in ${seconds}s")
final case class FleetClaimLost(server: String, nodeId: String)
    extends RuntimeException(
      s"fleet server $server no longer holds $nodeId (drained, removed or released while starting)"
    )
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

  /** Injected by Main so the backend does not depend on the whole ControlPlaneStore. Defaults to
    * "exists" so an unwired backend releases nothing in `discoverExisting` (fail-safe).
    */
  var nodeRowExists: String => Boolean = _ => true

  private def assignmentFor(spec: NodeSpec, token: String): FleetAssignment =
    FleetAssignment(
      epoch = 0L, // the store bumps and writes the real epoch
      nodeId = spec.nodeId,
      poolKey = spec.poolKey,
      port = 0, // the store stamps the claimed server's node_port
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
    spec.memory.filter(_.nonEmpty).flatMap { m =>
      val parsed = Try(Quantity.getAmountInBytes(new Quantity(m)).longValue).toOption
      if parsed.isEmpty then
        logger.warn(
          s"fleet: unparsable memory '$m' for ${spec.nodeId}; claiming without a memory fit"
        )
      parsed
    }

  def start(spec: NodeSpec): IO[RunningNode] =
    val token = LocalQuackBackend.randomToken()
    // The supervisor only starts a node id it believes has no live node, so any server still
    // holding this id is stale: a dead or drained server, a crash orphan (claimed and running, but
    // the manager died before writing the node row), or a server that came back between the
    // reconcile pass's read and this start. `claimReplacing` releases that holder and claims in
    // ONE transaction, and only when a replacement claim succeeds:
    //   - on NoFreeServer the holder keeps its assignment (owner policy), so a dead server that
    //     returns before capacity appears resumes its node without a restart;
    //   - a reachable, schedulable stale holder (a crash orphan) is re-claimed in place under a
    //     new epoch and token, and its agent restarts the node on the new assignment identity;
    //   - otherwise the holder's agent sees no assignment on its next heartbeat and stops its node.
    // Releasing first also keeps the unique assigned_node_id from refusing the claim.
    // Claim and arm the release atomically: a cancellation between the claim and the guarantee
    // would otherwise leak a claimed server. Every non-success outcome after the claim (error,
    // cancellation, failed report, timeout) releases it.
    val claimAndWait: IO[RunningNode] = IO.uncancelable { poll =>
      IO.blocking(
        store.claimReplacing(
          assignmentFor(spec, token),
          cfg.heartbeatTimeoutSec,
          requiredMemoryBytes(spec)
        )
      ).flatMap {
        case Left(miss) =>
          val reason = miss match
            case ClaimMiss.NoneFree => "none_free"
            case ClaimMiss.NoneFits => "none_fits"
          IO.blocking(store.byNodeId(spec.nodeId).map(_.name)).flatMap { holder =>
            IO.raiseError(NoFreeServer(spec.poolKey, spec.nodeId, reason, holder))
          }
        case Right(row) =>
          val waited = awaitRunning(row.name, spec.nodeId).map { _ =>
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
          poll(waited).guaranteeCase {
            case Outcome.Succeeded(_) => IO.unit
            case _                    => IO.blocking(store.release(spec.nodeId)).attempt.void
          }
      }
    }
    claimAndWait

  /** True when `row`'s node state answers the current claim of `nodeId`. Claim and release never
    * reset node_state, so a `running` or `failed` left by the previous assignment would otherwise
    * read as the current one. Only a heartbeat recorded after the claim counts; both timestamps are
    * the store's clock (Postgres now()), so HA replicas agree.
    */
  private def reportsFor(row: FleetServerRow, nodeId: String): Boolean =
    row.assignedNodeId.contains(nodeId) && row.claimedAt.exists(c => row.lastHeartbeatAt.isAfter(c))

  private def awaitRunning(server: String, nodeId: String): IO[Unit] =
    val deadline       = clock().plusSeconds(cfg.startupTimeoutSec.toLong)
    def loop: IO[Unit] =
      IO.blocking(store.get(server)).flatMap {
        case Some(row) if reportsFor(row, nodeId) && row.nodeState == "running" => IO.unit
        case Some(row) if reportsFor(row, nodeId) && row.nodeState == "failed"  =>
          IO.raiseError(FleetNodeFailed(server, nodeId, row.nodeError.getOrElse("unknown")))
        // The claim was taken away (drain, remove, a concurrent release): nothing can answer it
        // any more, so fail now instead of holding the per-pool lock to the startup deadline.
        // The caller's release on failure is then a no-op.
        case r if !r.exists(_.assignedNodeId.contains(nodeId)) =>
          IO.raiseError(FleetClaimLost(server, nodeId))
        case _ if !clock().isBefore(deadline) =>
          IO.raiseError(FleetStartTimeout(server, nodeId, cfg.startupTimeoutSec))
        case _ => IO.sleep(pollInterval) *> loop
      }
    loop

  /** Never fails: a store error is logged and swallowed, since callers tear down regardless. */
  def stop(key: PoolKey, nodeId: String): IO[Unit] =
    stopOrFail(nodeId).handleErrorWith(e =>
      IO.delay(logger.warn(s"fleet: stop of $nodeId failed: ${e.getMessage}", e))
    )

  private def stopOrFail(nodeId: String): IO[Unit] =
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
            r.assignment.map(_.nodeId)
        }
        .flatten
        .toSet
    )
  }

  /** Leader duty at boot / promotion. Nothing to adopt (node rows live in the store already); the
    * useful work is releasing claims a crashed manager left behind before the node row was written.
    *
    * Only claims that never reached `running` are released here. A `running` claim with no node row
    * is deliberately left alone, for two reasons:
    *   - ephemeral maintenance and merge nodes are claimed, run and released without ever getting a
    *     node row; releasing them here would kill a job in flight. They are safe only because of
    *     this `running` exemption.
    *   - a `running` crash orphan is owned by the reconcile's missing-slot fill: the supervisor
    *     starts the slot's node id again and `start` (through `claimReplacing`) releases the holder
    *     and claims in one transaction: a reachable orphan is re-claimed in place under a new epoch
    *     (its agent restarts the node), otherwise the orphan's agent stops its node.
    */
  def discoverExisting(): IO[List[RunningNode]] = IO.blocking {
    store.list().foreach { r =>
      r.assignedNodeId.foreach { id =>
        // Claim age comes from the store's clock, never this JVM's: a newly promoted manager
        // whose clock runs ahead would otherwise release a fresh claim another replica is still
        // starting.
        val orphan =
          r.nodeState != "running" && r.claimAgeSeconds.exists(_ > cfg.startupTimeoutSec.toLong)
        if orphan && !nodeRowExists(id) then
          logger.warn(s"fleet: releasing orphan assignment $id on ${r.name} (state=${r.nodeState})")
          store.release(id)
      }
    }
    Nil
  }

  def cleanup(): IO[Unit] = IO.unit
