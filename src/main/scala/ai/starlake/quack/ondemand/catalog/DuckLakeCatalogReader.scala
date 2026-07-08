package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogSchemaEntry,
  CatalogSnapshotEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry,
  CatalogTableRef
}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.ResultSet
import scala.collection.mutable.ListBuffer

/** Reads schemas / tables / columns / data files out of the DuckLake metadata tables. The metadata
  * schema is fixed by DuckLake itself (`ducklake_schema`, `ducklake_table`, `ducklake_column`,
  * `ducklake_data_file`) and lives in the same Postgres DB the catalog is attached to.
  *
  * `meta` is the resolved per-tenant map produced by `PoolSupervisor.metastoreFor(...)` - same
  * shape the rest of the stack uses.
  */
class DuckLakeCatalogReader(private val ds: HikariDataSource):

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
    * snapshot.
    *
    * `limit` is the page size (caller is responsible for clamping). `before` is the exclusive
    * keyset cursor: only snapshots with snapshot_id strictly less than `before` are returned.
    */
  def listSnapshots(limit: Int = 200, before: Option[Long] = None): List[CatalogSnapshotEntry] =
    val (whereClause, whereParams) = before match
      case None    => ("", Nil)
      case Some(n) => ("\n WHERE s.snapshot_id < ?", List(n))
    val snapSql =
      s"""SELECT s.snapshot_id,
         |       s.snapshot_time,
         |       s.schema_version,
         |       coalesce(c.changes_made, '')       AS changes_made,
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
    val snapParams = whereParams :+ limit
    val rows       = query(snapSql, snapParams*) { rs =>
      (
        rs.getLong("snapshot_id"),
        rs.getTimestamp("snapshot_time").toInstant.toString,
        rs.getLong("schema_version"),
        rs.getString("changes_made"),
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
    rows.map { case (sid, at, ver, changes, rowsAdded, filesAdded, filesRemoved) =>
      CatalogSnapshotEntry(
        snapshotId = sid,
        committedAt = at,
        schemaVersion = ver,
        changes = changes,
        rowsAdded = rowsAdded,
        filesAdded = filesAdded,
        filesRemoved = filesRemoved,
        affectedTables = affectedTables(sid, changes, history)
      )
    }

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
