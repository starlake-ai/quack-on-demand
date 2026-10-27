package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.PoolKey
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import java.time.Instant

/** What a server's `qod fleet join` process must run. Serialised as JSONB in
  * qodstate_fleet_server.assignment.
  */
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
    lockdownSql: String,
    // The tenant's authorized-location secrets (TenantLocationSecrets.initSql); None when the
    // tenant has none, and in an assignment an earlier manager wrote.
    tenantStoreSql: Option[String] = None
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
    startedAt: Option[Instant],
    // The join process's suggested fix for a failed node; None when healthy or unclassified.
    action: Option[String] = None
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
    qodVersion: Option[String],
    os: Option[String],
    duckdbVersion: Option[String],
    cpus: Option[Int],
    memoryBytes: Option[Long],
    nodeState: String, // none | starting | running | failed | stopped | stale
    nodeError: Option[String],
    nodePid: Option[Long],
    nodeStartedAt: Option[Instant],
    // Approval (Liquibase 0041): a server takes nodes only once approved. `approvedBy` is `auto`,
    // `upgrade` (joined before approval existed) or the approving admin's username.
    approved: Boolean,
    approvedAt: Option[Instant],
    approvedBy: Option[String],
    // The resolved client address of the latest heartbeat, never the self-reported one.
    sourceAddr: Option[String],
    // The resolved source an approved server is bound to (Liquibase 0042): the heartbeat that
    // auto-approved it, or the latest source when an admin approved it. None while pending, and
    // for an approval whose source was unknown (bound by the next heartbeat with a known source).
    approvedSource: Option[String],
    // The suggested fix for the node's failure (`node_action`, Liquibase 0046), from the report.
    nodeAction: Option[String] = None
)

/** Heartbeat upsert input: everything the join process reports. */
final case class Heartbeat(
    name: String,
    advertiseHost: String,
    nodePort: Int,
    qodVersion: Option[String],
    os: Option[String],
    duckdbVersion: Option[String],
    cpus: Option[Int],
    memoryBytes: Option[Long],
    node: NodeReport,
    // The resolved client address; None when unknown.
    sourceAddr: Option[String],
    // The manager's decision for this heartbeat's source (in QOD_FLEET_AUTO_APPROVE). Approves a
    // server that is not approved yet; ignored for an approved one. Required: a default would
    // silently decide approval for the caller.
    autoApprove: Boolean
):
  // An auto-approval must bind to the source it was judged from; an unbound approval is exactly
  // the dangerous state the join-approval fix exists to prevent.
  require(
    sourceAddr.isDefined || !autoApprove,
    "a heartbeat can only be auto-approved from a known source address"
  )

sealed trait HeartbeatOutcome
object HeartbeatOutcome:
  case object Joined  extends HeartbeatOutcome // first heartbeat, server row inserted
  case object Updated extends HeartbeatOutcome
  case object AddressChangeRefused
      extends HeartbeatOutcome // known name, other address, not drained or still assigned
  case object SourceChangeRefused
      extends HeartbeatOutcome // approved name, heartbeat from another source than it is bound to
  case object ApprovalUnbound
      extends HeartbeatOutcome // approved name bound to no source, heartbeat from outside the list

/** What an admin [[FleetServerStore.approve]] did. */
enum ApproveResult:
  case Approved        // pending server approved, bound to its latest known source
  case AlreadyApproved // no change: the first approver, time and binding are kept
  case NotFound        // no server by that name
  case SourceUnknown   // pending server whose latest heartbeat has no known source: refused

/** Why a claim found nothing. */
enum ClaimMiss:
  case NoneFree // no reachable, schedulable, unassigned server
  case NoneFits // some free, none with memory_bytes >= the requested memory

trait FleetServerStore:
  /** First contact inserts the server row (joined_at from the DB clock). Every call upserts the
    * heartbeat row with last_heartbeat_at = DB now(). A known name reporting a different address is
    * refused unless the server row is `unschedulable` (drained) and holds no assignment: with a
    * shared join token an idle name must not be claimable by another machine, and drain flips the
    * flag before it releases, so a heartbeat in between must not move a server holding a node. A
    * report whose `assignmentEpoch` or node id is not the server row's is stored with node_state =
    * stale, except `none` and `stopped`, which are stored as reported (see
    * `FleetServerStore.effectiveState`).
    *
    * A new server row starts unapproved; `hb.autoApprove` approves it (`approved_by = auto`), on
    * the join or on any later heartbeat while it is still pending. An approved server is never
    * re-judged at its address. An accepted re-address (a drained server reporting a different
    * advertise host or node port) resets approval (approved, approvedAt and approvedBy cleared)
    * before `hb.autoApprove` applies, so the new address is judged on that same heartbeat.
    *
    * An approval is bound to a source (`approvedSource`): the auto-approving heartbeat's, or the
    * latest one on an admin [[approve]]. A heartbeat for an approved row from another source (an
    * unknown one included) is refused (`SourceChangeRefused`, nothing written) unless
    * `hb.autoApprove` (rebind to the new source) or the row is drained and unassigned (approval
    * reset, judged again).
    *
    * An approved row bound to no source (servers approved before Liquibase 0042) binds only to a
    * known source with `hb.autoApprove`. From any other source (an unknown one included) it is
    * refused (`ApprovalUnbound`, nothing written) unless drained and unassigned (approval reset,
    * judged again): whoever heartbeats first with the name must not inherit the approval.
    */
  def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome

  /** Atomically claim one free server: approved, unassigned, schedulable, heartbeat within
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

  /** [[claim]] for a node id a server may still hold, in ONE transaction: release any current
    * holder of `assignment.nodeId` (bump its epoch, clear its assignment and claimed_at), then run
    * the claim. On a miss (`Left`) the whole transaction rolls back, so the old holder keeps its
    * assignment, epoch and claim untouched: a dead server that returns before capacity appears
    * resumes its node. A holder that is itself reachable and schedulable is a legitimate candidate
    * once released, so a reachable stale holder (a crash orphan) is re-claimed in place under a new
    * epoch and token.
    */
  def claimReplacing(
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

  /** Approve a server by name, recording `by` and binding the latest heartbeat's source, read
    * atomically with the write. `SourceUnknown` (nothing written) when that source is unknown: an
    * approval never starts unbound. Idempotent: an approved server keeps its first approver, time
    * and binding (`AlreadyApproved`). `NotFound` when the name is unknown.
    */
  def approve(name: String, by: String): ApproveResult

  def delete(name: String): Boolean // heartbeat row cascades

object FleetServerStore:
  /** The stale rule both implementations share: a `none` or `stopped` report is stored as is; any
    * other report is stored as `stale` unless both its epoch and its node id match the server
    * row's. The node id matters because epochs restart at 0 after `delete` + re-join, so a server
    * still running a pre-delete node could otherwise match a fresh assignment's epoch.
    *
    * `stopped` is exempt because the join process confirms a stop under the epoch and node id of
    * the node it stopped, while `release` has already bumped the row's epoch and cleared its node
    * id: judged stale, a stop confirmation could never be seen and every manager-driven stop would
    * run out `stopTimeoutSec`. It is safe: nothing waiting on a claim accepts `stopped` (only
    * `running` and `failed` answer a claim), so a late stop confirmation can never satisfy a newer
    * assignment.
    */
  def effectiveState(node: NodeReport, rowEpoch: Long, assignedNodeId: Option[String]): String =
    if node.state == "none" || node.state == "stopped" then node.state
    else if node.assignmentEpoch == rowEpoch && node.nodeId == assignedNodeId then node.state
    else "stale"
