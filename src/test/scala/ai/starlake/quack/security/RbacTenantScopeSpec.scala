// src/test/scala/ai/starlake/quack/security/RbacTenantScopeSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.StatementRecord
import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  PoolPermission,
  RbacGroup,
  RbacRole,
  RolePermission
}
import at.favre.lib.crypto.bcrypt.BCrypt
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Cross-tenant authZ on the RBAC REST surface.
  *
  * Before the fix, a tenant-A admin session could mutate tenant-B's RBAC graph because none of the
  * RBAC handlers consulted [[TenantScopeCheck]]. This spec pins that down: every RBAC mutation
  * called with a tenant-B id under a tenant-A admin session must come back `403 tenant_forbidden`,
  * while the same calls under a superuser session (or static-key) must succeed.
  *
  * The fixture extends [[SecurityFixtures]] with a second tenant `globex` (id `t-globex01`)
  * carrying its own admin role + grant edge so the spec can address tenant-B resources by id
  * without per-test plumbing.
  */
class RbacTenantScopeSpec extends AnyFlatSpec with Matchers:

  private val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  // Tenant-B fixture constants.
  private val GlobexTenantId = "t-globex01"
  private val GlobexRoleId   = "r-globex01"
  private val GlobexPermId   = "rp-globex01"
  private val GlobexGroupId  = "g-globex01"
  private val GlobexPoolPerm = "pp-globex01" // group-scoped, no pool (covers all pools in tenant)

  private val GlobexTenantDbId = "td-globex01"
  private val GlobexPoolId     = "p-globex01"

  private val CarolUser     = "carol"
  private val CarolPassword = "carolpw"

  // Tenant-B ADMIN (unlike carol). Globex's displayName ("globex") differs from its id
  // ("t-globex01"), which is exactly the case the pool/list tenant filter must survive.
  private val DaveUser     = "dave"
  private val DavePassword = "davepw"

  private def bcryptHash(s: String): String =
    BCrypt.withDefaults().hashToString(10, s.toCharArray)

  /** Augment a [[SecurityFixtures.Fixture]] with a second tenant, a role + grant inside it, a
    * group, a user `carol` who has no admin grant, and one group- scoped pool permission so
    * [[revokePoolPermission]] has a target id.
    */
  private def addTenantB(fix: SecurityFixtures.Fixture): String =
    val s = fix.store
    s.upsertTenant(
      Tenant(
        id = GlobexTenantId,
        displayName = "globex",
        authProvider = "db"
      )
    )
    // Seed a TenantDb and Pool for globex so pool/list tests have a real globex
    // pool in the supervisor snapshot. Without these, the pool/list cross-tenant
    // scoping test would pass trivially (empty list never contains the globex id).
    s.upsertTenantDb(
      TenantDb(
        id = GlobexTenantDbId,
        tenantId = GlobexTenantId,
        name = "globex_main",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = ""
      )
    )
    s.upsertPool(
      Pool(
        id = GlobexPoolId,
        tenantId = GlobexTenantId,
        tenantDbId = GlobexTenantDbId,
        name = "bi",
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
        maxConcurrentPerNode = 0,
        disabled = false
      )
    )
    s.upsertRole(
      RbacRole(
        id = GlobexRoleId,
        tenantId = GlobexTenantId,
        name = "admin",
        description = Some("tenant-B admin role")
      )
    )
    s.insertRolePermission(
      RolePermission(
        id = GlobexPermId,
        roleId = GlobexRoleId,
        catalogName = "*",
        schemaName = "*",
        tableName = "*",
        verb = "ALL"
      )
    )
    s.upsertGroup(
      RbacGroup(
        id = GlobexGroupId,
        tenantId = GlobexTenantId,
        name = "ops",
        description = None
      )
    )
    s.insertPoolPermission(
      PoolPermission(
        id = GlobexPoolPerm,
        tenantId = GlobexTenantId,
        poolId = None,
        userId = None,
        groupId = Some(GlobexGroupId)
      )
    )
    val carolId = s.upsertUserWithHash(
      tenant = Some(GlobexTenantId),
      username = CarolUser,
      passwordHash = bcryptHash(CarolPassword),
      role = "user"
    )
    // dave -- globex tenant admin (mirrors alice in acme: admin role label + role membership).
    val daveId = s.upsertUserWithHash(
      tenant = Some(GlobexTenantId),
      username = DaveUser,
      passwordHash = bcryptHash(DavePassword),
      role = "admin"
    )
    s.addUserRole(daveId, GlobexRoleId)
    carolId

  // ---------- HTTP helpers --------------------------------------------------

  private def post(
      client: HttpClient,
      url: String,
      body: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .header("Content-Type", "application/json")
      .timeout(RequestTimeout)
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def get(
      client: HttpClient,
      url: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(RequestTimeout)
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def postWithCookie(
      client: HttpClient,
      url: String,
      body: String,
      cookieToken: String
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .header("Content-Type", "application/json")
      .header("Cookie", s"qod_session=$cookieToken")
      .timeout(RequestTimeout)
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def getWithCookie(
      client: HttpClient,
      url: String,
      cookieToken: String
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .header("Cookie", s"qod_session=$cookieToken")
      .GET()
      .timeout(RequestTimeout)
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def errorCode(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("error").toOption)

  private def expectForbidden(resp: HttpResponse[String], context: String): Unit =
    withClue(s"$context body: ${resp.body()}") {
      resp.statusCode() shouldBe 403
      errorCode(resp.body()) shouldBe Some("tenant_forbidden")
    }

  // ---------- Spec ----------------------------------------------------------

  private def bootWithTwoTenants()
      : (ManagerServerHarness.Harness, SecurityFixtures.Fixture, String) =
    val fix     = SecurityFixtures.freshStore()
    val carolId = addTenantB(fix)
    val h       = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    (h, fix, carolId)

  // ---- /role/delete ----

  "deleteRole" should "reject a tenant-A admin trying to delete a tenant-B role" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"$GlobexRoleId"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/role/delete", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /role/delete on tenant-B role")
    finally h.shutdown()
  }

  it should "allow a superuser to delete a tenant-B role" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val body  = s"""{"id":"$GlobexRoleId"}"""
      val resp  = post(h.httpClient, s"${h.baseUrl}/api/role/delete", body, apiKey = Some(token))
      withClue(s"superuser -> /role/delete body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "404 (not 403) when the role id does not exist, even for a tenant-A admin" in {
    // Missing-id must NOT leak as 403 -- otherwise a cross-tenant id probe
    // could distinguish "exists elsewhere" from "doesn't exist at all".
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"r-doesnotexist"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/role/delete", body, apiKey = Some(token))
      withClue(s"missing-id -> /role/delete body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
      }
    finally h.shutdown()
  }

  // ---- /role/permission/grant ----

  "grantRolePermission" should "reject a tenant-A admin granting on a tenant-B role" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"roleId":"$GlobexRoleId","verb":"ALL"}"""
      val resp =
        post(h.httpClient, s"${h.baseUrl}/api/role/permission/grant", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> grant on tenant-B role")
    finally h.shutdown()
  }

  "revokeRolePermission" should "reject a tenant-A admin revoking a tenant-B permission" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"$GlobexPermId"}"""
      val resp =
        post(h.httpClient, s"${h.baseUrl}/api/role/permission/revoke", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> revoke tenant-B permission")
    finally h.shutdown()
  }

  // ---- /group/delete + /membership/group-role/add ----

  "deleteGroup" should "reject a tenant-A admin deleting a tenant-B group" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"$GlobexGroupId"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/group/delete", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /group/delete on tenant-B group")
    finally h.shutdown()
  }

  "addGroupRoleMembership" should "reject a tenant-A admin attaching to a tenant-B group" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"groupId":"$GlobexGroupId","roleId":"$GlobexRoleId"}"""
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/membership/group-role/add",
        body,
        apiKey = Some(token)
      )
      expectForbidden(resp, "tenant-A admin -> add group-role on tenant-B group")
    finally h.shutdown()
  }

  // ---- /user/update + /user/delete + /user/{id}/effective ----

  "updateUser" should "reject a tenant-A admin updating a tenant-B user" in {
    val (h, _, carolId) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"$carolId","password":"newpw"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/user/update", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /user/update on tenant-B user")
    finally h.shutdown()
  }

  "deleteUser" should "reject a tenant-A admin deleting a tenant-B user" in {
    val (h, _, carolId) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"$carolId"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/user/delete", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /user/delete on tenant-B user")
    finally h.shutdown()
  }

  it should "reject a tenant-A admin deleting a superuser account" in {
    val (h, fix, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"${fix.rootUserId}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/user/delete", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /user/delete on superuser")
    finally h.shutdown()
  }

  "effective" should "reject a tenant-A admin probing a tenant-B user's effective set" in {
    val (h, _, carolId) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp =
        get(h.httpClient, s"${h.baseUrl}/api/user/$carolId/effective", apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /user/{carolId}/effective")
    finally h.shutdown()
  }

  // ---- /user/create (body-tenant) ----

  "createUser" should "reject a tenant-A admin creating a tenant-B user" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        s"""{"tenant":"$GlobexTenantId","username":"mallory","password":"pw","role":"admin"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/user/create", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /user/create in tenant-B")
    finally h.shutdown()
  }

  it should "reject a tenant-A admin creating a superuser (tenant=null)" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = """{"tenant":null,"username":"mallory","password":"pw","role":"admin"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/user/create", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /user/create with tenant=null (superuser)")
    finally h.shutdown()
  }

  // ---- /pool/permission/revoke ----

  "revokePoolPermission" should "reject a tenant-A admin revoking a tenant-B grant" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"id":"$GlobexPoolPerm"}"""
      val resp =
        post(h.httpClient, s"${h.baseUrl}/api/pool/permission/revoke", body, apiKey = Some(token))
      expectForbidden(resp, "tenant-A admin -> /pool/permission/revoke on tenant-B grant")
    finally h.shutdown()
  }

  // ---- happy path: alice can still operate on her own tenant ----

  "alice (tenant-A admin)" should "still be able to create + delete a role in her own tenant" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )

      val createBody =
        s"""{"tenant":"${SecurityFixtures.TenantId}","name":"qa","description":"alice's role"}"""
      val create =
        post(h.httpClient, s"${h.baseUrl}/api/role/create", createBody, apiKey = Some(token))
      withClue(s"alice -> /role/create body: ${create.body()}") {
        create.statusCode() shouldBe 200
      }
      val newId = parse(create.body()).toOption
        .flatMap(_.hcursor.get[String]("id").toOption)
        .getOrElse(fail(s"no id in create response: ${create.body()}"))

      val delResp = post(
        h.httpClient,
        s"${h.baseUrl}/api/role/delete",
        s"""{"id":"$newId"}""",
        apiKey = Some(token)
      )
      withClue(s"alice -> /role/delete (own) body: ${delResp.body()}") {
        delResp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  // ---- cookie-transport tests for pool/node endpoints ----
  // Each case proves that a tenant-A admin session carried via the qod_session cookie
  // (no X-API-Key header) gets 403 tenant_forbidden when targeting a tenant-B resource.
  // Pre-fix: the cookie is not bound to the endpoint, apiKey=None reaches the handler,
  // and TenantScopeCheck admits. Post-fix: key.orElse(cookie) propagates the session.

  "pool/create via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        s"""{"tenant":"$GlobexTenantId","tenantDb":"globex_main","pool":"bi","size":1,"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}"""
      val resp =
        postWithCookie(h.httpClient, s"${h.baseUrl}/api/pool/create", body, cookieToken = token)
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/pool/create on tenant-B")
    finally h.shutdown()
  }

  "pool/scale via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        s"""{"tenant":"$GlobexTenantId","tenantDb":"globex_main","pool":"bi","targetSize":2,"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}"""
      val resp =
        postWithCookie(h.httpClient, s"${h.baseUrl}/api/pool/scale", body, cookieToken = token)
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/pool/scale on tenant-B")
    finally h.shutdown()
  }

  "pool/stop via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"tenant":"$GlobexTenantId","tenantDb":"globex_main","pool":"bi"}"""
      val resp =
        postWithCookie(h.httpClient, s"${h.baseUrl}/api/pool/stop", body, cookieToken = token)
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/pool/stop on tenant-B")
    finally h.shutdown()
  }

  "pool/delete via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"tenant":"$GlobexTenantId","tenantDb":"globex_main","pool":"bi"}"""
      val resp =
        postWithCookie(h.httpClient, s"${h.baseUrl}/api/pool/delete", body, cookieToken = token)
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/pool/delete on tenant-B")
    finally h.shutdown()
  }

  "pool/setDisabled via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        s"""{"tenant":"$GlobexTenantId","tenantDb":"globex_main","pool":"bi","disabled":true}"""
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/pool/setDisabled",
        body,
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/pool/setDisabled on tenant-B")
    finally h.shutdown()
  }

  "node/setMaxConcurrent via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        s"""{"tenant":"$GlobexTenantId","tenantDb":"globex_main","pool":"bi","nodeId":"n-test","max":5}"""
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/node/setMaxConcurrent",
        body,
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/node/setMaxConcurrent on tenant-B")
    finally h.shutdown()
  }

  // ---- cookie-transport tests for tenant + database endpoints ----
  // Each case proves that a session carried via the qod_session cookie
  // gets the correct rejection once the cookie input is wired to these endpoints.
  // Pre-fix: the cookie is ignored (endpoint only has 2 inputs), apiKey=None
  // reaches the handler, and the scope/superuser check admits. Post-fix:
  // key.orElse(cookie) propagates the session.

  "tenant/create via session cookie" should "reject a tenant admin with 403 superuser_required" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/tenant/create",
        """{"id":"newt","displayName":"New Tenant"}""",
        cookieToken = token
      )
      withClue(s"tenant admin (cookie) -> /api/tenant/create: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("superuser_required")
      }
    finally h.shutdown()
  }

  "tenant/delete via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/tenant/delete",
        s"""{"name":"$GlobexTenantId"}""",
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/tenant/delete on tenant-B")
    finally h.shutdown()
  }

  "tenant/setDisabled via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/tenant/setDisabled",
        s"""{"name":"$GlobexTenantId","disabled":true}""",
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/tenant/setDisabled on tenant-B")
    finally h.shutdown()
  }

  "tenant/setAuth via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/tenant/setAuth",
        s"""{"name":"$GlobexTenantId","authProvider":"db"}""",
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/tenant/setAuth on tenant-B")
    finally h.shutdown()
  }

  "database/create via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/database/create",
        s"""{"tenant":"$GlobexTenantId","name":"globex_main"}""",
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/database/create on tenant-B")
    finally h.shutdown()
  }

  "database/delete via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/database/delete",
        s"""{"tenant":"$GlobexTenantId","name":"globex_main"}""",
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/database/delete on tenant-B")
    finally h.shutdown()
  }

  "database/update via session cookie" should "reject a tenant-A admin targeting tenant-B" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/database/update",
        s"""{"tenant":"$GlobexTenantId","name":"globex_main"}""",
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/database/update on tenant-B")
    finally h.shutdown()
  }

  // ---- cookie-transport tests for RBAC create/grant endpoints ----
  // Each case proves that a tenant-A admin session carried via the qod_session cookie
  // (no X-API-Key header) gets 403 tenant_forbidden when creating a resource in tenant-B.
  // Pre-fix: the cookie is not bound to the endpoint, apiKey=None reaches the handler,
  // and TenantScopeCheck admits. Post-fix: key.orElse(cookie) propagates the session.

  "user/create via session cookie" should "reject a tenant-A admin creating a tenant-B user" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        s"""{"tenant":"$GlobexTenantId","username":"mallory","password":"pw","role":"user"}"""
      val resp =
        postWithCookie(h.httpClient, s"${h.baseUrl}/api/user/create", body, cookieToken = token)
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/user/create in tenant-B")
    finally h.shutdown()
  }

  "role/create via session cookie" should "reject a tenant-A admin creating a tenant-B role" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"tenant":"$GlobexTenantId","name":"testrole"}"""
      val resp =
        postWithCookie(h.httpClient, s"${h.baseUrl}/api/role/create", body, cookieToken = token)
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/role/create in tenant-B")
    finally h.shutdown()
  }

  "group/create via session cookie" should "reject a tenant-A admin creating a tenant-B group" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"tenant":"$GlobexTenantId","name":"testgroup"}"""
      val resp =
        postWithCookie(h.httpClient, s"${h.baseUrl}/api/group/create", body, cookieToken = token)
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/group/create in tenant-B")
    finally h.shutdown()
  }

  "pool/permission/grant via session cookie" should "reject a tenant-A admin granting in tenant-B" in {
    val (h, _, carolId) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"tenant":"$GlobexTenantId","userId":"$carolId"}"""
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/pool/permission/grant",
        body,
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/pool/permission/grant in tenant-B")
    finally h.shutdown()
  }

  // ---- cookie-transport tests for RBAC id-only endpoints (Task 4) ----
  // Covers four handler families: delete, revoke, membership, policy.
  // Pre-fix: endpoint only has 2 inputs, cookie is ignored, apiKey=None reaches
  // TenantScopeCheck which admits unconditionally. Post-fix: key.orElse(cookie)
  // propagates the session and the scope check fires.

  // (c) delete family: role/delete
  "role/delete via session cookie" should "reject a tenant-A admin deleting a tenant-B role" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/role/delete",
        s"""{"id":"$GlobexRoleId"}""",
        cookieToken = token
      )
      expectForbidden(resp, "tenant-A admin (cookie) -> /api/role/delete on tenant-B role")
    finally h.shutdown()
  }

  // (d) revoke family: role/permission/revoke
  "role/permission/revoke via session cookie" should "reject a tenant-A admin revoking a tenant-B permission" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/role/permission/revoke",
        s"""{"id":"$GlobexPermId"}""",
        cookieToken = token
      )
      expectForbidden(
        resp,
        "tenant-A admin (cookie) -> /api/role/permission/revoke on tenant-B perm"
      )
    finally h.shutdown()
  }

  // (d2) grant family: role/permission/grant
  "role/permission/grant via session cookie" should "reject a tenant-A admin granting on a tenant-B role" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body = s"""{"roleId":"$GlobexRoleId","verb":"ALL"}"""
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/role/permission/grant",
        body,
        cookieToken = token
      )
      expectForbidden(
        resp,
        "tenant-A admin (cookie) -> /api/role/permission/grant on tenant-B role"
      )
    finally h.shutdown()
  }

  // (a) membership family: membership/user-role/add (gated on the user's tenant)
  "membership/user-role/add via session cookie" should "reject a tenant-A admin adding a tenant-B user to a role" in {
    val (h, _, carolId) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/membership/user-role/add",
        s"""{"userId":"$carolId","roleId":"$GlobexRoleId"}""",
        cookieToken = token
      )
      expectForbidden(
        resp,
        "tenant-A admin (cookie) -> /api/membership/user-role/add on tenant-B user"
      )
    finally h.shutdown()
  }

  // (b) policy family: role/column-policy/create (gated on roleId's tenant)
  "role/column-policy/create via session cookie" should "reject a tenant-A admin creating a policy on a tenant-B role" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        s"""{"roleId":"$GlobexRoleId","catalogName":"*","schemaName":"*","tableName":"t","columnName":"c","action":"MASK"}"""
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/role/column-policy/create",
        body,
        cookieToken = token
      )
      expectForbidden(
        resp,
        "tenant-A admin (cookie) -> /api/role/column-policy/create on tenant-B role"
      )
    finally h.shutdown()
  }

  // ---- cookie-transport tests for id-scoped RBAC read endpoints (Fix B) ----
  // listColumnPolicies and listGroupRoleMembership gate via rejectForResource, which
  // admits when apiKey is None. Pre-fix: cookie session reaches the handler with
  // apiKey=None and reads another tenant's data (200). Post-fix: the endpoint uses
  // authToken so cookie resolves to Some(token), rejectForResource sees the acme
  // scope, and globex is not in manageableTenants -> 403 tenant_forbidden.

  "role/column-policy/list via session cookie" should "reject a tenant-A admin reading a tenant-B role's policies" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/role/column-policy/list?roleId=$GlobexRoleId",
        token
      )
      expectForbidden(
        resp,
        "tenant-A admin (cookie) -> /api/role/column-policy/list on tenant-B role"
      )
    finally h.shutdown()
  }

  "membership/group-role/list via session cookie" should "reject a tenant-A admin reading a tenant-B group's roles" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/membership/group-role/list?groupId=$GlobexGroupId",
        token
      )
      expectForbidden(
        resp,
        "tenant-A admin (cookie) -> /api/membership/group-role/list on tenant-B group"
      )
    finally h.shutdown()
  }

  // ---- cookie-transport tests for superuser-gated endpoints (Task 5b) ----
  // manifest/export, manifest/import, config/server are superuser-only.
  // Pre-fix: apiKey=None -> None arm admits unconditionally.
  // Post-fix: key.orElse(cookie) resolves the session -> 403 superuser_required.

  "manifest/export via session cookie" should "reject a tenant admin with 403 superuser_required" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(h.httpClient, s"${h.baseUrl}/api/manifest/export", token)
      withClue(s"tenant admin (cookie) -> /api/manifest/export: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("superuser_required")
      }
    finally h.shutdown()
  }

  "manifest/import via session cookie" should "reject a tenant admin with 403 superuser_required" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = postWithCookie(
        h.httpClient,
        s"${h.baseUrl}/api/manifest/import",
        "tenants: []",
        cookieToken = token
      )
      withClue(s"tenant admin (cookie) -> /api/manifest/import: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("superuser_required")
      }
    finally h.shutdown()
  }

  "config/server via session cookie" should "reject a tenant admin with 403 superuser_required" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(h.httpClient, s"${h.baseUrl}/api/config/server", token)
      withClue(s"tenant admin (cookie) -> /api/config/server: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("superuser_required")
      }
    finally h.shutdown()
  }

  // ---- cookie-transport tests for self-filtering read endpoints (Task 5b) ----
  // listTenants, listPools, statementHistory filter by the session's manageableTenants.
  // Pre-fix: apiKey=None -> None arm -> returns unfiltered (all tenants / all pools).
  // Post-fix: cookie resolves to acme session -> scoped to acme only.

  "tenant/list via session cookie" should "scope the result to the calling session's tenant" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(h.httpClient, s"${h.baseUrl}/api/tenant/list", token)
      withClue(s"tenant-A admin (cookie) -> /api/tenant/list: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        // Post-fix: only acme is returned; globex must not appear.
        resp.body() should include(SecurityFixtures.TenantId)
        resp.body() should not include GlobexTenantId
      }
    finally h.shutdown()
  }

  "pool/list via session cookie" should "scope the result to the calling session's tenant" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(h.httpClient, s"${h.baseUrl}/api/pool/list", token)
      withClue(s"tenant-A admin (cookie) -> /api/pool/list: ${resp.body()}") {
        // addTenantB seeds a globex pool (tenant id = GlobexTenantId in the response).
        // Post-fix: acme admin's cookie is resolved; only acme pools returned.
        // The acme pool (tenant field = SecurityFixtures.TenantId) is present;
        // the globex pool (tenant field = GlobexTenantId) is filtered out.
        resp.statusCode() shouldBe 200
        resp.body() should include(SecurityFixtures.TenantId)
        // PoolResponse.tenant carries the tenant id (PoolKey.tenant), so a
        // cross-tenant leak would surface GlobexTenantId ("t-globex01").
        resp.body() should not include GlobexTenantId
      }
    finally h.shutdown()
  }

  it should "return the pools of a tenant whose display name differs from its id" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      // dave is a globex tenant admin; globex has id "t-globex01" but displayName
      // "globex". The pool filter must compare tenant IDS (what PoolResponse.tenant
      // carries), not display names, or dave sees zero pools.
      val token = h.mintToken(DaveUser, DavePassword, Some(GlobexTenantId))
      val resp  = getWithCookie(h.httpClient, s"${h.baseUrl}/api/pool/list", token)
      withClue(s"globex admin (displayName != id) -> /api/pool/list: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        resp.body() should include(GlobexTenantId)
        resp.body() should not include SecurityFixtures.TenantId
      }
    finally h.shutdown()
  }

  "node/statements via session cookie" should "scope the result to the calling session's tenant" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      // Seed one acme statement and one globex statement so the tenant filter has
      // real data to act on. Without seeding, the ring buffer is empty and the
      // assertion would pass trivially regardless of whether filtering is applied.
      h.stmtHistory.record(
        StatementRecord(
          ts = Instant.EPOCH,
          user = SecurityFixtures.AliceUsername,
          tenant = SecurityFixtures.TenantId,
          pool = SecurityFixtures.PoolName,
          nodeId = "n-acme-01",
          sql = "SELECT 1",
          durationMs = 1L,
          status = "ok",
          error = None
        )
      )
      h.stmtHistory.record(
        StatementRecord(
          ts = Instant.EPOCH,
          user = CarolUser,
          tenant = GlobexTenantId,
          pool = "bi",
          nodeId = "n-globex-01",
          sql = "SELECT 2",
          durationMs = 1L,
          status = "ok",
          error = None
        )
      )
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(h.httpClient, s"${h.baseUrl}/api/node/statements", token)
      withClue(s"tenant-A admin (cookie) -> /api/node/statements: ${resp.body()}") {
        // Post-fix: acme admin's cookie resolves to an acme session.
        // The acme statement (tenant = SecurityFixtures.TenantId) is included.
        // The globex statement (tenant = GlobexTenantId) is filtered out.
        resp.statusCode() shouldBe 200
        resp.body() should include(SecurityFixtures.TenantId)
        resp.body() should not include GlobexTenantId
      }
    finally h.shutdown()
  }

  // ---- cookie-transport tests for optional-tenant read endpoints (Task 5e) ----
  // listUsers and listPoolPermissions accept an optional ?tenant= query param and
  // self-filter when it is present. When it is absent and the caller is a cookie
  // session, the None-arm returned unfiltered cross-tenant data pre-fix.
  // Post-fix: both endpoints use authToken, so the cookie resolves to Some(scope)
  // and the handler's None-arm infers the tenant from the session.

  "user/list via session cookie" should "scope the result to the calling session's tenant" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(h.httpClient, s"${h.baseUrl}/api/user/list", token)
      withClue(s"tenant-A admin (cookie) -> /api/user/list: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        // carol (globex user) is seeded by addTenantB; UserResponse serializes her
        // username verbatim (the tenant field is the display name, not the id, so
        // GlobexTenantId would never appear and cannot detect the leak).
        // Pre-fix: apiKey=None -> None arm -> no filter -> carol leaks in the body.
        // Post-fix: cookie resolves to acme scope; handler filters to acme users only.
        resp.body() should not include CarolUser
        // alice is in acme -- verify a real scoped result is returned, not an empty list.
        resp.body() should include(SecurityFixtures.AliceUsername)
      }
    finally h.shutdown()
  }

  "pool/permission/list via session cookie" should "scope the result to the calling session's tenant" in {
    val (h, _, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = getWithCookie(h.httpClient, s"${h.baseUrl}/api/pool/permission/list", token)
      withClue(s"tenant-A admin (cookie) -> /api/pool/permission/list: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        // GlobexPoolPerm ("pp-globex01") is seeded by addTenantB with tenantId "t-globex01".
        // PoolPermissionResponse serializes both id and tenantId, so both would appear on a leak.
        // Pre-fix: apiKey=None -> None arm -> no tenant filter -> globex perm leaks.
        // Post-fix: cookie resolves to acme scope; handler filters to acme permissions only.
        resp.body() should not include GlobexTenantId
        resp.body() should not include GlobexPoolPerm
        // alice's pool permission (SecurityFixtures.PoolPermId) is in acme -- confirm real result.
        resp.body() should include(SecurityFixtures.PoolPermId)
      }
    finally h.shutdown()
  }
