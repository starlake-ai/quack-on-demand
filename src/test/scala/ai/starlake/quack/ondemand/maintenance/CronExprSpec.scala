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
