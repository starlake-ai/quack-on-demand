package ai.starlake.quack.ondemand.branch

import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager}
import scala.util.control.NonFatal

/** Binds a merge's commit to the main snapshot its row decisions were made at.
  *
  * The merge decides against main snapshot S on a node, in steps. Nothing on the DuckDB side can
  * hold those decisions to the commit: a DuckLake transaction pins its snapshot at its first
  * catalog read, not at BEGIN, so a main write in between is simply built upon, and DuckLake's
  * commit-time check does not see a concurrent write into a table the merge DROPs.
  *
  * [[arm]] closes that window where the commit happens, in the parent catalog's Postgres
  * transaction: a BEFORE INSERT trigger on `ducklake_snapshot_changes` refuses the row carrying the
  * merge's unique commit message unless every other snapshot after S is harmless: only row data or
  * maintenance on tables outside `tableIds`. Anything else (a schema change, an unknown verb)
  * refuses. Postgres serializes snapshot ids through the primary key, so the check and the commit
  * are atomic: DuckLake's metadata transactions run REPEATABLE READ, but a commit the trigger's
  * view cannot see holds the id this commit needs, so this try fails and the retry reads afresh.
  * The refusal's text avoids the words DuckLake retries (`conflict`, `concurrent`, `unique`,
  * `primary key`), so it fails at once. Commits on other tables force the merge to retry with the
  * next snapshot id, so the Apply batch leaves retries on.
  *
  * With `holdSec`, until that many seconds from now (the database's clock), the same trigger
  * refuses every other commit naming a table in `tableIds`, in words DuckLake does retry, so a
  * writer waits. The hold ends by itself: a dead manager cannot block writers for longer. Three
  * kinds of commit get past it and land: at most one whose metadata transaction began before the
  * arm (its first try's view has no row, and once one lands the others lose its id and retry on a
  * view that does); a rename, whose changes hold the new name and no id; and a commit with a fence
  * row of its own, such as a restore, which returns before the hold is read. The fence then refuses
  * the merge: the hold saves attempts, and the fence alone keeps main's writes.
  */
trait MergeFence:

  /** Arms the fence for the commit stamped `message`. `Left` when it cannot be installed; the
    * caller must not merge. Arming the same message again replaces the row: each attempt does.
    */
  def arm(
      parentMeta: Map[String, String],
      message: String,
      baseSnapshot: Long,
      tableIds: Set[Long],
      holdSec: Option[Int]
  ): Either[String, Unit]

  /** Removes the fence row for `message`. Best effort: a leftover row only matches that one
    * message, and its hold expires.
    */
  def disarm(parentMeta: Map[String, String], message: String): Unit

  /** The commit stamped `message` has landed as `landedSnapshot`: ends its hold and keeps its row.
    * A look that finds one commit cannot tell how many sends are behind it: with two sends that had
    * no answer and a settle between their landings, the other COMMIT arrives later still, and only
    * this row refuses it: DuckLake's own check does not, on an insert-only merge, and the rows
    * would land twice. The base sits below the landed commit, so that commit is what refuses the
    * late one: a base at the commit would let it pass. `Left` as for [[arm]].
    */
  final def settled(
      parentMeta: Map[String, String],
      message: String,
      landedSnapshot: Long,
      tableIds: Set[Long]
  ): Either[String, Unit] =
    arm(parentMeta, message, landedSnapshot - 1, tableIds, None)

/** Binds a single-table write's commit to the table's state it was validated against: the commit
  * stamped `message` lands only if no other snapshot after `baseSnapshot` touched table `tableId`.
  * Commits on other tables go on meanwhile. A restore validates `expectedCurrentSnapshot` before
  * its replace runs; nothing on the DuckDB side ties that check to the commit, and DuckLake's
  * conflict check does not see a concurrent write into a table the replace drops, so a row
  * committed in between would be erased with a success reported.
  */
trait TableCommitFence:

  /** Arms the fence for the commit stamped `message`. `Left` when it cannot be installed; the
    * caller must not write.
    */
  def arm(
      catalogMeta: Map[String, String],
      message: String,
      baseSnapshot: Long,
      tableId: Long
  ): Either[String, Unit]

  /** Removes the fence row for `message`. Best effort: a leftover row only matches that message. */
  def disarm(catalogMeta: Map[String, String], message: String): Unit

object TableCommitFence:

  /** No fence: only for handlers built without a reachable catalog (unit specs), passed explicitly.
    */
  val unfenced: TableCommitFence = new TableCommitFence:
    def arm(m: Map[String, String], message: String, base: Long, tableId: Long) = Right(())
    def disarm(m: Map[String, String], message: String): Unit                   = ()

/** [[MergeFence]] and [[TableCommitFence]] over the catalog's Postgres database (metastore `pgHost`
  * / `pgPort` / `pgUser` / `pgPassword` / `dbName`, the credentials DuckLake created its tables
  * with, so they own `ducklake_snapshot_changes`). Objects live in their own schema,
  * [[FenceSchema]], so [[BranchCloner]] (which copies the metadata schema only) never carries them
  * into a branch. The trigger is installed once per catalog and left in place: it lets through
  * every commit whose message has no fence row, unless a live hold names its tables, and
  * re-creating it on each merge would take a lock that stalls the tenant's commits.
  */
