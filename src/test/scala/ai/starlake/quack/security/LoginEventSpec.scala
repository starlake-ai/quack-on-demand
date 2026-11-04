// src/test/scala/ai/starlake/quack/security/LoginEventSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LoginEventSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
  private val sink: ManagerEventSink = e => { received.add(e); () }

  "REST login" should "emit SessionOpened via=rest" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, events = sink)
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
      val sessionOpened = received.toArray.toList.collect { case e: ManagerEvent.SessionOpened =>
        e
      }
      sessionOpened.exists(e =>
        e.via == "rest" && e.user == SecurityFixtures.RootUsername
      ) shouldBe true
    finally h.shutdown()
  }

  // The route needs no credential, and a failed login wrote the typed user name twice, up to the
  // 64 KiB the public body limit lets through: a row each, kept for the audit retention.
  it should "write one bounded audit row for a failed login whose user name is 60 KiB" in {
    val fix       = SecurityFixtures.freshStore()
    val telemetry = new RecordingTelemetryStore
    val h = ManagerServerHarness.boot(fix.store, audit = new AuditRecorder(telemetry, _ => None))
    try
      val name = "u" * (60 * 1024)
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/login",
        s"""{"username":"$name","password":"pw"}"""
      )
      withClue(s"POST /api/auth/login answered ${resp.statusCode()}") {
        resp.statusCode() shouldBe 401
      }

      telemetry.events should have size 1
      val e = telemetry.events.head
      e.action shouldBe "auth.login.failure"
      e.actor.length should be <= 256
      e.detail("username").length should be <= 1024
      e.actor should startWith("uuuu")
    finally h.shutdown()
  }
