package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogSchemaEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry
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

  def getTable(schema: String, table: String): Option[CatalogTableDetailResponse] =
    listTables(schema).find(_.name == table).map { header =>
      CatalogTableDetailResponse(
        table = header,
        columns = listColumns(schema, table),
        dataFiles = listDataFiles(schema, table)
      )
    }

  /** Returns just the column names for (schema, table), in declaration order. Used by the
    * column-level-security rewriter to expand `SELECT *`. Reuses the same metadata query as
    * `listColumns` but skips the type / nullable fields.
    */
  def columnNames(schema: String, table: String): List[String] =
    listColumns(schema, table).map(_.name)

  def listColumns(schema: String, table: String): List[CatalogColumnEntry] =
    // DuckLake (as of v0.3, DuckDB 1.5.x) doesn't ship a
    // `ducklake_table_constraint` table - PRIMARY KEY / UNIQUE constraints
    // are rejected at CREATE TABLE time. Until the metadata schema gains a
    // way to mark primary keys, we always return isPrimaryKey = false.
    val sql =
      """SELECT c.column_order,
        |       c.column_name,
        |       c.column_type,
        |       c.nulls_allowed
        |  FROM ducklake_schema s
        |  JOIN ducklake_table   t  ON t.schema_id  = s.schema_id
        |  JOIN ducklake_column  c  ON c.table_id   = t.table_id
        | WHERE s.schema_name = ?
        |   AND t.table_name  = ?
        |   AND s.end_snapshot IS NULL
        |   AND t.end_snapshot IS NULL
        |   AND c.end_snapshot IS NULL
        | ORDER BY c.column_order""".stripMargin
    query(sql, schema, table) { rs =>
      CatalogColumnEntry(
        ordinal = rs.getInt("column_order"),
        name = rs.getString("column_name"),
        typeName = rs.getString("column_type"),
        nullable = rs.getBoolean("nulls_allowed"),
        isPrimaryKey = false
      )
    }

  private def listDataFiles(schema: String, table: String): List[CatalogDataFileEntry] =
    val sql =
      """SELECT d.path,
        |       d.file_size_bytes,
        |       d.record_count,
        |       d.begin_snapshot
        |  FROM ducklake_schema s
        |  JOIN ducklake_table   t  ON t.schema_id = s.schema_id
        |  JOIN ducklake_data_file d ON d.table_id = t.table_id
        | WHERE s.schema_name = ?
        |   AND t.table_name  = ?
        |   AND s.end_snapshot IS NULL
        |   AND t.end_snapshot IS NULL
        |   AND d.end_snapshot IS NULL
        | ORDER BY d.path""".stripMargin
    query(sql, schema, table) { rs =>
      CatalogDataFileEntry(
        path = rs.getString("path"),
        sizeBytes = rs.getLong("file_size_bytes"),
        rowCount = rs.getLong("record_count"),
        snapshotId = rs.getLong("begin_snapshot")
      )
    }

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
    new DuckLakeCatalogReader(new HikariDataSource(cfg))
