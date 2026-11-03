package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.ondemand.catalog.TableVersion

/** What a branch (or main) did to one table since the fork snapshot. */
enum ChangeKind(val wire: String):
  /** Did not exist at the fork, exists at head. */
  case Created extends ChangeKind("created")

  /** Existed at the fork, gone at head. */
  case Dropped extends ChangeKind("dropped")

  /** Dropped and created again under the same name (different `table_id`). */
  case Recreated extends ChangeKind("recreated")

  /** Same `table_id`, same columns, rows changed. */
  case Modified extends ChangeKind("modified")

  /** Same `table_id`, columns (names, types, nullability) or name differ: not mergeable. */
  case Altered extends ChangeKind("altered")

/** One touched table with its classification and, when the counts were computed, the change-feed
  * summary over `(fork, head]`.
  */
final case class TableChange(
    schema: String,
    table: String,
    kind: ChangeKind,
    inserted: Long = 0L,
    deleted: Long = 0L,
    updated: Long = 0L,
    /** The counts were asked for and could not be had (the node refused the requester the table, or
      * the count failed): the zeros are no counts.
      */
    countsUnavailable: Boolean = false,
    /** A `Modified` table with a column of its own named after one the merge reads (any case):
      * `rowid` hides DuckDB's row identifier, and the change functions rename a user `snapshot_id`,
      * so the merge would match or copy the wrong values.
      */
    reservedColumn: Option[String] = None,
    /** The table's id on main: its id at the fork. None for a table the branch created. */
    tableId: Option[Long] = None,
    /** Main changed this table's rows too: merged cell by cell. */
    threeWay: Boolean = false
):
  def mergeable: Boolean = kind != ChangeKind.Altered && reservedColumn.isEmpty

  /** Only modified tables are counted: a count of this table ran, and did not fail. */
  def counted: Boolean = kind == ChangeKind.Modified && !countsUnavailable

  def reason: Option[String] =
    if kind == ChangeKind.Altered then
      Some("schema change (column add/drop/retype or rename) cannot be merged")
    else
      reservedColumn.map(c =>
        s"a column named $c hides a column the merge reads; rename the column to merge this table"
      )

/** Snapshots from `from` to `to`, both inclusive: one read of a table's changes. */
final case class SnapshotRange(from: Long, to: Long)

final case class BranchConflict(schema: String, table: String, reason: String)

/** The full change set of a branch against its fork point, plus the conflicts against main. */
final case class BranchChangeSet(
    forkSnapshot: Long,
    headSnapshot: Long,
    mainSnapshot: Long,
    tables: List[TableChange],
    conflicts: List[BranchConflict],
    unsupported: List[String]
):
  def mergeable: Boolean =
    conflicts.isEmpty && unsupported.isEmpty && tables.forall(_.mergeable)

/** The metadata reads the classifier needs from one catalog (branch or main). Implemented by
  * `DuckLakeCatalogReader`; abstracted so the classification is unit-testable without Postgres.
  */
trait ChangeSource:
  def maxSnapshotId(): Option[Long]
  def tableVersions(): List[TableVersion]
  def snapshotChangesSince(fork: Long): List[(Long, String)]
  def columnsOfTableAt(tableId: Long, snapshotId: Long): List[CatalogColumnEntry]

  /** The table's own columns: `columnsOfTableAt` also lists fields nested in a STRUCT, LIST or MAP.
    */
  def topLevelColumnsOfTableAt(tableId: Long, snapshotId: Long): List[CatalogColumnEntry]

  /** Names of the schemas dropped after `fork`. */
  def droppedSchemasSince(fork: Long): Set[String]

/** Pure classification of `ducklake_snapshot_changes` verbs into table change sets (Epic 1).
  *
  * Verb vocabulary (DuckLake 1.0 metadata written by DuckDB 1.5.x; the pin-bump checklist spec
  * asserts the engine emits nothing outside `DuckLakeCatalogReader.HistoryVerbs` ++
  * `HistoryIgnoredVerbs`, and every verb here is drawn from that union plus the schema/view forms):
  *   - data verbs: `inserted_into_table`, `deleted_from_table`, `inlined_insert`, `inlined_delete`
  *   - table DDL verbs: `created_table`, `dropped_table`, `altered_table`
  *   - maintenance verbs, never a touch: `inline_flush`, `merge_adjacent`, `compacted_table`,
  *     `rewrite_delete`
  *   - schema-level, never a table touch: `created_schema`, and `dropped_schema`, whose payload is
  *     a schema id (the schemas main dropped come from `ChangeSource.droppedSchemasSince`)
  *   - anything else with a numeric payload: treated as a touch of that table (fail closed);
  *     anything else with a non-numeric payload: reported in `unsupported` (views, macros).
  *
  * A flush snapshot (`inline_flush:<id>` present) also emits `deleted_from_table:<id>` for the
  * inlined deletes it materializes; those are the same logical deletes already counted at their
  * `inlined_delete` snapshot, so every verb of a flushed table id in a flush snapshot is ignored.
  */
