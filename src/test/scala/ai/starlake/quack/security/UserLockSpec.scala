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

  // ------------------------------------------------------------------
  // Guardrails (Task 2)
  // ------------------------------------------------------------------

  "the lock guardrails" should "keep a tenant admin off a superuser row (pin)" in {
    val (h, fix) = boot()
    try
      val alice = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val denied = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.rootUserId, Some(false)),
        apiKey = Some(alice)
      )
      withClue(s"tenant-admin -> superuser body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("tenant_forbidden")
      }
    finally h.shutdown()
  }

  it should "refuse self-lock with 400 cannot_lock_self and allow self-unlock" in {
    val (h, fix) = boot()
    try
      val root   = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val denied = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.rootUserId, Some(false)),
        apiKey = Some(root)
      )
      withClue(s"self-lock body: ${denied.body()}") {
        denied.statusCode() shouldBe 400
        errorCode(denied.body()) should contain("cannot_lock_self")
      }
      // Self-unlock (a no-op here) stays allowed: recovery must never be blocked.
      post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.rootUserId, Some(true)),
        apiKey = Some(root)
      ).statusCode() shouldBe 200
      // Unlock-of-a-genuinely-disabled-row (round trip + login restored) is already
      // pinned by the very first case in this file ("lock a tenant user out of login,
      // and unlock restores"); not duplicated here.
    finally h.shutdown()
  }

  it should "never allow the last enabled superuser to be locked" in {
    val (h, fix) = boot()
    try
      // Second superuser, seeded like the fixture ones so it can log in.
      fix.store.upsertUserWithHash(
        tenant = None,
        username = "root2",
        passwordHash = at.favre.lib.crypto.bcrypt.BCrypt
          .withDefaults()
          .hashToString(10, "root2pw".toCharArray),
        role = "admin"
      )
      val root2Id = fix.store.findUser(None, "root2").get.id
      val root    = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      // Mint root2's session BEFORE locking it: stateless JWTs outlive the lock,
      // and this still-live session is exactly the hole the guard must close.
      val root2 = h.mintToken("root2", "root2pw")

      // Two enabled superusers: locking one is fine.
      post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(root2Id, Some(false)),
        apiKey = Some(root)
      ).statusCode() shouldBe 200

      // root is now the last enabled superuser: root2's live session may not lock it...
      val viaGhost = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.rootUserId, Some(false)),
        apiKey = Some(root2)
      )
      withClue(s"ghost-session lock body: ${viaGhost.body()}") {
        viaGhost.statusCode() shouldBe 400
        errorCode(viaGhost.body()) should contain("last_superuser")
      }
      // ...and neither may the identity-less static key.
      val viaKey = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.rootUserId, Some(false)),
        apiKey = Some("lock-spec-key")
      )
      withClue(s"static-key lock body: ${viaKey.body()}") {
        viaKey.statusCode() shouldBe 400
        errorCode(viaKey.body()) should contain("last_superuser")
      }
    finally h.shutdown()
  }

  it should "let the static key lock a tenant user (no identity, no self-check)" in {
    val (h, fix) = boot()
    try
      val locked = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.bobUserId, Some(false)),
        apiKey = Some("lock-spec-key")
      )
      withClue(s"static-key lock body: ${locked.body()}")(locked.statusCode() shouldBe 200)
      locked.body() should include(""""enabled":false""")
      login(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword) shouldBe 401
    finally h.shutdown()
  }

  it should "apply a lock sent together with a password rotation" in {
    val (h, fix) = boot()
    try
      val root   = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val locked = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        s"""{"id":"${fix.bobUserId}","password":"newpw123","enabled":false}""",
        apiKey = Some(root)
      )
      withClue(s"lock+rotate body: ${locked.body()}")(locked.statusCode() shouldBe 200)
      locked.body() should include(""""enabled":false""")
      // Locked wins over the fresh password: login refused with either password.
      // Note: `login` checks the in-memory fixture store's hash, which the userStore
      // (DuckDB) password rotation never touches (a documented split between the two
      // stores in this harness) - so the OLD password assertion below proves the LOCK
      // took effect, not that the rotation was applied. Both assertions are kept
      // deliberately: the new-password one pins "locked wins", the old-password one
      // pins the lock itself.
      login(h, SecurityFixtures.BobUsername, "newpw123") shouldBe 401
      login(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword) shouldBe 401
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // Guardrails on user/delete (follow-up wave)
  // ------------------------------------------------------------------

  "user/delete" should "refuse self-delete and deleting the last enabled superuser" in {
    val (h, fix) = boot()
    try
      val root = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val self = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/delete",
        s"""{"id":"${fix.rootUserId}"}""",
        apiKey = Some(root)
      )
      withClue(s"self-delete body: ${self.body()}") {
        self.statusCode() shouldBe 400
        errorCode(self.body()) should contain("cannot_delete_self")
      }
      // Root is the fixture's only superuser: the identity-less static key may not
      // delete it either (the floor, not the self-check, is what refuses here).
      val viaKey = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/delete",
        s"""{"id":"${fix.rootUserId}"}""",
        apiKey = Some("lock-spec-key")
      )
      withClue(s"static-key delete-last body: ${viaKey.body()}") {
        viaKey.statusCode() shouldBe 400
        errorCode(viaKey.body()) should contain("last_superuser")
      }
      // A second enabled superuser lifts the floor.
      fix.store.upsertUserWithHash(
        tenant = None,
        username = "root2",
        passwordHash = at.favre.lib.crypto.bcrypt.BCrypt
          .withDefaults()
          .hashToString(10, "root2pw".toCharArray),
        role = "admin"
      )
      val root2Id = fix.store.findUser(None, "root2").get.id
      val lifted  = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/delete",
        s"""{"id":"$root2Id"}""",
        apiKey = Some(root)
      )
      withClue(s"delete-second-superuser body: ${lifted.body()}") {
        lifted.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  "the self-lock guard" should "resolve an OIDC-shaped identity through its manageable tenants" in {
    val (h, fix) = boot()
    try
      // OIDC sessions carry profile.tenant = None with the grants in manageableTenants;
      // a (None, username) lookup matches nothing for a tenant admin, leaving self-lock
      // silently inert for such sessions. The guard must resolve through the scope.
      val oidcAlice = h.tokens.mintWithScope(
        ai.starlake.quack.edge.auth.AuthenticatedProfile(
          username = SecurityFixtures.AliceUsername,
          role = "admin",
          groups = Set.empty,
          claims = Map.empty,
          authMethod = "oidc",
          tenant = None
        ),
        ai.starlake.quack.ondemand.auth.SessionScope(
          superuser = false,
          manageableTenants = Set(SecurityFixtures.TenantId)
        )
      )
      val denied = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        updateBody(fix.aliceUserId, Some(false)),
        apiKey = Some(oidcAlice)
      )
      withClue(s"oidc self-lock body: ${denied.body()}") {
        denied.statusCode() shouldBe 400
        errorCode(denied.body()) should contain("cannot_lock_self")
      }
    finally h.shutdown()
  }
