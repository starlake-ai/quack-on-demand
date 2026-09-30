package ai.starlake.quack.ondemand.catalog.iceberg

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IcebergSnapshotsSpec extends AnyFlatSpec with Matchers:

  // Verbatim summaries from the 2026-09-29 fixture probe (DuckDB 1.5.6): append 3, append 1,
  // overwrite for the UPDATE, delete. The delete carries no added/deleted-records at all.
  private def row(
      id: String,
      parent: Option[String],
      seq: Long,
      ts: Long,
      summary: String,
      cur: Boolean
  ) =
    List(
      Json.fromString(id),
      parent.fold(Json.Null)(Json.fromString),
      Json.fromLong(seq),
      Json.fromLong(ts),
      Json.fromString(summary),
      Json.fromBoolean(cur)
    )

  // Identical to the last row of `rows` below, except seq is null: the format-v1 shape.
  private val v1Row = row(
    "7761858545720969174",
    None,
    1,
    1790709486022L,
    """{"operation":"append","total-records":"3","total-data-files":"1","added-records":"3","added-data-files":"1"}""",
    false
  ).updated(2, Json.Null)

  private val rows = List(
    row(
      "7557379181527318936",
      Some("4413216593749560206"),
      4,
      1790709486188L,
      """{"operation":"delete","total-records":"5","total-data-files":"3","added-position-deletes":"1"}""",
      true
    ),
    row(
      "4413216593749560206",
      Some("3745365227286907608"),
      3,
      1790709486145L,
      """{"operation":"overwrite","total-records":"5","total-data-files":"3","added-records":"1","added-position-deletes":"1","added-data-files":"1","deleted-records":"0"}""",
      false
    ),
    row(
      "3745365227286907608",
      Some("7761858545720969174"),
      2,
      1790709486098L,
      """{"operation":"append","total-records":"4","total-data-files":"2","added-records":"1","added-data-files":"1"}""",
      false
    ),
    row(
      "7761858545720969174",
      None,
      1,
      1790709486022L,
      """{"operation":"append","total-records":"3","total-data-files":"1","added-records":"3","added-data-files":"1"}""",
      false
    )
  )

  "parse" should "read every snapshot, keeping ids as strings above 2^53" in {
    val snaps = IcebergSnapshots.parse(rows).toOption.get
    snaps.map(_.snapshotId) shouldBe List(
      "7557379181527318936",
      "4413216593749560206",
      "3745365227286907608",
      "7761858545720969174"
    )
    snaps.head.snapshotId.toLong should be > (1L << 53)
    snaps.last.parentId shouldBe None
    snaps.head.current shouldBe true
  }

  it should "expose the summary counts, absent keys as None" in {
    val snaps = IcebergSnapshots.parse(rows).toOption.get
    snaps(1).operation shouldBe Some("overwrite")
    snaps(1).count("added-records") shouldBe Some(1L)
    snaps.head.count("deleted-records") shouldBe None
    snaps.head.count("added-position-deletes") shouldBe Some(1L)
  }

  it should "reject a malformed row instead of guessing" in {
    IcebergSnapshots.parse(List(List(Json.fromString("1")))) should matchPattern {
      case Left(IcebergSnapshots.ParseError.Malformed(_)) =>
    }
    IcebergSnapshots.parse(List(row("1", None, 1, 1, "not json", false))) should matchPattern {
      case Left(IcebergSnapshots.ParseError.Malformed(_)) =>
    }
  }

  it should "refuse a format-v1 row (null seq) with ParseError.FormatV1" in {
    IcebergSnapshots.parse(List(v1Row)) shouldBe Left(IcebergSnapshots.ParseError.FormatV1)
  }

  it should "refuse a mixed list containing any format-v1 (null seq) row" in {
    IcebergSnapshots.parse(rows.take(1) ++ List(v1Row)) shouldBe Left(
      IcebergSnapshots.ParseError.FormatV1
    )
  }

  it should "let FormatV1 win over a later malformed row in the same list" in {
    val malformedRow = row("1", None, 1, 1, "not json", false)
    IcebergSnapshots.parse(List(v1Row, malformedRow)) shouldBe Left(
      IcebergSnapshots.ParseError.FormatV1
    )
  }

  "validId" should "accept 64-bit decimal ids only" in {
    IcebergSnapshots.validId("7557379181527318936") shouldBe true
    IcebergSnapshots.validId("-42") shouldBe true
    IcebergSnapshots.validId("") shouldBe false
    IcebergSnapshots.validId("12345678901234567890") shouldBe false // 20 digits
    IcebergSnapshots.validId("1; DROP TABLE t") shouldBe false
    IcebergSnapshots.validId("main") shouldBe false
  }

  "tooLargeForDiff" should "compare total-data-files against the cap" in {
    val snaps = IcebergSnapshots.parse(rows).toOption.get
    IcebergSnapshots.tooLargeForDiff(snaps, maxFiles = 3) shouldBe false
    IcebergSnapshots.tooLargeForDiff(snaps, maxFiles = 2) shouldBe true
  }
