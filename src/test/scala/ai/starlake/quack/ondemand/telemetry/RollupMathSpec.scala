package ai.starlake.quack.ondemand.telemetry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Unit tests for [[RollupMath]] pure helpers. No I/O, no database. */
class RollupMathSpec extends AnyFlatSpec with Matchers:

  private val h10  = Instant.parse("2026-07-06T10:00:00Z")
  private val h11  = Instant.parse("2026-07-06T11:00:00Z")
  private val h12  = Instant.parse("2026-07-06T12:00:00Z")
  private val h13  = Instant.parse("2026-07-06T13:00:00Z")
  private val day6 = Instant.parse("2026-07-06T00:00:00Z")
  private val day7 = Instant.parse("2026-07-07T00:00:00Z")
  private val day8 = Instant.parse("2026-07-08T00:00:00Z")

  // ---------------------------------------------------------------------------
  // hourFloor
  // ---------------------------------------------------------------------------

  "hourFloor" should "return the same instant when already on an hour boundary" in {
    RollupMath.hourFloor(h10) shouldBe h10
  }

  it should "truncate a mid-hour instant to the hour start" in {
    val mid = Instant.parse("2026-07-06T10:37:42.123Z")
    RollupMath.hourFloor(mid) shouldBe h10
  }

  it should "truncate one nanosecond before the next hour boundary" in {
    val almostH11 = h11.minusNanos(1L)
    RollupMath.hourFloor(almostH11) shouldBe h10
  }

  // ---------------------------------------------------------------------------
  // dayFloor
  // ---------------------------------------------------------------------------

  "dayFloor" should "return the same instant when already at midnight" in {
    RollupMath.dayFloor(day6) shouldBe day6
  }

  it should "truncate a mid-day instant to midnight" in {
    val mid = Instant.parse("2026-07-06T15:30:00Z")
    RollupMath.dayFloor(mid) shouldBe day6
  }

  it should "truncate one nanosecond before midnight to the same day" in {
    val almostDay7 = day7.minusNanos(1L)
    RollupMath.dayFloor(almostDay7) shouldBe day6
  }

  // ---------------------------------------------------------------------------
  // hourWindow
  // ---------------------------------------------------------------------------

  "hourWindow" should "return None when no watermark and oldestRaw is None" in {
    RollupMath.hourWindow(None, h10, None) shouldBe None
  }

  it should "use oldestRaw as lo when fromExclusive is None" in {
    val oldest = Instant.parse("2026-07-06T10:30:00Z")
    // lo = hourFloor(10:30) = 10:00; hi = hourFloor(12:00) + 1h = 13:00
    RollupMath.hourWindow(None, h12, Some(oldest)) shouldBe Some((h10, h13))
  }

  it should "use hourFloor(fromExclusive) as lo when provided" in {
    val from = Instant.parse("2026-07-06T10:45:00Z")
    // lo = hourFloor(10:45) = 10:00; hi = hourFloor(12:00) + 1h = 13:00
    RollupMath.hourWindow(Some(from), h12, None) shouldBe Some((h10, h13))
  }

  it should "include the bucket containing toInclusive when on an exact hour boundary" in {
    // toInclusive = 12:00:00Z; hi = hourFloor(12:00) + 1h = 13:00
    RollupMath.hourWindow(Some(h10), h12, None) shouldBe Some((h10, h13))
  }

  it should "set hi = toHour + 1h when toInclusive is mid-hour" in {
    val to = Instant.parse("2026-07-06T11:45:00Z")
    // hi = hourFloor(11:45) + 1h = 11:00 + 1h = 12:00
    RollupMath.hourWindow(Some(h10), to, None) shouldBe Some((h10, h12))
  }

  it should "return None when lo equals hi (degenerate range)" in {
    // To force lo >= hi: fromExclusive one hour ahead of toInclusive.
    val farFuture = Instant.parse("2026-07-06T23:00:00Z")
    val earlyTo   = Instant.parse("2026-07-06T22:00:00Z")
    // lo = hourFloor(23:00) = 23:00; hi = hourFloor(22:00) + 1h = 23:00; lo = hi -> None
    RollupMath.hourWindow(Some(farFuture), earlyTo, None) shouldBe None
  }

  it should "not evaluate oldestRaw when fromExclusive is provided" in {
    var evaluated = false
    RollupMath.hourWindow(Some(h10), h12, { evaluated = true; Some(h10) })
    evaluated shouldBe false
  }

  // ---------------------------------------------------------------------------
  // dayWindow
  // ---------------------------------------------------------------------------

  "dayWindow" should "widen a same-day hourly window to midnight boundaries" in {
    val (dlo, dhi) = RollupMath.dayWindow((h10, h12))
    dlo shouldBe day6
    dhi shouldBe day7
  }

  it should "widen a window crossing midnight across two calendar days" in {
    val lo         = Instant.parse("2026-07-06T23:00:00Z")
    val hi         = Instant.parse("2026-07-07T02:00:00Z")
    val (dlo, dhi) = RollupMath.dayWindow((lo, hi))
    dlo shouldBe day6
    dhi shouldBe day8
  }

  it should "not extend to the next day when hi falls exactly on midnight" in {
    // hi = 2026-07-07T00:00:00Z (exactly midnight); hi - 1ns is still July 6
    val lo         = Instant.parse("2026-07-06T20:00:00Z")
    val hi         = day7 // midnight July 7 = start of July 7
    val (dlo, dhi) = RollupMath.dayWindow((lo, hi))
    dlo shouldBe day6
    // hi - 1ns = 2026-07-06T23:59:59.999999999Z, dayFloor = day6, + 1d = day7
    dhi shouldBe day7
  }
