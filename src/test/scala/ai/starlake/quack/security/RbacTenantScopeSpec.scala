// src/test/scala/ai/starlake/quack/security/RbacTenantScopeSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.Tenant
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

  private val CarolUser     = "carol"
  private val CarolPassword = "carolpw"

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
