package ai.starlake.quack.ondemand.branch

import com.typesafe.scalalogging.LazyLogging
import org.postgresql.PGConnection

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/** Outcome of one clone: the parent snapshot the copy reflects (the branch's fork point) and copy
  * statistics for logs and audit.
  */
final case class CloneResult(forkSnapshot: Long, tablesCopied: Int, rowsCopied: Long)

/** Zero-copy clone of a DuckLake catalog at the Postgres level (Epic 1, design section 2).
  *
  * Copies every table of the parent's metadata schema into an (existing, empty) target database
  * under ONE `REPEATABLE READ` transaction on the parent, so the copy is a consistent snapshot even
  * while the parent is being written. Then rewrites the copy so it stands on its own:
  *   - every relative data / delete file path becomes the absolute parent location (`data_path ||
  *     schema.path || table.path || path`, `path_is_relative = false`), so the branch reads the
  *     parent's Parquet in place;
  *   - `ducklake_metadata.data_path` points at the branch's own prefix, so new files land there;
  *   - `ducklake_files_scheduled_for_deletion` is truncated: those are the PARENT's files and the
  *     branch must never delete them (branch maintenance is excluded anyway).
  *
  * DDL is derived from `pg_attribute` / `pg_indexes` rather than hard-coded, so the copy is exact
  * for whatever DuckLake metadata version the parent carries (inlined-data tables included). Rows
  * move through `COPY ... TO/FROM STDOUT` in binary form via a temp file per table.
  *
  * Never modifies the parent. On any failure the target transaction is rolled back; the caller owns
  * dropping the target database.
  */
