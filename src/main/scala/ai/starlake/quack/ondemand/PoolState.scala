package ai.starlake.quack.ondemand

import ai.starlake.quack.model.{PoolKey, RoleDistribution, RunningNode}

final case class PoolState(
    key: PoolKey,
    nodes: List[RunningNode],
    distribution: RoleDistribution,
    metastore: Map[String, String],
    s3: Map[String, String],
    maxConcurrentPerNode: Int = 0,
    draining: Set[String] = Set.empty,
    disabled: Boolean = false,
    kindWire: String = "ducklake",  // propagated to NodeSpec for spawn-quack-node.sh
    extraSetupSql: String = ""      // resolved federation blob; propagated to NodeSpec
):
  def size: Int = nodes.size