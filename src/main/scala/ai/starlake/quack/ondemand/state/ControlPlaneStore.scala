package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RunningNode, Tenant, TenantDb}

/** Per-entity persistence for the normalized control plane
  * (`qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`,
  * `qodstate_node`). Implementations are responsible for ordered
  * teardown -- deleting a parent fails when children remain
  * (matches the FK RESTRICT on the Postgres backend). */
trait ControlPlaneStore:

  def upsertTenant(t: Tenant): Unit
  def listTenants(): List[Tenant]
  def deleteTenant(id: String): Unit

  def upsertTenantDb(t: TenantDb): Unit
  def listTenantDbs(tenantId: String): List[TenantDb]
  def deleteTenantDb(id: String): Unit

  def upsertPool(p: Pool): Unit
  def listPools(tenantDbId: String): List[Pool]
  def deletePool(id: String): Unit

  /** Insert/update a running node under the given pool surrogate id.
    * `RunningNode.poolKey` is a natural key (tenant/pool) carried by the
    * runtime and does not always match a single Postgres row, so the FK
    * to `qodstate_pool.id` is supplied explicitly by the caller. */
  def upsertNode(n: RunningNode, poolId: String): Unit
  def listNodes(poolId: String): List[RunningNode]
  def deleteNode(nodeId: String): Unit

  /** Load the full topology in one round-trip. Used by the supervisor
    * at boot to seed its in-memory caches. */
  def snapshot(): ControlPlaneSnapshot
