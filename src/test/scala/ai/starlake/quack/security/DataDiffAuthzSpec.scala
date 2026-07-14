// src/test/scala/ai/starlake/quack/security/DataDiffAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** AuthZ contract of the row-level data-diff REST surface (Spec 02).
  *
  * `/api/catalog/.../data-diff` must sit behind
  * [[ai.starlake.quack.ondemand.ManagerServer.apiKeyGuard]] and the handler-level
  * [[ai.starlake.quack.ondemand.api.TenantScopeCheck]] gate, exactly like [[PreviewAuthzSpec]]'s
  * surfaces. Pins: credential-less 401, cross-tenant 403 tenant_forbidden, 404 on an unknown tenant
  * (no existence leak), superuser pass-through to the handler (invalid_kind on an InMemory
  * tenant-db proves the handler was reached), the pre-bound-resolution 400 invalid_cursor, and the
  * empty-catalog 422 invalid_snapshot from bound resolution.
  */
class DataDiffAuthzSpec extends AnyFlatSpec with Matchers:

  private val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  private val GlobexTenantId   = "t-globex01"
  private val GlobexTenantDbId = "td-globex01"
  private val GlobexTenantDb   = "globex_main"

  private val DuckLakeTenantDbId = "td-acmedl01"
  private val DuckLakeTenantDb   = "acme_dl"

  private def addFixtures(fix: SecurityFixtures.Fixture): Unit =
    val s = fix.store
    s.upsertTenant(Tenant(id = GlobexTenantId, displayName = "globex", authProvider = "db"))
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
    s.upsertTenantDb(
      TenantDb(
        id = DuckLakeTenantDbId,
        tenantId = SecurityFixtures.TenantId,
        name = DuckLakeTenantDb,
        kind = TenantDbKind.DuckLake,
        metastore = Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "5432",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> DuckLakeTenantDb,
          "schemaName" -> "main"
        ),
        dataPath = "/tmp/qod-datadiff-authz-test"
      )
    )
    s.upsertPool(
      Pool(
        id = "p-dlbi0001",
        tenantId = SecurityFixtures.TenantId,
        tenantDbId = DuckLakeTenantDbId,
        name = "dlbi",
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1)
      )
    )

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

  private def boot(
      staticApiKey: Option[String] = None
  ): (ManagerServerHarness.Harness, SecurityFixtures.Fixture) =
    val fix = SecurityFixtures.freshStore()
    addFixtures(fix)
    (ManagerServerHarness.boot(fix.store, staticApiKey = staticApiKey), fix)

  private def diffUrl(
      tenant: String,
      tenantDb: String,
      params: String = "?from=1&to=2"
  ): String =
    s"/api/catalog/tenant/$tenant/database/$tenantDb/schemas/main/tables/region/data-diff$params"

  "data-diff endpoint" should "reject a credential-less GET with 401" in {
    val (h, _) = boot(staticApiKey = Some("static-key-for-datadiff-authz"))
    try
      val resp = get(h.httpClient, h.baseUrl + diffUrl(SecurityFixtures.TenantId, DuckLakeTenantDb))
      withClue(s"credential-less GET data-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin diffing tenant-B's table with 403 tenant_forbidden" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, h.baseUrl + diffUrl(GlobexTenantId, GlobexTenantDb), Some(token))
      withClue(s"tenant-A admin -> tenant-B /data-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("tenant_forbidden")
      }
    finally h.shutdown()
  }

  it should "404 an unknown tenant without leaking existence" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, h.baseUrl + diffUrl("nope", "whatever_db"), Some(token))
      withClue(s"superuser -> unknown tenant /data-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
      }
    finally h.shutdown()
  }

  it should "let a superuser reach the handler (invalid_kind on an InMemory tenant-db)" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp = get(h.httpClient, h.baseUrl + diffUrl(GlobexTenantId, GlobexTenantDb), Some(token))
      withClue(s"superuser -> tenant-B /data-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_kind")
      }
    finally h.shutdown()
  }

  it should "400 invalid_cursor before bound resolution on a malformed cursor" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        h.baseUrl + diffUrl(SecurityFixtures.TenantId, DuckLakeTenantDb, "?from=1&to=2&cursor=abc"),
        Some(token)
      )
      withClue(s"superuser malformed cursor /data-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_cursor")
      }
    finally h.shutdown()
  }

  it should "422 invalid_snapshot from bound resolution on the harness's empty catalog" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        h.baseUrl + diffUrl(SecurityFixtures.TenantId, DuckLakeTenantDb),
        Some(token)
      )
      // The harness wires a no-snapshot reader stub (empty catalog): `from=1` resolves via
      // SnapshotSelector to EmptyCatalog -> 422 invalid_snapshot. Not 401/403: the gate was
      // cleared and the real data-diff handler was reached.
      withClue(s"superuser -> own-tenant /data-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 422
        errorCode(resp.body()) shouldBe Some("invalid_snapshot")
      }
    finally h.shutdown()
  }
