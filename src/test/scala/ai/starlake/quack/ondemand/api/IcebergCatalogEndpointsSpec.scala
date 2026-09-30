package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType}
import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures, SecurityHttpHelpers}
import ai.starlake.quack.security.SecurityFixtures.addTenantB
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpResponse

/** HTTP round trip of the six Iceberg catalog view routes through the real [[ManagerServer]] (Task
  * 6): each route is mounted, sits behind the api-key guard and the handler's [[TenantDbGate]], and
  * resolves the alias against the tenant-db's enabled `iceberg_rest` sources. `asOfTs` is parsed at
  * the endpoint by `QueryParams.instantAs`, so a malformed value answers 400 `invalid_selector`
  * before the handler runs.
  *
  * The harness wires a metadata runner with no read pool, so a KNOWN alias reaches the runner and
  * answers 404 `no_pool`; an unknown alias answers 404 `catalog_not_found`. Both prove the route
  * reached the Iceberg handler rather than the router's bare 404.
  */
class IcebergCatalogEndpointsSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val Tenant = SecurityFixtures.TenantId
  private val Db     = SecurityFixtures.TenantDbName
  private val Alias  = "lake"

  private val lakeSource = FederatedSource(
    id = "fs-lake0001",
    tenantDbId = SecurityFixtures.TenantDbId,
    alias = Alias,
    sourceType = FederatedSourceType.IcebergRest,
    config = Some("{}")
  )

  private def boot(staticApiKey: Option[String] = None): ManagerServerHarness.Harness =
    val fix = SecurityFixtures.freshStore()
    addTenantB(fix)
    ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      icebergSourcesOf =
        Some(id => if id == SecurityFixtures.TenantDbId then List(lakeSource) else Nil)
    )

  private def base(alias: String, tenant: String = Tenant, db: String = Db): String =
    s"/api/catalog/tenant/$tenant/database/$db/iceberg/$alias"

  /** The six routes, keyed by name, for a given alias. */
  private def routes(
      alias: String,
      tenant: String = Tenant,
      db: String = Db
  ): List[(String, String)] =
    val b = base(alias, tenant, db)
    val t = s"$b/schemas/sales/tables/orders"
    List(
      "schemas"   -> s"$b/schemas",
      "tables"    -> s"$b/schemas/sales/tables",
      "detail"    -> t,
      "history"   -> s"$t/history?limit=5&operation=append",
      "preview"   -> s"$t/preview?asOf=1&limit=5",
      "data-diff" -> s"$t/data-diff?from=1&to=2&changeType=added"
    )

  private def expect(resp: HttpResponse[String], status: Int, code: String, ctx: String): Unit =
    withClue(s"$ctx body: ${resp.body()}") {
      resp.statusCode() shouldBe status
      errorCode(resp.body()) shouldBe Some(code)
    }

  "the Iceberg catalog routes" should "answer 404 catalog_not_found for an unknown alias" in {
    val h = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      routes("nope").foreach { (name, url) =>
        expect(get(h.httpClient, h.baseUrl + url, Some(token)), 404, "catalog_not_found", name)
      }
    finally h.shutdown()
  }

  it should "resolve a known alias (case-insensitively) and reach the metadata runner" in {
    val h = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      routes("LAKE").foreach { (name, url) =>
        expect(get(h.httpClient, h.baseUrl + url, Some(token)), 404, "no_pool", name)
      }
    finally h.shutdown()
  }

  it should "answer 400 invalid_selector for a malformed asOfTs" in {
    val h = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val url   = s"${base(Alias)}/schemas/sales/tables/orders/preview?asOfTs=not-a-time"
      expect(get(h.httpClient, h.baseUrl + url, Some(token)), 400, "invalid_selector", "asOfTs")
    finally h.shutdown()
  }

  it should "reject a credential-less request with 401" in {
    val h = boot(staticApiKey = Some("static-key-for-iceberg-endpoints"))
    try
      routes(Alias).foreach { (name, url) =>
        withClue(s"$name: ")(get(h.httpClient, h.baseUrl + url).statusCode() shouldBe 401)
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin on tenant-B's tenant-db with 403 tenant_forbidden" in {
    val h = boot()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      routes(Alias, SecurityFixtures.GlobexTenantId, SecurityFixtures.GlobexTenantDbName).foreach {
        (name, url) =>
          expect(get(h.httpClient, h.baseUrl + url, Some(token)), 403, "tenant_forbidden", name)
      }
    finally h.shutdown()
  }