object PostgresMergeFence extends MergeFence with TableCommitFence with LazyLogging:

  val FenceSchema: String = "qod_merge_fence"

  private def q(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""

  private def connect(meta: Map[String, String]): Connection =
    Class.forName("org.postgresql.Driver")
    DriverManager.getConnection(
      s"jdbc:postgresql://${meta.getOrElse("pgHost", "localhost")}:${meta
          .getOrElse("pgPort", "5432")}/${meta.getOrElse("dbName", "")}",
      meta.getOrElse("pgUser", "postgres"),
      meta.getOrElse("pgPassword", "")
    )

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

  private def triggerInstalled(c: Connection, schema: String): Boolean =
    val ps = c.prepareStatement(
      "SELECT 1 FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid " +
        "JOIN pg_namespace n ON n.oid = r.relnamespace " +
        "WHERE n.nspname = ? AND r.relname = 'ducklake_snapshot_changes' AND t.tgname = ?"
    )
    try
      ps.setString(1, schema)
      ps.setString(2, FenceSchema)
      val rs = ps.executeQuery()
      try rs.next()
      finally rs.close()
    finally ps.close()

  private val HarmlessVerbs =
    "inserted_into_table|deleted_from_table|inlined_insert|inlined_delete|" +
      "inline_flush|merge_adjacent|compacted_table|rewrite_delete"

  /** Postgres `lock_timeout`, in ms, on an arm's own connection. Bounds how long an ALTER of the
    * fence table or the CREATE TRIGGER waits behind a transaction that holds its table, and so how
    * long every commit on the catalog queues behind the pending lock. Past it the arm answers
    * `Left`.
    */
  private val ArmLockTimeoutMs = 5000

  /** What a fence table an earlier build created may lack. table_id: one table the commit is held
    * to (a restore). table_ids: a merge's tables.
    */
  private val AddedColumns =
    List("table_id" -> "BIGINT", "table_ids" -> "BIGINT[]", "hold_until" -> "TIMESTAMPTZ")

  private def fenceColumns(c: Connection): Set[String] =
    val ps = c.prepareStatement(
      "SELECT a.attname FROM pg_attribute a JOIN pg_class r ON r.oid = a.attrelid " +
        "JOIN pg_namespace n ON n.oid = r.relnamespace " +
        "WHERE n.nspname = ? AND r.relname = 'fence' AND a.attnum > 0 AND NOT a.attisdropped"
    )
    try
      ps.setString(1, FenceSchema)
      val rs = ps.executeQuery()
      try Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toSet
      finally rs.close()
    finally ps.close()

  private def install(c: Connection, schema: String): Unit =
    val fs = q(FenceSchema)
    val ms = q(schema)
    val st = c.createStatement()
    try
      st.execute(s"SET lock_timeout = $ArmLockTimeoutMs")
      st.execute(s"CREATE SCHEMA IF NOT EXISTS $fs")
      st.execute(
        s"CREATE TABLE IF NOT EXISTS $fs.fence " +
          "(commit_message TEXT PRIMARY KEY, base_snapshot BIGINT NOT NULL)"
      )
      // ALTER takes ACCESS EXCLUSIVE even for a column that exists, and the trigger reads this table
      // on every commit: asked on every arm, one stuck reader would queue them all behind it.
      val present = fenceColumns(c)
      AddedColumns
        .filterNot((name, _) => present.contains(name))
        .foreach((name, kind) =>
          st.execute(s"ALTER TABLE $fs.fence ADD COLUMN IF NOT EXISTS $name $kind")
        )
      // CASE, not AND: Postgres does not promise to test the pattern before the cast. Anything that
      // does not read as a known verb on a table id refuses, NULL ids included.
      st.execute(
        s"""CREATE OR REPLACE FUNCTION $fs.harmless(changes TEXT, ids BIGINT[]) RETURNS boolean AS $$$$
           |  SELECT changes IS NOT NULL AND NOT EXISTS (
           |    SELECT 1 FROM regexp_split_to_table(changes, ',') AS e(entry)
           |    WHERE NOT coalesce(
           |      CASE WHEN entry ~ '^($HarmlessVerbs):[0-9]+$$'
           |           THEN NOT (split_part(entry, ':', 2)::BIGINT = ANY(ids))
           |           ELSE false END,
           |      false))
           |$$$$ LANGUAGE sql STABLE""".stripMargin
      )
      st.execute(
        s"""CREATE OR REPLACE FUNCTION $fs.check_commit() RETURNS trigger AS $$$$
           |DECLARE f $fs.fence%ROWTYPE;
           |BEGIN
           |  SELECT * INTO f FROM $fs.fence x WHERE x.commit_message = NEW.commit_message;
           |  IF FOUND THEN
           |    IF f.table_id IS NOT NULL THEN
           |      -- A snapshot touched the table when its changes name the table's id after a verb
           |      -- (inserted_into_table:7, ...). A quoted name holding such a text can only refuse.
           |      IF EXISTS (SELECT 1 FROM $ms.ducklake_snapshot_changes c
           |          WHERE c.snapshot_id > f.base_snapshot AND c.snapshot_id <> NEW.snapshot_id
           |            AND c.changes_made ~ ('(^|,)[a-z_]+:' || f.table_id || '(,|$$)')) THEN
           |        RAISE EXCEPTION 'qod commit fence: conflict, table % changed after snapshot % before the commit', f.table_id, f.base_snapshot;
           |      END IF;
           |    ELSIF EXISTS (SELECT 1 FROM $ms.ducklake_snapshot s
           |        LEFT JOIN $ms.ducklake_snapshot_changes c ON c.snapshot_id = s.snapshot_id
           |        WHERE s.snapshot_id > f.base_snapshot AND s.snapshot_id <> NEW.snapshot_id
           |          AND NOT $fs.harmless(c.changes_made, f.table_ids)) THEN
           |      RAISE EXCEPTION 'qod merge fence: a merged table moved past snapshot %', f.base_snapshot;
           |    END IF;
           |    RETURN NEW;
           |  END IF;
           |  IF EXISTS (SELECT 1 FROM $fs.fence h
           |      WHERE h.hold_until > clock_timestamp()
           |        AND EXISTS (SELECT 1 FROM unnest(h.table_ids) AS x(id)
           |            WHERE NEW.changes_made ~ ('(^|,)[a-z_]+:' || x.id || '(,|$$)'))) THEN
           |    RAISE EXCEPTION 'qod merge hold: conflict, table is being merged; retry';
           |  END IF;
           |  RETURN NEW;
           |END $$$$ LANGUAGE plpgsql""".stripMargin
      )
      if !triggerInstalled(c, schema) then
        st.execute(
          s"CREATE TRIGGER $fs BEFORE INSERT ON $ms.ducklake_snapshot_changes " +
            s"FOR EACH ROW EXECUTE FUNCTION $fs.check_commit()"
        )
    finally st.close()

  def arm(
      parentMeta: Map[String, String],
      message: String,
      baseSnapshot: Long,
      tableIds: Set[Long],
      holdSec: Option[Int]
  ): Either[String, Unit] =
    armRow(parentMeta, message, baseSnapshot, None, Some(tableIds), holdSec)

  def arm(
      catalogMeta: Map[String, String],
      message: String,
      baseSnapshot: Long,
      tableId: Long
  ): Either[String, Unit] =
    armRow(catalogMeta, message, baseSnapshot, Some(tableId), None, None)

  private def armRow(
      meta: Map[String, String],
      message: String,
      baseSnapshot: Long,
      tableId: Option[Long],
      tableIds: Option[Set[Long]],
      holdSec: Option[Int]
  ): Either[String, Unit] =
    try
      val c = connect(meta)
      try
        metaSchema(c) match
          case None         => Left("catalog has no DuckLake metadata")
          case Some(schema) =>
            install(c, schema)
            val ps = c.prepareStatement(
              s"INSERT INTO ${q(FenceSchema)}.fence " +
                "(commit_message, base_snapshot, table_id, table_ids, hold_until) " +
                "VALUES (?, ?, ?, ?, CASE WHEN ?::int IS NULL THEN NULL " +
                "ELSE clock_timestamp() + make_interval(secs => ?::int) END) " +
                "ON CONFLICT (commit_message) DO UPDATE SET " +
                "base_snapshot = EXCLUDED.base_snapshot, table_id = EXCLUDED.table_id, " +
                "table_ids = EXCLUDED.table_ids, hold_until = EXCLUDED.hold_until"
            )
            try
              ps.setString(1, message)
              ps.setLong(2, baseSnapshot)
              tableId match
                case Some(t) => ps.setLong(3, t)
                case None    => ps.setNull(3, java.sql.Types.BIGINT)
              tableIds match
                case Some(ids) =>
                  ps.setArray(4, c.createArrayOf("bigint", ids.toArray.map(Long.box)))
                case None => ps.setNull(4, java.sql.Types.ARRAY)
              holdSec match
                case Some(s) =>
                  ps.setInt(5, s)
                  ps.setInt(6, s)
                case None =>
                  ps.setNull(5, java.sql.Types.INTEGER)
                  ps.setNull(6, java.sql.Types.INTEGER)
              ps.executeUpdate()
              Right(())
            finally ps.close()
      finally c.close()
    catch case NonFatal(e) => Left(s"cannot arm the commit fence: ${e.getMessage}")

  def disarm(parentMeta: Map[String, String], message: String): Unit =
    try
      val c = connect(parentMeta)
      try
        val ps =
          c.prepareStatement(s"DELETE FROM ${q(FenceSchema)}.fence WHERE commit_message = ?")
        try
          ps.setString(1, message)
          ps.executeUpdate(): Unit
        finally ps.close()
      finally c.close()
    catch
      case NonFatal(e) =>
        logger.warn(s"merge fence: cannot remove the row for '$message': ${e.getMessage}")
