package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{PoolKey, Role}
import ai.starlake.quack.ondemand.PoolSupervisor

/** Deterministic pool picks for `(tenant, tenantDb)`, shared by the catalog REST handlers.
  * Candidates are sorted by pool name (stable across calls; `sup.list()` iterates a mutable map);
  * among them, prefer one whose current snapshot carries at least one role-eligible node, falling
  * through to the first candidate by name so `Router.pick` inside the executor stays the authority
  * on routability.
  */
object PoolPicks:
  private def pick(sup: PoolSupervisor, tenant: String, tenantDb: String)(
      eligible: Role => Boolean
  ): Option[PoolKey] =
    val candidates = sup
      .list()
      .map(_.key)
      .filter(k => k.tenant == tenant && k.tenantDb == tenantDb)
      .sortBy(_.pool)
    candidates
      .find(key => sup.snapshot(key).exists(_.nodes.exists(n => eligible(n.role))))
      .orElse(candidates.headOption)

  /** Read-preferring pick (ReadOnly/Dual): preview, diff, restore dry-run. */
  def readPoolKey(sup: PoolSupervisor, tenant: String, tenantDb: String): Option[PoolKey] =
    pick(sup, tenant, tenantDb)(r => r == Role.ReadOnly || r == Role.Dual)

  /** Write-preferring pick (WriteOnly/Dual): undrop and restore CTAS. */
  def writePoolKey(sup: PoolSupervisor, tenant: String, tenantDb: String): Option[PoolKey] =
    pick(sup, tenant, tenantDb)(r => r == Role.WriteOnly || r == Role.Dual)
