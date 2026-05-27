package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, RunningNode}
import cats.effect.IO

trait QuackBackend:
  def start(spec: NodeSpec): IO[RunningNode]
  def stop(nodeId: String): IO[Unit]
  def isAlive(nodeId: String): Boolean
  def discoverExisting(): IO[List[RunningNode]]
  def cleanup(): IO[Unit]

  /** Register a node that the backend didn't start (e.g. survived a
    * manager restart) so subsequent stop / port-allocation operations
    * see it. Backends that don't need internal bookkeeping for adopted
    * nodes (K8s — pods live on the apiserver) can no-op. */
  def adopt(node: RunningNode): IO[Unit] = IO.unit