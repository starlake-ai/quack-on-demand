package ai.starlake.quack.ondemand.branch

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode
import io.circe.syntax.*

/** Which side's value a conflicting cell takes, named on the proposal. */
enum ConflictPolicy(val wire: String):
  case Main   extends ConflictPolicy("main")
  case Branch extends ConflictPolicy("branch")

object ConflictPolicy:
  def fromWire(s: String): Option[ConflictPolicy] = values.find(_.wire == s)

/** One table's row merge as Decide saw it: how many rows each statement would write, the conflicts
  * and sample rowids. Numbers and rowids only: no cell value is ever kept (design section 8.2).
  * `updateDeleteConflicts`: main updated, the branch deleted. `deleteUpdateConflicts`: main
  * deleted, the branch updated.
  */
final case class RowCounts(
    schema: String,
    table: String,
    toUpdate: Long = 0L,
    toDelete: Long = 0L,
    toInsert: Long = 0L,
    cellConflicts: Long = 0L,
    updateDeleteConflicts: Long = 0L,
    deleteUpdateConflicts: Long = 0L,
    keyMatches: Long = 0L,
    minUpdateRowid: Long = 0L,
    maxUpdateRowid: Long = 0L,
    columnConflicts: Map[String, Long] = Map.empty,
    samples: Map[String, List[Long]] = Map.empty
):
  def conflicts: Long = cellConflicts + updateDeleteConflicts + deleteUpdateConflicts + keyMatches
  def writesNothing: Boolean = toUpdate == 0L && toDelete == 0L && toInsert == 0L

object RowCounts:
  // The counts are stored with a proposal and outlive the build that wrote them. A field added
  // later reads as its default in a row stored before it: plain deriveCodec rejects a missing
  // field (ConfigManifest, FleetAssignment and IcebergRestConfig derive the same way).
  private given Configuration = Configuration.default.withDefaults
  given Codec[RowCounts]      = ConfiguredCodec.derived

  def listToJson(cs: List[RowCounts]): String = cs.asJson.noSpaces

  /** A proposal's stored counts. What cannot be read is an error, never an empty list: no counts
    * would show the approver, and the merge, a proposal that found no conflicts. A counter a stored
    * row lacks reads as 0, which understates what the proposal showed, so `MergeDecision.decide`
    * still refuses more, not less.
    */
  def listFromJson(s: String): Either[String, List[RowCounts]] =
    decode[List[RowCounts]](s).left.map(_ => "the proposal's counts cannot be read; propose again")

/** What a proposal asks for. `keys`: `"schema.table"` to the columns that catch double inserts. */
final case class ProposeOptions(
    onConflict: Option[ConflictPolicy] = None,
    keys: Map[String, List[String]] = Map.empty
)

object ProposeOptions:
  def parse(
      onConflict: Option[String],
      keys: Map[String, List[String]]
  ): Either[BranchFailure, ProposeOptions] =
    val policy: Either[BranchFailure, Option[ConflictPolicy]] = onConflict match
      case None    => Right(None)
      case Some(w) =>
        ConflictPolicy
          .fromWire(w)
          .map(Some(_))
          .toRight(
            BranchFailure.badRequest(
              "invalid_on_conflict",
              s"onConflict must be 'main' or 'branch', not '$w'"
            )
          )
    policy.flatMap { p =>
      keys.collectFirst {
        case (t, cols) if !t.contains('.') || cols.isEmpty || cols.exists(_.isBlank) => t
      } match
        case Some(bad) =>
          Left(
            BranchFailure.badRequest(
              "invalid_key",
              s"key '$bad' must name a schema.table and at least one column"
            )
          )
        case None => Right(ProposeOptions(p, keys))
    }

