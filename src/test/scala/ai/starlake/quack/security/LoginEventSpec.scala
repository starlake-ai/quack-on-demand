// src/test/scala/ai/starlake/quack/security/LoginEventSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LoginEventSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val received               = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
  private val sink: ManagerEventSink = e => { received.add(e); () }

  "REST login" should "emit SessionOpened via=rest" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None, events = sink)
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
