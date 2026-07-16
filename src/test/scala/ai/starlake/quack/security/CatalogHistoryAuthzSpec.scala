// src/test/scala/ai/starlake/quack/security/CatalogHistoryAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.catalog.{
  DuckLakeCatalogReader,
  TableHistoryFilter,
  TableHistoryPage
}
import ai.starlake.quack.security.SecurityFixtures.{addTenantB, GlobexTenantId}
import ai.starlake.quack.security.SecurityFixtures.GlobexTenantDbName as GlobexTenantDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpResponse

/** AuthZ contract of the per-table history endpoint (EPIC Spec 01).
  *
  * `GET /api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/history`
  * is session-gated exactly like the other catalog browser GETs pinned by [[CatalogAuthzSpec]]:
  * `apiKeyGuard` at the perimeter plus the handler-level TenantScopeCheck gate. This spec pins:
  * credential-less 401 (static key set), cross-tenant 403 tenant_forbidden, superuser pass-through,
  * 404 on an unknown tenant (no existence leak), and 400 invalid_filter on malformed filter params.
  */
class CatalogHistoryAuthzSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  /** Stub reader: history resolves for any table with an empty page. Authz cases only care about
    * status codes.
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
    override def listTableHistory(
        schema: String,
        table: String,
        filter: TableHistoryFilter = TableHistoryFilter(),
        limit: Int = 50,
        before: Option[Long] = None
    ): Option[TableHistoryPage] = Some(TableHistoryPage(1L, Nil, hasMore = false))

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

  private def historyUrl(
      h: ManagerServerHarness.Harness,
      tenant: String,
      db: String
  ): String =
    s"${h.baseUrl}/api/catalog/tenant/$tenant/database/$db/schemas/tpch1/tables/region/history"

  // ---------- Spec ------------------------------------------------------------

  "the history endpoint" should "reject a credential-less GET with 401 when a static key is set" in {
    val (h, _) = bootWithTwoTenants(staticApiKey = Some("static-key-for-history-authz"))
    try
      get(h.httpClient, historyUrl(h, SecurityFixtures.TenantId, SecurityFixtures.TenantDbName))
        .statusCode() shouldBe 401
    finally h.shutdown()
  }

  it should "reject a tenant-A admin reading tenant-B history with 403 tenant_forbidden" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      expectForbidden(
        get(h.httpClient, historyUrl(h, GlobexTenantId, GlobexTenantDb), apiKey = Some(token)),
        "tenant-A admin -> tenant-B /history"
      )
    finally h.shutdown()
  }

  it should "let a superuser read any tenant's history (200)" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  =
        get(h.httpClient, historyUrl(h, GlobexTenantId, GlobexTenantDb), apiKey = Some(token))
      withClue(s"superuser -> tenant-B /history body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "404 an unknown tenant without leaking existence" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, historyUrl(h, "nope", "whatever_db"), apiKey = Some(token))
      resp.statusCode() shouldBe 404
      errorCode(resp.body()) shouldBe Some("not_found")
    finally h.shutdown()
  }

  it should "400 invalid_filter on a malformed from timestamp and an unknown operation" in {
    val (h, _) = bootWithTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val base  = historyUrl(h, SecurityFixtures.TenantId, SecurityFixtures.TenantDbName)
      List(s"$base?from=yesterday", s"$base?operation=merge").foreach { url =>
        val resp = get(h.httpClient, url, apiKey = Some(token))
        withClue(s"$url body: ${resp.body()}") {
          resp.statusCode() shouldBe 400
          errorCode(resp.body()) shouldBe Some("invalid_filter")
        }
      }
    finally h.shutdown()
  }
