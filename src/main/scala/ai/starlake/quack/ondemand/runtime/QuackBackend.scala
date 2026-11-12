package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, RunningNode}
import cats.effect.IO

trait QuackBackend:
  def start(spec: NodeSpec): IO[RunningNode]

  /** Stop the node's runtime resources. `key` is passed by the caller (the supervisor always knows
    * it) so backends never have to read it back off a possibly-already-deleted resource.
    */
  def stop(key: PoolKey, nodeId: String): IO[Unit]
  def isAlive(nodeId: String): Boolean
  def discoverExisting(): IO[List[RunningNode]]
  def cleanup(): IO[Unit]

  /** True when this backend can honor
    * [[ai.starlake.quack.model.NodeSpec.placement NodeSpec.placement]] (K8s pod scheduling hints).
    * The local backend has nowhere to put a node label and ignores placement entirely; the
    * supervisor drops cohort data on create when this is false so the persisted pool row matches
    * what was actually scheduled.
    */
  def supportsPlacement: Boolean = false

  /** Whether the nodes this backend starts may open local files
    * (`quack-on-demand.nodeFiles.allowLocal`). The supervisor reads it to block a database whose
    * data path is local and to refuse a create that would make one, and the health probe to check
    * that a node has them off. No default: a backend that forgot it would read as "on".
    */
  def allowsLocalFiles: Boolean

  /** True when every node this backend starts carries its process id (the local backend). A node
    * row with none was then written by another runtime against the same control plane (a fleet or
    * Kubernetes manager that ran before this one), so nothing this backend runs serves it:
    * reconcile reads such a row as dead and replaces it, where a backend that records no pid reads
    * it by [[liveNodeIds]].
    */
  def recordsPids: Boolean = false

  /** True for the fleet backend (bare servers joined via `qod fleet join`). Surfaced to the UI so
    * it shows the Fleet servers page only where servers can exist.
    */
  def isFleet: Boolean = false

  /** Register a node that the backend didn't start (e.g. survived a manager restart) so subsequent
    * stop / port-allocation operations see it. Backends that don't need internal bookkeeping for
    * adopted nodes (K8s - pods live on the apiserver) can no-op.
    */
  def adopt(node: RunningNode): IO[Unit] = IO.unit

  /** Stop `node`'s process when it is still running but this backend does not hold it (it outlived
    * an earlier manager and was never adopted). Reconcile calls it before replacing a node it
    * judged dead, so the replacement cannot leave the old process running untracked. The default
    * has nothing to stop: a pod name or a fleet assignment is unique per node id.
    */
  def stopStray(node: RunningNode): IO[Unit] = IO.unit

  /** Keep the ports of `nodes` whose process is still running but not held by this backend (left by
    * an earlier manager) from being handed to a new node. Reconcile calls it with every node row at
    * the start of each pass, before any spawn. The default has nothing to reserve: pods and fleet
    * servers do not share a port space with the manager.
    */
  def reservePorts(nodes: List[RunningNode]): IO[Unit] = IO.unit

  /** `node` as its runtime currently places it. A backend that can move a live node without the
    * supervisor writing the row (fleet: a claim committed, then the manager died before the node
    * row write) answers the real address and token, and reconcile rewrites the row. The same
    * instance when nothing moved; the default never moves anything.
    */
  def located(node: RunningNode): IO[RunningNode] = IO.pure(node)

  /** Node ids with a live runtime for `key`, or None when the backend cannot enumerate (local mode,
    * or a transient apiserver error). Reconcile treats None as "fall back to the pid/socket probe"
    * and never prunes or respawns on it.
    */
  def liveNodeIds(key: PoolKey): IO[Option[Set[String]]] = IO.pure(None)
