package ai.starlake.quack.ondemand

import ai.starlake.quack.model.{PoolKey, RoleDistribution, RunningNode}

final case class PoolState(
    key: PoolKey,
    nodes: List[RunningNode],
    distribution: RoleDistribution,
    metastore: Map[String, String],
    s3: Map[String, String],
    maxConcurrentPerNode: Int = 0,
    disabled: Boolean = false,
    suspended: Boolean = false,    // scale-to-zero; see Pool.suspended
    kindWire: String = "ducklake", // propagated to NodeSpec for spawn-quack-node.sh
    extraSetupSql: String = "",    // resolved federation blob; propagated to NodeSpec
    /** The owning tenant-db's initSql. Runs EARLIEST in the node boot sequence: right after the
      * proxy and resource settings and BEFORE the catalog ATTACH, so engine-level defaults are in
      * effect when quack starts. Shipped as NodeSpec.tenantDbInitSql, never folded into
      * extraSetupSql. Resolved from the tenant-db row wherever PoolState is built; edits take
      * effect on the next node spawn.
      */
    dbInitSql: String = "",
    /** Whether [[dbInitSql]] was written by a superuser or the manager (`TenantDb.initSqlTrusted`).
      */
    dbInitSqlTrusted: Boolean = false,
    /** Operator-authored per-pool SQL, run after the catalog ATTACH and before the federation blob
      * (shipped as NodeSpec.tenantPoolInitSql, never joined with extraSetupSql). Editing on a
      * running pool takes effect on the next node spawn (scale-up, manual restart, crash-recovery);
      * running nodes keep their old setup.
      */
    initSql: String = "",
    /** Whether [[initSql]] was written by a superuser or the manager (`Pool.initSqlTrusted`). */
    initSqlTrusted: Boolean = false,
    defaultDatabase: Option[String] = None, // tenant-db-level override for SQL validation context
    defaultSchema: Option[String] = None,   // tenant-db-level override for SQL validation context
    cpu: String = "",
    memory: String = "",
    podTemplateYaml: String = ""
):
  def size: Int = nodes.size
