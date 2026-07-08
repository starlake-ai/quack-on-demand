package ai.starlake.quack.ondemand.maintenance

/** Pure SQL builders for the DuckLake maintenance chain. `alias` is the ATTACH alias of the
  * DuckLake catalog on the executing session: the tenant-db's `dbName` on a spawned node, `lake` in
  * the PostgresFixture-based specs. Order of use is load-bearing (see spec section 4); these
  * builders do not enforce it - MaintenanceRunner does.
  *
  * Expiry deliberately has NO older_than variant: retention holds require explicit versions (an
  * older_than call would expire pinned snapshots).
  *
  * `rewriteTable`'s table name is a positional argument on the pinned engine (DuckDB 1.5.4 /
  * DuckLake): `ducklake_rewrite_data_files` has no `table_name` named parameter - only
  * `(catalog, table, delete_threshold, schema)` positional/named, confirmed via
  * `duckdb_functions()` and a live probe (see task-1-report.md). Adjusted from the brief's
  * `table_name => '$table'` form accordingly.
  */
object MaintenanceChainSql:

  def flush(alias: String): String =
    s"CALL ducklake_flush_inlined_data('$alias')"

  /** Explicit-version expiry; caller has already subtracted the pin-set and the latest snapshot.
    * Never called with an empty list (the runner skips the step instead).
    */
  def expireVersions(alias: String, versions: List[Long]): String =
    s"CALL ducklake_expire_snapshots('$alias', versions => [${versions.mkString(", ")}])"

  def mergeAdjacent(alias: String): String =
    s"CALL ducklake_merge_adjacent_files('$alias')"

  def rewriteTable(alias: String, schema: String, table: String): String =
    s"CALL ducklake_rewrite_data_files('$alias', '$table', schema => '$schema')"

  def cleanupOldFiles(alias: String, graceDays: Int): String =
    if graceDays <= 0 then s"CALL ducklake_cleanup_old_files('$alias', cleanup_all => true)"
    else
      s"CALL ducklake_cleanup_old_files('$alias', older_than => now() - INTERVAL '$graceDays days')"

  def deleteOrphans(alias: String, minAgeDays: Int): String =
    if minAgeDays <= 0 then s"CALL ducklake_delete_orphaned_files('$alias', cleanup_all => true)"
    else
      s"CALL ducklake_delete_orphaned_files('$alias', older_than => now() - INTERVAL '$minAgeDays days')"
