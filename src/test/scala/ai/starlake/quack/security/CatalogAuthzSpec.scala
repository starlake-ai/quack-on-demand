// src/test/scala/ai/starlake/quack/security/CatalogAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.security.SecurityFixtures.{addTenantB, GlobexTenantId}
import ai.starlake.quack.security.SecurityFixtures.GlobexTenantDbName as GlobexTenantDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpResponse

/** AuthZ contract of the catalog browser read surface (Spec 00 time-travel viewer).
  *
  * The four catalog GETs and `GET /api/database/list` historically carried no session input (the
  * endpoint-drift item in docs/AUDIT-FOLLOWUPS.md); they are now session-gated exactly like
  * [[ai.starlake.quack.ondemand.api.TagEndpoints]]: `apiKeyGuard` at the perimeter plus the
  * handler-level TenantScopeCheck gate. This spec pins: credential-less 401 (static key set),
  * cross-tenant 403 tenant_forbidden, superuser pass-through, 404 on an unknown tenant (no
  * existence leak), listTenantDbs scope gating, and the open-mode dev path staying zero-config.
  */
class CatalogAuthzSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  /** Stub reader: every listing empty, every table absent. The authz cases only care about status
    * codes, never catalog content.
    */
  private def emptyReader: DuckLakeCatalogReader = new DuckLakeCatalogReader(null):
    override def listSchemas()              = Nil
    override def listTables(schema: String) = Nil
    override def listSnapshots(
        limit: Int = 200,
        before: Option[Long] = None,
        table: Option[(String, String)] = None
    ) = Nil
    override def getTable(schema: String, table: String, asOf: Option[Long] = None) = None

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
    val h = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      catalogReader = Some((_, _) => emptyReader)
    )
    (h, fix)

  private def schemasUrl(h: ManagerServerHarness.Harness, tenant: String, db: String): String =
    s"${h.baseUrl}/api/catalog/tenant/$tenant/database/$db/schemas"

  // ---------- Spec ----------------------------------------------------------

  "catalog read endpoints" should "reject a credential-less schemas GET with 401 when a static key is set" in {
    val (h, _) = bootWithTwoTenants(staticApiKey = Some("static-key-for-catalog-authz"))
    try
      val resp = get(
        h.httpClient,
        schemasUrl(h, SecurityFixtures.TenantId, SecurityFixtures.TenantDbName)
      )
      withClue(s"credential-less GET schemas body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin reading tenant-B's snapshots with 403 tenant_forbidden" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/catalog/tenant/$GlobexTenantId/database/$GlobexTenantDb/snapshots",
        apiKey = Some(token)
      )
      expectForbidden(resp, "tenant-A admin -> tenant-B /snapshots")
    finally h.shutdown()
  }

  it should "let a superuser read any tenant's schemas (200, possibly empty)" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        schemasUrl(h, GlobexTenantId, GlobexTenantDb),
        apiKey = Some(token)
      )
      withClue(s"superuser -> tenant-B /schemas body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "404 an unknown tenant without leaking existence" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        schemasUrl(h, "nope", "whatever_db"),
        apiKey = Some(token)
      )
      withClue(s"superuser -> unknown tenant /schemas body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
      }
    finally h.shutdown()
  }

  it should "gate listTenantDbs by session scope" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val denied = get(
        h.httpClient,
        s"${h.baseUrl}/api/database/list?tenant=$GlobexTenantId",
        apiKey = Some(token)
      )
      expectForbidden(denied, "tenant-A admin -> /database/list on tenant-B")
      val allowed = get(
        h.httpClient,
        s"${h.baseUrl}/api/database/list?tenant=${SecurityFixtures.TenantId}",
        apiKey = Some(token)
      )
      withClue(s"tenant-A admin -> /database/list on own tenant body: ${allowed.body()}") {
        allowed.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "keep the zero-config dev path: open mode (no static key) admits a same-tenant read" in {
    // UI path regression: no static key, no cookie, no token. apiKeyGuard is
    // open and TenantScopeCheck admits key-less callers, so the catalog
    // browser keeps working on a dev laptop without any configuration.
    val (h, _) = bootWithTwoTenants()
    try
      val resp = get(
        h.httpClient,
        schemasUrl(h, SecurityFixtures.TenantId, SecurityFixtures.TenantDbName)
      )
      withClue(s"open-mode credential-less GET schemas body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "accept the table query parameter on snapshots GET" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/catalog/tenant/${SecurityFixtures.TenantId}/database/${SecurityFixtures.TenantDbName}/snapshots?table=tpch1.region",
        apiKey = Some(token)
      )
      withClue(s"snapshots with table filter body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }
