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
    kindWire: String = "ducklake", // propagated to NodeSpec for spawn-quack-node.sh
    extraSetupSql: String = "",    // resolved federation blob; propagated to NodeSpec
    /** The owning tenant-db's initSql. Runs EARLIEST in the node boot sequence: right after the
      * proxy http settings and BEFORE the quack extension is installed/loaded and the catalog
      * ATTACHed, so engine-level defaults are already in effect when quack starts. Shipped via
      * NodeSpec.dbInitSql (its own `dbInitSql` env var), NOT folded into extraSetupSql. The pool's
      * own initSql and the federation blob run later, after LOAD quack (a later SET wins, so pools
      * still override db defaults). Resolved from the tenant-db row wherever PoolState is built;
      * same next-spawn timing as initSql.
      */
    dbInitSql: String = "",
    /** Operator-authored per-pool SQL prepended to the federation blob at node spawn time. PRAGMAs
      * / SET / INSTALL / LOAD belong here ("SET memory_limit=...", "INSTALL httpfs", ...); use the
      * Federation tab for ATTACH aliases. PoolSupervisor concatenates initSql + "\n" +
      * extraSetupSql into NodeSpec.extraSetupSql so PRAGMAs are in effect before any federation
      * source is attached. Editing on a running pool takes effect on the next node spawn (scale-up,
      * manual restart, crash-recovery); running nodes keep their old setup.
      */
    initSql: String = "",
    defaultDatabase: Option[String] = None, // tenant-db-level override for SQL validation context
    defaultSchema: Option[String] = None,   // tenant-db-level override for SQL validation context
    cpu: String = "",
    memory: String = "",
    podTemplateYaml: String = ""
):
  def size: Int = nodes.size
