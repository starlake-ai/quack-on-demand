package ai.starlake.quack.ondemand.branch

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

  it should "say there is nothing to do when no row would be written and no table changes" in {
    val none = RowCounts("s", "a")
    MergeDecision.decide(List(none), Nil, None, otherTables = false) shouldBe Verdict.NothingToDo
    MergeDecision.decide(List(none), Nil, None, otherTables = true) shouldBe Verdict.Proceed
    MergeDecision.decide(Nil, Nil, None, otherTables = true) shouldBe Verdict.Proceed
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

  "RowCounts" should "round trip through its stored JSON, and read garbage as none" in {
    val c = RowCounts(
      "s",
      "t",
      toUpdate = 2L,
      cellConflicts = 1L,
      columnConflicts = Map("a" -> 1L),
      samples = Map("cell" -> List(7L))
    )
    RowCounts.listFromJson(RowCounts.listToJson(List(c))) shouldBe List(c)
    RowCounts.listFromJson("not json") shouldBe Nil
    c.conflicts shouldBe 1L
    c.writesNothing shouldBe false
    RowCounts("s", "t").writesNothing shouldBe true
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
