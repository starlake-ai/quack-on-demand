package ai.starlake.quack.ondemand.branch

import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MergeDecisionSpec extends AnyFlatSpec with Matchers:

  import MergeDecision.Verdict

  private def row(table: String, update: Long = 1L, cell: Long = 0L) =
    RowCounts("s", table, toUpdate = update, cellConflicts = cell)

  private def code(v: Verdict) = v match
    case Verdict.Refuse(f) => f.code
    case other             => other.toString

  "decide" should "proceed when nothing conflicts" in {
    MergeDecision.decide(List(row("a")), Nil, None, otherTables = false) shouldBe Verdict.Proceed
  }

  it should "refuse any conflict when the proposal names no winner, naming tables, not numbers" in {
    val v =
      MergeDecision.decide(List(row("a", cell = 3L), row("b")), Nil, None, otherTables = false)
    code(v) shouldBe "merge_conflict"
    val Verdict.Refuse(f) = v: @unchecked
    f.status shouldBe 409
    f.message should include("s.a")
    f.message should not include "3"
  }

  it should "count every kind of conflict" in {
    def refused(c: RowCounts) = code(MergeDecision.decide(List(c), Nil, None, otherTables = false))
    val base                  = RowCounts("s", "a", toUpdate = 1L)
    refused(base.copy(cellConflicts = 1L)) shouldBe "merge_conflict"
    refused(base.copy(updateDeleteConflicts = 1L)) shouldBe "merge_conflict"
    refused(base.copy(deleteUpdateConflicts = 1L)) shouldBe "merge_conflict"
    refused(base.copy(keyMatches = 1L)) shouldBe "merge_conflict"
  }

  it should "proceed with a policy while conflicts stay within what the proposal showed" in {
    val proposed = List(row("a", cell = 3L))
    MergeDecision.decide(
      List(row("a", cell = 3L)),
      proposed,
      Some(ConflictPolicy.Branch),
      false
    ) shouldBe
      Verdict.Proceed
    MergeDecision.decide(
      List(row("a", cell = 2L)),
      proposed,
      Some(ConflictPolicy.Main),
      false
    ) shouldBe
      Verdict.Proceed
  }

  it should "refuse more conflicts than the proposal showed, even with a policy" in {
    val proposed = List(row("a", cell = 3L))
    val v        =
      MergeDecision.decide(List(row("a", cell = 4L)), proposed, Some(ConflictPolicy.Branch), false)
    code(v) shouldBe "conflicts_changed"
    // A table with none at propose time, which has some now.
    code(
      MergeDecision.decide(List(row("b", cell = 1L)), proposed, Some(ConflictPolicy.Main), false)
    ) shouldBe "conflicts_changed"
  }

  it should "compare each kind of conflict on its own, so one cannot stand in for another" in {
    val base                                     = RowCounts("s", "a", toUpdate = 1L)
    val kinds: List[(String, Long => RowCounts)] = List(
      ("cell", (n: Long) => base.copy(cellConflicts = n)),
      ("update/delete", (n: Long) => base.copy(updateDeleteConflicts = n)),
      ("delete/update", (n: Long) => base.copy(deleteUpdateConflicts = n)),
      ("key", (n: Long) => base.copy(keyMatches = n))
    )
    // The proposal showed one cell conflict; now there is none of that kind, and one update/delete.
    val shown = List(base.copy(cellConflicts = 1L))
    val now   = List(base.copy(updateDeleteConflicts = 1L))
    code(MergeDecision.decide(now, shown, Some(ConflictPolicy.Branch), false)) shouldBe
      "conflicts_changed"
    // Every pairing: only the same kind, in the same number, is within what the proposal showed.
    for
      (was, shownOf) <- kinds
      (isNow, nowOf) <- kinds
      policy         <- ConflictPolicy.values
    do
      withClue(s"shown $was, now $isNow, $policy: ") {
        val v = MergeDecision.decide(List(nowOf(1L)), List(shownOf(1L)), Some(policy), false)
        if was == isNow then v shouldBe Verdict.Proceed else code(v) shouldBe "conflicts_changed"
      }
  }

  it should "cap the rows deleted from main under branch, which new key matches can raise unseen" in {
    // Under branch every main row with a matching key is deleted, however many now match, while
    // keyMatches counts the branch's rows only: one branch row can come to delete a million.
    val shown = RowCounts("s", "a", toUpdate = 1L, toDelete = 1L, keyMatches = 1L)
    def decide(now: RowCounts, policy: ConflictPolicy) =
      MergeDecision.decide(List(now), List(shown), Some(policy), otherTables = false)
    val flood = shown.copy(toDelete = 1000001L)
    code(decide(flood, ConflictPolicy.Branch)) shouldBe "conflicts_changed"
    decide(flood, ConflictPolicy.Main) shouldBe Verdict.Proceed
    // As many as were shown, or fewer (main deleted some of the rows the branch deleted): fine.
    decide(shown, ConflictPolicy.Branch) shouldBe Verdict.Proceed
    decide(shown.copy(toDelete = 0L), ConflictPolicy.Branch) shouldBe Verdict.Proceed
  }

  it should "credit a table only with the counts of its own schema and table" in {
    val proposed = List(RowCounts("x", "t", toUpdate = 1L, cellConflicts = 3L))
    val inX      = RowCounts("x", "t", toUpdate = 1L, cellConflicts = 3L)
    val inY      = RowCounts("y", "t", toUpdate = 1L, cellConflicts = 3L)
    code(MergeDecision.decide(List(inY), proposed, Some(ConflictPolicy.Main), false)) shouldBe
      "conflicts_changed"
    MergeDecision.decide(List(inX), proposed, Some(ConflictPolicy.Main), false) shouldBe
      Verdict.Proceed
    // Two tables of one name, each with its own counts: only the one that grew is named.
    val bothShown = List(inX, inY.copy(cellConflicts = 1L))
    val bothNow   = List(inX.copy(cellConflicts = 1L), inY)
    val v         = MergeDecision.decide(bothNow, bothShown, Some(ConflictPolicy.Main), false)
    val Verdict.Refuse(f) = v: @unchecked
    f.message should include("y.t")
    f.message should not include "x.t"
  }

  it should "refuse with a 409 that names only the tables that grew and holds no count" in {
    val proposed          = List(row("a", cell = 3L), row("b", cell = 1L))
    val now               = List(row("a", cell = 4L), row("b", cell = 1L), row("c"))
    val v                 = MergeDecision.decide(now, proposed, Some(ConflictPolicy.Branch), false)
    val Verdict.Refuse(f) = v: @unchecked
    f.code shouldBe "conflicts_changed"
    f.status shouldBe 409
    f.message should include("s.a")
    f.message should not include "s.b"
    f.message should not include "s.c"
    f.message.exists(_.isDigit) shouldBe false
  }

  it should "say there is nothing to do when no row would be written and no table changes" in {
    val none = RowCounts("s", "a")
    MergeDecision.decide(List(none), Nil, None, otherTables = false) shouldBe Verdict.NothingToDo
    MergeDecision.decide(List(none), Nil, None, otherTables = true) shouldBe Verdict.Proceed
    MergeDecision.decide(Nil, Nil, None, otherTables = true) shouldBe Verdict.Proceed
    MergeDecision.decide(Nil, Nil, None, otherTables = false) shouldBe Verdict.NothingToDo
  }

  it should "proceed when only some of the tables write a row" in {
    val writes  = RowCounts("s", "w", toUpdate = 1L)
    val nothing = RowCounts("s", "n")
    MergeDecision.decide(List(writes, nothing), Nil, None, otherTables = false) shouldBe
      Verdict.Proceed
    MergeDecision.decide(List(nothing, writes), Nil, None, otherTables = false) shouldBe
      Verdict.Proceed
  }

  it should "count a delete or an insert as a write, not only an update" in {
    def decide(c: RowCounts) = MergeDecision.decide(List(c), Nil, None, otherTables = false)
    decide(RowCounts("s", "a", toUpdate = 1L)) shouldBe Verdict.Proceed
    decide(RowCounts("s", "a", toDelete = 1L)) shouldBe Verdict.Proceed
    decide(RowCounts("s", "a", toInsert = 1L)) shouldBe Verdict.Proceed
    decide(RowCounts("s", "a")) shouldBe Verdict.NothingToDo
  }

  "ConflictPolicy.fromWire" should "read main and branch only" in {
    ConflictPolicy.fromWire("main") shouldBe Some(ConflictPolicy.Main)
    ConflictPolicy.fromWire("branch") shouldBe Some(ConflictPolicy.Branch)
    ConflictPolicy.fromWire("ours") shouldBe None
  }

  "ProposeOptions.parse" should "read the policy and keys, and refuse bad ones" in {
    ProposeOptions.parse(None, Map.empty) shouldBe Right(ProposeOptions())
    ProposeOptions.parse(Some("main"), Map("s.t" -> List("k"))) shouldBe
      Right(ProposeOptions(Some(ConflictPolicy.Main), Map("s.t" -> List("k"))))
    ProposeOptions.parse(Some("ours"), Map.empty).left.map(_.code) shouldBe
      Left("invalid_on_conflict")
    ProposeOptions.parse(None, Map("t" -> List("k"))).left.map(_.code) shouldBe Left("invalid_key")
    ProposeOptions.parse(None, Map("s.t" -> Nil)).left.map(_.code) shouldBe Left("invalid_key")
    ProposeOptions.parse(None, Map("s.t" -> List(" "))).left.map(_.code) shouldBe
      Left("invalid_key")
  }

  it should "refuse with a 400" in {
    def failure(r: Either[BranchFailure, ProposeOptions]) = r.left.map(f => (f.status, f.code))
    failure(ProposeOptions.parse(Some("ours"), Map.empty)) shouldBe
      Left((400, "invalid_on_conflict"))
    failure(ProposeOptions.parse(None, Map("t" -> List("k")))) shouldBe Left((400, "invalid_key"))
    failure(ProposeOptions.parse(None, Map("s.t" -> Nil))) shouldBe Left((400, "invalid_key"))
    failure(ProposeOptions.parse(None, Map("s.t" -> List(" ")))) shouldBe
      Left((400, "invalid_key"))
  }

  "keyProblem" should "refuse a table not being merged by rows, and an unknown column" in {
    val tables = List(
      TableChange("s", "t", ChangeKind.Modified),
      TableChange("s", "made", ChangeKind.Created)
    )
    val columns = (_: TableChange) => List("id", "k")
    MergeDecision.keyProblem(Map("s.t" -> List("k", "id")), tables, columns) shouldBe None
    MergeDecision.keyProblem(Map("s.nope" -> List("k")), tables, columns).get should include(
      "s.nope"
    )
    MergeDecision.keyProblem(Map("s.made" -> List("k")), tables, columns).get should include(
      "s.made"
    )
    MergeDecision.keyProblem(Map("s.t" -> List("nope")), tables, columns).get should include("nope")
  }

  "RowCounts" should "round trip through its stored JSON" in {
    val c = RowCounts(
      "s",
      "t",
      toUpdate = 2L,
      cellConflicts = 1L,
      columnConflicts = Map("a" -> 1L),
      samples = Map("cell" -> List(7L))
    )
    RowCounts.listFromJson(RowCounts.listToJson(List(c))) shouldBe Right(List(c))
    RowCounts.listFromJson("[]") shouldBe Right(Nil)
    c.conflicts shouldBe 1L
    c.writesNothing shouldBe false
    RowCounts("s", "t").writesNothing shouldBe true
  }

  it should "never read what it cannot read as none" in {
    val unreadable = Left("the proposal's counts cannot be read; propose again")
    RowCounts.listFromJson("not json") shouldBe unreadable
    RowCounts.listFromJson("") shouldBe unreadable
    // A row with a wrong type, or one lacking its table, spoils the list: the readable row's
    // conflicts are not shown alone.
    RowCounts.listFromJson("""[{"schema":"s","table":"t","toUpdate":"many"}]""") shouldBe unreadable
    RowCounts.listFromJson(
      """[{"schema":"s","table":"t","cellConflicts":2},{"schema":"s"}]"""
    ) shouldBe unreadable
  }

  it should "read a stored row that lacks a field added since" in {
    RowCounts.listFromJson("""[{"schema":"s","table":"t"}]""") shouldBe
      Right(List(RowCounts("s", "t")))
    RowCounts.listFromJson("""[{"schema":"s","table":"t","cellConflicts":2}]""") shouldBe
      Right(List(RowCounts("s", "t", cellConflicts = 2L)))
  }

  it should "store every field under its own name" in {
    val full = RowCounts(
      "s",
      "t",
      toUpdate = 2L,
      toDelete = 3L,
      toInsert = 4L,
      cellConflicts = 5L,
      updateDeleteConflicts = 6L,
      deleteUpdateConflicts = 7L,
      keyMatches = 8L,
      minUpdateRowid = 9L,
      maxUpdateRowid = 10L,
      columnConflicts = Map("a" -> 11L),
      samples = Map("cell" -> List(12L, 13L))
    )
    val stored =
      """[{
        |  "schema": "s", "table": "t",
        |  "toUpdate": 2, "toDelete": 3, "toInsert": 4,
        |  "cellConflicts": 5, "updateDeleteConflicts": 6, "deleteUpdateConflicts": 7,
        |  "keyMatches": 8, "minUpdateRowid": 9, "maxUpdateRowid": 10,
        |  "columnConflicts": {"a": 11}, "samples": {"cell": [12, 13]}
        |}]""".stripMargin
    RowCounts.listFromJson(stored) shouldBe Right(List(full))
    parse(RowCounts.listToJson(List(full))) shouldBe parse(stored)
  }

  "the error helpers" should "recognize a moved main and an exhausted memory" in {
    MergeDecision.mainMoved("x qod merge fence: a merged table moved past snapshot 4") shouldBe true
    MergeDecision.mainMoved("Transaction conflict - attempting to delete") shouldBe true
    MergeDecision.mainMoved(
      "Failed to commit DuckLake transaction. Exceeded the maximum retry count of 10"
    ) shouldBe true
    MergeDecision.mainMoved("Binder Error: no such table") shouldBe false
    MergeDecision.outOfMemory("Out of Memory Error: failed to allocate") shouldBe true
    MergeDecision.outOfMemory("Binder Error") shouldBe false
  }

  "failureText" should "end at the first quote: DuckLake quotes its failed batch, inlined rows included" in {
    MergeDecision.failureText(
      """Failed to commit: Failed to execute query "INSERT INTO i VALUES ('SENTINEL')": ERROR: boom"""
    ) shouldBe "Failed to commit: Failed to execute query"
    MergeDecision.failureText("Conversion Error: could not convert 'SENTINEL' to INT32") shouldBe
      "Conversion Error: could not convert"
  }

  it should "say the merge node needs more memory, keeping the sizes" in {
    val oom =
      "Out of Memory Error: failed to allocate data of size 1.0 GiB (15.9 GiB/16.0 GiB used)"
    MergeDecision.failureText(oom) shouldBe s"the merge node needs more memory: $oom"
  }

  it should "stay short, and say something when nothing survives" in {
    MergeDecision.failureText("x" * 1000).length shouldBe 300
    MergeDecision.failureText("\"quoted from the start\"") shouldBe "the merge failed on the node"
  }

  it should "keep the first line only: later lines hold hints, quotes and values" in {
    // DuckDB 2.0.0's text for SET memory_limit='20MB' on an in-memory database, as printed.
    val oom =
      """Out of Memory Error: could not allocate block of size 256.0 KiB (19.0 MiB/19.0 MiB used)
        |Database is launched in in-memory mode and no temporary directory is specified.
        |Unused blocks cannot be offloaded to disk.
        |
        |Launch the database with a persistent storage back-end
        |Or set SET temp_directory='/path/to/tmp.tmp'
        |
        |Possible solutions:
        |* Reducing the number of threads (SET threads=X)
        |* Disabling insertion-order preservation (SET preserve_insertion_order=false)
        |* Increasing the memory limit (SET memory_limit='...GB')
        |
        |See also https://duckdb.org/docs/current/guides/performance/how_to_tune_workloads""".stripMargin
    MergeDecision.failureText(oom) shouldBe
      "the merge node needs more memory: Out of Memory Error: could not allocate block of size " +
      "256.0 KiB (19.0 MiB/19.0 MiB used)"
    MergeDecision.failureText("Binder Error: no such table\r\nLINE 1: SELECT 1") shouldBe
      "Binder Error: no such table"
    // A detail line can hold a value with no quote around it.
    val detail =
      "Failed to commit: ERROR: duplicate key value\nDETAIL: Key (day)=(SENTINEL) already exists."
    MergeDecision.failureText(detail) shouldBe "Failed to commit: ERROR: duplicate key value"
  }

  it should "say the merge node needs more memory with no colon when no text is left" in {
    MergeDecision.failureText("\"quoted from the start\" Out of Memory Error") shouldBe
      "the merge node needs more memory"
  }
