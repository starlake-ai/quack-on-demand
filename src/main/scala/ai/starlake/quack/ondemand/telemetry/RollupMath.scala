package ai.starlake.quack.ondemand.telemetry

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Pure UTC helpers for the rollup window computation.
  *
  * All methods are side-effect-free and operate on `java.time.Instant` values. The "window" they
  * produce is a closed-open interval `[lo, hi)` of UTC-aligned hour or day bucket starts that must
  * be deleted and recomputed during an incremental or full rollup pass.
  */
object RollupMath:

  /** Truncate `i` to the start of its UTC hour. */
  def hourFloor(i: Instant): Instant = i.truncatedTo(ChronoUnit.HOURS)

  /** Truncate `i` to the start of its UTC day (midnight). */
  def dayFloor(i: Instant): Instant = i.truncatedTo(ChronoUnit.DAYS)

  /** Compute the closed-open hourly recompute window `[lo, hi)`.
    *
    *   - `lo = hourFloor(fromExclusive)` when set, or `hourFloor(oldestRaw)` when the watermark is
    *     absent.
    *   - `hi = hourFloor(toInclusive) + 1 hour` (ensures the bucket containing `toInclusive` is
    *     covered even when `toInclusive` falls exactly on a bucket boundary).
    *
    * Returns `None` when there is nothing to roll: either `fromExclusive` is absent AND `oldestRaw`
    * evaluates to `None`, or the computed `lo >= hi` (degenerate range).
    *
    * `oldestRaw` is a by-name parameter and is only evaluated when `fromExclusive` is absent,
    * avoiding an unnecessary database round-trip when a watermark is already known.
    */
  def hourWindow(
      fromExclusive: Option[Instant],
      toInclusive: Instant,
      oldestRaw: => Option[Instant]
  ): Option[(Instant, Instant)] =
    val loOpt: Option[Instant] = fromExclusive match
      case Some(from) => Some(hourFloor(from))
      case None       => oldestRaw.map(hourFloor)
    loOpt.flatMap { lo =>
      val hi = hourFloor(toInclusive).plus(1L, ChronoUnit.HOURS)
      if lo.compareTo(hi) >= 0 then None
      else Some((lo, hi))
    }

  /** Widen an hourly window `(lo, hi)` to full UTC day boundaries.
    *
    *   - `dlo = dayFloor(lo)` - expands leftward to midnight.
    *   - `dhi = dayFloor(hi - 1 nanosecond) + 1 day` - the `-1 ns` trick prevents including an
    *     extra calendar day when `hi` falls exactly on a midnight boundary.
    */
  def dayWindow(hourWindow: (Instant, Instant)): (Instant, Instant) =
    val (lo, hi) = hourWindow
    val dlo      = dayFloor(lo)
    val dhi      = dayFloor(hi.minusNanos(1L)).plus(1L, ChronoUnit.DAYS)
    (dlo, dhi)
