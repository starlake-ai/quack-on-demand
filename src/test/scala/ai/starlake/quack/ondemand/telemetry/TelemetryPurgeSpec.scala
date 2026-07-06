package ai.starlake.quack.ondemand.telemetry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

class TelemetryPurgeSpec extends AnyFlatSpec with Matchers:

  "TelemetryPurge.cutoffFor" should "return None when retentionDays is 0 (keep forever)" in {
    TelemetryPurge.cutoffFor(0, Instant.now()) shouldBe None
  }

  it should "return None for negative retentionDays" in {
    TelemetryPurge.cutoffFor(-1, Instant.now()) shouldBe None
    TelemetryPurge.cutoffFor(-100, Instant.now()) shouldBe None
  }

  it should "return Some(now minus N days) for positive retentionDays" in {
    val now    = Instant.parse("2026-07-06T12:00:00Z")
    val cutoff = TelemetryPurge.cutoffFor(90, now)
    cutoff shouldBe Some(now.minus(Duration.ofDays(90)))
  }

  it should "return Some for retentionDays=1" in {
    val now    = Instant.parse("2026-07-06T00:00:00Z")
    val cutoff = TelemetryPurge.cutoffFor(1, now)
    cutoff shouldBe Some(now.minus(Duration.ofDays(1)))
  }
