package ai.starlake.quack.model

/** Desired compute config under a single [[TenantDb]]. Inherits the database's metastore + data
  * path; carries only its own size, role distribution, and per-node concurrency knob.
  *
  * `tenantId` is denormalized from `qodstate_tenant_db.tenant_id` so the schema can enforce
  * per-tenant pool-name uniqueness (`UNIQUE (tenant_id, name)`) and so the FlightSQL edge can
  * resolve `(tenant, poolName) -> PoolKey` at handshake time without a separate `db` URL param.
  */
final case class Pool(
    id: String,
    tenantId: String,
    tenantDbId: String,
    name: String,
    size: Int,
    distribution: RoleDistribution,
    maxConcurrentPerNode: Int = 0,
    idleTimeoutSec: Option[Int] = None,
    disabled: Boolean = false,
    // Optional placement plan: when non-empty, the per-cohort
    // RoleDistributions must sum to `distribution` and the total node
    // count must equal `size`. When empty, the supervisor schedules
    // every node with no placement constraint.
    cohorts: List[PoolCohort] = Nil,
    /** Operator-authored per-pool init SQL. PoolSupervisor concatenates this with the resolved
      * federation blob and ships the result via NodeSpec.extraSetupSql, so spawn-quack-node.sh sees
      * one SQL stream. PRAGMAs / SET / INSTALL / LOAD live here; ATTACH aliases live on federation
      * sources. Empty by default for backward compat.
      */
    initSql: String = "",
    // Kubernetes node-pod sizing. Each applied as BOTH request and limit on the
    // quack container. Empty = unset. Kubernetes-only; ignored by the local backend.
    cpu: String = "",
    memory: String = "",
    // Full Pod-manifest YAML used as a base template when QOD_POD_TEMPLATE_ENABLED
    // is on; the manager overlays identity labels, the env contract, and resources.
    podTemplateYaml: String = ""
):
  /** Effective scheduling plan: either the explicit cohorts, or one synthesized placement-less
    * cohort carrying the flat distribution.
    */
  def effectiveCohorts: List[PoolCohort] =
    if cohorts.nonEmpty then cohorts
    else List(PoolCohort.singleton(distribution))
