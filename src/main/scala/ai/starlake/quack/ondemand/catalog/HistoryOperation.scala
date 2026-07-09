package ai.starlake.quack.ondemand.catalog

/** Operation taxonomy of the per-table history timeline (EPIC Spec 01). Classification itself is
  * computed inside the reader's SQL CASE (so the server-side `operation` filter and `hasMore` stay
  * exact under keyset pagination); this object owns the value set, the filter validation the
  * handler applies, and the row-delta normalization the reader applies after the query.
  */
object HistoryOperation:
  val Create      = "create"
  val Insert      = "insert"
  val Delete      = "delete"
  val Update      = "update"
  val Alter       = "alter"
  val Drop        = "drop"
  val Maintenance = "maintenance"
  val Unknown     = "unknown"

  val Values: Set[String] =
    Set(Create, Insert, Delete, Update, Alter, Drop, Maintenance, Unknown)

  def isValid(op: String): Boolean = Values.contains(op)

  /** (rowsAdded, rowsRemoved) shown for a commit. Maintenance rewrites (compaction) move rows
    * between files without changing the table's logical content, so both deltas render as zero. For
    * every other operation rowsRemoved is the delete-file delta plus the rows of data files retired
    * at the snapshot (drop/truncate paths; zero elsewhere).
    */
  def effectiveDeltas(
      operation: String,
      rowsAdded: Long,
      rowsRetired: Long,
      rowsDeleted: Long
  ): (Long, Long) =
    if operation == Maintenance then (0L, 0L)
    else (rowsAdded, rowsDeleted + rowsRetired)
