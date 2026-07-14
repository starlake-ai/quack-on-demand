// src/test/scala/ai/starlake/quack/security/UndropAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** AuthZ contract of the undrop REST surface (Spec 03).
  *
  * `GET /api/catalog/tenant/{t}/database/{db}/recoverable` and `POST /api/catalog/undrop` must sit
  * behind [[ai.starlake.quack.ondemand.ManagerServer.apiKeyGuard]] and the handler-level
  * [[ai.starlake.quack.ondemand.api.TenantScopeCheck]] gate, exactly like [[DataDiffAuthzSpec]]'s
  * surface. Pins: credential-less 401 on both verbs, cross-tenant 403 tenant_forbidden on both, 404
  * on an unknown tenant (no existence leak), superuser pass-through to the handler (invalid_kind on
  * an InMemory tenant-db), and the POST reaching the dropped-table lookup (404 not_found on the
  * harness's empty catalog).
  */
class UndropAuthzSpec extends AnyFlatSpec with Matchers:

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
        dataPath = "/tmp/qod-undrop-authz-test"
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

  private def post(
      client: HttpClient,
      url: String,
      body: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .header("Content-Type", "application/json")
      .timeout(RequestTimeout)
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

  private def recoverableUrl(tenant: String, tenantDb: String): String =
    s"/api/catalog/tenant/$tenant/database/$tenantDb/recoverable"

  private def undropBody(tenant: String, tenantDb: String): String =
    s"""{"tenant":"$tenant","tenantDb":"$tenantDb","schema":"main","table":"region"}"""

  "recoverable endpoint" should "reject a credential-less GET with 401" in {
    val (h, _) = boot(staticApiKey = Some("static-key-for-undrop-authz"))
    try
      val resp =
        get(h.httpClient, h.baseUrl + recoverableUrl(SecurityFixtures.TenantId, DuckLakeTenantDb))
      withClue(s"credential-less GET recoverable body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin listing tenant-B's recoverables with 403" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp =
        get(h.httpClient, h.baseUrl + recoverableUrl(GlobexTenantId, GlobexTenantDb), Some(token))
      withClue(s"tenant-A admin -> tenant-B /recoverable body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("tenant_forbidden")
      }
    finally h.shutdown()
  }

  it should "404 an unknown tenant without leaking existence" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, h.baseUrl + recoverableUrl("nope", "whatever_db"), Some(token))
      withClue(s"superuser -> unknown tenant /recoverable body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
      }
    finally h.shutdown()
  }

  it should "let a superuser reach the handler (invalid_kind on an InMemory tenant-db)" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  =
        get(h.httpClient, h.baseUrl + recoverableUrl(GlobexTenantId, GlobexTenantDb), Some(token))
      withClue(s"superuser -> tenant-B /recoverable body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_kind")
      }
    finally h.shutdown()
  }

  "undrop endpoint" should "reject a credential-less POST with 401" in {
    val (h, _) = boot(staticApiKey = Some("static-key-for-undrop-authz"))
    try
      val resp = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/undrop",
        undropBody(SecurityFixtures.TenantId, DuckLakeTenantDb)
      )
      withClue(s"credential-less POST undrop body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin undropping in tenant-B with 403 tenant_forbidden" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/undrop",
        undropBody(GlobexTenantId, GlobexTenantDb),
        Some(token)
      )
      withClue(s"tenant-A admin -> tenant-B POST /undrop body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("tenant_forbidden")
      }
    finally h.shutdown()
  }

  it should "reach the dropped-table lookup for a superuser (404 on the harness's empty catalog)" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/undrop",
        undropBody(SecurityFixtures.TenantId, DuckLakeTenantDb),
        Some(token)
      )
      // The harness wires a reader stub with no dropped tables: the request clears the gate and
      // the name screen, then 404s at the dropped-table lookup. Not 401/403: handler reached.
      withClue(s"superuser -> own-tenant POST /undrop body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
        resp.body() should include("no dropped table")
      }
    finally h.shutdown()
  }
