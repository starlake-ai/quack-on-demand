package ai.starlake.quack.model

/** Desired compute config under a single [[TenantDb]]. Inherits the
  * database's metastore + data path; carries only its own size,
  * role distribution, and per-node concurrency knob. */
final case class Pool(
    id:                   String,
    tenantDbId:           String,
    name:                 String,
    size:                 Int,
    distribution:         RoleDistribution,
    maxConcurrentPerNode: Int         = 0,
    idleTimeoutSec:       Option[Int] = None
)