final class BranchCloner(
    pgHost: String,
    pgPort: String,
    pgUser: String,
    pgPassword: String
) extends LazyLogging:

  Class.forName("org.postgresql.Driver")

  private def connect(db: String): Connection =
    DriverManager.getConnection(s"jdbc:postgresql://$pgHost:$pgPort/$db", pgUser, pgPassword)

  private def q(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""

  /** Copy `parentDb` into `branchDb` (already created, empty) and point the copy at
    * `branchDataPath`. Returns the fork snapshot.
    */
  def clone(
      parentDb: String,
      branchDb: String,
      branchDataPath: String
  ): Either[String, CloneResult] =
    val src = connect(parentDb)
    try
      src.setAutoCommit(false)
      src.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
      // A first read pins the snapshot for the rest of the transaction.
      val schema = metaSchema(src).getOrElse(
        return Left(s"database '$parentDb' holds no DuckLake metadata (ducklake_metadata missing)")
      )
      val fork = maxSnapshot(src, schema).getOrElse(
        return Left(s"database '$parentDb' has no snapshots")
      )
      val tables = listTables(src, schema)
      val dst    = connect(branchDb)
      try
        dst.setAutoCommit(false)
        var rows = 0L
        ensureSchema(dst, schema)
        tables.foreach { t =>
          createLike(src, dst, schema, t)
          rows += copyRows(src, dst, schema, t)
        }
        rewrite(dst, schema, branchDataPath)
        dst.commit()
        logger.info(
          s"branch clone: '$parentDb' -> '$branchDb' at snapshot $fork " +
            s"(${tables.size} tables, $rows rows, data_path '$branchDataPath')"
        )
        Right(CloneResult(fork, tables.size, rows))
      catch
        case NonFatal(e) =>
          try dst.rollback()
          catch case NonFatal(_) => ()
          Left(s"clone failed: ${e.getMessage}")
      finally dst.close()
    catch case NonFatal(e) => Left(s"clone failed: ${e.getMessage}")
    finally
      try src.rollback()
      catch case NonFatal(_) => ()
      src.close()

  private def metaSchema(c: Connection): Option[String] =
    val ps = c.prepareStatement(
      "SELECT table_schema FROM information_schema.tables WHERE table_name = 'ducklake_metadata' " +
        "ORDER BY (table_schema = 'public') DESC LIMIT 1"
    )
    try
      val rs = ps.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally ps.close()

  private def maxSnapshot(c: Connection, schema: String): Option[Long] =
    val st = c.createStatement()
    try
      val rs = st.executeQuery(s"SELECT max(snapshot_id) FROM ${q(schema)}.ducklake_snapshot")
      try
        if rs.next() then
          val v = rs.getLong(1)
          if rs.wasNull() then None else Some(v)
        else None
      finally rs.close()
    finally st.close()

  private def listTables(c: Connection, schema: String): List[String] =
    val ps = c.prepareStatement(
      "SELECT table_name FROM information_schema.tables " +
        "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name"
    )
    try
      ps.setString(1, schema)
      val rs  = ps.executeQuery()
      val acc = ListBuffer.empty[String]
      try while rs.next() do acc += rs.getString(1)
      finally rs.close()
      acc.toList
    finally ps.close()

  private def ensureSchema(dst: Connection, schema: String): Unit =
    val st = dst.createStatement()
    try st.execute(s"CREATE SCHEMA IF NOT EXISTS ${q(schema)}")
    finally st.close()

  /** `CREATE TABLE` from the parent's column definitions (exact `format_type` rendering, NOT NULL
    * preserved) plus every index the parent carries on the table (unique indexes back DuckLake's
    * primary keys). Defaults are not carried: DuckLake writes every column explicitly.
    */
  private def createLike(src: Connection, dst: Connection, schema: String, table: String): Unit =
    val cols = {
      val ps = src.prepareStatement(
        """SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS typ, a.attnotnull
          |  FROM pg_attribute a
          |  JOIN pg_class c ON c.oid = a.attrelid
          |  JOIN pg_namespace n ON n.oid = c.relnamespace
          | WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
          | ORDER BY a.attnum""".stripMargin
      )
      try
        ps.setString(1, schema); ps.setString(2, table)
        val rs  = ps.executeQuery()
        val acc = ListBuffer.empty[String]
        try
          while rs.next() do
            val nn = if rs.getBoolean(3) then " NOT NULL" else ""
            acc += s"${q(rs.getString(1))} ${rs.getString(2)}$nn"
        finally rs.close()
        acc.toList
      finally ps.close()
    }
    val indexes = {
      val ps = src.prepareStatement(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ? ORDER BY indexname"
      )
      try
        ps.setString(1, schema); ps.setString(2, table)
        val rs  = ps.executeQuery()
        val acc = ListBuffer.empty[String]
        try while rs.next() do acc += rs.getString(1)
        finally rs.close()
        acc.toList
      finally ps.close()
    }
    val st = dst.createStatement()
    try
      st.execute(s"CREATE TABLE ${q(schema)}.${q(table)} (${cols.mkString(", ")})")
      indexes.foreach(st.execute)
    finally st.close()

  private def copyRows(src: Connection, dst: Connection, schema: String, table: String): Long =
    val tmp: Path = Files.createTempFile("qod-branch-clone-", ".bin")
    try
      val out = Files.newOutputStream(tmp)
      try
        src
          .unwrap(classOf[PGConnection])
          .getCopyAPI
          .copyOut(s"COPY ${q(schema)}.${q(table)} TO STDOUT WITH (FORMAT binary)", out)
      finally out.close()
      val in = Files.newInputStream(tmp)
      try
        dst
          .unwrap(classOf[PGConnection])
          .getCopyAPI
          .copyIn(s"COPY ${q(schema)}.${q(table)} FROM STDIN WITH (FORMAT binary)", in)
      finally in.close()
    finally Files.deleteIfExists(tmp)

  /** The rewrite of design section 2, on the target only. Joins through the CURRENT schema/table
    * rows; a data file of a dropped-but-unexpired table version still resolves because the join is
    * on ids, not on `end_snapshot`. When the parent's `data_path` carries no trailing separator the
    * concatenation would glue segments together, so one is appended defensively (DuckLake itself
    * stores the normalized value with the separator).
    */
  private def rewrite(dst: Connection, schema: String, branchDataPath: String): Unit =
    val s  = q(schema)
    val st = dst.createStatement()
    try
      val parentPath = {
        val rs = st.executeQuery(s"SELECT value FROM $s.ducklake_metadata WHERE key = 'data_path'")
        try if rs.next() then rs.getString(1) else ""
        finally rs.close()
      }
      val base =
        if parentPath.endsWith("/") || parentPath.endsWith("\\") then parentPath
        else parentPath + "/"
      val baseLit = "'" + base.replace("'", "''") + "'"
      // Schema/table paths are relative segments (`tpch1/`, `region/`) unless the operator set an
      // absolute per-table location, which the CASE arms honor.
      def absExpr(fileAlias: String) =
        s"""CASE WHEN NOT t.path_is_relative THEN t.path || $fileAlias.path
           |     WHEN NOT sc.path_is_relative THEN sc.path || t.path || $fileAlias.path
           |     ELSE $baseLit || sc.path || t.path || $fileAlias.path END""".stripMargin
      st.executeUpdate(
        s"""UPDATE $s.ducklake_data_file f
           |   SET path = ${absExpr("f")}, path_is_relative = false
           |  FROM $s.ducklake_table t, $s.ducklake_schema sc
           | WHERE f.path_is_relative AND t.table_id = f.table_id AND sc.schema_id = t.schema_id
           |   AND t.begin_snapshot <= f.begin_snapshot
           |   AND (t.end_snapshot IS NULL OR t.end_snapshot > f.begin_snapshot)
           |   AND sc.begin_snapshot <= f.begin_snapshot
           |   AND (sc.end_snapshot IS NULL OR sc.end_snapshot > f.begin_snapshot)""".stripMargin
      )
      st.executeUpdate(
        s"""UPDATE $s.ducklake_delete_file f
           |   SET path = ${absExpr("f")}, path_is_relative = false
           |  FROM $s.ducklake_table t, $s.ducklake_schema sc
           | WHERE f.path_is_relative AND t.table_id = f.table_id AND sc.schema_id = t.schema_id
           |   AND t.begin_snapshot <= f.begin_snapshot
           |   AND (t.end_snapshot IS NULL OR t.end_snapshot > f.begin_snapshot)
           |   AND sc.begin_snapshot <= f.begin_snapshot
           |   AND (sc.end_snapshot IS NULL OR sc.end_snapshot > f.begin_snapshot)""".stripMargin
      )
      // Anything still relative here belongs to a table version whose row does not cover the
      // file's begin snapshot (should not happen on a spec-conformant catalog); fail closed rather
      // than let the branch resolve it against its own empty prefix.
      val leftover = {
        val rs = st.executeQuery(
          s"SELECT count(*) FROM (SELECT 1 FROM $s.ducklake_data_file WHERE path_is_relative " +
            s"UNION ALL SELECT 1 FROM $s.ducklake_delete_file WHERE path_is_relative) x"
        )
        try
          rs.next(); rs.getLong(1)
        finally rs.close()
      }
      if leftover > 0 then
        throw new IllegalStateException(
          s"$leftover file rows could not be resolved to an absolute parent path"
        )
      st.executeUpdate(s"TRUNCATE $s.ducklake_files_scheduled_for_deletion")
      val bp = "'" + branchDataPath.replace("'", "''") + "'"
      st.executeUpdate(s"UPDATE $s.ducklake_metadata SET value = $bp WHERE key = 'data_path'")
    finally st.close()

object BranchCloner:
  /** Build from a resolved DuckLake metastore map (`pgHost`, `pgPort`, `pgUser`, `pgPassword`). */
  def apply(meta: Map[String, String]): BranchCloner =
    new BranchCloner(
      pgHost = meta.getOrElse("pgHost", "localhost"),
      pgPort = meta.getOrElse("pgPort", "5432"),
      pgUser = meta.getOrElse("pgUser", "postgres"),
      pgPassword = meta.getOrElse("pgPassword", "")
    )
