package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{PoolKey, RoleDistribution, RunningNode}

final case class StoredPool(
    key: PoolKey,
    size: Int,
    distribution: RoleDistribution,
    metastore: Map[String, String],
    s3: Map[String, String],
    nodes: List[RunningNode],
    maxConcurrentPerNode: Int = 0
)