object MergeDecision:

  enum Verdict:
    case Proceed
    case NothingToDo
    case Refuse(failure: BranchFailure)

  private def names(cs: List[RowCounts]): String =
    cs.map(c => s"${c.schema}.${c.table}").mkString(", ")

  /** What the proposal showed for `c`'s table: all zeros when it did not list it. */
  private def shownFor(c: RowCounts, proposed: List[RowCounts]): RowCounts =
    proposed
      .find(p => p.schema == c.schema && p.table == c.table)
      .getOrElse(RowCounts(c.schema, c.table))

  /** Whether `c` is above what the proposal showed in any one kind of conflict. A total would let a
    * shown cell conflict stand in for a delete the approver never saw.
    *
    * Under `branch` it is also above when it deletes more rows from main. `branch` deletes every
    * main row whose key matches, however many now do, while `keyMatches` counts the branch's rows
    * only: one branch row can come to delete a million that no conflict count showed. The branch is
    * frozen, so only key matches can raise `toDelete`; main deleting rows the branch deleted only
    * lowers it.
    */
  private def grew(c: RowCounts, shown: RowCounts, policy: Option[ConflictPolicy]): Boolean =
    c.cellConflicts > shown.cellConflicts ||
      c.updateDeleteConflicts > shown.updateDeleteConflicts ||
      c.deleteUpdateConflicts > shown.deleteUpdateConflicts ||
      c.keyMatches > shown.keyMatches ||
      (policy.contains(ConflictPolicy.Branch) && c.toDelete > shown.toDelete)

  /** Whether an attempt goes on to Apply. `proposed`: the counts the approver saw. Messages name
    * tables, never numbers: the caller may hold no grant on them (design section 8.2).
    */
  def decide(
      now: List[RowCounts],
      proposed: List[RowCounts],
      policy: Option[ConflictPolicy],
      otherTables: Boolean
  ): Verdict =
    val conflicted = now.filter(_.conflicts > 0L)
    val grown      = conflicted.filter(c => grew(c, shownFor(c, proposed), policy))
    if policy.isEmpty && conflicted.nonEmpty then
      Verdict.Refuse(
        BranchFailure.conflict(
          "merge_conflict",
          s"row conflicts on: ${names(conflicted)}; see the proposal, or propose again with " +
            "--on-conflict"
        )
      )
    else if policy.nonEmpty && grown.nonEmpty then
      Verdict.Refuse(
        BranchFailure.conflict(
          "conflicts_changed",
          s"more row conflicts than the proposal showed on: ${names(grown)}; propose again"
        )
      )
    else if !otherTables && now.forall(_.writesNothing) then Verdict.NothingToDo
    else Verdict.Proceed

  /** Why a declared key cannot be used, if it cannot. */
  def keyProblem(
      keys: Map[String, List[String]],
      tables: List[TableChange],
      columnsOf: TableChange => List[String]
  ): Option[String] =
    keys.toList
      .sortBy(_._1)
      .flatMap { (name, cols) =>
        tables.find(t => s"${t.schema}.${t.table}" == name && t.kind == ChangeKind.Modified) match
          case None =>
            Some(s"key on '$name': the branch did not change that table's rows")
          case Some(t) =>
            val known = columnsOf(t)
            cols
              .find(c => !known.contains(c))
              .map(c => s"key on '$name': no column named '$c'")
      }
      .headOption

  /** The commit failed in a way the next attempt can get past: the qod fence, DuckLake's own
    * table-level check, or DuckLake giving up after its retries. The last does not prove that main
    * moved. DuckLake retries any commit error whose text holds `primary key`, `unique`, `conflict`
    * or `concurrent`, the failed statement's own text included, so a permanent failure can end in
    * the same exhausted text and read as main moved. It then ends as `concurrent_write` with the
    * proposal still open, which bounds the harm.
    */
  def mainMoved(error: String): Boolean =
    error.contains("qod merge fence:") || error.contains("Transaction conflict") ||
      error.contains("Exceeded the maximum retry count")

  def outOfMemory(error: String): Boolean = error.contains("Out of Memory Error")

  private val MaxFailureText = 300

  /** What may be stored and returned of a node's error: its first line, up to the first quote.
    * DuckDB's errors quote values, and DuckLake's quote the whole failed metadata batch, inlined
    * rows included, so no cell value is ever stored (design section 8.2). Only the first line,
    * because the lines after it can hold a value with no quote around it (a detail line) and, in an
    * out-of-memory error, tuning hints naming settings the caller cannot change; the sizes are on
    * the first line.
    *
    * The cut does not catch a value DuckDB prints with no quote, as in "with value N can't be
    * cast". That error is not reachable: the merge's own SQL holds no cast, it copies and compares
    * columns of the same type. A statement added to it that casts must check this again.
    */
  def failureText(error: String): String =
    val kept = error
      .takeWhile(c => c != '\n' && c != '\r' && c != '\'' && c != '"')
      .take(MaxFailureText)
      .trim
    if outOfMemory(error) then
      if kept.isEmpty then "the merge node needs more memory"
      else s"the merge node needs more memory: $kept"
    else if kept.isEmpty then "the merge failed on the node"
    else kept
