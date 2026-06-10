package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, RunningNode}
import cats.effect.IO

trait QuackBackend:
  def start(spec: NodeSpec): IO[RunningNode]
  def stop(nodeId: String): IO[Unit]
  def isAlive(nodeId: String): Boolean
  def discoverExisting(): IO[List[RunningNode]]
  def cleanup(): IO[Unit]

  /** True when this backend can honor [[NodeSpec.placement]] (K8s pod scheduling hints). The local
    * backend has nowhere to put a node label and ignores placement entirely; the supervisor drops
    * cohort data on create when this is false so the persisted pool row matches what was actually
    * scheduled.
    */
  def supportsPlacement: Boolean = false

  /** Register a node that the backend didn't start (e.g. survived a manager restart) so subsequent
    * stop / port-allocation operations see it. Backends that don't need internal bookkeeping for
    * adopted nodes (K8s - pods live on the apiserver) can no-op.
    */
  def adopt(node: RunningNode): IO[Unit] = IO.unit
