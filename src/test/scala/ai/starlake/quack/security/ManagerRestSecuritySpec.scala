// src/test/scala/ai/starlake/quack/security/ManagerRestSecuritySpec.scala
package ai.starlake.quack.security

import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End-to-end security tests for the ManagerServer REST API.
  *
  * Each test boots a fresh ManagerServer on an ephemeral port backed by the in-memory fixture store
  * from [[SecurityFixtures]]. There is no shared state between cases. The harness is torn down in a
  * try/finally block immediately after each test so ports are released promptly.
  *
  * Groups: A. apiKeyGuard modes (open vs. static key enforcement) B. Public paths that bypass the
  * guard C. Login admin gate (AuthHandlers.login) D. Superuser gate on /api/config/server and
  * /api/manifest/export|import
  */
class ManagerRestSecuritySpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  // ------------------------------------------------------------------
  // A. apiKeyGuard modes
  // ------------------------------------------------------------------

  "apiKeyGuard" should "allow GET /api/tenants with no key when staticApiKey is None (open mode)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list")
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "return 401 when static key is set but X-API-Key header is missing" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list")
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "allow GET /api/tenants when X-API-Key matches the static key" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some("k1"))
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "allow GET /api/tenants when X-API-Key is a valid session token" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "return 401 when X-API-Key is a wrong value" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some("wrong"))
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "not admit a percent-encoded /api prefix with no key" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      // The guard tests the request path for the `/api/` prefix while tapir
      // routes on DECODED segments. If the two ever disagree, `/%61pi/...`
      // ("a" percent-encoded) skips the guard yet still matches the tenant-list
      // route -- a full auth bypass. Any non-2xx answer proves it did not; the
      // guard's own 401 is what actually answers today. Asserted as "not 2xx"
      // rather than the exact code so a stack that rejects the encoded form
      // upstream (404) stays green: only leaking data is the failure.
      val resp = get(h.httpClient, s"${h.baseUrl}/%61pi/tenant/list")
      withClue(s"GET /%61pi/tenant/list status=${resp.statusCode()} body: ${resp.body()}") {
        (resp.statusCode() >= 200 && resp.statusCode() < 300) shouldBe false
      }
    finally h.shutdown()
  }

  it should "not admit a slash-prefixed /api path with no key" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      // Tapir drops EVERY leading slash before segmenting, so `////api/...`
      // routes to the tenant-list endpoint. A guard that keeps the leading
      // empty segment sees `//api/tenant/list`, decides it is outside the
      // guarded namespace and waves the request through unauthenticated --
      // the exact bypass this pins shut. Two spellings so a fix that only
      // normalizes one extra slash cannot pass.
      val four = get(h.httpClient, s"${h.baseUrl}////api/tenant/list")
      withClue(s"GET ////api/tenant/list status=${four.statusCode()} body: ${four.body()}") {
        (four.statusCode() >= 200 && four.statusCode() < 300) shouldBe false
      }
      val five = get(h.httpClient, s"${h.baseUrl}/////api/pool/list")
      withClue(s"GET /////api/pool/list status=${five.statusCode()} body: ${five.body()}") {
        (five.statusCode() >= 200 && five.statusCode() < 300) shouldBe false
      }
    finally h.shutdown()
  }

  it should "demote a non-admin session to the profile allowlist" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      // bob is a role=user principal in acme: a tenant-scoped login mints a
      // profile-only session (no admin grant, empty manageableTenants).
      val token = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )

      // An admin endpoint answers 403 with a distinguishable code -- NOT the
      // bare 401 an unknown token gets. The session is real; the grant isn't.
      val denied = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(token))
      withClue(s"GET /api/pool/list body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("admin_required")
      }

      // Allowlisted self-service paths reach their handlers.
      val whoami = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami", apiKey = Some(token))
      withClue(s"GET /api/auth/whoami body: ${whoami.body()}") {
        whoami.statusCode() shouldBe 200
      }
      val logout = post(h.httpClient, s"${h.baseUrl}/api/auth/logout", "", apiKey = Some(token))
      withClue(s"POST /api/auth/logout body: ${logout.body()}") {
        logout.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "let a non-admin session reach both /api/profile routes" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val token = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // 200 (not 401/403) proves the guard's allowlist arm handed the request
      // to the real handler: these two paths 404'd until the routes existed,
      // which is why the case was deferred out of the guard's own task.
      val usage = get(h.httpClient, s"${h.baseUrl}/api/profile/usage", apiKey = Some(token))
      withClue(s"GET /api/profile/usage body: ${usage.body()}") {
        usage.statusCode() shouldBe 200
      }
      val stmts = get(h.httpClient, s"${h.baseUrl}/api/profile/statements", apiKey = Some(token))
      withClue(s"GET /api/profile/statements body: ${stmts.body()}") {
        stmts.statusCode() shouldBe 200
      }

      // The same session on an admin route stays sealed -- the allowlist widened
      // by exactly two paths, not by a namespace.
      val denied = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(token))
      withClue(s"GET /api/pool/list body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("admin_required")
      }
    finally h.shutdown()
  }

  it should "match the profile allowlist exactly, never by prefix" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val token = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // A path that merely EXTENDS an allowlisted one must stay sealed. A
      // prefix-based predicate would admit it (and answer 404 from the router)
      // -- and would likewise leak every SPI module prefix mounted under /api.
      val resp = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami/extra", apiKey = Some(token))
      withClue(s"GET /api/auth/whoami/extra body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("admin_required")
      }
    finally h.shutdown()
  }

  it should "keep admin sessions unrestricted" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(token))
      withClue(s"GET /api/pool/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // B. Public paths bypass the guard
  // ------------------------------------------------------------------

  "Public paths" should "allow POST /api/auth/login without X-API-Key (static key mode)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      // The request must reach the handler (not be blocked by apiKeyGuard).
      // A 200 login success satisfies this -- we don't require a specific
      // status beyond proving the guard didn't fire (which would be 401).
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() should not be 401
      }
    finally h.shutdown()
  }

  it should "allow POST /api/auth/change-password without X-API-Key (static key mode)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","currentPassword":"${SecurityFixtures.RootPassword}","newPassword":"new-password"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/change-password", body)
      // The harness never wires a changePasswordStore, so a request that
      // reaches the handler always answers 503 auth_disabled -- a JSON
      // body with an error code, unlike apiKeyGuard's bare, bodyless 401.
      // Asserting that exact handler-level shape (rather than merely "not
      // 401") proves the guard's isPublicApi arm actually let the request
      // through instead of coincidentally matching a would-be 401.
      withClue(s"POST /api/auth/change-password body: ${resp.body()}") {
        resp.statusCode() shouldBe 503
        errorCode(resp.body()) should contain("auth_disabled")
      }
    finally h.shutdown()
  }

  it should "allow POST /api/auth/forgot-password without X-API-Key (static key mode)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      // Anti-enumeration: a nonexistent account still answers 200 (the row lookup
      // misses and nothing is mailed). A 200 -- not the guard's bare 401 -- proves
      // the isPublicApi arm let the request reach the handler.
      val body = """{"username":"ghost-nobody"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/forgot-password", body)
      withClue(s"POST /api/auth/forgot-password body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "allow POST /api/auth/reset-password without X-API-Key (static key mode)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      // A garbage token reaches the handler and comes back 400 invalid_token --
      // a JSON error body, unlike apiKeyGuard's bare bodyless 401. Asserting that
      // handler-level shape proves the guard's isPublicApi arm actually admitted it.
      val body = """{"token":"garbage.not.a.jwt","newPassword":"whatever"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/reset-password", body)
      withClue(s"POST /api/auth/reset-password body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) should contain("invalid_token")
      }
    finally h.shutdown()
  }

  it should "allow GET /api/config/client without X-API-Key (static key mode)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/config/client")
      withClue(s"GET /api/config/client body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // C. Login admin gate (AuthHandlers.login)
  // ------------------------------------------------------------------

  "AuthHandlers.login" should "return 200 with token + superuser=true for root" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val cursor = parse(resp.body()).toOption.get.hcursor
        cursor.get[String]("token").toOption.getOrElse("") should not be empty
        cursor.get[Boolean]("superuser").toOption should contain(true)
        // role intentionally NOT in the response anymore; whoami carries it.
        cursor.get[String]("role").toOption shouldBe None
      }
    finally h.shutdown()
  }

  // The former "403 admin_required for bob" case is gone: a tenant-scoped login
  // by a role=user principal now mints a profile-only session (below). The
  // login gate's remaining admin_required arms (system login, OIDC scopes, a
  // tenant the principal holds no grant on) are unreachable over the wire for a
  // db tenant user -- authentication itself fails first with invalid_credentials
  // -- so they stay pinned at unit level in AuthHandlersSpec / AuthHandlersOidcSpec.
  it should "mint a non-admin session for bob on his own tenant" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val body =
        s"""{"username":"${SecurityFixtures.BobUsername}","password":"${SecurityFixtures.BobPassword}","tenant":"${SecurityFixtures.TenantId}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val cursor = parse(resp.body()).toOption.get.hcursor
        cursor.get[String]("token").toOption.getOrElse("") should not be empty
        cursor.get[Boolean]("admin").toOption should contain(false)
        cursor.get[Boolean]("superuser").toOption should contain(false)
      }
    finally h.shutdown()
  }

  it should "return 401 invalid_credentials for a disabled user presenting the CORRECT password" in {
    val fix = SecurityFixtures.freshStore()
    val s   = fix.store
    // Disable root: re-upsert with the existing hash and enabled = false.
    val rootHash = s.getPasswordHash(None, SecurityFixtures.RootUsername).get
    s.upsertUserWithHash(
      tenant = None,
      username = SecurityFixtures.RootUsername,
      passwordHash = rootHash,
      role = "admin",
      enabled = false
    )
    val h = ManagerServerHarness.boot(s, staticApiKey = None)
    try
      val goodPw =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val badPw =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"wrong-password"}"""
      val respGood = post(h.httpClient, s"${h.baseUrl}/api/auth/login", goodPw)
      val respBad  = post(h.httpClient, s"${h.baseUrl}/api/auth/login", badPw)
      withClue(s"disabled-user login body: ${respGood.body()}") {
        respGood.statusCode() shouldBe 401
        errorCode(respGood.body()) should contain("invalid_credentials")
      }
      // Same failure shape as a bad password: identical status + code, so the
      // response does not reveal whether the account is disabled or the
      // password was wrong.
      respBad.statusCode() shouldBe respGood.statusCode()
      errorCode(respBad.body()) shouldBe errorCode(respGood.body())
    finally h.shutdown()
  }

  it should "return 400 with code invalid_credentials for blank password" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val body = s"""{"username":"${SecurityFixtures.RootUsername}","password":""}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) should contain("invalid_credentials")
      }
    finally h.shutdown()
  }

  it should "return 503 with code auth_disabled when no providers are configured" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None, enableProviders = false)
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 503
        errorCode(resp.body()) should contain("auth_disabled")
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // D. Superuser gate on /api/config/server and /api/manifest/*
  // ------------------------------------------------------------------

  "ConfigHandlers /api/config/server" should "admit root's superuser session" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/config/server", apiKey = Some(token))
      withClue(s"GET /api/config/server body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "admit the static-key path" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/config/server", apiKey = Some("k1"))
      withClue(s"GET /api/config/server body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "deny alice's tenant-scoped session with 403 superuser_required" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/config/server", apiKey = Some(token))
      withClue(s"GET /api/config/server body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("superuser_required")
      }
    finally h.shutdown()
  }

  "ManifestHandlers /api/manifest/export" should "admit root's superuser session" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/manifest/export", apiKey = Some(token))
      withClue(s"GET /api/manifest/export body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "admit the static-key path" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/manifest/export", apiKey = Some("k1"))
      withClue(s"GET /api/manifest/export body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "deny alice's tenant-scoped session with 403 superuser_required" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/manifest/export", apiKey = Some(token))
      withClue(s"GET /api/manifest/export body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("superuser_required")
      }
    finally h.shutdown()
  }

  "ManifestHandlers /api/manifest/import" should "admit root's superuser session (200 or 400, not 403)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      // Minimal valid-shape manifest body. The handler may accept or reject
      // the body content -- we only care that the security check passes (no 403).
      val yamlBody =
        """apiVersion: quack-on-demand/v1
          |kind: ConfigManifest
          |exportedAt: '2026-06-10T00:00:00Z'
          |exportedFrom: { managerVersion: test, hostname: localhost }
          |""".stripMargin
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/manifest/import",
        yamlBody,
        contentType = "application/yaml",
        apiKey = Some(token)
      )
      withClue(s"POST /api/manifest/import body: ${resp.body()}") {
        resp.statusCode() should not be 403
      }
    finally h.shutdown()
  }

  it should "admit the static-key path (200 or 400, not 403)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = Some("k1"))
    try
      val yamlBody =
        """apiVersion: quack-on-demand/v1
          |kind: ConfigManifest
          |exportedAt: '2026-06-10T00:00:00Z'
          |exportedFrom: { managerVersion: test, hostname: localhost }
          |""".stripMargin
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/manifest/import",
        yamlBody,
        contentType = "application/yaml",
        apiKey = Some("k1")
      )
      withClue(s"POST /api/manifest/import body: ${resp.body()}") {
        resp.statusCode() should not be 403
      }
    finally h.shutdown()
  }

  it should "deny alice's tenant-scoped session with 403 before parsing the body" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // Syntactically invalid body -- the 403 must fire before the parser runs.
      val invalidYaml = "this is not valid yaml: : :"
      val resp        = post(
        h.httpClient,
        s"${h.baseUrl}/api/manifest/import",
        invalidYaml,
        contentType = "application/yaml",
        apiKey = Some(token)
      )
      withClue(s"POST /api/manifest/import body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("superuser_required")
      }
    finally h.shutdown()
  }
