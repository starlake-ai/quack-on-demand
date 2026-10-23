package ai.starlake.quack.edge.adapter

import ai.starlake.quack.edge.authz.NodeHandles

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
  case Failed(reason: String)

  def healthy: Boolean = this == Ok

object ProbeVerdict:

  /** The warning for node `nodeId` refusing the manager's session handles: the node's answer and,
    * when the answer says which, the likely cause and the fix. `nodeText` holds no handle (qod-node
    * never echoes one).
    */
  def handleRefusedWarning(nodeId: String, nodeText: String): String =
    val refusal = s"node $nodeId refuses the manager's session handles, so no statement runs on " +
      s"it and it stays unhealthy ($nodeText)"
    if nodeText.contains(NodeHandles.KeyMismatchRefusal) then
      s"$refusal: it holds a key derived from another node-key secret (QOD_NODE_KEY_SECRET " +
        "changed since it started, or the manager replicas disagree on it); restart the node"
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
