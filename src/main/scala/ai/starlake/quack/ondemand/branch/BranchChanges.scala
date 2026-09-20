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

  /** Same `table_id`, columns (names, types, nullability) or name differ: not fast-forwardable. */
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
    updated: Long = 0L
):
  def mergeable: Boolean = kind != ChangeKind.Altered

  def reason: Option[String] = kind match
    case ChangeKind.Altered =>
      Some("schema change (column add/drop/retype or rename) is not fast-forwardable in v1")
    case _ => None

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

/** Pure classification of `ducklake_snapshot_changes` verbs into table change sets (Epic 1).
  *
  * Verb vocabulary (DuckLake 1.0 metadata written by DuckDB 1.5.x; the pin-bump checklist spec
  * asserts the engine emits nothing outside `DuckLakeCatalogReader.HistoryVerbs` ++
  * `HistoryIgnoredVerbs`, and every verb here is drawn from that union plus the schema/view forms):
  *   - data verbs: `inserted_into_table`, `deleted_from_table`, `inlined_insert`, `inlined_delete`
  *   - table DDL verbs: `created_table`, `dropped_table`, `altered_table`
  *   - maintenance verbs, never a touch: `inline_flush`, `merge_adjacent`, `compacted_table`
  *   - schema-level: `created_schema` (harmless), `dropped_schema` (a main-side conflict)
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
  val TableDdlVerbs: Set[String]      = Set("created_table", "dropped_table", "altered_table")
  val MaintenanceVerbs: Set[String]   = Set("inline_flush", "merge_adjacent", "compacted_table")
  val IgnoredSchemaVerbs: Set[String] = Set("created_schema")
  val DroppedSchemaVerb: String       = "dropped_schema"

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
    * unsupported (non-table) entries and the schemas dropped. `dataTouched` is the subset with a
    * data verb.
    */
  final case class Touches(
      tables: Set[Ref],
      dataTouched: Set[Ref],
      droppedSchemas: Set[String],
      unsupported: List[String]
  )

  def touches(entries: List[Entry], versions: List[TableVersion]): Touches =
    val bySnapshot  = entries.groupBy(_.snapshotId)
    var tables      = Set.empty[Ref]
    var data        = Set.empty[Ref]
    var schemas     = Set.empty[String]
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
        else if e.verb == DroppedSchemaVerb then schemas += e.schemaName.getOrElse(e.raw)
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
    Touches(tables, data, schemas, unsupported.result())

  private def sameShape(a: List[CatalogColumnEntry], b: List[CatalogColumnEntry]): Boolean =
    a.map(c => (c.name, c.typeName, c.nullable)) == b.map(c => (c.name, c.typeName, c.nullable))

  /** Classify every table the branch touched since `fork`, against the branch's own metadata. */
  def classify(branch: ChangeSource, fork: Long, head: Long): (List[TableChange], List[String]) =
    val versions = branch.tableVersions()
    val t        = touches(parseEntries(branch.snapshotChangesSince(fork)), versions)
    val changes  = t.tables.toList.sortBy(r => (r.schema, r.table)).flatMap { ref =>
      def idAt(n: Long): Option[Long] =
        versions
          .find(v => v.schema == ref.schema && v.name == ref.table && v.visibleAt(n))
          .map(_.tableId)
      val atFork = idAt(fork)
      val atHead = idAt(head)
      (atFork, atHead) match
        case (None, Some(_)) => Some(TableChange(ref.schema, ref.table, ChangeKind.Created))
        case (Some(_), None) => Some(TableChange(ref.schema, ref.table, ChangeKind.Dropped))
        case (Some(f), Some(h)) if f != h =>
          Some(TableChange(ref.schema, ref.table, ChangeKind.Recreated))
        case (Some(f), Some(_)) =>
          val altered =
            !sameShape(branch.columnsOfTableAt(f, fork), branch.columnsOfTableAt(f, head))
          if altered then Some(TableChange(ref.schema, ref.table, ChangeKind.Altered))
          else if t.dataTouched.contains(ref) then
            Some(TableChange(ref.schema, ref.table, ChangeKind.Modified))
          else None
        case (None, None) =>
          // Created and dropped again within the branch: nothing to merge.
          None
    }
    (changes, t.unsupported)

  /** Conflicts: a table the branch touched that main also touched since the fork, or a schema main
    * dropped that holds a branch-touched table.
    */
  def conflicts(
      branchTables: List[TableChange],
      main: ChangeSource,
      fork: Long
  ): List[BranchConflict] =
    val mt = touches(parseEntries(main.snapshotChangesSince(fork)), main.tableVersions())
    branchTables.flatMap { c =>
      val ref = Ref(c.schema, c.table)
      if mt.tables.contains(ref) then
        Some(BranchConflict(c.schema, c.table, "changed on main since the fork"))
      else if mt.droppedSchemas.contains(c.schema) then
        Some(BranchConflict(c.schema, c.table, s"schema '${c.schema}' was dropped on main"))
      else None
    }

  /** The whole change set without change-feed counts (the caller fills counts through the branch
    * pool when it wants them).
    */
  def compute(branch: ChangeSource, main: ChangeSource, fork: Long): BranchChangeSet =
    val head                  = branch.maxSnapshotId().getOrElse(fork)
    val mainHead              = main.maxSnapshotId().getOrElse(fork)
    val (tables, unsupported) = classify(branch, fork, head)
    BranchChangeSet(
      forkSnapshot = fork,
      headSnapshot = head,
      mainSnapshot = mainHead,
      tables = tables,
      conflicts = conflicts(tables, main, fork),
      unsupported = unsupported
    )
