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
    /** Operator-authored per-pool SQL prepended to the federation blob at node spawn time.
      * PRAGMAs / SET / INSTALL / LOAD belong here ("SET memory_limit=...", "INSTALL httpfs", ...);
      * use the Federation tab for ATTACH aliases. PoolSupervisor concatenates initSql + "\n" +
      * extraSetupSql into NodeSpec.extraSetupSql so PRAGMAs are in effect before any federation
      * source is attached. Editing on a running pool takes effect on the next node spawn
      * (scale-up, manual restart, crash-recovery); running nodes keep their old setup. */
    initSql: String = "",
    defaultDatabase: Option[String] = None, // tenant-db-level override for SQL validation context
    defaultSchema: Option[String] = None    // tenant-db-level override for SQL validation context
):
  def size: Int = nodes.size
