// src/test/scala/ai/starlake/quack/security/TagAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.security.SecurityFixtures.{addTenantB, GlobexTenantId}
import ai.starlake.quack.security.SecurityFixtures.GlobexTenantDbName as GlobexTenantDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpResponse

/** AuthZ contract of the snapshot-tag REST surface (EPIC P2 / Spec 06).
  *
  * The tag endpoints live under `/api/catalog/...` and must sit behind
  * [[ai.starlake.quack.ondemand.ManagerServer.apiKeyGuard]] (they are NOT in the public-path list)
  * and behind the handler-level [[ai.starlake.quack.ondemand.api.TenantScopeCheck]] gate. This spec
  * pins the four behaviors: credential-less 401, cross-tenant 403 tenant_forbidden, superuser
  * pass-through to the handler, and 404 on an unknown tenant (no existence leak).
  */
class TagAuthzSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private def expectForbidden(resp: HttpResponse[String], context: String): Unit =
    withClue(s"$context body: ${resp.body()}") {
      resp.statusCode() shouldBe 403
      errorCode(resp.body()) shouldBe Some("tenant_forbidden")
    }

  private def bootWithTwoTenants(
      staticApiKey: Option[String] = None
  ): (ManagerServerHarness.Harness, SecurityFixtures.Fixture) =
    val fix = SecurityFixtures.freshStore()
    addTenantB(fix)
    val h = ManagerServerHarness.boot(fix.store, staticApiKey = staticApiKey)
    (h, fix)

  private def createBody(tenant: String, tenantDb: String): String =
    s"""{"tenant":"$tenant","tenantDb":"$tenantDb","name":"v1","snapshotId":1,"protected":false}"""

  // ---------- Spec ----------------------------------------------------------

  "tag endpoints" should "reject a credential-less GET list with 401" in {
    // Static key configured -> apiKeyGuard enforces; no X-API-Key, no cookie -> 401.
    val (h, _) = bootWithTwoTenants(staticApiKey = Some("static-key-for-tag-authz"))
    try
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/catalog/tenant/${SecurityFixtures.TenantId}/database/${SecurityFixtures.TenantDbName}/tags"
      )
      withClue(s"credential-less GET tags body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin creating a tag on tenant-B with 403 tenant_forbidden" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/catalog/tag/create",
        createBody(GlobexTenantId, GlobexTenantDb),
        apiKey = Some(token)
      )
      expectForbidden(resp, "tenant-A admin -> /catalog/tag/create on tenant-B")
    finally h.shutdown()
  }

  it should "let a superuser reach the handler on any tenant" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = post(
        h.httpClient,
        s"${h.baseUrl}/api/catalog/tag/create",
        createBody(GlobexTenantId, GlobexTenantDb),
        apiKey = Some(token)
      )
      // globex_main is an InMemory tenant-db, so a superuser who passes the scope
      // gate hits the handler's invalid_kind (400) arm; a dangling snapshot would
      // yield 404. Either proves the handler was reached: NOT 401/403.
      withClue(s"superuser -> /catalog/tag/create on tenant-B body: ${resp.body()}") {
        resp.statusCode() should (be(400) or be(404))
        errorCode(resp.body()) should not be Some("tenant_forbidden")
      }
    finally h.shutdown()
  }

  it should "404 a tag mutation on an unknown tenant without leaking existence" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = post(
        h.httpClient,
        s"${h.baseUrl}/api/catalog/tag/create",
        createBody("nope", "whatever_db"),
        apiKey = Some(token)
      )
      withClue(s"superuser -> /catalog/tag/create on unknown tenant body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
      }
    finally h.shutdown()
  }
