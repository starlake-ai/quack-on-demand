package ai.starlake.quack.model

/** A tenant's database. Each row maps 1:1 to a Postgres database (named
  * after `name`, composed `${tenant}_${suffix}` on the shared server) and
  * owns one DuckLake catalog + data path + object-store config. */
final case class TenantDb(
    id:          String,
    tenantId:    String,
    name:        String,
    metastore:   Map[String, String],
    dataPath:    String,
    objectStore: Map[String, String] = Map.empty,
    disabled:    Boolean             = false
)
