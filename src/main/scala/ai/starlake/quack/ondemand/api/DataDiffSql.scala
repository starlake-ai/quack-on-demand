package ai.starlake.quack.ondemand.api

import io.circe.Json

/** Shared vocabulary + SQL builders for `ducklake_table_changes`-based diffs (Spec 02 data diff,
  * Spec 04 restore dry-run). Verified live on the pinned DuckDB 1.5.4 / DuckLake 0.3 (see
  * docs/duckdb-pin-bump-checklist.md section 5): the change types are exactly insert / delete /
  * update_preimage / update_postimage; BOTH function bounds are inclusive, hence the `fromId + 1`
  * convention for an exclusive-of-from diff; and a bound naming a nonexistent snapshot is an engine
  * ERROR, not an empty result, so callers must short-circuit `fromId == toId` themselves.
  */
private[api] object DataDiffSql:
  val InsertTypes    = Set("insert")
  val DeleteTypes    = Set("delete")
  val UpdateTypes    = Set("update_preimage", "update_postimage")
  val UpdatePostType = "update_postimage"

  def quoteLit(v: String): String = "'" + v.replace("'", "''") + "'"

  def diffFn(alias: String, schema: String, table: String, fromId: Long, toId: Long): String =
    s"ducklake_table_changes(${quoteLit(alias)}, ${quoteLit(schema)}, ${quoteLit(table)}, " +
      s"${fromId + 1}, $toId)"

  def summarySql(alias: String, schema: String, table: String, fromId: Long, toId: Long): String =
    s"SELECT change_type, count(*) AS n FROM ${diffFn(alias, schema, table, fromId, toId)} " +
      "GROUP BY change_type"

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
