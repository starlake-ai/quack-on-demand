package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.model.SqlLiterals
import io.circe.Json

/** One table going through the row merge: what to read, on each side. `index` names its working
  * tables, and its values are named by position in them (`v0`, `v1`, ...), so no user column can
  * collide with an internal one. `branchRanges` and `mainRanges` are empty for a side with no
  * commits since the fork, whose reads are skipped: a bound naming no snapshot is an engine error.
  */
final case class RowMergeTable(
    index: Int,
    schema: String,
    table: String,
    columns: List[String],
    keyColumns: List[String],
    branchRanges: List[SnapshotRange],
    mainRanges: List[SnapshotRange]
)

object RowMergeTable:

  enum Problem:
    /** A snapshot at or after the fork is gone from `catalog`, `main` or `branch`. */
    case HistoryGap(catalog: String, missing: Long)
    case MixedSnapshots(schema: String, table: String, snapshots: List[Long])

  /** Each catalog's snapshot rows from the fork itself on, `(main's, the branch's)`, or the first
    * snapshot gone from one of them, a [[Problem.HistoryGap]]: main's over `[fork, S]`, checked
    * first, then the branch's over `[fork, head]`. The fork is part of it because Prepare reads the
    * tables AT it, and a snapshot gone from either catalog hides its table touches from the
    * classifier, so a table touched only there would leave the change set and the merge would
    * succeed without it.
    */
  private def history(
      cs: BranchChangeSet,
      branch: ChangeSource,
      main: ChangeSource
  ): Either[Problem, (List[(Long, String)], List[(Long, String)])] =
    val fork          = cs.forkSnapshot
    val mainChanges   = main.snapshotChangesSince(fork - 1)
    val branchChanges = branch.snapshotChangesSince(fork - 1)
    BranchChanges
      .historyGap(mainChanges, fork, cs.mainSnapshot)
      .map(Problem.HistoryGap("main", _))
      .orElse(
        BranchChanges
          .historyGap(branchChanges, fork, cs.headSnapshot)
          .map(Problem.HistoryGap("branch", _))
      )
      .toLeft((mainChanges, branchChanges))

  /** The first snapshot gone from either catalog (a [[Problem.HistoryGap]]), for a caller that does
    * not [[plan]]: a proposal is never recorded over a history that is not whole, whatever else
    * rules the merge out.
    */
  def historyGap(
      cs: BranchChangeSet,
      branch: ChangeSource,
      main: ChangeSource
  ): Option[Problem] = history(cs, branch, main).left.toOption

  /** The tables of `cs` that merge by rows, read up to `cs.mainSnapshot` on main. A history that is
    * not whole is refused first, whether or not any table merges by rows.
    */
  def plan(
      cs: BranchChangeSet,
      branch: ChangeSource,
      main: ChangeSource,
      keys: Map[String, List[String]]
  ): Either[Problem, List[RowMergeTable]] =
    val fork = cs.forkSnapshot
    history(cs, branch, main).flatMap { (mainChanges, branchChanges) =>
      // Only what came after the fork is a change: the fork's own row is there to be counted.
      val branchEntries = BranchChanges.parseEntries(branchChanges.filter(_._1 > fork))
      val mainEntries   = BranchChanges.parseEntries(mainChanges.filter(_._1 > fork))
      cs.tables
        .filter(_.kind == ChangeKind.Modified)
        .zipWithIndex
        .foldLeft[Either[Problem, List[RowMergeTable]]](Right(Nil)) { case (acc, (t, i)) =>
          val id = t.tableId.getOrElse(
            throw new IllegalArgumentException(s"modified table ${t.schema}.${t.table} has no id")
          )
          // Such a column hides the row identifier from the merge's writes but not from the feed
          // it reads, so wrong rows would be written, silently. Callers refuse the table first.
          t.reservedColumn.foreach(c =>
            throw new IllegalArgumentException(
              s"table ${t.schema}.${t.table} has a column named $c and is not mergeable"
            )
          )
          def ranges(es: List[BranchChanges.Entry], head: Long) =
            BranchChanges
              .readRanges(es, id, fork, head)
              .left
              .map(Problem.MixedSnapshots(t.schema, t.table, _))
          for
            done         <- acc
            branchRanges <- ranges(branchEntries, cs.headSnapshot)
            mainRanges   <- ranges(mainEntries, cs.mainSnapshot)
          yield done :+ RowMergeTable(
            i,
            t.schema,
            t.table,
            branch.topLevelColumnsOfTableAt(id, cs.headSnapshot).map(_.name),
            keys.getOrElse(s"${t.schema}.${t.table}", Nil),
            branchRanges,
            mainRanges
          )
        }
    }

