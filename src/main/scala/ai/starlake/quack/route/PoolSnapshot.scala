package ai.starlake.quack.route

import ai.starlake.quack.model.{PoolKey, RunningNode}

final case class PoolSnapshot(
    key: PoolKey,
    nodes: List[RunningNode],
    load: Map[String, NodeLoad]
):
  def loadOf(nodeId: String): NodeLoad = load.getOrElse(nodeId, NodeLoad.empty)
