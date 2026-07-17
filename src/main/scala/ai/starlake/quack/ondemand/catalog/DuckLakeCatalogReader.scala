package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogHistoryCommit,
  CatalogSchemaEntry,
  CatalogSnapshotEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry,
  CatalogTableRef
}
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.ResultSet
import scala.collection.mutable.ListBuffer

/** Result of a column-level schema diff between two snapshots of one table (time-travel viewer).
  * `typeChanged` / `nullabilityChanged` tuples are `(column, from, to)`; only columns present at
  * BOTH ends are considered for retyping/nullability (a column that was added or removed shows up
  * in `added`/`removed` instead).
  */
final case class SchemaDiffResult(
    added: List[CatalogColumnEntry],
    removed: List[CatalogColumnEntry],
    typeChanged: List[(String, String, String)],
    nullabilityChanged: List[(String, Boolean, Boolean)]
)

/** Server-side filters of the per-table history timeline (EPIC Spec 01). All four push into SQL so
  * keyset pagination and hasMore stay exact.
  */
final case class TableHistoryFilter(
    from: Option[java.time.Instant] = None,
    to: Option[java.time.Instant] = None,
    operation: Option[String] = None,
    author: Option[String] = None
)

/** One history page. `tableId` is the stable DuckLake table_id the page was resolved against. */
final case class TableHistoryPage(
    tableId: Long,
    commits: List[CatalogHistoryCommit],
    hasMore: Boolean
)

/** One dropped-but-maybe-recoverable table (Spec 03 undrop). `lastLiveSnapshot` is the snapshot the
  * table can be read AT to reconstruct its final state (`droppedAtSnapshot - 1`); `recoverable` is
  * false once that snapshot has been expired. `droppedAt` is the drop snapshot's ISO-8601 commit
  * time when that snapshot itself still exists.
  */
final case class DroppedTableEntry(
    schema: String,
    table: String,
    droppedAtSnapshot: Long,
    lastLiveSnapshot: Long,
    droppedAt: Option[String],
    recoverable: Boolean
)

/** Reads schemas / tables / columns / data files out of the DuckLake metadata tables. The metadata
  * schema is fixed by DuckLake itself (`ducklake_schema`, `ducklake_table`, `ducklake_column`,
  * `ducklake_data_file`) and lives in the same Postgres DB the catalog is attached to.
  *
  * `meta` is the resolved per-tenant map produced by `PoolSupervisor.metastoreFor(...)` - same
  * shape the rest of the stack uses.
  */
