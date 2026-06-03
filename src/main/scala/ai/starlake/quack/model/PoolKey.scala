package ai.starlake.quack.model

/** Three-part naistural addressing key. Pools are owned by a tenant-db,
  * so two pools with the same `pool` name under different tenant-dbs
  * of the same tenant are distinct -- the per-`(tenantDb_id, name)`
  * UNIQUE on `qodstate_pool` enforces it. Segments are lowercased on
  * input to keep equality case-insensitive. */
final case class PoolKey private (tenant: String, tenantDb: String, pool: String):
  override def toString: String = s"$tenant/$tenantDb/$pool"

object PoolKey:

  def apply(tenant: String, tenantDb: String, pool: String): PoolKey =
    new PoolKey(tenant.toLowerCase, tenantDb.toLowerCase, pool.toLowerCase)

  def parse(s: String): Either[String, PoolKey] = s.split("/", 3) match
    case Array(t, td, p) if t.nonEmpty && td.nonEmpty && p.nonEmpty =>
      Right(PoolKey(t, td, p))
    case _ =>
      Left(s"invalid pool key: '$s' (expected tenant/tenantDb/pool)")
