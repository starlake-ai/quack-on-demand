package ai.starlake.quack.ondemand.api

import io.circe.Json

/** Shared vocabulary + SQL builders for `ducklake_table_changes`-based diffs (Spec 02 data diff,
  * Spec 04 restore dry-run). Verified live on DuckDB 1.5.4 / DuckLake 0.3, re-verified unchanged on
  * 1.5.5 (2026-07-25, live edge probes incl. the nonexistent-snapshot error) (see
  * docs/duckdb-pin-bump-checklist.md section 5): the change types are exactly insert / delete /
  * update_preimage / update_postimage; BOTH function bounds are inclusive, hence the `fromId + 1`
  * convention for an exclusive-of-from diff; and a bound naming a nonexistent snapshot is an engine
  * ERROR, not an empty result, so callers must short-circuit `fromId == toId` themselves. The page
  * statement reads the change feed; the counts ([[summarySql]]) read only the rowids and snapshots
  * of the two functions the feed joins, half the feed's reads.
  */
private[quack] object DataDiffSql:
  val InsertTypes    = Set("insert")
  val DeleteTypes    = Set("delete")
  val UpdateTypes    = Set("update_preimage", "update_postimage")
  val UpdatePostType = "update_postimage"

  def quoteLit(v: String): String = "'" + v.replace("'", "''") + "'"

  /** The arguments every change function takes: catalog, schema, table and the two snapshot bounds.
    * Both bounds are inclusive, hence `fromId + 1` for a diff exclusive of `fromId`; this is the
    * one place that convention lives.
    */
  private def feedArgs(
      alias: String,
      schema: String,
      table: String,
      fromId: Long,
      toId: Long
  ): String =
    s"${quoteLit(alias)}, ${quoteLit(schema)}, ${quoteLit(table)}, ${fromId + 1}, $toId"

  def diffFn(alias: String, schema: String, table: String, fromId: Long, toId: Long): String =
    s"ducklake_table_changes(${feedArgs(alias, schema, table, fromId, toId)})"

  /** `(change_type, n)` over `(fromId, toId]`, from rowids and snapshots alone.
    * `ducklake_table_changes` is two joins over these same functions: DuckDB prunes its columns for
    * a count, but it scans each function twice, so this reads half the bytes. A delete and an
    * insert of one rowid in one snapshot are an update, as in the feed; no `update_preimage` row is
    * reported, since `foldSummary` counts updates by their post-images.
    */
  def summarySql(alias: String, schema: String, table: String, fromId: Long, toId: Long): String =
    val args = feedArgs(alias, schema, table, fromId, toId)
    "SELECT CASE WHEN d.rowid IS NULL THEN 'insert' WHEN i.rowid IS NULL THEN 'delete' " +
      s"ELSE '$UpdatePostType' END AS change_type, count(*) AS n " +
      s"FROM (SELECT snapshot_id, rowid FROM ducklake_table_deletions($args)) d " +
      s"FULL JOIN (SELECT snapshot_id, rowid FROM ducklake_table_insertions($args)) i " +
      "ON d.snapshot_id = i.snapshot_id AND d.rowid = i.rowid GROUP BY change_type"

  /** Fold decoded `(change_type, n)` aggregate rows into the response summary. */
  def foldSummary(rows: List[List[Json]]): DataDiffSummary =
    val counts = rows.flatMap { r =>
      for
        ct <- r.headOption.flatMap(_.asString)
        n  <- r.lift(1).flatMap(_.asNumber).flatMap(_.toLong)
      yield ct -> n
    }.toMap
    DataDiffSummary(
      inserted = InsertTypes.toList.map(counts.getOrElse(_, 0L)).sum,
      deleted = DeleteTypes.toList.map(counts.getOrElse(_, 0L)).sum,
      updated = counts.getOrElse(UpdatePostType, 0L)
    )
