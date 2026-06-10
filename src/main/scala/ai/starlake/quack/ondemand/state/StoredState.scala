package ai.starlake.quack.ondemand.state

/** Keyed by `PoolKey.toString` (tenant/pool) for stable JSON.
  *
  * `tenants` defaults to `Map.empty` for back-compat with state files written before tenants became
  * first-class. The decoder in [[StateStore]] hand-rolls `getOrElse("tenants")(Map.empty)` because
  * circe 0.14 deriveCodec does not honor Scala 3 case-class defaults on decode.
  */
final case class StoredState(
    pools: Map[String, StoredPool],
    tenants: Map[String, StoredTenant] = Map.empty
)