object BranchChanges:

  val DataVerbs: Set[String] =
    Set("inserted_into_table", "deleted_from_table", "inlined_insert", "inlined_delete")
  val TableDdlVerbs: Set[String]    = Set("created_table", "dropped_table", "altered_table")
  val MaintenanceVerbs: Set[String] =
    Set("inline_flush", "merge_adjacent", "compacted_table", "rewrite_delete")
  val IgnoredSchemaVerbs: Set[String] = Set("created_schema", "dropped_schema")

  /** Column names the merge's reads use for their own purposes (compared case-insensitively). */
  val ReservedColumns: List[String] = List("rowid", "snapshot_id")

  final case class Ref(schema: String, table: String)

  /** One parsed `verb:payload` entry. `tableId` when the payload is numeric, `ref` when it is a
    * quoted qualified name; `schemaName` for schema-level payloads.
    */
  final case class Entry(
      snapshotId: Long,
      verb: String,
      tableId: Option[Long],
      ref: Option[Ref],
      schemaName: Option[String],
      raw: String
  )

  def parseEntries(changes: List[(Long, String)]): List[Entry] =
    changes.flatMap { case (sid, s) =>
      s.split(",").toList.map(_.trim).filter(_.nonEmpty).flatMap { entry =>
        val sep = entry.indexOf(':')
        if sep <= 0 then Nil
        else
          val verb    = entry.substring(0, sep)
          val payload = entry.substring(sep + 1)
          payload.toLongOption match
            case Some(id) => List(Entry(sid, verb, Some(id), None, None, entry))
            case None     =>
              payload.split("\"\\.\"").toList match
                case s :: t :: Nil =>
                  List(
                    Entry(
                      sid,
                      verb,
                      None,
                      Some(Ref(s.stripPrefix("\""), t.stripSuffix("\""))),
                      None,
                      entry
                    )
                  )
                case _ =>
                  List(
                    Entry(
                      sid,
                      verb,
                      None,
                      None,
                      Some(payload.stripPrefix("\"").stripSuffix("\"")),
                      entry
                    )
                  )
      }
    }

  /** Touched tables of one catalog since `fork`, keyed by name at the time of the touch, plus the
    * unsupported (non-table) entries. `dataTouched` is the subset with a data verb.
    */
  final case class Touches(
      tables: Set[Ref],
      dataTouched: Set[Ref],
      unsupported: List[String]
  )

  def touches(entries: List[Entry], versions: List[TableVersion]): Touches =
    val bySnapshot  = entries.groupBy(_.snapshotId)
    var tables      = Set.empty[Ref]
    var data        = Set.empty[Ref]
    val unsupported = List.newBuilder[String]

    def resolve(e: Entry): Option[Ref] =
      e.ref.orElse(
        e.tableId.flatMap(id =>
          versions
            .filter(v => v.tableId == id && v.coversChangeAt(e.snapshotId))
            .sortBy(_.begin)
            .lastOption
            .orElse(versions.filter(_.tableId == id).sortBy(_.begin).lastOption)
            .map(v => Ref(v.schema, v.name))
        )
      )

    bySnapshot.toList.sortBy(_._1).foreach { case (_, es) =>
      val flushedIds = es.collect { case e if e.verb == "inline_flush" => e.tableId }.flatten.toSet
      es.foreach { e =>
        val flushArtifact = e.tableId.exists(flushedIds.contains)
        if MaintenanceVerbs.contains(e.verb) || flushArtifact then ()
        else if IgnoredSchemaVerbs.contains(e.verb) then ()
        else if DataVerbs.contains(e.verb) then
          resolve(e) match
            case Some(r) => tables += r; data += r
            case None    => unsupported += e.raw
        else if TableDdlVerbs.contains(e.verb) then
          resolve(e) match
            case Some(r) => tables += r
            case None    => unsupported += e.raw
        else if e.tableId.isDefined then
          // Unknown verb naming a table id: fail closed, count it as a touch.
          resolve(e) match
            case Some(r) => tables += r; data += r
            case None    => unsupported += e.raw
        else unsupported += e.raw
      }
    }
    Touches(tables, data, unsupported.result())

  /** Whether two column listings are the same columns. The order number is the column's identity: a
    * column dropped and added again under the same name and type takes the next one, and has lost
    * its values.
    */
  private def sameShape(a: List[CatalogColumnEntry], b: List[CatalogColumnEntry]): Boolean =
    def shape(cs: List[CatalogColumnEntry]) =
      cs.map(c => (c.ordinal, c.name, c.typeName, c.nullable))
    shape(a) == shape(b)

  /** Classify the tables `source` touched since `fork`, against its own metadata. `only` narrows
    * which of them to look at, which saves their column reads.
    */
  def classify(
      source: ChangeSource,
      fork: Long,
      head: Long,
      only: Ref => Boolean = _ => true
  ): (List[TableChange], List[String]) =
    val versions = source.tableVersions()
    val t        = touches(parseEntries(source.snapshotChangesSince(fork)), versions)
    val changes  = t.tables.toList.filter(only).sortBy(r => (r.schema, r.table)).flatMap { ref =>
      def idAt(n: Long): Option[Long] =
        versions
          .find(v => v.schema == ref.schema && v.name == ref.table && v.visibleAt(n))
          .map(_.tableId)
      // A rename commits rows written meanwhile under the name the table has at that moment, so a
      // data touch counts under any name the table's id has held.
      def rowsTouched(id: Long): Boolean =
        versions.exists(v => v.tableId == id && t.dataTouched.contains(Ref(v.schema, v.name)))
      val atFork = idAt(fork)
      val atHead = idAt(head)
      (atFork, atHead) match
        case (None, Some(h)) =>
          // A table that existed at the fork under another name was renamed, not created.
          if versions.exists(v => v.tableId == h && v.visibleAt(fork)) then
            Some(TableChange(ref.schema, ref.table, ChangeKind.Altered, tableId = Some(h)))
          else Some(TableChange(ref.schema, ref.table, ChangeKind.Created))
        case (Some(f), None) =>
          Some(TableChange(ref.schema, ref.table, ChangeKind.Dropped, tableId = Some(f)))
        case (Some(f), Some(h)) if f != h =>
          Some(TableChange(ref.schema, ref.table, ChangeKind.Recreated, tableId = Some(f)))
        case (Some(f), Some(_)) =>
          val altered =
            !sameShape(source.columnsOfTableAt(f, fork), source.columnsOfTableAt(f, head))
          if altered then
            Some(TableChange(ref.schema, ref.table, ChangeKind.Altered, tableId = Some(f)))
          else if rowsTouched(f) then
            val own = source.topLevelColumnsOfTableAt(f, head)
            Some(
              TableChange(
                ref.schema,
                ref.table,
                ChangeKind.Modified,
                reservedColumn = ReservedColumns.find(r => own.exists(_.name.equalsIgnoreCase(r))),
                tableId = Some(f)
              )
            )
          else None
        case (None, None) =>
          // Created and dropped again: nothing to merge.
          None
    }
    (changes, t.unsupported)

  /** What main did to the named tables since the fork, and the schemas it dropped. */
  private final case class MainView(kinds: Map[Ref, ChangeKind], droppedSchemas: Set[String])

  private def mainView(names: Set[Ref], main: ChangeSource, fork: Long): MainView =
    val head     = main.maxSnapshotId().getOrElse(fork)
    val versions = main.tableVersions()
    // `touches` reports a rename under the new name only, so the name at the fork goes by its id.
    val renamed = names.filter { ref =>
      versions
        .find(v => v.schema == ref.schema && v.name == ref.table && v.visibleAt(fork))
        .exists(atFork =>
          versions.exists(v =>
            v.tableId == atFork.tableId && v.visibleAt(head) &&
              (v.schema != ref.schema || v.name != ref.table)
          )
        )
    }
    MainView(
      classify(main, fork, head, names.contains)._1
        .map(t => Ref(t.schema, t.table) -> t.kind)
        .toMap ++ renamed.map(_ -> ChangeKind.Altered),
      main.droppedSchemasSince(fork)
    )

  /** Table conflicts: what no row merge can settle. Rows changed on both sides are not one. */
  private def conflictsIn(tables: List[TableChange], view: MainView): List[BranchConflict] =
    tables.flatMap { c =>
      (c.kind, view.kinds.get(Ref(c.schema, c.table))) match
        case (_, None) =>
          Option.when(view.droppedSchemas.contains(c.schema))(
            BranchConflict(c.schema, c.table, s"schema '${c.schema}' was dropped on main")
          )
        case (ChangeKind.Modified, Some(ChangeKind.Modified)) => None
        case (_, Some(onMain))                                =>
          val what = onMain match
            case ChangeKind.Altered   => "schema changed"
            case ChangeKind.Dropped   => "dropped"
            case ChangeKind.Recreated => "recreated"
            case ChangeKind.Created   => "also created"
            case ChangeKind.Modified  => "changed"
          Some(BranchConflict(c.schema, c.table, s"$what on main since the fork"))
    }

  /** Table conflicts of `branchTables` against main since `fork`. */
  def conflicts(
      branchTables: List[TableChange],
      main: ChangeSource,
      fork: Long
  ): List[BranchConflict] =
    conflictsIn(
      branchTables,
      mainView(branchTables.map(c => Ref(c.schema, c.table)).toSet, main, fork)
    )

  /** The whole change set without change-feed counts (the caller fills counts through the branch
    * pool when it wants them).
    */
  def compute(branch: ChangeSource, main: ChangeSource, fork: Long): BranchChangeSet =
    val head                  = branch.maxSnapshotId().getOrElse(fork)
    val mainHead              = main.maxSnapshotId().getOrElse(fork)
    val (tables, unsupported) = classify(branch, fork, head)
    val view                  = mainView(tables.map(c => Ref(c.schema, c.table)).toSet, main, fork)
    BranchChangeSet(
      forkSnapshot = fork,
      headSnapshot = head,
      mainSnapshot = mainHead,
      tables = tables.map(t =>
        t.copy(threeWay =
          t.kind == ChangeKind.Modified &&
            view.kinds.get(Ref(t.schema, t.table)).contains(ChangeKind.Modified)
        )
      ),
      conflicts = conflictsIn(tables, view),
      unsupported = unsupported
    )

  /** The snapshots to read one table's changes over: every one in `(fork, head]` except that
    * table's maintenance, which would report surviving rows as changes (a rewrite) or nothing (a
    * flush). Left: snapshots that cannot be read correctly, those mixing maintenance and data on
    * the table and those holding a verb on the table that this code does not know (it fails closed,
    * as `touches` does). A flush's `deleted_from_table` on the flushed table is the known artifact,
    * not a mix.
    */
  def readRanges(
      entries: List[Entry],
      tableId: Long,
      fork: Long,
      head: Long
  ): Either[List[Long], List[SnapshotRange]] =
    val ofTable = entries
      .filter(e => e.tableId.contains(tableId) && e.snapshotId > fork && e.snapshotId <= head)
      .groupBy(_.snapshotId)
    val known                             = DataVerbs ++ MaintenanceVerbs ++ TableDdlVerbs
    def isMaintenance(verbs: Set[String]) = verbs.exists(MaintenanceVerbs.contains)
    def isMixed(verbs: Set[String])       =
      val data     = verbs -- MaintenanceVerbs
      val artifact = if verbs.contains("inline_flush") then Set("deleted_from_table") else Set.empty
      isMaintenance(verbs) && (data -- artifact).nonEmpty
    def isUnreadable(verbs: Set[String]) = isMixed(verbs) || (verbs -- known).nonEmpty
    val verbsBySnapshot                  = ofTable.view.mapValues(_.map(_.verb).toSet).toMap
    val unreadable = verbsBySnapshot.collect { case (sid, vs) if isUnreadable(vs) => sid }.toList
    if unreadable.nonEmpty then Left(unreadable.sorted)
    else
      val skipped = verbsBySnapshot.collect { case (sid, vs) if isMaintenance(vs) => sid }.toSet
      val ranges  =
        (fork + 1 to head).filterNot(skipped.contains).foldLeft(List.empty[SnapshotRange]) {
          case (SnapshotRange(from, to) :: rest, id) if to + 1 == id =>
            SnapshotRange(from, id) :: rest
          case (acc, id) => SnapshotRange(id, id) :: acc
        }
      Right(ranges.reverse)

  /** The first snapshot id missing from `(fork, head]`: a manual expiry, or a branch older than the
    * maintenance rule that keeps them. `changes` is what `snapshotChangesSince(fork)` returned.
    */
  def historyGap(changes: List[(Long, String)], fork: Long, head: Long): Option[Long] =
    val present = changes.iterator.map(_._1).toSet
    (fork + 1 to head).find(id => !present.contains(id))