class DuckLakeCatalogReader(private val ds: HikariDataSource) extends LazyLogging:

  def listSchemas(): List[CatalogSchemaEntry] =
    val sql =
      """SELECT s.schema_name,
        |       count(t.table_id) AS table_count
        |  FROM ducklake_schema s
        |  LEFT JOIN ducklake_table t
        |         ON t.schema_id = s.schema_id
        |        AND t.end_snapshot IS NULL
        | WHERE s.end_snapshot IS NULL
        | GROUP BY s.schema_name
        | ORDER BY s.schema_name""".stripMargin
    query(sql) { rs =>
      CatalogSchemaEntry(rs.getString("schema_name"), rs.getInt("table_count"))
    }

  def listTables(schema: String): List[CatalogTableEntry] =
    // Row counts live in `ducklake_table_stats.record_count` - DuckLake's
    // `ducklake_table` table doesn't carry stats columns. We `LEFT JOIN` it
    // so tables with no committed snapshot yet still appear with rowCount=-1.
    //
    // `folder` composition: DuckLake stores per-row `path` + `path_is_relative`
    // on `ducklake_schema` AND `ducklake_table`. The base is the catalog-wide
    // `data_path` metadata key (typed at ATTACH time, e.g.
    // `s3://ducklake/tpch/` or `/app/ducklake/tpch/`). Resolution:
    //   - if `t.path_is_relative=false` → folder = `t.path` (absolute)
    //   - else if `s.path_is_relative=false` → folder = `s.path || t.path`
    //   - else → folder = `<data_path> || s.path || t.path`
    // NULLIF turns the empty-string fallback into NULL so the UI can show
    // "(none)" cleanly when DuckLake is missing the data_path metadata.
    val sql =
      """SELECT s.schema_name,
        |       t.table_name,
        |       coalesce(ts.record_count, -1) AS row_count,
        |       count(d.data_file_id) FILTER (WHERE d.end_snapshot IS NULL) AS data_file_count,
        |       NULLIF(
        |         CASE WHEN NOT t.path_is_relative THEN t.path
        |              WHEN NOT s.path_is_relative THEN s.path || t.path
        |              ELSE coalesce(
        |                     (SELECT value FROM ducklake_metadata
        |                       WHERE key = 'data_path' LIMIT 1), ''
        |                   ) || s.path || t.path
        |         END
        |       , '') AS folder
        |  FROM ducklake_schema s
        |  JOIN ducklake_table   t  ON t.schema_id = s.schema_id
        |  LEFT JOIN ducklake_table_stats ts ON ts.table_id = t.table_id
        |  LEFT JOIN ducklake_data_file   d  ON d.table_id  = t.table_id
        | WHERE s.schema_name = ?
        |   AND s.end_snapshot IS NULL
        |   AND t.end_snapshot IS NULL
        | GROUP BY s.schema_name, t.table_name, ts.record_count,
        |          t.path_is_relative, t.path,
        |          s.path_is_relative, s.path
        | ORDER BY t.table_name""".stripMargin
    query(sql, schema) { rs =>
      CatalogTableEntry(
        schema = rs.getString("schema_name"),
        name = rs.getString("table_name"),
        rowCount = rs.getLong("row_count"),
        dataFileCount = rs.getInt("data_file_count"),
        folder = Option(rs.getString("folder"))
      )
    }

  /** Snapshot-visibility predicate for one aliased versioned table. Without asOf this is the
    * current-state filter (`end_snapshot IS NULL`); with asOf = n a row is visible when it began at
    * or before n and had not been superseded by n.
    *
    * Delete files written by `ducklake_flush_inlined_data` are backdated: their `begin_snapshot` is
    * the `inlined_delete` snapshot, not the later `deleted_from_table` flush snapshot. That
    * backdating is deliberate (the delete is logically part of the table state at the DELETE
    * snapshot) and the `<=` here matches the engine: `SELECT count(*) ... AT (VERSION => n)` in
    * DuckDB already reflects the delete at the inlined-delete snapshot (verified empirically: 6
    * rows at n-1, 4 rows at n, where n is the inlined_delete snapshot).
    */
  private def visible(alias: String, asOf: Option[Long]): (String, List[Any]) =
    asOf match
      case None    => (s"$alias.end_snapshot IS NULL", Nil)
      case Some(n) =>
        (
          s"$alias.begin_snapshot <= ? AND ($alias.end_snapshot IS NULL OR $alias.end_snapshot > ?)",
          List(n, n)
        )

  def snapshotExists(id: Long): Boolean =
    query("SELECT 1 FROM ducklake_snapshot WHERE snapshot_id = ?", id)(_ => 1).nonEmpty

  /** Dropped tables, newest drop first (Spec 03 undrop). A renamed/altered table keeps a live
    * `ducklake_table` row for its table_id, so the NOT EXISTS arm restricts to true drops. The drop
    * transaction end-dates the row at the drop snapshot D, so last-live = D - 1 (verified live
    * 2026-07-14, see the undrop design doc); `recoverable` checks that snapshot still exists (not
    * expired). The schema row is deliberately joined without an end_snapshot filter so tables of a
    * dropped schema still list.
    */
  private val droppedTablesSelect =
    """SELECT s.schema_name,
      |       t.table_name,
      |       t.end_snapshot AS dropped_at_snapshot,
      |       sn.snapshot_time AS dropped_at,
      |       EXISTS(SELECT 1 FROM ducklake_snapshot e
      |               WHERE e.snapshot_id = t.end_snapshot - 1) AS recoverable
      |  FROM ducklake_table t
      |  JOIN ducklake_schema s ON s.schema_id = t.schema_id
      |  LEFT JOIN ducklake_snapshot sn ON sn.snapshot_id = t.end_snapshot
      | WHERE t.end_snapshot IS NOT NULL
      |   AND NOT EXISTS (SELECT 1 FROM ducklake_table l
      |                    WHERE l.table_id = t.table_id AND l.end_snapshot IS NULL)""".stripMargin

  private def droppedEntry(rs: ResultSet): DroppedTableEntry =
    val d = rs.getLong("dropped_at_snapshot")
    DroppedTableEntry(
      schema = rs.getString("schema_name"),
      table = rs.getString("table_name"),
      droppedAtSnapshot = d,
      lastLiveSnapshot = d - 1,
      droppedAt = Option(rs.getTimestamp("dropped_at")).map(_.toInstant.toString),
      recoverable = rs.getBoolean("recoverable")
    )

  /** Discovery list only: a drop whose (schema, name) is live again - an undrop recovery re-created
    * it, or an unrelated table took the name - is excluded, because its default Undrop action would
    * 409 name_conflict. [[findDroppedTable]] deliberately keeps no such filter so an explicit
    * undrop with asName can still recover an occupied name.
    */
  private val nameNotLiveAgain =
    """   AND NOT EXISTS (SELECT 1 FROM ducklake_table l
      |                    JOIN ducklake_schema ls ON ls.schema_id = l.schema_id
      |                   WHERE ls.schema_name = s.schema_name
      |                     AND l.table_name  = t.table_name
      |                     AND l.end_snapshot IS NULL)""".stripMargin

  def listDroppedTables(limit: Int = 50): List[DroppedTableEntry] =
    query(
      droppedTablesSelect + "\n" + nameNotLiveAgain +
        "\n ORDER BY t.end_snapshot DESC, t.table_name\n LIMIT ?",
      limit
    )(droppedEntry)

  /** Targeted lookup for the undrop path: unlike paging through [[listDroppedTables]], this finds a
    * dropped table regardless of how many drops happened after it (the discovery list caps at 200).
    * Newest drop of that name wins when the name was dropped more than once.
    */
  def findDroppedTable(schema: String, table: String): Option[DroppedTableEntry] =
    query(
      droppedTablesSelect +
        "\n   AND s.schema_name = ? AND t.table_name = ?\n ORDER BY t.end_snapshot DESC\n LIMIT 1",
      schema,
      table
    )(droppedEntry).headOption

  /** Newest committed snapshot id, or None on an empty (just-attached) catalog. */
  def maxSnapshotId(): Option[Long] =
    query("SELECT max(snapshot_id) AS m FROM ducklake_snapshot") { rs =>
      Option(rs.getObject("m")).map(_ => rs.getLong("m"))
    }.headOption.flatten

  /** Nearest snapshot committed at or before `ts` - the time-travel viewer's "as of this instant"
    * resolution. None when `ts` is before the catalog's first snapshot.
    */
  def snapshotAtOrBefore(ts: java.time.Instant): Option[Long] =
    query(
      "SELECT max(snapshot_id) AS m FROM ducklake_snapshot WHERE snapshot_time <= ?",
      java.sql.Timestamp.from(ts)
    ) { rs =>
      Option(rs.getObject("m")).map(_ => rs.getLong("m"))
    }.headOption.flatten

  /** Subset of `ids` that exist in the catalog. One round-trip; used to flag dangling tags. */
  def snapshotsExist(ids: Set[Long]): Set[Long] =
    if ids.isEmpty then Set.empty
    else
      val placeholders = List.fill(ids.size)("?").mkString(",")
      query(
        s"SELECT snapshot_id FROM ducklake_snapshot WHERE snapshot_id IN ($placeholders)",
        ids.toList*
      )(_.getLong("snapshot_id")).toSet

  /** Expiry candidates: snapshots strictly older than `cutoff`, never including the latest snapshot
    * (the current state must always survive a maintenance run). Ascending.
    */
  def snapshotsOlderThan(cutoff: java.time.Instant): List[Long] =
    query(
      """SELECT snapshot_id FROM ducklake_snapshot
        |WHERE snapshot_time < ?
        |  AND snapshot_id <> (SELECT max(snapshot_id) FROM ducklake_snapshot)
        |ORDER BY snapshot_id""".stripMargin,
      java.sql.Timestamp.from(cutoff)
    )(_.getLong(1))

  /** Per-(schema, table) count of CURRENT data files smaller than `maxBytes` - the threshold
    * trigger's input (spec section 4). Metadata-only Postgres read; no node involved.
    */
  def smallFileCounts(maxBytes: Long): Map[(String, String), Int] =
    query(
      """SELECT s.schema_name, t.table_name, count(*)
        |FROM ducklake_data_file f
        |JOIN ducklake_table t ON t.table_id = f.table_id
        |JOIN ducklake_schema s ON s.schema_id = t.schema_id
        |WHERE f.end_snapshot IS NULL AND f.file_size_bytes < ?
        |GROUP BY 1, 2""".stripMargin,
      maxBytes
    )(rs => ((rs.getString(1), rs.getString(2)), rs.getInt(3))).toMap

  /** Paths pending physical deletion (scheduled by expire/merge, not yet cleaned). Feeds the
    * pinned-file fail-safe guard. No sizes: unrecoverable from the catalog post-schedule on this
    * DuckLake version (byte accounting uses totalDataFileBytes deltas instead).
    */
  def filesScheduledForDeletion(): List[String] =
    query(
      "SELECT path FROM ducklake_files_scheduled_for_deletion"
    )(_.getString(1))

  /** Total bytes of all catalog-referenced data files. The maintenance runner samples this before
    * and after a chain run; the delta is the run's bytesReclaimed (catalog bytes released).
    */
  def totalDataFileBytes(): Long =
    query(
      "SELECT COALESCE(sum(file_size_bytes), 0) FROM ducklake_data_file"
    )(_.getLong(1)).headOption.getOrElse(0L)

  /** Every file path the given snapshot references: data files AND delete files visible at
    * `snapshotId` under the same predicate the AS OF browser uses. This is the file-level pin-set
    * for EPIC P2: a maintenance run must not delete any of these while the snapshot is pinned.
    */
  def filesReferencedAt(snapshotId: Long): Set[String] =
    val (dPred, dArgs) = visible("d", Some(snapshotId))
    val dataFiles      = query(
      s"SELECT d.path FROM ducklake_data_file d WHERE $dPred",
      dArgs*
    )(_.getString("path"))
    val (delPred, delArgs) = visible("del", Some(snapshotId))
    val deleteFiles        = query(
      s"SELECT del.path FROM ducklake_delete_file del WHERE $delPred",
      delArgs*
    )(_.getString("path"))
    (dataFiles ++ deleteFiles).toSet

  def getTable(
      schema: String,
      table: String,
      asOf: Option[Long] = None
  ): Option[CatalogTableDetailResponse] =
    if asOf.exists(n => !snapshotExists(n)) then None
    else
      val header = asOf match
        case None    => listTables(schema).find(_.name == table)
        case Some(n) => tableHeaderAsOf(schema, table, n)
      header.map { h =>
        CatalogTableDetailResponse(
          table = h,
          columns = listColumns(schema, table, asOf),
          dataFiles = listDataFiles(schema, table, asOf)
        )
      }

  /** AS OF rowCount is computed as visible-data-file rows minus visible-delete-file rows, so it
    * excludes inlined rows not yet flushed to parquet. The current-state path reads
    * `ducklake_table_stats.record_count`, which includes them; on a write-hot table "current" and
    * "as of the newest snapshot" can therefore briefly disagree until the next flush.
    */
  private def tableHeaderAsOf(schema: String, table: String, n: Long): Option[CatalogTableEntry] =
    val (sPred, sArgs) = visible("s", Some(n))
    val (tPred, tArgs) = visible("t", Some(n))
    val (dPred, dArgs) = visible("d", Some(n))
    val sql            =
      s"""SELECT s.schema_name,
         |       t.table_name,
         |       count(d.data_file_id)            AS data_file_count,
         |       coalesce(sum(d.record_count), 0) AS rows_in_files,
         |       NULLIF(
         |         CASE WHEN NOT t.path_is_relative THEN t.path
         |              WHEN NOT s.path_is_relative THEN s.path || t.path
         |              ELSE coalesce(
         |                     (SELECT value FROM ducklake_metadata
         |                       WHERE key = 'data_path' LIMIT 1), ''
         |                   ) || s.path || t.path
         |         END
         |       , '') AS folder
         |  FROM ducklake_schema s
         |  JOIN ducklake_table t ON t.schema_id = s.schema_id
         |  LEFT JOIN ducklake_data_file d ON d.table_id = t.table_id AND $dPred
         | WHERE s.schema_name = ? AND t.table_name = ?
         |   AND $sPred AND $tPred
         | GROUP BY s.schema_name, t.table_name,
         |          t.path_is_relative, t.path, s.path_is_relative, s.path""".stripMargin
    val (delFilePred, delFileArgs) = visible("del", Some(n))
    val deletedSql                 =
      s"""SELECT coalesce(sum(del.delete_count), 0) AS deleted
         |  FROM ducklake_delete_file del
         |  JOIN ducklake_table t   ON t.table_id  = del.table_id
         |  JOIN ducklake_schema s  ON s.schema_id = t.schema_id
         | WHERE s.schema_name = ? AND t.table_name = ?
         |   AND $sPred AND $tPred AND $delFilePred""".stripMargin
    val deleted = query(
      deletedSql,
      (List(schema, table) ++ sArgs ++ tArgs ++ delFileArgs)*
    )(_.getLong("deleted")).headOption.getOrElse(0L)
    query(sql, (dArgs ++ List(schema, table) ++ sArgs ++ tArgs)*) { rs =>
      CatalogTableEntry(
        schema = rs.getString("schema_name"),
        name = rs.getString("table_name"),
        rowCount = rs.getLong("rows_in_files") - deleted,
        dataFileCount = rs.getInt("data_file_count"),
        folder = Option(rs.getString("folder"))
      )
    }.headOption

  /** Returns just the column names for (schema, table), in declaration order. Used by the
    * column-level-security rewriter to expand `SELECT *`. Reuses the same metadata query as
    * `listColumns` but skips the type / nullable fields.
    */
  def columnNames(schema: String, table: String): List[String] =
    listColumns(schema, table).map(_.name)

  /** Public, snapshot-pinned form of the as-of column listing (Task 1 / time-travel viewer). Thin
    * wrapper: `listColumns` already accepts `asOf`, this just names the pinned form so callers
    * outside the package don't have to pass `asOf` as a bare `Option[Long]`.
    */
  def columnsAt(schema: String, table: String, snapshotId: Long): List[CatalogColumnEntry] =
    listColumns(schema, table, Some(snapshotId))

  def listColumns(
      schema: String,
      table: String,
      asOf: Option[Long] = None
  ): List[CatalogColumnEntry] =
    // DuckLake (as of v0.3, DuckDB 1.5.x) doesn't ship a
    // `ducklake_table_constraint` table - PRIMARY KEY / UNIQUE constraints
    // are rejected at CREATE TABLE time. Until the metadata schema gains a
    // way to mark primary keys, we always return isPrimaryKey = false.
    val (sPred, sArgs) = visible("s", asOf)
    val (tPred, tArgs) = visible("t", asOf)
    val (cPred, cArgs) = visible("c", asOf)
    val sql            =
      s"""SELECT c.column_order,
         |       c.column_name,
         |       c.column_type,
         |       c.nulls_allowed
         |  FROM ducklake_schema s
         |  JOIN ducklake_table   t  ON t.schema_id = s.schema_id
         |  JOIN ducklake_column  c  ON c.table_id  = t.table_id
         | WHERE s.schema_name = ?
         |   AND t.table_name  = ?
         |   AND $sPred AND $tPred AND $cPred
         | ORDER BY c.column_order""".stripMargin
    query(sql, (List(schema, table) ++ sArgs ++ tArgs ++ cArgs)*) { rs =>
      CatalogColumnEntry(
        ordinal = rs.getInt("column_order"),
        name = rs.getString("column_name"),
        typeName = rs.getString("column_type"),
        nullable = rs.getBoolean("nulls_allowed"),
        isPrimaryKey = false
      )
    }

  private def listDataFiles(
      schema: String,
      table: String,
      asOf: Option[Long] = None
  ): List[CatalogDataFileEntry] =
    val (sPred, sArgs) = visible("s", asOf)
    val (tPred, tArgs) = visible("t", asOf)
    val (dPred, dArgs) = visible("d", asOf)
    val sql            =
      s"""SELECT d.path,
         |       d.file_size_bytes,
         |       d.record_count,
         |       d.begin_snapshot
         |  FROM ducklake_schema s
         |  JOIN ducklake_table   t  ON t.schema_id = s.schema_id
         |  JOIN ducklake_data_file d ON d.table_id = t.table_id
         | WHERE s.schema_name = ?
         |   AND t.table_name  = ?
         |   AND $sPred AND $tPred AND $dPred
         | ORDER BY d.path""".stripMargin
    query(sql, (List(schema, table) ++ sArgs ++ tArgs ++ dArgs)*) { rs =>
      CatalogDataFileEntry(
        path = rs.getString("path"),
        sizeBytes = rs.getLong("file_size_bytes"),
        rowCount = rs.getLong("record_count"),
        snapshotId = rs.getLong("begin_snapshot")
      )
    }

  /** All snapshots, newest first. Counts are computed from `ducklake_data_file`: a file "added at"
    * snapshot n has begin_snapshot = n, "removed at" n has end_snapshot = n. `affectedTables`
    * resolves the table ids referenced by `changes_made` to (schema, table) names visible at that
    * snapshot. `author` / `commitMessage` are the P1 stamping columns - they live on
    * `ducklake_snapshot_changes`, not `ducklake_snapshot` (verified against a live DuckLake 0.3
    * catalog; `information_schema.columns` for `ducklake_snapshot` carries only
    * `snapshot_id/snapshot_time/schema_version/next_catalog_id/next_file_id`). Both are SQL NULL
    * (not empty string) on an unstamped snapshot, so `Option(rs.getString(...))` maps them to None
    * directly.
    *
    * `limit` is the page size (caller is responsible for clamping). `before` is the exclusive
    * keyset cursor: only snapshots with snapshot_id strictly less than `before` are returned.
    * `table` is an optional (schema, table) filter: only snapshots whose `affectedTables` include
    * it survive. Filtering happens after resolving `affectedTables` (same in-memory parse
    * `affectedTables` already does - no second SQL-level parser for `changes_made`), and `limit` is
    * applied AFTER the filter so a caller asking for the last 20 snapshots touching one table
    * actually gets 20, not up to 20 after truncating the unfiltered page.
    *
    * The SQL-side `LIMIT` is `limit` exactly when `table` is None (the UI panel's hot path stays a
    * bounded read, identical to the pre-filter behavior). When `table` is set, the SQL window is
    * widened to `limit * 50` newest-first rows before the Scala-side filter runs - a best-effort
    * window, not a full-history guarantee: a table touched fewer than `limit` times within the
    * newest `limit * 50` snapshots returns fewer rows than a full scan would, but a pathological
    * catalog with a huge snapshot history can never drag the listing into an unbounded scan.
    */
  def listSnapshots(
      limit: Int = 200,
      before: Option[Long] = None,
      table: Option[(String, String)] = None
  ): List[CatalogSnapshotEntry] =
    val (whereClause, whereParams) = before match
      case None    => ("", Nil)
      case Some(n) => ("\n WHERE s.snapshot_id < ?", List(n))
    val fetchLimit = table match
      case None    => limit
      case Some(_) => math.min(limit.toLong * 50L, Int.MaxValue.toLong).toInt
    val snapSql =
      s"""SELECT s.snapshot_id,
         |       s.snapshot_time,
         |       s.schema_version,
         |       coalesce(c.changes_made, '')       AS changes_made,
         |       c.author                           AS author,
         |       c.commit_message                    AS commit_message,
         |       coalesce(added.rows_added, 0)      AS rows_added,
         |       coalesce(added.files_added, 0)     AS files_added,
         |       coalesce(removed.files_removed, 0) AS files_removed
         |  FROM ducklake_snapshot s
         |  LEFT JOIN ducklake_snapshot_changes c ON c.snapshot_id = s.snapshot_id
         |  LEFT JOIN (SELECT begin_snapshot   AS sid,
         |                    sum(record_count) AS rows_added,
         |                    count(*)          AS files_added
         |               FROM ducklake_data_file
         |              GROUP BY begin_snapshot) added ON added.sid = s.snapshot_id
         |  LEFT JOIN (SELECT end_snapshot AS sid,
         |                    count(*)     AS files_removed
         |               FROM ducklake_data_file
         |              WHERE end_snapshot IS NOT NULL
         |              GROUP BY end_snapshot) removed ON removed.sid = s.snapshot_id$whereClause
         | ORDER BY s.snapshot_id DESC
         | LIMIT ?""".stripMargin
    val rows = query(snapSql, (whereParams :+ fetchLimit)*) { rs =>
      (
        rs.getLong("snapshot_id"),
        rs.getTimestamp("snapshot_time").toInstant.toString,
        rs.getLong("schema_version"),
        rs.getString("changes_made"),
        Option(rs.getString("author")),
        Option(rs.getString("commit_message")),
        rs.getLong("rows_added"),
        rs.getInt("files_added"),
        rs.getInt("files_removed")
      )
    }
    // Full (small) table-version history, resolved in memory per snapshot so
    // the listing stays at two round trips instead of one per snapshot.
    val history = query(
      """SELECT t.table_id,
        |       sch.schema_name,
        |       t.table_name,
        |       t.begin_snapshot,
        |       t.end_snapshot
        |  FROM ducklake_table t
        |  JOIN ducklake_schema sch ON sch.schema_id = t.schema_id""".stripMargin
    ) { rs =>
      TableVersion(
        rs.getLong("table_id"),
        rs.getString("schema_name"),
        rs.getString("table_name"),
        rs.getLong("begin_snapshot"),
        Option(rs.getObject("end_snapshot")).map(_ => rs.getLong("end_snapshot"))
      )
    }
    val entries =
      rows.map {
        case (sid, at, ver, changes, author, commitMessage, rowsAdded, filesAdded, filesRemoved) =>
          CatalogSnapshotEntry(
            snapshotId = sid,
            committedAt = at,
            schemaVersion = ver,
            changes = changes,
            rowsAdded = rowsAdded,
            filesAdded = filesAdded,
            filesRemoved = filesRemoved,
            affectedTables = affectedTables(sid, changes, history),
            author = author,
            commitMessage = commitMessage
          )
      }
    val filtered = table match
      case None                    => entries
      case Some((schema, tblName)) =>
        entries.filter(_.affectedTables.exists(t => t.schema == schema && t.name == tblName))
    filtered.take(limit)

  private case class TableVersion(
      tableId: Long,
      schema: String,
      name: String,
      begin: Long,
      end: Option[Long]
  )

  /** Verbs in changes_made whose payload references a table. */
  private val TableChangeVerbs = Set(
    "created_table",
    "dropped_table",
    "altered_table",
    "inserted_into_table",
    "deleted_from_table",
    "compacted_table"
  )

  /** Parses changes_made (comma-separated `verb:payload` entries) and resolves table references to
    * names. Payloads are numeric table ids in older DuckLake versions and quoted qualified names
    * ("schema"."table") in newer ones; both are handled. Unresolvable ids are skipped (the raw
    * string still shows them).
    */
  private def affectedTables(
      sid: Long,
      changes: String,
      history: List[TableVersion]
  ): List[CatalogTableRef] =
    val refs = changes.split(",").toList.map(_.trim).filter(_.nonEmpty).flatMap { entry =>
      val sep = entry.indexOf(':')
      if sep <= 0 then Nil
      else
        val verb    = entry.substring(0, sep)
        val payload = entry.substring(sep + 1)
        if !TableChangeVerbs.contains(verb) then Nil
        else
          payload.toLongOption match
            case Some(id) =>
              // Visible at sid; `end >= sid` (not `>`) so a table dropped AT
              // sid still resolves for its own drop entry.
              history
                .find(v => v.tableId == id && v.begin <= sid && v.end.forall(_ >= sid))
                .map(v => CatalogTableRef(v.schema, v.name))
                .toList
            case None =>
              payload.split("\"\\.\"").toList match
                case s :: t :: Nil =>
                  List(CatalogTableRef(s.stripPrefix("\""), t.stripSuffix("\"")))
                case _ => Nil
    }
    refs.distinct

  /** table_id of `(schema, table)` in the CURRENT catalog state. History identity: all history
    * predicates key on this id, so the timeline survives renames (a rename inserts a new
    * `ducklake_table` row under the SAME table_id; see `tableIdAt`).
    */
  private def currentTableId(schema: String, table: String): Option[Long] =
    query(
      """SELECT t.table_id
        |  FROM ducklake_schema s
        |  JOIN ducklake_table t ON t.schema_id = s.schema_id
        | WHERE s.schema_name = ? AND t.table_name = ?
        |   AND s.end_snapshot IS NULL AND t.end_snapshot IS NULL""".stripMargin,
      schema,
      table
    )(_.getLong("table_id")).headOption

  /** Escapes POSIX-regex metacharacters so identifier names can be embedded in a `~` pattern. */
  private def regexEscape(s: String): String =
    s.replaceAll("""([.^$*+?()\[\]{}|\\])""", """\\$1""")

  /** Escapes SQL LIKE metacharacters (`%`, `_`) so identifier names can be embedded in a `LIKE`
    * pattern without accidentally matching more than the literal path segment.
    */
  private def likeEscape(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  /** POSIX regex matching one `verb:payload` entry of `changes_made` that references this table, in
    * either payload form DuckLake emits (numeric table id on older versions, quoted qualified name
    * on newer ones - the same duality `affectedTables` parses in Scala).
    */
  private def changeRefPattern(
      verbs: List[String],
      tableId: Long,
      schema: String,
      table: String
  ): String =
    val quotedName = regexEscape(s"\"$schema\".\"$table\"")
    s"(^|,)(${verbs.mkString("|")}):($tableId|$quotedName)(,|$$)"

  /** Per-table commit history, newest first (EPIC Spec 01). One aggregated SQL query per page:
    * membership, classification (SQL CASE - single source of truth so the `operation` filter and
    * `hasMore` stay exact), row/file deltas, and all filters are computed in the catalog DB; Scala
    * applies only `HistoryOperation.effectiveDeltas` on the way out. `rowsRemoved` is the
    * delete-file DELTA (new delete file count minus the superseded one's), NOT an
    * `end_snapshot = S` sum - delete files are backdated two-phase (see `visible`) and a naive sum
    * would report compaction rewrites as phantom mass deletes. `filesRemoved` adds a `sched` CTE on
    * top of the `end_snapshot`-based `tr` count: verified against a live catalog,
    * `ducklake_merge_adjacent_files` does NOT end-date the superseded `ducklake_data_file` rows -
    * it hard-deletes them and appends their paths to `ducklake_files_scheduled_for_deletion`
    * (already used unscoped by `filesScheduledForDeletion`), which carries no `table_id` or
    * `snapshot_id` column. `schedule_start` matches the triggering snapshot's `snapshot_time`
    * exactly (same transaction), so `sched` joins on that equality and scopes to this table via a
    * `path LIKE 'schema/table/%'` prefix. That prefix scoping UNDERCOUNTS in two known cases: a
    * renamed table (rows scheduled under the old name no longer match the current `schema/table/%`
    * prefix) and files stored with `path_is_relative = false` (absolute paths never match the
    * relative prefix); both make `filesRemoved` read low, never high. `sched` deliberately does NOT
    * participate in the membership WHERE below - `schedule_start = snapshot_time` is a
    * timestamp-equality join with no snapshot_id/table_id on the DuckLake side, so on a timestamp
    * collision between two snapshots it could admit a foreign snapshot into this table's timeline;
    * compaction snapshots are already members via `tf` (the merged output file's
    * `ducklake_data_file` row has `begin_snapshot` = the compaction snapshot).
    * `tr.files_removed + sched.n` is a plain sum, not `greatest(...)`: verified against a live
    * catalog that the two never populate for the same `snapshot_id` - DuckLake rejects a
    * transaction that both writes data and compacts ("Transactions can either make changes OR
    * perform compaction - not both"), and separate DML vs. compaction transactions land on
    * different snapshot_ids, so `tr` (keyed by a DML snapshot's end_snapshot) and `sched` (keyed by
    * a compaction snapshot's own snapshot_id) are structurally disjoint keys; re-verify this
    * invariant on a DuckLake pin bump. Row/file deltas are computed from this live metadata, so
    * they decay once maintenance has run: `filesRemoved` reads
    * `ducklake_files_scheduled_for_deletion`, whose rows are consumed by
    * `ducklake_cleanup_old_files` (part of the shipped Spec 09 maintenance chain), so a commit's
    * `filesRemoved` reverts to 0 after cleanup runs; and `merge_adjacent` hard-deletes the
    * superseded `ducklake_data_file` rows, so an insert commit's `rowsAdded`/`filesAdded` are lost
    * once compaction has run over it. None = table name unknown in the current catalog state.
    */
  def listTableHistory(
      schema: String,
      table: String,
      filter: TableHistoryFilter = TableHistoryFilter(),
      limit: Int = 50,
      before: Option[Long] = None
  ): Option[TableHistoryPage] =
    currentTableId(schema, table).map { tid =>
      val creation = earliestBeginSnapshot(tid).getOrElse(-1L)
      // Verb names pinned against a live DuckLake 0.3 (DuckDB 1.5.4) catalog - see
      // task-2-report.md for the raw `changes_made` dump. Two verbs differ from the DuckLake docs'
      // naming: unflushed inline writes emit `inlined_insert` / `inlined_delete` (NOT
      // `inserted_into_table` / `deleted_from_table`, which only appear once a write is flushed to
      // a real data/delete file), and adjacent-file compaction emits `merge_adjacent` (NOT
      // `compacted_table`).
      val allVerbs = List(
        "created_table",
        "dropped_table",
        "altered_table",
        "inserted_into_table",
        "inlined_insert",
        "deleted_from_table",
        "inlined_delete",
        "merge_adjacent"
      )
      val insRef =
        changeRefPattern(List("inserted_into_table", "inlined_insert"), tid, schema, table)
      val delRef =
        changeRefPattern(List("deleted_from_table", "inlined_delete"), tid, schema, table)
      val compRef  = changeRefPattern(List("merge_adjacent"), tid, schema, table)
      val anyRef   = changeRefPattern(allVerbs, tid, schema, table)
      val pathLike = s"${likeEscape(schema)}/${likeEscape(table)}/%"

      val beforePred = if before.isDefined then "\n     AND s.snapshot_id < ?" else ""
      val fromPred   = if filter.from.isDefined then "\n     AND s.snapshot_time >= ?" else ""
      val toPred     = if filter.to.isDefined then "\n     AND s.snapshot_time <= ?" else ""
      val authorPred = if filter.author.isDefined then "\n     AND c.author = ?" else ""
      val opPred     = if filter.operation.isDefined then "\n WHERE h.operation = ?" else ""

      val sql =
        s"""WITH tf AS (SELECT begin_snapshot AS sid, sum(record_count) AS rows_added,
           |                   count(*) AS files_added
           |              FROM ducklake_data_file WHERE table_id = ? GROUP BY 1),
           |     tr AS (SELECT end_snapshot AS sid, sum(record_count) AS rows_retired,
           |                   count(*) AS files_removed
           |              FROM ducklake_data_file
           |             WHERE table_id = ? AND end_snapshot IS NOT NULL GROUP BY 1),
           |     dn AS (SELECT begin_snapshot AS sid, sum(delete_count) AS del_new,
           |                   count(*) AS del_files
           |              FROM ducklake_delete_file WHERE table_id = ? GROUP BY 1),
           |     dold AS (SELECT end_snapshot AS sid, sum(delete_count) AS del_old
           |                FROM ducklake_delete_file
           |               WHERE table_id = ? AND end_snapshot IS NOT NULL GROUP BY 1),
           |     tb AS (SELECT begin_snapshot AS sid, count(*) AS n
           |              FROM ducklake_table WHERE table_id = ? GROUP BY 1),
           |     te AS (SELECT end_snapshot AS sid, count(*) AS n
           |              FROM ducklake_table
           |             WHERE table_id = ? AND end_snapshot IS NOT NULL GROUP BY 1),
           |     cb AS (SELECT begin_snapshot AS sid, count(*) AS n
           |              FROM ducklake_column WHERE table_id = ? GROUP BY 1),
           |     ce AS (SELECT end_snapshot AS sid, count(*) AS n
           |              FROM ducklake_column
           |             WHERE table_id = ? AND end_snapshot IS NOT NULL GROUP BY 1),
           |     sched AS (SELECT s2.snapshot_id AS sid, count(*) AS n
           |                 FROM ducklake_files_scheduled_for_deletion d
           |                 JOIN ducklake_snapshot s2 ON s2.snapshot_time = d.schedule_start
           |                WHERE d.path LIKE ? ESCAPE '\' GROUP BY 1)
           |SELECT * FROM (
           |  SELECT s.snapshot_id,
           |         s.snapshot_time,
           |         s.schema_version,
           |         c.author,
           |         c.commit_message,
           |         coalesce(tf.rows_added, 0)    AS rows_added,
           |         coalesce(tf.files_added, 0)   AS files_added,
           |         coalesce(tr.rows_retired, 0)  AS rows_retired,
           |         coalesce(tr.files_removed, 0) + coalesce(sched.n, 0) AS files_removed,
           |         greatest(coalesce(dn.del_new, 0) - coalesce(dold.del_old, 0), 0)
           |           AS rows_deleted,
           |         ((coalesce(cb.n, 0) + coalesce(ce.n, 0)) > 0 AND s.snapshot_id <> ?)
           |           AS schema_changed,
           |         CASE
           |           WHEN s.snapshot_id = ? THEN 'create'
           |           WHEN coalesce(te.n, 0) > 0 AND coalesce(tb.n, 0) = 0 THEN 'drop'
           |           WHEN coalesce(tb.n, 0) > 0 OR coalesce(cb.n, 0) > 0
           |                OR coalesce(ce.n, 0) > 0 THEN 'alter'
           |           WHEN coalesce(c.changes_made, '') ~ ? THEN 'maintenance'
           |           WHEN (coalesce(dn.del_files, 0) > 0 OR coalesce(c.changes_made, '') ~ ?)
           |            AND (coalesce(tf.files_added, 0) > 0 OR coalesce(c.changes_made, '') ~ ?)
           |             THEN 'update'
           |           WHEN coalesce(dn.del_files, 0) > 0 OR coalesce(tr.files_removed, 0) > 0
           |                OR coalesce(c.changes_made, '') ~ ? THEN 'delete'
           |           WHEN coalesce(tf.files_added, 0) > 0
           |                OR coalesce(c.changes_made, '') ~ ? THEN 'insert'
           |           ELSE 'unknown'
           |         END AS operation
           |    FROM ducklake_snapshot s
           |    LEFT JOIN ducklake_snapshot_changes c ON c.snapshot_id = s.snapshot_id
           |    LEFT JOIN tf   ON tf.sid   = s.snapshot_id
           |    LEFT JOIN tr   ON tr.sid   = s.snapshot_id
           |    LEFT JOIN dn   ON dn.sid   = s.snapshot_id
           |    LEFT JOIN dold ON dold.sid = s.snapshot_id
           |    LEFT JOIN tb   ON tb.sid   = s.snapshot_id
           |    LEFT JOIN te   ON te.sid   = s.snapshot_id
           |    LEFT JOIN cb    ON cb.sid    = s.snapshot_id
           |    LEFT JOIN ce    ON ce.sid    = s.snapshot_id
           |    LEFT JOIN sched ON sched.sid = s.snapshot_id
           |   WHERE (tf.sid IS NOT NULL OR tr.sid IS NOT NULL OR dn.sid IS NOT NULL
           |          OR dold.sid IS NOT NULL OR tb.sid IS NOT NULL OR te.sid IS NOT NULL
           |          OR cb.sid IS NOT NULL OR ce.sid IS NOT NULL
           |          OR coalesce(c.changes_made, '') ~ ?)$beforePred$fromPred$toPred$authorPred
           |) h$opPred
           |ORDER BY h.snapshot_id DESC
           |LIMIT ?""".stripMargin

      val params: List[Any] =
        List[Any](tid, tid, tid, tid, tid, tid, tid, tid) ++ // 8 CTEs
          List[Any](pathLike) ++                             // sched CTE
          List[Any](creation, creation, compRef, delRef, insRef, delRef, insRef) ++
          List[Any](anyRef) ++ // membership
          before.toList ++
          filter.from.map(java.sql.Timestamp.from).toList ++
          filter.to.map(java.sql.Timestamp.from).toList ++
          filter.author.toList ++
          filter.operation.toList ++
          List[Any](limit + 1) // hasMore sentinel

      val raw = query(sql, params*) { rs =>
        val op                       = rs.getString("operation")
        val (rowsAdded, rowsRemoved) = HistoryOperation.effectiveDeltas(
          op,
          rs.getLong("rows_added"),
          rs.getLong("rows_retired"),
          rs.getLong("rows_deleted")
        )
        CatalogHistoryCommit(
          snapshotId = rs.getLong("snapshot_id"),
          committedAt = rs.getTimestamp("snapshot_time").toInstant.toString,
          operation = op,
          author = Option(rs.getString("author")),
          commitMessage = Option(rs.getString("commit_message")),
          schemaChanged = rs.getBoolean("schema_changed"),
          schemaVersion = rs.getLong("schema_version"),
          rowsAdded = rowsAdded,
          rowsRemoved = rowsRemoved,
          filesAdded = rs.getInt("files_added"),
          filesRemoved = rs.getInt("files_removed")
        )
      }
      TableHistoryPage(tid, raw.take(limit), hasMore = raw.length > limit)
    }

  /** Resolves `table_id` for (schema, table) visible at snapshot `n`. Table identity survives a
    * rename (verified against a live catalog: `ALTER TABLE ... RENAME TO` inserts a new
    * `ducklake_table` row with the SAME `table_id`, end-dating the old name's row) - so resolving
    * by name at a specific snapshot and then walking columns by that id is rename-proof, unlike
    * resolving columns by name at each snapshot independently.
    */
  private def tableIdAt(schema: String, table: String, n: Long): Option[Long] =
    val (sPred, sArgs) = visible("s", Some(n))
    val (tPred, tArgs) = visible("t", Some(n))
    query(
      s"""SELECT t.table_id
         |  FROM ducklake_schema s
         |  JOIN ducklake_table  t ON t.schema_id = s.schema_id
         | WHERE s.schema_name = ? AND t.table_name = ?
         |   AND $sPred AND $tPred""".stripMargin,
      (List(schema, table) ++ sArgs ++ tArgs)*
    )(_.getLong("table_id")).headOption

  /** Cheap existence probe: does `(schema, table)` resolve to a `table_id` visible at snapshot
    * `snapshotId`? One single-row SELECT (delegating to `tableIdAt`, the same query `schemaDiff`
    * resolves identity with), exposed publicly so the schema-diff handler can 404 an unknown table
    * without paying `getTable`'s multi-query cost (header + delete-count + columns + data files).
    */
  def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
    tableIdAt(schema, table, snapshotId).isDefined

  /** Columns visible at snapshot `n` for a table pinned by `table_id` (not name) - the rename-proof
    * counterpart to `listColumns`/`columnsAt`, used internally by `schemaDiff`.
    */
  private def columnsAtByTableId(tableId: Long, n: Long): List[CatalogColumnEntry] =
    val (cPred, cArgs) = visible("c", Some(n))
    query(
      s"""SELECT c.column_order, c.column_name, c.column_type, c.nulls_allowed
         |  FROM ducklake_column c
         | WHERE c.table_id = ? AND $cPred
         | ORDER BY c.column_order""".stripMargin,
      (List[Any](tableId) ++ cArgs)*
    ) { rs =>
      CatalogColumnEntry(
        ordinal = rs.getInt("column_order"),
        name = rs.getString("column_name"),
        typeName = rs.getString("column_type"),
        nullable = rs.getBoolean("nulls_allowed"),
        isPrimaryKey = false
      )
    }

  /** Earliest `begin_snapshot` across every `ducklake_table` row sharing `tableId` - the table's
    * true creation point across renames (a rename inserts a new row under the SAME table_id rather
    * than updating in place; see `tableIdAt`). None when the aggregate comes back NULL, i.e. no
    * `ducklake_table` row carries this id - impossible when the id was just resolved by
    * `tableIdAt`, but surfaced as an Option (and warned on by the caller) rather than silently
    * defaulting.
    */
  private def earliestBeginSnapshot(tableId: Long): Option[Long] =
    query(
      "SELECT min(begin_snapshot) AS m FROM ducklake_table WHERE table_id = ?",
      tableId
    )(rs => Option(rs.getObject("m")).map(_ => rs.getLong("m"))).headOption.flatten

  /** Column-level diff of one table between two snapshots (EPIC time-travel viewer). `table` is
    * resolved by its CURRENT name at `to`; if that name doesn't exist yet at `to` (e.g. comparing
    * against an earlier pre-rename snapshot where only the old name existed) we fall back to
    * resolving at `from` instead, so a rename between the two snapshots doesn't make the table
    * "disappear" from the diff. Columns are then loaded at both ends keyed by that stable
    * `table_id`, so `added`/`removed` reflect real column lifecycle instead of a name coincidence.
    *
    * `from` is clamped up to the table's own earliest `begin_snapshot` when the caller passes a
    * `from` that predates the table's creation (e.g. diffing against the catalog's very first
    * snapshot): "columns at a point before the table existed" is otherwise vacuously empty, which
    * would misreport every column the table has ever had as "added" instead of surfacing the ones
    * dropped since creation.
    */
  def schemaDiff(schema: String, table: String, from: Long, to: Long): SchemaDiffResult =
    val tableId = tableIdAt(schema, table, to).orElse(tableIdAt(schema, table, from))
    tableId match
      case None     => SchemaDiffResult(Nil, Nil, Nil, Nil)
      case Some(id) =>
        val effectiveFrom = earliestBeginSnapshot(id) match
          case Some(created) => from.max(created)
          case None          =>
            logger.warn(
              s"schemaDiff: no ducklake_table row for table_id=$id " +
                s"($schema.$table) despite name resolution; using from=$from unclamped"
            )
            from
        val colsFrom    = columnsAtByTableId(id, effectiveFrom)
        val colsTo      = columnsAtByTableId(id, to)
        val byNameFrom  = colsFrom.map(c => c.name -> c).toMap
        val byNameTo    = colsTo.map(c => c.name -> c).toMap
        val added       = colsTo.filterNot(c => byNameFrom.contains(c.name))
        val removed     = colsFrom.filterNot(c => byNameTo.contains(c.name))
        val common      = byNameFrom.keySet.intersect(byNameTo.keySet).toList.sorted
        val typeChanged = common.flatMap { name =>
          val f = byNameFrom(name)
          val t = byNameTo(name)
          if f.typeName != t.typeName then Some((name, f.typeName, t.typeName)) else None
        }
        val nullabilityChanged = common.flatMap { name =>
          val f = byNameFrom(name)
          val t = byNameTo(name)
          if f.nullable != t.nullable then Some((name, f.nullable, t.nullable)) else None
        }
        SchemaDiffResult(added, removed, typeChanged, nullabilityChanged)

  private def query[A](sql: String, params: Any*)(map: ResultSet => A): List[A] =
    val conn = ds.getConnection
    try
      val ps = conn.prepareStatement(sql)
      try
        params.zipWithIndex.foreach { case (p, i) =>
          ps.setObject(i + 1, p.asInstanceOf[AnyRef])
        }
        val rs  = ps.executeQuery()
        val acc = ListBuffer.empty[A]
        try
          while rs.next() do acc += map(rs)
        finally rs.close()
        acc.toList
      finally ps.close()
    finally conn.close()

  def close(): Unit = ds.close()

object DuckLakeCatalogReader:

  /** Build a reader from a resolved metastore map (the same shape
    * `PoolSupervisor.metastoreFor(tenant)` produces).
    */
  def apply(meta: Map[String, String]): DuckLakeCatalogReader =
    val cfg = new HikariConfig()
    cfg.setJdbcUrl(
      s"jdbc:postgresql://${meta("pgHost")}:${meta("pgPort")}/${meta("dbName")}"
    )
    cfg.setUsername(meta("pgUser"))
    cfg.setPassword(meta("pgPassword"))
    cfg.setMaximumPoolSize(2)
    cfg.setPoolName(s"ducklake-cat-${meta("dbName")}")
    // catalog Postgres may be down; tableCount/browse degrade instead of stalling list calls for the 30s default
    cfg.setConnectionTimeout(5000)
    cfg.setInitializationFailTimeout(-1)
    new DuckLakeCatalogReader(new HikariDataSource(cfg))
