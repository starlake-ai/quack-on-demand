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

  /** How far back a match may lie and still be owed: a year and a day, so a yearly cadence is
    * found. A run that has never happened looks back 48h.
    */
  val MaxLookbackDays: Long = 366

  /** Cadence due-ness: is there a cron-matching minute (shifted by the stagger offset) in the
    * window (lastRun, now]? The window starts the minute after lastRun's (or 48h before now when
    * never run) and reaches back at most [[MaxLookbackDays]]. It is walked day by day, and only a
    * day the date fields match is expanded to its matching hours and minutes, so a weekly, monthly
    * or yearly cadence costs at most a few hundred day checks per tick. Unparseable cron: never due
    * (the handler validates on write; this is defense in depth).
    */
  def due(cron: String, offsetMinutes: Int, lastRun: Option[Instant], now: Instant): Boolean =
    parse(cron) match
      case Left(_)     => false
      case Right(expr) =>
        import java.time.temporal.ChronoUnit.MINUTES
        val floor = lastRun.getOrElse(now.minusSeconds(48L * 3600))
        // Exclusive of lastRun's minute.
        val from = floor.truncatedTo(MINUTES).plusSeconds(60)
        // A match at cron time c fires at c + offset: search cron times in the shifted window.
        val shift = offsetMinutes * 60L
        val end   = ZonedDateTime.ofInstant(now.minusSeconds(shift), ZoneOffset.UTC)
        val start =
          val s   = ZonedDateTime.ofInstant(from.minusSeconds(shift), ZoneOffset.UTC)
          val cap = end.minusDays(MaxLookbackDays)
          if s.isBefore(cap) then cap else s
        if start.isAfter(end) then false
        else
          val hours   = (0 to 23).filter(expr.hour.matches)
          val minutes = (0 to 59).filter(expr.minute.matches)
          Iterator
            .iterate(start.toLocalDate)(_.plusDays(1))
            .takeWhile(!_.isAfter(end.toLocalDate))
            .exists { day =>
              expr.dom.matches(day.getDayOfMonth) && expr.month.matches(day.getMonthValue) &&
              expr.dow.matches(day.getDayOfWeek.getValue % 7) &&
              hours.exists(h =>
                minutes.exists { m =>
                  val t = day.atTime(h, m).atZone(ZoneOffset.UTC)
                  !t.isBefore(start) && !t.isAfter(end)
                }
              )
            }
