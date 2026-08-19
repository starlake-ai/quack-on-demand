// src/test/scala/ai/starlake/quack/security/UserLockSpec.scala
package ai.starlake.quack.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The admin user lock: `enabled` surfaced on user/update. This spec covers the plumbing (lock
  * takes effect, unlock restores, omit = unchanged); the guardrails (self-lock, last superuser,
  * superuser-target scope) are pinned in Task 2 cases appended to this same file.
  */
class UserLockSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private def boot() =
    val fix = SecurityFixtures.freshStore()
    (ManagerServerHarness.boot(fix.store, staticApiKey = Some("lock-spec-key")), fix)

  private def updateBody(id: String, enabled: Option[Boolean]): String =
    enabled match
      case Some(e) => s"""{"id":"$id","enabled":$e}"""
      case None    => s"""{"id":"$id","role":"user"}"""

  private def login(h: ManagerServerHarness.Harness, user: String, pass: String): Int =
    post(
      h.httpClient,
      s"${h.baseUrl}/api/auth/login",
      s"""{"username":"$user","password":"$pass","tenant":"${SecurityFixtures.TenantId}"}"""
    ).statusCode()

  "user/update enabled=false" should "lock a tenant user out of login, and unlock restores" in {
    val (h, fix) = boot()
    try
      val root = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      login(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword) shouldBe 200

      val locked = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.bobUserId, Some(false)),
        apiKey = Some(root)
      )
      withClue(s"lock body: ${locked.body()}")(locked.statusCode() shouldBe 200)
      locked.body() should include(""""enabled":false""")

      // Locked = the same anonymous invalid_credentials a wrong password gets.
      val denied = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/login",
        s"""{"username":"${SecurityFixtures.BobUsername}","password":"${SecurityFixtures.BobPassword}","tenant":"${SecurityFixtures.TenantId}"}"""
      )
      denied.statusCode() shouldBe 401
      errorCode(denied.body()) should contain("invalid_credentials")

      val unlocked = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.bobUserId, Some(true)),
        apiKey = Some(root)
      )
      withClue(s"unlock body: ${unlocked.body()}")(unlocked.statusCode() shouldBe 200)
      login(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword) shouldBe 200
    finally h.shutdown()
  }

  it should "let a tenant admin lock a user of their own tenant" in {
    val (h, fix) = boot()
    try
      val alice = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val locked = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.bobUserId, Some(false)),
        apiKey = Some(alice)
      )
      withClue(s"tenant-admin lock body: ${locked.body()}")(locked.statusCode() shouldBe 200)
      login(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword) shouldBe 401
    finally h.shutdown()
  }

  it should "leave the flag untouched when the request omits enabled" in {
    val (h, fix) = boot()
    try
      val root = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.bobUserId, Some(false)),
        apiKey = Some(root)
      ).statusCode() shouldBe 200
      // A role-only update must not silently unlock.
      val roleOnly = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.bobUserId, None),
        apiKey = Some(root)
      )
      withClue(s"role-only body: ${roleOnly.body()}")(roleOnly.statusCode() shouldBe 200)
      roleOnly.body() should include(""""enabled":false""")
      login(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword) shouldBe 401
    finally h.shutdown()
  }
