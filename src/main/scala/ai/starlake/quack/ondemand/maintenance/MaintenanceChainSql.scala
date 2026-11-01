package ai.starlake.quack.ondemand.maintenance

/** Pure SQL builders for the DuckLake maintenance chain. `alias` is the ATTACH alias of the
  * DuckLake catalog on the executing session: the tenant-db's `dbName` on a spawned node, `lake` in
  * the PostgresFixture-based specs. Order of use is load-bearing (see spec section 4); these
  * builders do not enforce it - MaintenanceRunner does.
  *
  * Expiry deliberately has NO older_than variant: retention holds require explicit versions (an
  * older_than call would expire pinned snapshots).
  *
  * The table name of `rewriteTable` and `mergeAdjacentTable` is a positional argument:
  * `ducklake_rewrite_data_files` and `ducklake_merge_adjacent_files` have no `table_name` named
  * parameter, only a table overload `(catalog, table_name, ..., schema)` whose other arguments are
  * named (`delete_threshold`, `schema`), confirmed via `duckdb_functions()` on DuckDB 2.0.0 and
  * pinned live by DuckLakeCatalogReaderMaintenanceSpec.
  */
object MaintenanceChainSql:

  /** Escape a raw identifier/value for interpolation into a single-quoted SQL literal: doubles
    * embedded single quotes so `s.t'); anything--`-style scope values can't break out of the
    * literal and reach the privileged node session. Every interpolated string argument in this
    * object (alias, schema, table) must be routed through this helper.
    */
  private def esc(raw: String): String = raw.replace("'", "''")

  def flush(alias: String): String =
    s"CALL ducklake_flush_inlined_data('${esc(alias)}')"

  /** Explicit-version expiry; caller has already subtracted the pin-set and the latest snapshot.
    * Never called with an empty list (the runner skips the step instead).
    */
  def expireVersions(alias: String, versions: List[Long]): String =
    s"CALL ducklake_expire_snapshots('${esc(alias)}', versions => [${versions.mkString(", ")}])"

  /** Merges every table of the database: the tenantdb-scoped run's step. */
  def mergeAdjacent(alias: String): String =
    s"CALL ducklake_merge_adjacent_files('${esc(alias)}')"

  /** Merges one table's files only: the table-scoped run's step. Same overload shape as
    * [[rewriteTable]] (table positional, schema named).
    */
  def mergeAdjacentTable(alias: String, schema: String, table: String): String =
    s"CALL ducklake_merge_adjacent_files('${esc(alias)}', '${esc(table)}', schema => '${esc(schema)}')"

  /** Rewrites the table's files whose deleted rows exceed `deleteThreshold` (a fraction in [0, 1],
    * the policy's `rewriteDeleteThreshold`); without it DuckLake applies its own default.
    */
  def rewriteTable(alias: String, schema: String, table: String, deleteThreshold: Double): String =
    require(
      deleteThreshold >= 0.0 && deleteThreshold <= 1.0,
      s"delete threshold must be within [0, 1], got $deleteThreshold"
    )
    s"CALL ducklake_rewrite_data_files('${esc(alias)}', '${esc(table)}', " +
      s"schema => '${esc(schema)}', delete_threshold => $deleteThreshold)"

  def cleanupOldFiles(alias: String, graceDays: Int): String =
    if graceDays <= 0 then s"CALL ducklake_cleanup_old_files('${esc(alias)}', cleanup_all => true)"
    else
      s"CALL ducklake_cleanup_old_files('${esc(
          alias
        )}', older_than => now() - INTERVAL '$graceDays days')"

  def deleteOrphans(alias: String, minAgeDays: Int): String =
    if minAgeDays <= 0 then
      s"CALL ducklake_delete_orphaned_files('${esc(alias)}', cleanup_all => true)"
    else
      s"CALL ducklake_delete_orphaned_files('${esc(
          alias
        )}', older_than => now() - INTERVAL '$minAgeDays days')"
