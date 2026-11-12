package ai.starlake.quack.edge.adapter

import ai.starlake.quack.NodeFilesConfig
import ai.starlake.quack.edge.authz.NodeHandles
import ai.starlake.quack.model.RunningNode
import ai.starlake.quack.route.NodeUnhealthy

import scala.collection.concurrent.TrieMap

/** What the health probe found on a node ([[QuackHttpAdapter.probe]]). Only [[Ok]] is healthy. */
enum ProbeVerdict:

  /** The probe statement ran after its session handle, and the canary (a statement with none) was
    * refused for want of one, or failed for another reason.
    */
  case Ok

  /** The node refused the probe's own session handle, so the probe statement never ran: it refuses
    * every handle the manager mints for it the same way. `nodeText` is the node's answer.
    */
  case HandleRefused(nodeText: String)

  /** The node ran the canary, a statement with no session handle: it does not enforce
    * authorization.
    */
  case Unenforced

  /** The probe statement failed for any other reason: the node is down, the statement errored. */
  case Failed(error: QuackError)

  /** This manager runs its nodes with local files off and the node did not show that it does.
    * `reason` is what the pool listing says of it ([[ProbeVerdict.localFilesNotOffReason]]).
    */
  case LocalFilesNotOff(reason: String)

  def healthy: Boolean = this == Ok

object ProbeVerdict:

  /** Why `node` is unhealthy after `verdict`, as the pool listing shows it, or None for [[Ok]]. It
    * quotes no text of the node's (see [[NodeUnhealthy]]): a handle refusal is told by the markers
    * qod-node's answers carry, a failure by its kind.
    */
  def cause(node: RunningNode, verdict: ProbeVerdict): Option[NodeUnhealthy.Cause] =
    verdict match
      case Ok                      => None
      case HandleRefused(nodeText) =>
        Some(
          if nodeText.contains(NodeHandles.KeyMismatchRefusal) then NodeUnhealthy.HandleKeyMismatch
          else if NodeHandles.ClockRefusals.exists(nodeText.contains) then NodeUnhealthy.HandleClock
          else NodeUnhealthy.HandleRefused
        )
      case Unenforced                            => Some(NodeUnhealthy.Unenforced)
      case LocalFilesNotOff(reason)              => Some(NodeUnhealthy.localFilesOn(reason))
      case Failed(QuackError.Transient(_, true)) =>
        Some(NodeUnhealthy.unreachable(node.host, node.port))
      case Failed(QuackError.Transient(_, _)) => Some(NodeUnhealthy.ProbeLost)
      case Failed(QuackError.Permanent(_))    => Some(NodeUnhealthy.ProbeRefused)

  /** Why node `nodeId` is held out of routing, as the pool's `statusDetail` and the log read it:
    * what its `enable_external_access` answered (`reading`, the cell, None when the read failed or
    * answered no cell), and the ways out. It quotes nothing else of the node's.
    */
  def localFilesNotOffReason(nodeId: String, reading: Option[String]): String =
    val answer = reading match
      case Some("true") => "its enable_external_access reads true"
      case Some(_)      => "its enable_external_access reads neither true nor false"
      case None         => "its enable_external_access could not be read"
    s"node $nodeId does not show local files off: $answer, though " +
      s"${NodeFilesConfig.AllowLocalEnv}=false turns them off on every node. It is unhealthy and " +
      "not routed to. Fix: restart the node; or upgrade its image or fleet server, as an older " +
      "release does not apply the setting"

  /** The warning for node `nodeId` refusing the manager's session handles: the node's answer and,
    * when the answer says which, the likely cause and the fix. `nodeText` holds no handle (qod-node
    * never echoes one).
    */
  def handleRefusedWarning(nodeId: String, nodeText: String): String =
    val refusal = s"node $nodeId refuses the manager's session handles, so no statement runs on " +
      s"it and it stays unhealthy ($nodeText)"
    if nodeText.contains(NodeHandles.KeyMismatchRefusal) then
      s"$refusal: it holds a key derived from another node-key secret (QOD_NODE_KEY_SECRET " +
        "changed since it started, or the manager replicas disagree on it); make " +
        "QOD_NODE_KEY_SECRET the same on every replica (or unset on all), then restart the node"
    else if NodeHandles.ClockRefusals.exists(nodeText.contains) then
      s"$refusal: the manager's and the node's clocks disagree by about the handle lifetime " +
        s"(${NodeHandles.LifetimeMs / 1000} s) or more; check clock sync"
    else refusal

/** Names why a node refuses the manager's session handles once per node, until a probe of it passes
  * again: Main's health loop logs what [[warning]] returns. Keyed on the probe's own verdicts, not
  * on the tracker's healthy flag: under traffic a statement's refused handle flips that flag first,
  * so a healthy-to-unhealthy check on it would never name the cause.
  */
final class HandleRefusalWarnings:

  private val named = TrieMap.empty[String, Unit]

  /** The warning to log for `verdict` on node `nodeId`: only the first refusal after a pass. */
  def warning(nodeId: String, verdict: ProbeVerdict): Option[String] = verdict match
    case ProbeVerdict.HandleRefused(nodeText) =>
      Option.when(named.putIfAbsent(nodeId, ()).isEmpty)(
        ProbeVerdict.handleRefusedWarning(nodeId, nodeText)
      )
    case ProbeVerdict.Ok =>
      named.remove(nodeId)
      None
    case _ => None
