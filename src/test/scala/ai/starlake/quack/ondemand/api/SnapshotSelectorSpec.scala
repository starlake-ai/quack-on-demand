package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class SnapshotSelectorSpec extends AnyFlatSpec with Matchers:

  private val Snapshot1  = 1L
  private val Snapshot5  = 5L
  private val Snapshot10 = 10L
  private val Ts1        = Instant.parse("2026-01-01T00:00:00Z")
  private val Ts5        = Instant.parse("2026-01-05T00:00:00Z")
  private val TsBefore   = Instant.parse("2025-12-31T00:00:00Z")
  private val TsAfter    = Instant.parse("2026-01-15T00:00:00Z")

  private def stubs =
    val maxId: () => Option[Long]           = () => Some(Snapshot10)
    val atOrBefore: Instant => Option[Long] = (ts: Instant) =>
      if ts.isBefore(Ts1) then None
      else if ts.isBefore(Ts5) then Some(Snapshot1)
      else Some(Snapshot5)
    val tagSnapshot: String => Option[Long] = (tag: String) =>
      if tag == "exists" then Some(Snapshot5)
      else if tag == "expired" then Some(2L)
      else None
    val exists: Long => Boolean = (id: Long) =>
      id == Snapshot1 || id == Snapshot5 || id == Snapshot10
    (maxId, atOrBefore, tagSnapshot, exists)

  "SnapshotSelector.resolve" should "return Current when no selector is supplied" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res = SnapshotSelector.resolve(None, None, None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Right(SnapshotSelector.Resolution.Current)
  }

  it should "resolve asOf when present and the id exists" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      =
      SnapshotSelector.resolve(Some(Snapshot5), None, None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Right(SnapshotSelector.Resolution.At(Snapshot5, None))
  }

  it should "resolve asOfTag when present and the tag exists and points to an extant snapshot" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      =
      SnapshotSelector.resolve(None, Some("exists"), None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Right(SnapshotSelector.Resolution.At(Snapshot5, None))
  }

  it should "resolve asOfTs when present and the timestamp has a prior snapshot" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      =
      SnapshotSelector.resolve(None, None, Some(Ts5), maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Right(SnapshotSelector.Resolution.At(Snapshot5, Some(Ts5)))
  }

  it should "reject MultipleSelectors when both asOf and asOfTag are supplied" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      = SnapshotSelector.resolve(
      Some(Snapshot5),
      Some("exists"),
      None,
      maxId,
      atOrBefore,
      tagSnapshot,
      exists
    )
    res shouldBe Left(SnapshotSelector.SelectorError.MultipleSelectors)
  }

  it should "reject MultipleSelectors when asOf and asOfTs are supplied" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      = SnapshotSelector.resolve(
      Some(Snapshot5),
      None,
      Some(Ts5),
      maxId,
      atOrBefore,
      tagSnapshot,
      exists
    )
    res shouldBe Left(SnapshotSelector.SelectorError.MultipleSelectors)
  }

  it should "reject MultipleSelectors when asOfTag and asOfTs are supplied" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      = SnapshotSelector.resolve(
      None,
      Some("exists"),
      Some(Ts5),
      maxId,
      atOrBefore,
      tagSnapshot,
      exists
    )
    res shouldBe Left(SnapshotSelector.SelectorError.MultipleSelectors)
  }

  it should "reject TagNotFound when asOfTag points to an unknown tag" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      =
      SnapshotSelector.resolve(None, Some("unknown"), None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.TagNotFound("unknown"))
  }

  it should "reject Expired when asOf points to a vacuumed snapshot (below max)" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res = SnapshotSelector.resolve(Some(2L), None, None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.Expired(2L))
  }

  it should "reject Expired when asOfTag resolves to a vacuumed snapshot" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      =
      SnapshotSelector.resolve(None, Some("expired"), None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.Expired(2L))
  }

  it should "reject BeyondLatest when asOf points to an id above the max" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      =
      SnapshotSelector.resolve(Some(15L), None, None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.BeyondLatest(15L))
  }

  it should "reject BeyondLatest when asOfTag resolves to an id above the max" in {
    val (maxId, atOrBefore, tagSnapshot, exists)  = stubs
    val tagSnapshotCustom: String => Option[Long] = (tag: String) =>
      if tag == "future" then Some(15L)
      else None
    val res = SnapshotSelector.resolve(
      None,
      Some("future"),
      None,
      maxId,
      atOrBefore,
      tagSnapshotCustom,
      exists
    )
    res shouldBe Left(SnapshotSelector.SelectorError.BeyondLatest(15L))
  }

  it should "reject BeforeFirstSnapshot when asOfTs is before the first snapshot and no max exists" in {
    val maxId: () => Option[Long]           = () => None
    val atOrBefore: Instant => Option[Long] = (_: Instant) => None
    val tagSnapshot: String => Option[Long] = (_: String) => None
    val exists: Long => Boolean             = (_: Long) => false
    val res                                 =
      SnapshotSelector.resolve(None, None, Some(TsBefore), maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.BeforeFirstSnapshot)
  }

  it should "reject BeforeFirstSnapshot when asOfTs is before any snapshot" in {
    val (maxId, atOrBefore, tagSnapshot, exists) = stubs
    val res                                      =
      SnapshotSelector.resolve(None, None, Some(TsBefore), maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.BeforeFirstSnapshot)
  }

  it should "reject EmptyCatalog when asOf targets a catalog with no snapshots" in {
    val maxId: () => Option[Long]           = () => None
    val atOrBefore: Instant => Option[Long] = (_: Instant) => None
    val tagSnapshot: String => Option[Long] = (_: String) => None
    val exists: Long => Boolean             = (_: Long) => false
    val res = SnapshotSelector.resolve(Some(7L), None, None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.EmptyCatalog)
  }

  it should "reject EmptyCatalog when a tag resolves against a catalog with no snapshots" in {
    val maxId: () => Option[Long]           = () => None
    val atOrBefore: Instant => Option[Long] = (_: Instant) => None
    val tagSnapshot: String => Option[Long] = (t: String) => if t == "ghost" then Some(7L) else None
    val exists: Long => Boolean             = (_: Long) => false
    val res                                 =
      SnapshotSelector.resolve(None, Some("ghost"), None, maxId, atOrBefore, tagSnapshot, exists)
    res shouldBe Left(SnapshotSelector.SelectorError.EmptyCatalog)
  }
