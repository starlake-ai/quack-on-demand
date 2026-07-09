package ai.starlake.quack.ondemand.catalog

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HistoryOperationSpec extends AnyFlatSpec with Matchers:

  "isValid" should "accept every taxonomy value and reject anything else" in {
    HistoryOperation.Values.foreach(op => HistoryOperation.isValid(op) shouldBe true)
    HistoryOperation.isValid("merge") shouldBe false
    HistoryOperation.isValid("") shouldBe false
    HistoryOperation.isValid("INSERT") shouldBe false
  }

  it should "carry exactly the eight agreed values" in {
    HistoryOperation.Values shouldBe Set(
      "create",
      "insert",
      "delete",
      "update",
      "alter",
      "drop",
      "maintenance",
      "unknown"
    )
  }

  "effectiveDeltas" should "zero both row deltas for maintenance commits" in {
    HistoryOperation.effectiveDeltas(HistoryOperation.Maintenance, 500L, 500L, 0L) shouldBe (0L, 0L)
  }

  it should "sum delete-file delta and retired rows for everything else" in {
    HistoryOperation.effectiveDeltas(HistoryOperation.Insert, 10L, 0L, 0L) shouldBe (10L, 0L)
    HistoryOperation.effectiveDeltas(HistoryOperation.Delete, 0L, 0L, 3L) shouldBe (0L, 3L)
    HistoryOperation.effectiveDeltas(HistoryOperation.Update, 2L, 0L, 2L) shouldBe (2L, 2L)
    HistoryOperation.effectiveDeltas(HistoryOperation.Drop, 0L, 7L, 1L) shouldBe (0L, 8L)
  }
