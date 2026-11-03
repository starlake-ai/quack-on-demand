package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.model.SqlLiterals

/** Renders a merge's table statements and its Apply batch: pure string building; identifiers are
  * double-quoted, literals escaped. Tables whose rows merge go through [[RowMergeSql]]. Every read
  * of the branch is pinned to `head`, the snapshot the change set was validated at. The batch also
  * refuses to commit when the branch moved past `head` ([[headGuard]]); the caller freezes the
  * branch's writes for the whole merge.
  */
object BranchMergeSql:

  val BranchAlias: String = "qod_branch"

  private def q(ident: String): String = SqlLiterals.duckdbIdent(ident)
  private def lit(v: String): String   = SqlLiterals.duckdbLiteral(v)

  def author(tenant: String, approver: String): String = s"tenant:$tenant/user:$approver"

  /** The commit message carries the merge id so the resulting snapshot can be located exactly. */
  def commitMessage(branchName: String, mergeId: String, proposer: String): String =
    s"merge branch $branchName [$mergeId] proposed by $proposer"

  /** Fails the batch (inside its transaction, before any write) unless the branch's latest snapshot
    * is still `head`: a branch write that landed after validation would otherwise be dropped by the
    * teardown that follows a merge without ever reaching main.
    */
  def headGuard(branchAlias: String, head: Long): String =
    val moved = lit(s"branch moved past snapshot $head after validation; propose it again")
    s"SELECT CASE WHEN (SELECT max(snapshot_id) FROM ${q(branchAlias)}.snapshots()) <> $head " +
      s"THEN error($moved) END AS head_guard"

  /** Statements of a table that is created, dropped or recreated whole. */
  def tableStatements(
      parentAlias: String,
      branchAlias: String,
      change: TableChange,
      fork: Long,
      head: Long
  ): List[String] =
    val target = s"${q(parentAlias)}.${q(change.schema)}.${q(change.table)}"
    val source =
      s"${q(branchAlias)}.${q(change.schema)}.${q(change.table)} AT (VERSION => $head)"
    change.kind match
      case ChangeKind.Created =>
        List(
          s"CREATE SCHEMA IF NOT EXISTS ${q(parentAlias)}.${q(change.schema)}",
          s"CREATE TABLE $target AS SELECT * FROM $source"
        )
      case ChangeKind.Recreated =>
        List(
          s"DROP TABLE $target",
          s"CREATE TABLE $target AS SELECT * FROM $source"
        )
      case ChangeKind.Dropped =>
        List(s"DROP TABLE $target")
      case ChangeKind.Modified =>
        throw new IllegalArgumentException(
          s"modified table ${change.schema}.${change.table} merges by rows"
        )
      case ChangeKind.Altered =>
        throw new IllegalArgumentException(
          s"altered table ${change.schema}.${change.table} is not mergeable"
        )

  /** The Apply stage: one transaction. Tables merged by rows come last, each with its statements in
    * the order [[RowMergeSql.applyStatements]] gives. Only safe under an armed [[MergeFence]].
    * `rowMerges` must be exactly the `Modified` tables of `tableChanges`: this is the last check
    * before a commit that would otherwise leave a table's changes out, silently.
    */
  def applyBatch(
      parentAlias: String,
      branchAlias: String,
      tableChanges: List[TableChange],
      rowMerges: List[(RowMergeTable, RowCounts)],
      ranged: Boolean,
      updateChunkRows: Long,
      fork: Long,
      head: Long,
      author: String,
      message: String
  ): String =
    def names(tables: Set[(String, String)]) =
      tables.toList.sorted.map((schema, table) => s"$schema.$table").mkString(", ")
    val modified =
      tableChanges.filter(_.kind == ChangeKind.Modified).map(c => (c.schema, c.table)).toSet
    val merged = rowMerges.map((t, _) => (t.schema, t.table)).toSet
    require(
      modified == merged,
      s"modified tables with no row merge: ${names(modified -- merged)}; " +
        s"row merges of no modified table: ${names(merged -- modified)}"
    )
    val others = tableChanges
      .filter(_.kind != ChangeKind.Modified)
      .sortBy(c => (c.kind.ordinal, c.schema, c.table))
      .flatMap(c => tableStatements(parentAlias, branchAlias, c, fork, head))
    val rows = rowMerges.flatMap((t, c) =>
      RowMergeSql.applyStatements(parentAlias, t, c, ranged, updateChunkRows)
    )
    (List(
      "BEGIN",
      s"CALL ducklake_set_commit_message(${lit(parentAlias)}, ${lit(author)}, ${lit(message)})",
      headGuard(branchAlias, head),
      RowMergeSql.Settings,
      // Each commit on another table costs this one a retry: DuckLake's default budget gave up in
      // about 8.5 s beside a writer at about 45 commits/s. A fence refusal is not retried.
      "SET ducklake_max_retry_count = 200",
      "SET ducklake_retry_wait_ms = 10",
      "SET ducklake_retry_backoff = 1"
    ) ++ others ++ rows ++ List("COMMIT")).mkString("", ";\n", ";")

  /** Locates the merge snapshot on the parent by its unique commit message. */
  def findSnapshotSql(parentAlias: String, message: String): String =
    s"SELECT max(snapshot_id) AS snapshot_id FROM ${q(parentAlias)}.snapshots() " +
      s"WHERE commit_message = ${lit(message)}"
