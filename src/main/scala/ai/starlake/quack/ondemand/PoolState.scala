package ai.starlake.quack.ondemand

import ai.starlake.quack.model.{PoolKey, RoleDistribution, RunningNode}

final case class PoolState(
    key: PoolKey,
    nodes: List[RunningNode],
    distribution: RoleDistribution,
    metastore: Map[String, String],
    s3: Map[String, String],
    maxConcurrentPerNode: Int = 0,
    draining: Set[String] = Set.empty
):
  def size: Int = nodes.size