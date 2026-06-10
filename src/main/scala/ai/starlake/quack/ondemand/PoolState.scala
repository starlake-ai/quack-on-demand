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
    kindWire: String = "ducklake",          // propagated to NodeSpec for spawn-quack-node.sh
    extraSetupSql: String = "",             // resolved federation blob; propagated to NodeSpec
    defaultDatabase: Option[String] = None, // tenant-db-level override for SQL validation context
    defaultSchema: Option[String] = None    // tenant-db-level override for SQL validation context
):
  def size: Int = nodes.size
