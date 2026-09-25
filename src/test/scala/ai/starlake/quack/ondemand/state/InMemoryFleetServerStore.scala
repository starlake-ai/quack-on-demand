package ai.starlake.quack.ondemand.state

import java.time.{Duration, Instant}
import scala.collection.mutable

final class InMemoryFleetServerStore(clock: () => Instant = () => Instant.now())
    extends FleetServerStore:
  private val rows = mutable.LinkedHashMap.empty[String, FleetServerRow]

  private def withSilent(r: FleetServerRow): FleetServerRow =
    r.copy(silentSeconds = Duration.between(r.lastHeartbeatAt, clock()).getSeconds)

  /** Test hook: move a server's heartbeat (and join) `seconds` into the past. */
  def backdate(name: String, seconds: Long): Unit = synchronized {
    rows
      .get(name)
      .foreach(r =>
        rows.put(
          name,
          r.copy(
            lastHeartbeatAt = r.lastHeartbeatAt.minusSeconds(seconds),
            joinedAt = r.joinedAt.minusSeconds(seconds)
          )
        )
      )
  }

  def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome = synchronized {
    val now = clock()
    rows.get(hb.name) match
      case None =>
        rows.put(
          hb.name,
          FleetServerRow(
            hb.name,
            hb.advertiseHost,
            hb.nodePort,
            now,
            unschedulable = false,
            None,
            None,
            0L,
            None,
            now,
            0L,
            hb.agentVersion,
            hb.os,
            hb.duckdbVersion,
            hb.cpus,
            hb.memoryBytes,
            FleetServerStore.effectiveState(hb.node, 0L),
            hb.node.error,
            hb.node.pid,
            hb.node.startedAt
          )
        )
        HeartbeatOutcome.Joined
      case Some(r)
          if !r.unschedulable && (r.advertiseHost != hb.advertiseHost || r.nodePort != hb.nodePort) =>
        HeartbeatOutcome.AddressChangeRefused
      case Some(r) =>
        rows.put(
          hb.name,
          r.copy(
            advertiseHost = hb.advertiseHost,
            nodePort = hb.nodePort,
            lastHeartbeatAt = now,
            agentVersion = hb.agentVersion,
            os = hb.os,
            duckdbVersion = hb.duckdbVersion,
            cpus = hb.cpus,
            memoryBytes = hb.memoryBytes,
            nodeState = FleetServerStore.effectiveState(hb.node, r.assignmentEpoch),
            nodeError = hb.node.error,
            nodePid = hb.node.pid,
            nodeStartedAt = hb.node.startedAt
          )
        )
        HeartbeatOutcome.Updated
  }

  def claim(
      a: FleetAssignment,
      reachableWithinSec: Int,
      requiredMemoryBytes: Option[Long]
  ): Either[ClaimMiss, FleetServerRow] =
    synchronized {
      val now  = clock()
      val free = rows.values
        .filter(r =>
          r.assignedNodeId.isEmpty && !r.unschedulable &&
            Duration.between(r.lastHeartbeatAt, now).getSeconds < reachableWithinSec
        )
        .toList
      val fit =
        free.filter(r => requiredMemoryBytes.forall(need => r.memoryBytes.forall(_ >= need)))
      fit.sortBy(_.joinedAt).headOption match
        case None if free.isEmpty => Left(ClaimMiss.NoneFree)
        case None                 => Left(ClaimMiss.NoneFits)
        case Some(r)              =>
          val epoch   = r.assignmentEpoch + 1
          val updated = r.copy(
            assignedNodeId = Some(a.nodeId),
            assignment = Some(a.copy(epoch = epoch)),
            assignmentEpoch = epoch,
            claimedAt = Some(now)
          )
          rows.put(r.name, updated); Right(withSilent(updated))
    }

  def setAssignment(name: String, a: FleetAssignment): Unit = synchronized {
    rows.get(name).foreach(r => rows.put(name, r.copy(assignment = Some(a))))
  }

  def release(nodeId: String): Option[String] = synchronized {
    rows.values.find(_.assignedNodeId.contains(nodeId)).map { r =>
      rows.put(
        r.name,
        r.copy(
          assignedNodeId = None,
          assignment = None,
          assignmentEpoch = r.assignmentEpoch + 1,
          claimedAt = None
        )
      )
      r.name
    }
  }

  def get(name: String): Option[FleetServerRow] = synchronized(rows.get(name).map(withSilent))
  def list(): List[FleetServerRow]              = synchronized(rows.values.toList.map(withSilent))
  def byNodeId(nodeId: String): Option[FleetServerRow] =
    synchronized(rows.values.find(_.assignedNodeId.contains(nodeId)).map(withSilent))
  def setUnschedulable(name: String, value: Boolean): Boolean = synchronized {
    rows.get(name).map(r => rows.put(name, r.copy(unschedulable = value))).isDefined
  }
  def delete(name: String): Boolean = synchronized(rows.remove(name).isDefined)
