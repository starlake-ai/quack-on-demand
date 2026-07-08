package ai.starlake.quack.ondemand.maintenance

import java.time.{Instant, ZoneOffset, ZonedDateTime}

/** Minimal 5-field cron (minute hour day-of-month month day-of-week), supporting asterisk, plain
  * integers, and step syntax (asterisk-slash-n). Deliberately no ranges/lists/names: the UI only
  * emits these forms. Evaluated in UTC.
  */
final case class CronExpr(
    minute: CronField,
    hour: CronField,
    dom: CronField,
    month: CronField,
    dow: CronField
):
  def matches(t: ZonedDateTime): Boolean =
    minute.matches(t.getMinute) && hour.matches(t.getHour) && dom.matches(t.getDayOfMonth) &&
      month.matches(t.getMonthValue) && dow.matches(t.getDayOfWeek.getValue % 7)

enum CronField:
  case Any
  case Exact(n: Int)
  case Step(n: Int)
  def matches(v: Int): Boolean = this match
    case Any      => true
    case Exact(n) => v == n
    case Step(n)  => n > 0 && v % n == 0

object CronExpr:
  def parse(s: String): Either[String, CronExpr] =
    val parts = s.trim.split("\\s+").toList
    if parts.size != 5 then Left(s"cron must have 5 fields, got ${parts.size}")
    else
      def field(p: String): Either[String, CronField] =
        if p == "*" then Right(CronField.Any)
        else if p.startsWith("*/") then
          p.drop(2)
            .toIntOption
            .filter(_ > 0)
            .map(CronField.Step.apply)
            .toRight(s"bad step '$p'")
        else p.toIntOption.map(CronField.Exact.apply).toRight(s"bad field '$p'")
      for
        m  <- field(parts(0)); h <- field(parts(1)); d <- field(parts(2))
        mo <- field(parts(3)); w <- field(parts(4))
      yield CronExpr(m, h, d, mo, w)

  /** Cadence due-ness: is there a cron-matching minute (shifted by the stagger offset) in the
    * window (lastRun, now]? Scans minute-by-minute from lastRun (or now - 48h when never run),
    * capped at 48h - fine for daily/hourly cadences, and the scheduler ticks every minute anyway.
    * Unparseable cron: never due (the handler validates on write; this is defense in depth).
    */
  def due(cron: String, offsetMinutes: Int, lastRun: Option[Instant], now: Instant): Boolean =
    parse(cron) match
      case Left(_)     => false
      case Right(expr) =>
        val floor = lastRun.getOrElse(now.minusSeconds(48L * 3600))
        val from  = floor.plusSeconds(60) // window is exclusive of lastRun's minute
        Iterator
          .iterate(from.truncatedTo(java.time.temporal.ChronoUnit.MINUTES))(_.plusSeconds(60))
          .takeWhile(!_.isAfter(now))
          .take(48 * 60)
          .exists(t =>
            expr.matches(
              ZonedDateTime.ofInstant(t.minusSeconds(offsetMinutes * 60L), ZoneOffset.UTC)
            )
          )
