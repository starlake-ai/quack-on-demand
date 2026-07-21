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
    disabled: Boolean = false,
    // Operator-authored per-database SQL prepended to every node's boot SQL for
    // pools of this database, BEFORE the pool's own initSql and the federation
    // blob. Engine defaults live here: SET temp_directory, SET memory_limit,
    // INSTALL/LOAD. Edits take effect on the next node spawn.
    initSql: String = ""
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

  /** Characters that would break out of a single-quoted DuckDB connection-string literal (or chain
    * extra statements) when `pgHost` / `pgUser` / `pgPassword` are interpolated verbatim into the
    * `ATTACH 'host=... user=... password=...'` literals in [[scripts/spawn-quack-node.sh]]. These
    * are free-form connection values (real passwords legitimately carry many symbols), so we deny
    * only the literal-breaking metacharacters rather than imposing an identifier allowlist. A `"`
    * is harmless inside a single-quoted literal, so it is intentionally not denied here (unlike
    * [[DataPathForbiddenChars]]).
    */
  private val ConnParamForbiddenChars: Set[Char] = Set('\'', ';', '\\', '\n', '\r')

  /** `schemaName` is interpolated unquoted into `CREATE SCHEMA` / `USE` in the node-bootstrap
    * script, so it must obey the same Postgres-identifier allowlist as `dbName` (which is validated
    * by [[dbNameError]] just below). A value carrying a space, semicolon, or an injected statement
    * is rejected here at the control-plane trust boundary, protecting both the local and Kubernetes
    * backends at the source.
    */
  private def schemaNameError(metastore: Map[String, String]): Option[String] =
    metastore.get("schemaName") match
      case Some(s) if !Names.isValid(s) =>
        Some(
          s"invalid schemaName '$s': must follow Postgres identifier rules " +
            "(1..63 chars, start with a letter or underscore, only letters, digits, underscore)"
        )
      case _ => None

  /** `dbName` is interpolated into the script both as a double-quoted DuckDB identifier (`ATTACH
    * ... AS "$dbName"`, `USE "$dbName"`) and inside single-quoted connection literals. For DuckLake
    * the supervisor force-sets it to a validated slug, but the DuckDbFile kind (and config-import
    * paths) carry a caller-supplied value, so we pin it to the Postgres-identifier allowlist here
    * to close that hole.
    */
  private def dbNameError(metastore: Map[String, String]): Option[String] =
    metastore.get("dbName") match
      case Some(d) if !Names.isValid(d) =>
        Some(
          s"invalid dbName '$d': must follow Postgres identifier rules " +
            "(1..63 chars, start with a letter or underscore, only letters, digits, underscore)"
        )
      case _ => None

  /** Rejects a single-quoted-literal connection value (`pgHost` / `pgUser` / `pgPassword`) that
    * carries a literal-breaking metacharacter. Absent keys pass (per-kind required-key checks
    * handle presence).
    */
  private def connParamError(metastore: Map[String, String], key: String): Option[String] =
    metastore.get(key) match
      case Some(v) if v.exists(ConnParamForbiddenChars.contains) =>
        Some(
          s"invalid $key: must not contain a single quote, semicolon, backslash, " +
            "or newline/carriage-return character"
        )
      case _ => None

  /** `pgPort` is interpolated into `port=$pgPort` inside a single-quoted connection literal; a
    * non-numeric value could smuggle SQL, so we require it to be all digits.
    */
  private def pgPortError(metastore: Map[String, String]): Option[String] =
    metastore.get("pgPort") match
      case Some(p) if !p.matches("^[0-9]+$") =>
        Some(s"invalid pgPort '$p': must be numeric")
      case _ => None

  private def dataPathError(dataPath: String): Option[String] =
    val bad = dataPath.toSet.intersect(DataPathForbiddenChars)
    if bad.nonEmpty then
      Some(
        "invalid dataPath: must not contain quote, semicolon, backslash, " +
          "or newline/carriage-return characters"
      )
    else None

  /** Rejects a semicolon in any `objectStore` VALUE (keys are unrestricted: they come from a fixed
    * UI vocabulary, not free-form user input). `ObjectStoreSecret.azureSecret` concatenates
    * `azure_account` / `azure_account_key` unescaped into a semicolon-delimited Azure connection
    * string before the whole string is wrapped in a single-quoted literal, so a semicolon smuggled
    * into a value injects extra connection-string fields (e.g. overriding `AccountName`) even
    * though the literal-quoting itself is safe.
    */
  private def objectStoreError(objectStore: Map[String, String]): Option[String] =
    objectStore.find { case (_, v) => v.contains(";") }.map { case (k, _) =>
      s"invalid objectStore value for '$k': must not contain a semicolon"
    }

  /** Injection-safety checks only: reject any script-interpolated value that carries a
    * SQL-literal-breaking or identifier-breaking metacharacter, validating each field only when it
    * is present. This is the security-critical subset of [[validate]] and is safe to run on paths
    * where the required metastore keys are supplied later from the default metastore (e.g. config
    * import), so it does NOT enforce key presence.
    */
  def validateSafety(td: TenantDb): Option[String] =
    schemaNameError(td.metastore)
      .orElse(dbNameError(td.metastore))
      .orElse(connParamError(td.metastore, "pgHost"))
      .orElse(connParamError(td.metastore, "pgUser"))
      .orElse(connParamError(td.metastore, "pgPassword"))
      .orElse(pgPortError(td.metastore))
      .orElse(dataPathError(td.dataPath))
      .orElse(objectStoreError(td.objectStore))

  /** Required metastore keys per kind. Used by both [[validate]] (presence check at create time)
    * and [[ai.starlake.quack.ondemand.PoolSupervisor.updateTenantDb]] (drop guard at update time)
    * so both share one source of truth.
    */
  def requiredMetastoreKeys(kind: TenantDbKind): Set[String] = kind match
    case TenantDbKind.DuckLake   => DuckLakeRequiredKeys
    case TenantDbKind.DuckDbFile => DuckDbFileRequiredKeys
    case TenantDbKind.InMemory   => Set.empty

  /** Returns Some(error) if the value violates its per-kind contract. Enforces required-key
    * presence AND injection safety; use on the REST createTenantDb path where the full metastore is
    * supplied inline.
    */
  def validate(td: TenantDb): Option[String] = td.kind match {
    case TenantDbKind.DuckLake =>
      val missing = requiredMetastoreKeys(td.kind) -- td.metastore.keySet
      if missing.nonEmpty then
        Some(s"kind=ducklake requires metastore keys ${missing.mkString(", ")}")
      else if td.dataPath.isEmpty then Some("kind=ducklake requires non-empty dataPath")
      else validateSafety(td)

    case TenantDbKind.DuckDbFile =>
      val missing = requiredMetastoreKeys(td.kind) -- td.metastore.keySet
      if missing.nonEmpty then
        Some(s"kind=duckdb-file requires metastore keys ${missing.mkString(", ")}")
      else if td.dataPath.isEmpty then Some("kind=duckdb-file requires non-empty dataPath")
      else validateSafety(td)

    case TenantDbKind.InMemory =>
      if td.metastore.nonEmpty then Some("kind=memory requires empty metastore")
      else if td.dataPath.nonEmpty then Some("kind=memory requires empty dataPath")
      else None
  }
}
