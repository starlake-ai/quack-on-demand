package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.TelemetryConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class AuditEventSpec extends AnyFlatSpec with Matchers:

  private def event(detail: Map[String, String]) = AuditEvent(
    ts = Instant.parse("2026-07-06T00:00:00Z"),
    family = "control-plane",
    actor = "admin",
    actorRealm = "system",
    tenant = Some("t-acme0001"),
    action = "role.create",
    target = Some("r-1"),
    outcome = "ok",
    origin = "rest",
    detail = detail
  )

  "AuditEvent" should "accept whitelisted detail keys" in {
    event(Map("name" -> "analyst", "durationMs" -> "12")).detail should have size 2
  }

  it should "reject a detail map containing a password-like key" in {
    intercept[IllegalArgumentException](event(Map("password" -> "x")))
    intercept[IllegalArgumentException](event(Map("pgPassword" -> "x")))
    intercept[IllegalArgumentException](event(Map("secretValue" -> "x")))
    intercept[IllegalArgumentException](event(Map("sessionToken" -> "x")))
  }

  "NoopTelemetryStore" should "be disabled and return nothing" in {
    NoopTelemetryStore.enabled shouldBe false
    NoopTelemetryStore.appendAudit(List(event(Map.empty)))
    NoopTelemetryStore.listAudit(AuditQuery()) shouldBe Nil
    NoopTelemetryStore.purgeAudit(Instant.now()) shouldBe 0
  }

  "TelemetryConfig.validate" should "accept postgres and none, refuse anything else" in {
    TelemetryConfig.validate("postgres", 7) shouldBe Right(())
    TelemetryConfig.validate("none", 7) shouldBe Right(())
    TelemetryConfig.validate("clickhouse", 7).isLeft shouldBe true
  }

  it should "reject stmtHistoryRetentionDays of 1" in {
    TelemetryConfig.validate("postgres", 1).isLeft shouldBe true
  }

  it should "accept 0 (keep forever) and >= 2" in {
    TelemetryConfig.validate("postgres", 0).isRight shouldBe true
    TelemetryConfig.validate("postgres", 2).isRight shouldBe true
    TelemetryConfig.validate("postgres", 7).isRight shouldBe true
  }
