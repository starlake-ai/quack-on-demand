package ai.starlake.quack.model

/** A tenant's database. Each row maps 1:1 to a Postgres database (named
  * after `name`, composed `${tenant}_${suffix}` on the shared server) and
  * owns its default catalog (DuckLake | local .duckdb file | in-memory)
  * plus any federated catalogs the admin has registered against it. */
final case class TenantDb(
    id:              String,
    tenantId:        String,
    name:            String,
    kind:            TenantDbKind,
    metastore:       Map[String, String],
    dataPath:        String,
    objectStore:     Map[String, String] = Map.empty,
    defaultDatabase: Option[String]      = None,
    defaultSchema:   Option[String]      = None,
    disabled:        Boolean             = false
)

object TenantDb {

  private val DuckLakeRequiredKeys: Set[String] =
    Set("pgHost", "pgPort", "pgUser", "pgPassword", "dbName", "schemaName")

  private val DuckDbFileRequiredKeys: Set[String] =
    Set("dbName", "schemaName")

  /** Returns Some(error) if the value violates its per-kind contract. */
  def validate(td: TenantDb): Option[String] = td.kind match {
    case TenantDbKind.DuckLake =>
      val missing = DuckLakeRequiredKeys -- td.metastore.keySet
      if missing.nonEmpty then
        Some(s"kind=ducklake requires metastore keys ${missing.mkString(", ")}")
      else if td.dataPath.isEmpty then
        Some("kind=ducklake requires non-empty dataPath")
      else None

    case TenantDbKind.DuckDbFile =>
      val missing = DuckDbFileRequiredKeys -- td.metastore.keySet
      if missing.nonEmpty then
        Some(s"kind=duckdb-file requires metastore keys ${missing.mkString(", ")}")
      else if td.dataPath.isEmpty then
        Some("kind=duckdb-file requires non-empty dataPath")
      else None

    case TenantDbKind.InMemory =>
      if td.metastore.nonEmpty then
        Some("kind=memory requires empty metastore")
      else if td.dataPath.nonEmpty then
        Some("kind=memory requires empty dataPath")
      else None
  }
}
