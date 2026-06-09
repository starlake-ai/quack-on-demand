package ai.starlake.quack.model

/** Desired compute config under a single [[TenantDb]]. Inherits the
  * database's metastore + data path; carries only its own size,
  * role distribution, and per-node concurrency knob.
  *
  * `tenantId` is denormalized from `qodstate_tenant_db.tenant_id`
  * so the schema can enforce per-tenant pool-name uniqueness
  * (`UNIQUE (tenant_id, name)`) and so the FlightSQL edge can
  * resolve `(tenant, poolName) -> PoolKey` at handshake time
  * without a separate `db` URL param. */
final case class Pool(
    id:                   String,
    tenantId:             String,
    tenantDbId:           String,
    name:                 String,
    size:                 Int,
    distribution:         RoleDistribution,
    maxConcurrentPerNode: Int               = 0,
    idleTimeoutSec:       Option[Int]       = None,
    disabled:             Boolean           = false,
    // Optional placement plan: when non-empty, the per-cohort
    // RoleDistributions must sum to `distribution` and the total node
    // count must equal `size`. When empty (legacy default), the
    // supervisor schedules every node with no placement constraint.
    cohorts:              List[PoolCohort]  = Nil
):
  /** Effective scheduling plan: either the explicit cohorts, or one
    * synthesized placement-less cohort carrying the flat distribution. */
  def effectiveCohorts: List[PoolCohort] =
    if cohorts.nonEmpty then cohorts
    else List(PoolCohort.singleton(distribution))
