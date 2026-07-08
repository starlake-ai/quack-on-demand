// src/test/scala/ai/starlake/quack/security/TagAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

/** AuthZ contract of the snapshot-tag REST surface (EPIC P2 / Spec 06).
  *
  * The tag endpoints live under `/api/catalog/...` and must sit behind
  * [[ai.starlake.quack.ondemand.ManagerServer.apiKeyGuard]] (they are NOT in the public-path list)
  * and behind the handler-level [[ai.starlake.quack.ondemand.api.TenantScopeCheck]] gate. This spec
  * pins the four behaviors: credential-less 401, cross-tenant 403 tenant_forbidden, superuser
  * pass-through to the handler, and 404 on an unknown tenant (no existence leak).
  *
  * Mirrors [[RbacTenantScopeSpec]]'s harness helpers (copied locally where private).
  */
class TagAuthzSpec extends AnyFlatSpec with Matchers:

  private val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  // Tenant-B fixture constants (same shape as RbacTenantScopeSpec's addTenantB).
  private val GlobexTenantId   = "t-globex01"
  private val GlobexTenantDbId = "td-globex01"
  private val GlobexTenantDb   = "globex_main"

  /** Augment a [[SecurityFixtures.Fixture]] with a second tenant `globex` and one InMemory
    * tenant-db so cross-tenant tag calls have a real target. InMemory (not DuckLake) on purpose:
    * the superuser test relies on the handler's `invalid_kind` arm firing AFTER the scope gate.
    */
  private def addTenantB(fix: SecurityFixtures.Fixture): Unit =
    val s = fix.store
    s.upsertTenant(
      Tenant(
        id = GlobexTenantId,
        displayName = "globex",
        authProvider = "db"
      )
    )
    s.upsertTenantDb(
      TenantDb(
        id = GlobexTenantDbId,
        tenantId = GlobexTenantId,
        name = GlobexTenantDb,
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = ""
      )
    )

  // ---------- HTTP helpers (mirroring RbacTenantScopeSpec) ------------------

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

  private def errorCode(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("error").toOption)

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