/** Renders the row merge's stages (design section 6): pure strings. Working tables live in the
  * merge node's in-memory database, which survives between requests and dies with the node. Each
  * statement is returned on its own; [[RowMergeRunner]] joins them into batches.
  */
object RowMergeSql:

  final case class OverlapSizes(overlap: Long, need: Long)

  /** Session settings: repeat them in every batch, each send is a new connection. Lets a semi-join
    * on rowids prune row groups (design finding 5).
    */
  val Settings: String = "SET dynamic_or_filter_threshold = 10000"

  val CreateWorkSchema: String = """CREATE SCHEMA IF NOT EXISTS "memory"."qod_merge""""

  /** Ranges of one update, measured at 64 ranges over 1B rows under a 16GB limit: after the first
    * UPDATE the planner may build on the scan side, which this width bounds.
    */
  val MaxRangeWidth: Long      = 16000000L
  val TargetRowsPerRange: Long = 2000000L

  private def q(ident: String): String = SqlLiterals.duckdbIdent(ident)
  private def lit(v: String): String   = SqlLiterals.duckdbLiteral(v)

  private def w(t: RowMergeTable, name: String): String =
    "\"memory\".\"qod_merge\"." + q(s"t${t.index}_$name")

  private def target(alias: String, t: RowMergeTable): String =
    s"${q(alias)}.${q(t.schema)}.${q(t.table)}"

  private def positions(t: RowMergeTable): List[Int] = t.columns.indices.toList

  private def valueList(t: RowMergeTable, prefix: String = ""): String =
    positions(t).map(i => s"${prefix}v$i").mkString(", ")

  private def named(t: RowMergeTable, prefix: String = ""): String =
    t.columns.zipWithIndex.map((c, i) => s"$prefix${q(c)} AS v$i").mkString(", ")

  private def forkRows(parentAlias: String, t: RowMergeTable, fork: Long): String =
    s"SELECT rowid, ${named(t)} FROM ${target(parentAlias, t)} AT (VERSION => $fork)"

  /** One change function over every range. */
  private def events(
      fn: String,
      alias: String,
      t: RowMergeTable,
      ranges: List[SnapshotRange],
      projection: String
  ): String =
    ranges
      .map(r =>
        s"SELECT $projection FROM $fn(${lit(alias)}, ${lit(t.schema)}, ${lit(t.table)}, " +
          s"${r.from}, ${r.to})"
      )
      .mkString(" UNION ALL ")

  /** A side's net change per rowid, from rowids and snapshots alone. A rowid whose first event is
    * an insert was born after the fork; the last event decides whether it survives.
    */
  private def net(
      name: String,
      alias: String,
      t: RowMergeTable,
      ranges: List[SnapshotRange]
  ): String =
    if ranges.isEmpty then
      s"CREATE OR REPLACE TABLE ${w(t, name)} " +
        "(rowid BIGINT, born_after_fork BOOLEAN, gone BOOLEAN, last_ins BIGINT)"
    else
      val del = events("ducklake_table_deletions", alias, t, ranges, "snapshot_id, rowid")
      val ins = events("ducklake_table_insertions", alias, t, ranges, "snapshot_id, rowid")
      s"CREATE OR REPLACE TABLE ${w(t, name)} AS " +
        s"WITH d AS (SELECT rowid, min(snapshot_id) AS first_del, max(snapshot_id) AS last_del " +
        s"FROM ($del) GROUP BY rowid), " +
        s"i AS (SELECT rowid, min(snapshot_id) AS first_ins, max(snapshot_id) AS last_ins " +
        s"FROM ($ins) GROUP BY rowid) " +
        "SELECT coalesce(d.rowid, i.rowid) AS rowid, " +
        "(d.rowid IS NULL OR coalesce(i.first_ins < d.first_del, false)) AS born_after_fork, " +
        "(i.rowid IS NULL OR coalesce(d.last_del > i.last_ins, false)) AS gone, " +
        "i.last_ins " +
        "FROM d FULL JOIN i ON d.rowid = i.rowid"

  private def emptyValues(
      name: String,
      parentAlias: String,
      t: RowMergeTable,
      fork: Long
  ): String =
    s"CREATE OR REPLACE TABLE ${w(t, name)} AS ${forkRows(parentAlias, t, fork)} WHERE false"

  /** The values of the rows `rowsSql` names (`rowid`, `last_ins`), at their last insertion. */
  private def postImages(
      name: String,
      alias: String,
      t: RowMergeTable,
      ranges: List[SnapshotRange],
      rowsSql: String
  ): String =
    val projection = "snapshot_id, rowid, " + t.columns.map(q).mkString(", ")
    s"CREATE OR REPLACE TABLE ${w(t, name)} AS SELECT p.rowid, ${named(t, "p.")} " +
      s"FROM (${events("ducklake_table_insertions", alias, t, ranges, projection)}) p " +
      s"JOIN ($rowsSql) n ON p.rowid = n.rowid AND p.snapshot_id = n.last_ins"

  /** Prepare, per table: everything on the frozen branch. */
  def branchSide(
      branchAlias: String,
      parentAlias: String,
      t: RowMergeTable,
      fork: Long
  ): List[String] =
    val post =
      if t.branchRanges.isEmpty then emptyValues("post_b", parentAlias, t, fork)
      else
        postImages(
          "post_b",
          branchAlias,
          t,
          t.branchRanges,
          s"SELECT rowid, last_ins FROM ${w(t, "net_b")} WHERE NOT gone"
        )
    List(
      net("net_b", branchAlias, t, t.branchRanges),
      post,
      s"CREATE TABLE IF NOT EXISTS ${w(t, "base")} AS ${forkRows(parentAlias, t, fork)} WHERE false"
    )

  /** Decide's first send: main's net changes, the rows both sides changed that existed at the fork,
    * and which of their original values are not held yet.
    */
  def mainSide(parentAlias: String, t: RowMergeTable): List[String] =
    List(
      net("net_m", parentAlias, t, t.mainRanges),
      s"CREATE OR REPLACE TABLE ${w(t, "overlap")} AS " +
        "SELECT m.rowid, m.gone AS m_gone, m.last_ins AS m_last_ins " +
        s"FROM ${w(t, "net_m")} m JOIN ${w(t, "net_b")} b ON m.rowid = b.rowid " +
        "WHERE NOT m.born_after_fork AND NOT b.born_after_fork",
      s"CREATE OR REPLACE TABLE ${w(t, "need")} AS SELECT rowid FROM ${w(t, "overlap")} " +
        s"EXCEPT SELECT rowid FROM ${w(t, "base")}"
    )

  def overlapSizes(t: RowMergeTable): String =
    s"SELECT (SELECT count(*) FROM ${w(t, "overlap")}), (SELECT count(*) FROM ${w(t, "need")})"

  /** Never send this with an empty `need`: a semi-join on an empty table reads the whole table
    * (design finding 14).
    */
  def topUp(parentAlias: String, t: RowMergeTable, fork: Long): String =
    s"INSERT INTO ${w(t, "base")} ${forkRows(parentAlias, t, fork)} " +
      s"WHERE rowid IN (SELECT rowid FROM ${w(t, "need")})"

  private def keyTables(parentAlias: String, t: RowMergeTable): List[String] =
    if t.keyColumns.isEmpty then Nil
    else if t.mainRanges.isEmpty then
      List(s"CREATE OR REPLACE TABLE ${w(t, "key_hits")} (b_rowid BIGINT, m_rowid BIGINT)")
    else
      val keys       = t.keyColumns.zipWithIndex
      val projection = "snapshot_id, rowid, " + t.keyColumns.map(q).mkString(", ")
      val keyM       =
        s"CREATE OR REPLACE TABLE ${w(t, "key_m")} AS SELECT p.rowid, " +
          keys.map((c, i) => s"p.${q(c)} AS k$i").mkString(", ") +
          s" FROM (${events("ducklake_table_insertions", parentAlias, t, t.mainRanges, projection)}) p " +
          s"JOIN (SELECT rowid, last_ins FROM ${w(t, "net_m")} WHERE born_after_fork AND NOT gone) n " +
          "ON p.rowid = n.rowid AND p.snapshot_id = n.last_ins"
      // Plain =, so a NULL key never matches.
      val matches = keys
        .map((c, i) => s"b.v${t.columns.indexOf(c)} = m.k$i")
        .mkString(" AND ")
      val hits =
        s"CREATE OR REPLACE TABLE ${w(t, "key_hits")} AS " +
          "SELECT b.rowid AS b_rowid, m.rowid AS m_rowid " +
          s"FROM ${w(t, "post_b")} b " +
          s"JOIN ${w(t, "net_b")} n ON n.rowid = b.rowid AND n.born_after_fork " +
          s"JOIN ${w(t, "key_m")} m ON $matches"
      List(keyM, hits)

  /** Decide's second send: main's values for the overlap, each row's action and merged values, and
    * the sets Apply writes. `overlap` is the size [[overlapSizes]] read; empty, main's values are
    * not read.
    */
  def values(
      parentAlias: String,
      t: RowMergeTable,
      fork: Long,
      overlap: Long,
      policy: Option[ConflictPolicy]
  ): List[String] =
    val postM =
      if overlap == 0L || t.mainRanges.isEmpty then emptyValues("post_m", parentAlias, t, fork)
      else
        postImages(
          "post_m",
          parentAlias,
          t,
          t.mainRanges,
          s"SELECT rowid, m_last_ins AS last_ins FROM ${w(t, "overlap")} WHERE NOT m_gone"
        )
    (postM :: keyTables(parentAlias, t)) ++
      List(merged(t, policy), updateSet(t, policy), deleteSet(t, policy), insertSet(t, policy))

  private def merged(t: RowMergeTable, policy: Option[ConflictPolicy]): String =
    val idx              = positions(t)
    val branchMoved      = idx.map(i => s"b.v$i IS DISTINCT FROM f.v$i").mkString(" OR ")
    val mainMoved        = idx.map(i => s"m.v$i IS DISTINCT FROM f.v$i").mkString(" OR ")
    def conflict(i: Int) =
      s"(ov.rowid IS NOT NULL AND NOT n.gone AND NOT ov.m_gone AND b.v$i IS DISTINCT FROM f.v$i " +
        s"AND m.v$i IS DISTINCT FROM f.v$i AND m.v$i IS DISTINCT FROM b.v$i)"
    val winner       = if policy.contains(ConflictPolicy.Branch) then "b" else "m"
    def pick(i: Int) =
      s"CASE WHEN ov.rowid IS NULL OR ov.m_gone THEN b.v$i " +
        s"WHEN b.v$i IS NOT DISTINCT FROM f.v$i THEN m.v$i " +
        s"WHEN m.v$i IS NOT DISTINCT FROM f.v$i THEN b.v$i " +
        s"WHEN m.v$i IS NOT DISTINCT FROM b.v$i THEN b.v$i " +
        s"ELSE $winner.v$i END AS v$i"
    val action =
      "CASE " +
        "WHEN n.gone AND ov.rowid IS NULL THEN 'apply_delete' " +
        "WHEN n.gone AND ov.m_gone THEN 'noop' " +
        s"WHEN n.gone AND ($mainMoved) THEN 'conflict_update_delete' " +
        "WHEN n.gone THEN 'apply_delete' " +
        "WHEN ov.rowid IS NULL THEN 'apply_update' " +
        s"WHEN NOT ($branchMoved) THEN 'noop' " +
        "WHEN ov.m_gone THEN 'conflict_delete_update' " +
        s"WHEN ${idx.map(conflict).mkString(" OR ")} THEN 'conflict_cell' " +
        "ELSE 'apply_update' END AS action"
    s"CREATE OR REPLACE TABLE ${w(t, "merged")} AS SELECT n.rowid, $action, " +
      idx.map(i => s"${conflict(i)} AS c$i").mkString(", ") + ", " +
      idx.map(pick).mkString(", ") +
      s" FROM ${w(t, "net_b")} n " +
      s"LEFT JOIN ${w(t, "post_b")} b ON b.rowid = n.rowid " +
      s"LEFT JOIN ${w(t, "overlap")} ov ON ov.rowid = n.rowid " +
      s"LEFT JOIN ${w(t, "post_m")} m ON m.rowid = n.rowid " +
      s"LEFT JOIN ${w(t, "base")} f ON f.rowid = n.rowid " +
      "WHERE NOT n.born_after_fork"

  private def updateSet(t: RowMergeTable, policy: Option[ConflictPolicy]): String =
    val actions = if policy.isEmpty then "'apply_update'" else "'apply_update', 'conflict_cell'"
    s"CREATE OR REPLACE TABLE ${w(t, "upd")} AS SELECT rowid, ${valueList(t)} " +
      s"FROM ${w(t, "merged")} WHERE action IN ($actions)"

  private def deleteSet(t: RowMergeTable, policy: Option[ConflictPolicy]): String =
    val branchWins = policy.contains(ConflictPolicy.Branch)
    val actions    =
      if branchWins then "'apply_delete', 'conflict_update_delete'" else "'apply_delete'"
    val keyed =
      if branchWins && t.keyColumns.nonEmpty then
        s" UNION SELECT m_rowid AS rowid FROM ${w(t, "key_hits")}"
      else ""
    s"CREATE OR REPLACE TABLE ${w(t, "del")} AS " +
      s"SELECT rowid FROM ${w(t, "merged")} WHERE action IN ($actions)$keyed"

  /** From `post_b`, simply the branch's own rows. `merged` holds no row born after the fork, and on
    * a `conflict_delete_update` row its first cell case gives the branch's value anyway.
    */
  private def insertSet(t: RowMergeTable, policy: Option[ConflictPolicy]): String =
    val born =
      s"SELECT ${valueList(t, "b.")} FROM ${w(t, "post_b")} b " +
        s"JOIN ${w(t, "net_b")} n ON n.rowid = b.rowid WHERE n.born_after_fork"
    val dropped =
      if policy.contains(ConflictPolicy.Main) && t.keyColumns.nonEmpty then
        s" AND b.rowid NOT IN (SELECT b_rowid FROM ${w(t, "key_hits")})"
      else ""
    val returned =
      if policy.contains(ConflictPolicy.Branch) then
        s" UNION ALL SELECT ${valueList(t, "b.")} FROM ${w(t, "post_b")} b " +
          s"JOIN ${w(t, "merged")} x ON x.rowid = b.rowid " +
          "WHERE x.action = 'conflict_delete_update'"
      else ""
    s"CREATE OR REPLACE TABLE ${w(t, "ins")} AS $born$dropped$returned"

  /** One row: rows to update, delete, insert, then the conflicts, the update's rowid bounds, main's
    * rows the key matches and the conflicts per column. Read back by [[parseCounts]].
    */
  def counts(t: RowMergeTable): String =
    def keyRows(side: String) =
      if t.keyColumns.nonEmpty then s"(SELECT count(DISTINCT $side) FROM ${w(t, "key_hits")})"
      else "0"
    val perColumn = positions(t).map(i => s"count(*) FILTER (c$i)").mkString(", ")
    s"SELECT (SELECT count(*) FROM ${w(t, "upd")}), (SELECT count(*) FROM ${w(t, "del")}), " +
      s"(SELECT count(*) FROM ${w(t, "ins")}), " +
      "count(*) FILTER (action = 'conflict_cell'), " +
      "count(*) FILTER (action = 'conflict_update_delete'), " +
      "count(*) FILTER (action = 'conflict_delete_update'), " +
      s"${keyRows("b_rowid")}, (SELECT min(rowid) FROM ${w(t, "upd")}), " +
      s"(SELECT max(rowid) FROM ${w(t, "upd")}), ${keyRows("m_rowid")}, " +
      s"$perColumn FROM ${w(t, "merged")}"

  /** Up to 20 rowids per kind of conflict: pointers to query under one's own grants, never values.
    */
  def samples(t: RowMergeTable): String =
    def ofAction(kind: String, action: String) =
      s"(SELECT '$kind' AS kind, rowid FROM ${w(t, "merged")} WHERE action = '$action' " +
        "ORDER BY rowid LIMIT 20)"
    val keyed =
      if t.keyColumns.nonEmpty then
        s" UNION ALL (SELECT DISTINCT 'key' AS kind, b_rowid AS rowid FROM ${w(t, "key_hits")} " +
          "ORDER BY rowid LIMIT 20)"
      else ""
    ofAction("cell", "conflict_cell") + " UNION ALL " +
      ofAction("update_delete", "conflict_update_delete") + " UNION ALL " +
      ofAction("delete_update", "conflict_delete_update") + keyed

  private def long(j: Json): Option[Long] = j.asNumber.flatMap(_.toLong)

  /** An answer that cannot be read is an error, never zero: a zero overlap would leave main's
    * values out of the merge, and main's changes would be overwritten.
    */
  def parseSizes(rows: List[List[Json]]): Either[String, OverlapSizes] =
    rows.headOption
      .collect { case a :: b :: _ => (long(a), long(b)) }
      .collect { case (Some(overlap), Some(need)) => OverlapSizes(overlap, need) }
      .toRight("the merge node's overlap sizes cannot be read")

  def parseCounts(
      t: RowMergeTable,
      countRows: List[List[Json]],
      sampleRows: List[List[Json]]
  ): Either[String, RowCounts] =
    val row            = countRows.headOption.getOrElse(Nil)
    def number(i: Int) = row.lift(i).flatMap(long)
    // Positions 7 and 8 are the update's rowid bounds, NULL only when it has no row: with rows and no
    // bounds, its ranges would match nothing, and the commit would still succeed.
    val bounds   = if number(0).exists(_ > 0L) then List(7, 8) else Nil
    val required = (0 to 6).toList ++ bounds ++ (9 +: t.columns.indices.map(_ + 10))
    if required.exists(number(_).isEmpty) then
      Left(s"the merge node's counts for ${t.schema}.${t.table} cannot be read")
    else
      def at(i: Int) = number(i).getOrElse(0L)
      val sampled    = sampleRows
        .collect { case kind :: id :: _ => (kind.asString, long(id)) }
        .collect { case (Some(kind), Some(id)) => (kind, id) }
      Right(
        RowCounts(
          schema = t.schema,
          table = t.table,
          toUpdate = at(0),
          toDelete = at(1),
          toInsert = at(2),
          cellConflicts = at(3),
          updateDeleteConflicts = at(4),
          deleteUpdateConflicts = at(5),
          keyMatches = at(6),
          minUpdateRowid = at(7),
          maxUpdateRowid = at(8),
          mainRowsByKey = at(9),
          columnConflicts =
            t.columns.zipWithIndex.map((c, i) => c -> at(10 + i)).filter(_._2 > 0L).toMap,
          samples = sampled.groupMap(_._1)(_._2)
        )
      )

  /** Half-open ranges covering `[minRowid, maxRowid]`, at most [[MaxRangeWidth]] rowids wide and
    * about [[TargetRowsPerRange]] rows each.
    */
  def updateRanges(minRowid: Long, maxRowid: Long, rows: Long): List[(Long, Long)] =
    def ceilDiv(a: Long, b: Long) = (a + b - 1L) / b
    val span                      = maxRowid - minRowid + 1L
    val n     = List(1L, ceilDiv(span, MaxRangeWidth), ceilDiv(rows, TargetRowsPerRange)).max
    val edges = (0L to n).toList.map(i => minRowid + (BigInt(span) * i / n).toLong)
    edges.zip(edges.tail)

  /** DELETE, then UPDATE, then INSERT: an UPDATE costs the table its statistics for the rest of the
    * transaction (design finding 3). A statement with an empty set is not rendered (finding 14).
    */
  def applyStatements(
      parentAlias: String,
      t: RowMergeTable,
      c: RowCounts,
      ranged: Boolean,
      updateChunkRows: Long
  ): List[String] =
    val tgt  = target(parentAlias, t)
    val sets = t.columns.zipWithIndex.map((col, i) => s"${q(col)} = u.v$i").mkString(", ")
    val del  =
      if c.toDelete == 0L then Nil
      else List(s"DELETE FROM $tgt WHERE rowid IN (SELECT rowid FROM ${w(t, "del")})")
    val upd =
      if c.toUpdate == 0L then Nil
      else if !ranged && c.toUpdate <= updateChunkRows then
        List(s"UPDATE $tgt AS t SET $sets FROM ${w(t, "upd")} u WHERE t.rowid = u.rowid")
      else
        updateRanges(c.minUpdateRowid, c.maxUpdateRowid, c.toUpdate).map((lo, hi) =>
          s"UPDATE $tgt AS t SET $sets " +
            s"FROM (SELECT * FROM ${w(t, "upd")} WHERE rowid >= $lo AND rowid < $hi) u " +
            s"WHERE t.rowid = u.rowid AND t.rowid >= $lo AND t.rowid < $hi"
        )
    val ins =
      if c.toInsert == 0L then Nil
      else
        List(
          s"INSERT INTO $tgt (${t.columns.map(q).mkString(", ")}) " +
            s"SELECT ${valueList(t)} FROM ${w(t, "ins")}"
        )
    del ++ upd ++ ins
