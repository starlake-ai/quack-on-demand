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

  /** Why [[forDatabase]] found no pool. */
  enum Miss:
    case PoolNotFound(pool: String)
    case OtherDatabase(pool: String, servedDb: String)
    case NoPool

  /** The pool a data call on `tenantDb` targets (MCP `run_sql`, the REST data edge): a named pool
    * must exist in the tenant and serve that database; none means [[readPoolKey]].
    */
  def forDatabase(
      sup: PoolSupervisor,
      tenant: String,
      tenantDb: String,
      pool: Option[String]
  ): Either[Miss, PoolKey] =
    pool match
      case Some(name) =>
        sup.findPoolKeyByTenantAndPoolName(tenant, name) match
          case Some(key) if key.tenantDb == tenantDb => Right(key)
          case Some(key)                             => Left(Miss.OtherDatabase(name, key.tenantDb))
          case None                                  => Left(Miss.PoolNotFound(name))
      case None => readPoolKey(sup, tenant, tenantDb).toRight(Miss.NoPool)

  /** Read-preferring pick (ReadOnly/Dual): preview, diff, restore dry-run. */
  def readPoolKey(sup: PoolSupervisor, tenant: String, tenantDb: String): Option[PoolKey] =
    pick(sup, tenant, tenantDb)(r => r == Role.ReadOnly || r == Role.Dual)

  /** Write-preferring pick (WriteOnly/Dual): undrop and restore CTAS. */
  def writePoolKey(sup: PoolSupervisor, tenant: String, tenantDb: String): Option[PoolKey] =
    pick(sup, tenant, tenantDb)(r => r == Role.WriteOnly || r == Role.Dual)
