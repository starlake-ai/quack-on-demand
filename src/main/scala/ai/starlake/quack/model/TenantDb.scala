package ai.starlake.quack.model

/** A tenant's database. Each row maps 1:1 to a Postgres database (named after `name`, composed
  * `${tenant}_${suffix}` on the shared server) and owns its default catalog (DuckLake | local
  * .duckdb file | in-memory) plus any federated catalogs the admin has registered against it.
  */
final case class TenantDb(
    id: String,
    tenantId: String,
    name: String,
    kind: TenantDbKind,
    metastore: Map[String, String],
    dataPath: String,
    objectStore: Map[String, String] = Map.empty,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    disabled: Boolean = false
)

object TenantDb {

  private val DuckLakeRequiredKeys: Set[String] =
    Set("pgHost", "pgPort", "pgUser", "pgPassword", "dbName", "schemaName")

  private val DuckDbFileRequiredKeys: Set[String] =
    Set("dbName", "schemaName")

  /** Characters that, if present in `dataPath`, would break out of the single-quoted SQL string
    * literal or chain extra statements inside the node-bootstrap script
    * ([[scripts/spawn-quack-node.sh]] interpolates `dataPath` into `ATTACH '...'` / `DATA_PATH
    * '...'`). `dataPath` is a filesystem/URI path, not an identifier, so a strict identifier
    * allowlist is wrong here; instead we deny the specific metacharacters that enable injection and
    * leave legitimate path characters (slashes, dots, colons, hyphens, underscores) untouched.
    */
  private val DataPathForbiddenChars: Set[Char] = Set('\'', '"', ';', '\\', '\n', '\r')

  /** `schemaName` is interpolated unquoted into `CREATE SCHEMA` / `USE` in the node-bootstrap
    * script, so it must obey the same Postgres-identifier allowlist as `dbName` (which is already
    * validated via [[Names]] before it reaches the script). A value carrying a space, semicolon, or
    * an injected statement is rejected here at the control-plane trust boundary, protecting both
    * the local and Kubernetes backends at the source.
    */
  private def schemaNameError(metastore: Map[String, String]): Option[String] =
    metastore.get("schemaName") match
      case Some(s) if !Names.isValid(s) =>
        Some(
          s"invalid schemaName '$s': must follow Postgres identifier rules " +
            "(1..63 chars, start with a letter or underscore, only letters, digits, underscore)"
        )
      case _ => None

  private def dataPathError(dataPath: String): Option[String] =
    val bad = dataPath.toSet.intersect(DataPathForbiddenChars)
    if bad.nonEmpty then
      Some(
        "invalid dataPath: must not contain quote, semicolon, backslash, " +
          "or newline/carriage-return characters"
      )
    else None

  /** Returns Some(error) if the value violates its per-kind contract. */
  def validate(td: TenantDb): Option[String] = td.kind match {
    case TenantDbKind.DuckLake =>
      val missing = DuckLakeRequiredKeys -- td.metastore.keySet
      if missing.nonEmpty then
        Some(s"kind=ducklake requires metastore keys ${missing.mkString(", ")}")
      else if td.dataPath.isEmpty then Some("kind=ducklake requires non-empty dataPath")
      else schemaNameError(td.metastore).orElse(dataPathError(td.dataPath))

    case TenantDbKind.DuckDbFile =>
      val missing = DuckDbFileRequiredKeys -- td.metastore.keySet
      if missing.nonEmpty then
        Some(s"kind=duckdb-file requires metastore keys ${missing.mkString(", ")}")
      else if td.dataPath.isEmpty then Some("kind=duckdb-file requires non-empty dataPath")
      else schemaNameError(td.metastore).orElse(dataPathError(td.dataPath))

    case TenantDbKind.InMemory =>
      if td.metastore.nonEmpty then Some("kind=memory requires empty metastore")
      else if td.dataPath.nonEmpty then Some("kind=memory requires empty dataPath")
      else None
  }
}
