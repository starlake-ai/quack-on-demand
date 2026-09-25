package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.PoolKey
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import java.time.Instant

/** What an agent must run. Serialised as JSONB in qodstate_fleet_server.assignment. */
final case class FleetAssignment(
    epoch: Long,
    nodeId: String,
    poolKey: PoolKey,
    port: Int,
    token: String,
    kind: String,
    env: Map[String, String],
    dbInitSql: String,
    objectStoreSql: String,
    extraSetupSql: String,
    lockdownSql: String
)
object FleetAssignment:
  given Codec[PoolKey]         = deriveCodec
  given Codec[FleetAssignment] = deriveCodec

/** The node block of a heartbeat. */
final case class NodeReport(
    assignmentEpoch: Long,
    nodeId: Option[String],
    state: String, // none | starting | running | failed | stopped
    pid: Option[Long],
    error: Option[String],
    startedAt: Option[Instant]
)

/** One server, joined over its heartbeat row. `silentSeconds` is computed by the store from ITS
  * clock (Postgres `now()`), never by the caller, so every HA replica classifies alike.
  */
final case class FleetServerRow(
    name: String,
    advertiseHost: String,
    nodePort: Int,
    joinedAt: Instant,
    unschedulable: Boolean,
    assignedNodeId: Option[String],
    assignment: Option[FleetAssignment],
    assignmentEpoch: Long,
    claimedAt: Option[Instant],
    // heartbeat side
    lastHeartbeatAt: Instant,
    silentSeconds: Long,
    // Seconds since claimed_at on the store clock (Postgres now()), None when unclaimed. Like
    // silentSeconds, measured by the store so a manager whose JVM clock drifts from the
    // database never misjudges how old a claim is.
    claimAgeSeconds: Option[Long],
    agentVersion: Option[String],
    os: Option[String],
    duckdbVersion: Option[String],
    cpus: Option[Int],
    memoryBytes: Option[Long],
    nodeState: String, // none | starting | running | failed | stopped | stale
    nodeError: Option[String],
    nodePid: Option[Long],
    nodeStartedAt: Option[Instant]
)

/** Heartbeat upsert input: everything the agent reports. */
final case class Heartbeat(
    name: String,
    advertiseHost: String,
    nodePort: Int,
    agentVersion: Option[String],
    os: Option[String],
    duckdbVersion: Option[String],
    cpus: Option[Int],
    memoryBytes: Option[Long],
    node: NodeReport
)

sealed trait HeartbeatOutcome
object HeartbeatOutcome:
  case object Joined  extends HeartbeatOutcome // first heartbeat, server row inserted
  case object Updated extends HeartbeatOutcome
  case object AddressChangeRefused
      extends HeartbeatOutcome // known name, other address, not drained

/** Why a claim found nothing. */
enum ClaimMiss:
  case NoneFree // no reachable, schedulable, unassigned server
  case NoneFits // some free, none with memory_bytes >= the requested memory

trait FleetServerStore:
  /** First contact inserts the server row (joined_at from the DB clock). Every call upserts the
    * heartbeat row with last_heartbeat_at = DB now(). A known name reporting a different address is
    * refused unless the server row is `unschedulable` (drained): with a shared join token an idle
    * name must not be claimable by another machine. A report whose `assignmentEpoch` or node id is
    * not the server row's is stored with node_state = stale, except `none` and `stopped`, which are
    * stored as reported (see `FleetServerStore.effectiveState`).
    */
  def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome

  /** Atomically claim one free server: unassigned, schedulable, heartbeat within
    * `reachableWithinSec` of the DB clock, and (when `requiredMemoryBytes` is set) either no
    * reported capacity or capacity >= the requirement; oldest join first. Writes the assignment
    * with a bumped epoch, the server's own node_port and claimed_at = now(); `assignment.epoch` and
    * `assignment.port` in the argument are ignored. Only the server row is locked (FOR UPDATE OF s
    * SKIP LOCKED); heartbeats never block it.
    */
  def claim(
      assignment: FleetAssignment,
      reachableWithinSec: Int,
      requiredMemoryBytes: Option[Long]
  ): Either[ClaimMiss, FleetServerRow]

  /** Rewrite the assignment JSON in place (no epoch change). */
  def setAssignment(name: String, a: FleetAssignment): Unit

  /** Clear the assignment of the server holding `nodeId`, bump the epoch, clear claimed_at. Returns
    * the server name when a row was released.
    */
  def release(nodeId: String): Option[String]

  def get(name: String): Option[FleetServerRow]
  def list(): List[FleetServerRow]
  def byNodeId(nodeId: String): Option[FleetServerRow]
  def setUnschedulable(name: String, value: Boolean): Boolean // false when unknown
  def delete(name: String): Boolean                           // heartbeat row cascades

object FleetServerStore:
  /** The stale rule both implementations share: a `none` or `stopped` report is stored as is; any
    * other report is stored as `stale` unless both its epoch and its node id match the server
    * row's. The node id matters because epochs restart at 0 after `delete` + re-join, so an agent
    * still running a pre-delete node could otherwise match a fresh assignment's epoch.
    *
    * `stopped` is exempt because the agent confirms a stop under the epoch and node id of the node
    * it stopped, while `release` has already bumped the row's epoch and cleared its node id: judged
    * stale, a stop confirmation could never be seen and every manager-driven stop would run out
    * `stopTimeoutSec`. It is safe: nothing waiting on a claim accepts `stopped` (only `running` and
    * `failed` answer a claim), so a late stop confirmation can never satisfy a newer assignment.
    */
  def effectiveState(node: NodeReport, rowEpoch: Long, assignedNodeId: Option[String]): String =
    if node.state == "none" || node.state == "stopped" then node.state
    else if node.assignmentEpoch == rowEpoch && node.nodeId == assignedNodeId then node.state
    else "stale"
