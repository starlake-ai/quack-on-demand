package ai.starlake.quack.ondemand.maintenance

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class CronExprSpec extends AnyFlatSpec with Matchers:

  "parse" should "accept the 5-field subset and reject junk" in {
    CronExpr.parse("0 3 * * *").isRight shouldBe true
    CronExpr.parse("*/15 * * * *").isRight shouldBe true
    CronExpr.parse("bogus").isLeft shouldBe true
    CronExpr.parse("0 3 * *").isLeft shouldBe true
  }

  "due" should "fire when a matching instant lies in (lastRun, now]" in {
    val cron      = "0 3 * * *" // daily 03:00 UTC
    val yesterday = Instant.parse("2026-07-07T10:00:00Z")
    val now       = Instant.parse("2026-07-08T10:00:00Z")
    CronExpr.due(cron, offsetMinutes = 0, lastRun = Some(yesterday), now = now) shouldBe true
    // already ran after today's 03:00
    CronExpr.due(cron, 0, Some(Instant.parse("2026-07-08T03:30:00Z")), now) shouldBe false
    // never ran -> due
    CronExpr.due(cron, 0, None, now) shouldBe true
    // stagger pushes the trigger past `now`
    CronExpr.due(
      cron,
      offsetMinutes = 30,
      lastRun = Some(Instant.parse("2026-07-08T02:00:00Z")),
      now = Instant.parse("2026-07-08T03:10:00Z")
    ) shouldBe false
  }

  it should "recur weekly: due again once the next week's match passes" in {
    val cron    = "0 3 * * 0" // Sundays 03:00 UTC; 2026-07-05 and 2026-07-12 are Sundays
    val lastRun = Some(Instant.parse("2026-07-05T03:00:30Z"))
    CronExpr.due(cron, 0, lastRun, Instant.parse("2026-07-11T23:59:00Z")) shouldBe false
    CronExpr.due(cron, 0, lastRun, Instant.parse("2026-07-12T02:59:00Z")) shouldBe false
    CronExpr.due(cron, 0, lastRun, Instant.parse("2026-07-12T03:00:00Z")) shouldBe true
    CronExpr.due(cron, 0, lastRun, Instant.parse("2026-07-14T10:00:00Z")) shouldBe true
  }

  it should "recur monthly: due again once the next month's match passes" in {
    val cron    = "0 3 1 * *" // the 1st, 03:00 UTC
    val lastRun = Some(Instant.parse("2026-07-01T03:01:00Z"))
    CronExpr.due(cron, 0, lastRun, Instant.parse("2026-07-31T23:00:00Z")) shouldBe false
    CronExpr.due(cron, 0, lastRun, Instant.parse("2026-08-01T03:00:00Z")) shouldBe true
  }

  it should "apply the stagger to a weekly match" in {
    val cron    = "0 3 * * 0"
    val lastRun = Some(Instant.parse("2026-07-05T03:30:00Z"))
    CronExpr.due(cron, 30, lastRun, Instant.parse("2026-07-12T03:10:00Z")) shouldBe false
    CronExpr.due(cron, 30, lastRun, Instant.parse("2026-07-12T03:30:00Z")) shouldBe true
  }

  it should "keep a frequent cadence's window exclusive of the last run's minute" in {
    val cron = "*/15 * * * *"
    val last = Some(Instant.parse("2026-07-08T10:00:10Z"))
    CronExpr.due(cron, 0, last, Instant.parse("2026-07-08T10:14:59Z")) shouldBe false
    CronExpr.due(cron, 0, last, Instant.parse("2026-07-08T10:15:00Z")) shouldBe true
  }
