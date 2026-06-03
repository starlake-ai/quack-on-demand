package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RunningNode, Tenant, TenantDb}

/** Full graph returned by [[ControlPlaneStore.snapshot]]. Loaded at
  * supervisor restore time in a single round-trip; relationships are
  * followed via the FK fields (`tenantDb.tenantId`, `pool.tenantDbId`,
  * `node.poolKey` joined back to the pool by the store on read). */
final case class ControlPlaneSnapshot(
    tenants:   List[Tenant]      = Nil,
    tenantDbs: List[TenantDb]    = Nil,
    pools:     List[Pool]        = Nil,
    nodes:     List[RunningNode] = Nil
)
