package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.model.SqlLiterals

/** Renders the fast-forward merge batch (design section 4.5): one DuckDB transaction on a node that
  * has the parent catalog attached under `parentAlias` and the branch catalog attached under
  * `branchAlias`. Pure string building; identifiers are double-quoted, literals escaped.
  *
  * Row arithmetic for a `Modified` table over the branch change feed `(fork, head]`:
  *   - delete on main every rowid the branch deleted or updated (pre-images);
  *   - insert on main the CURRENT branch row of every rowid the branch inserted or updated
  *     (post-images). A row inserted then deleted on the branch is absent at head, so the second
  *     step inserts nothing for it; a pre-existing row updated then deleted is deleted by the first
  *     step and not re-inserted. `rowid` is stable across the clone (verified live) and DuckLake
  *     keeps it on UPDATE, which is why the feed, not an anti-join, drives the merge.
  *
  * Every read of the branch is pinned to `head` (`AT (VERSION => head)`), the snapshot the change
  * set was validated at: the feed is bounded by it, so a current-state read would pair the feed's
  * rowids with values written after validation. The batch also refuses to commit when the branch
  * moved past `head` ([[headGuard]]); the caller freezes the branch's writes for the whole merge.
  *
  * A table with a column of its own named `rowid` shadows DuckDB's row identifier, so the rowid
  * arithmetic would compare that column against the feed's identifiers; [[BranchChanges]] marks
  * such a table not mergeable and this renderer refuses it.
  */
object BranchMergeSql:

  val BranchAlias: String = "qod_branch"

  private def q(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""
  private def lit(v: String): String   = SqlLiterals.duckdbLiteral(v)

  def author(tenant: String, approver: String): String = s"tenant:$tenant/user:$approver"

  /** The commit message carries the merge id so the resulting snapshot can be located exactly. */
  def commitMessage(branchName: String, mergeId: String, proposer: String): String =
    s"merge branch $branchName [$mergeId] proposed by $proposer"

  def changesFn(
      branchAlias: String,
      schema: String,
      table: String,
      fork: Long,
      head: Long
  ): String =
    s"ducklake_table_changes(${lit(branchAlias)}, ${lit(schema)}, ${lit(table)}, ${fork + 1}, $head)"

  /** Fails the batch (inside its transaction, before any write) unless the branch's latest snapshot
    * is still `head`: a branch write that landed after validation would otherwise be dropped by the
    * teardown that follows a merge without ever reaching main.
    */
  def headGuard(branchAlias: String, head: Long): String =
    val moved = lit(s"branch moved past snapshot $head after validation; propose it again")
    s"SELECT CASE WHEN (SELECT max(snapshot_id) FROM ${q(branchAlias)}.snapshots()) <> $head " +
      s"THEN error($moved) END AS head_guard"

  def tableStatements(
      parentAlias: String,
      branchAlias: String,
      change: TableChange,
      fork: Long,
      head: Long
  ): List[String] =
    if change.shadowsRowid then
      throw new IllegalArgumentException(
        s"table ${change.schema}.${change.table} has a column named rowid and is not mergeable"
      )
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
        val fn = changesFn(branchAlias, change.schema, change.table, fork, head)
        List(
          s"DELETE FROM $target WHERE rowid IN (SELECT rowid FROM $fn " +
            "WHERE change_type IN ('delete', 'update_preimage'))",
          s"INSERT INTO $target BY NAME SELECT * FROM $source WHERE rowid IN (SELECT rowid FROM $fn " +
            "WHERE change_type IN ('insert', 'update_postimage'))"
        )
      case ChangeKind.Altered =>
        throw new IllegalArgumentException(
          s"altered table ${change.schema}.${change.table} is not mergeable"
        )

  /** The whole batch. `changes` must contain no `Altered` entry (the caller refuses those first).
    * Only safe under an armed [[MergeFence]]: nothing in the batch itself ties the commit to the
    * main snapshot the change set was validated against.
    */
  def batch(
      parentAlias: String,
      branchAlias: String,
      changes: List[TableChange],
      fork: Long,
      head: Long,
      author: String,
      message: String
  ): String =
    val body = changes
      .sortBy(c => (c.kind.ordinal, c.schema, c.table))
      .flatMap(c => tableStatements(parentAlias, branchAlias, c, fork, head))
    // No commit retries: a retry means main moved, which the merge fence refuses anyway, and
    // DuckLake would otherwise spend its backoff budget before reporting it (see MergeFence).
    (List(
      "SET ducklake_max_retry_count = 0",
      "BEGIN",
      s"CALL ducklake_set_commit_message(${lit(parentAlias)}, ${lit(author)}, ${lit(message)})",
      headGuard(branchAlias, head)
    ) ++ body ++ List("COMMIT")).mkString("", ";\n", ";")

  /** Locates the merge snapshot on the parent by its unique commit message. */
  def findSnapshotSql(parentAlias: String, message: String): String =
    s"SELECT max(snapshot_id) AS snapshot_id FROM ${q(parentAlias)}.snapshots() " +
      s"WHERE commit_message = ${lit(message)}"
