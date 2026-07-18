// src/test/scala/ai/starlake/quack/security/RestoreAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.{Pool, RoleDistribution, TenantDb, TenantDbKind}
import ai.starlake.quack.security.SecurityFixtures.{addTenantB, GlobexTenantId}
import ai.starlake.quack.security.SecurityFixtures.GlobexTenantDbName as GlobexTenantDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** AuthZ contract of the restore REST surface (Spec 04).
  *
  * `POST /api/catalog/restore` must sit behind
  * [[ai.starlake.quack.ondemand.ManagerServer.apiKeyGuard]] and the handler-level
  * [[ai.starlake.quack.ondemand.api.TenantScopeCheck]] gate, exactly like [[UndropAuthzSpec]]'s
  * POST. Pins: credential-less 401, cross-tenant 403 tenant_forbidden, 404 on an unknown tenant (no
  * existence leak), superuser pass-through to the handler (invalid_kind on an InMemory tenant-db),
  * and the request reaching the live-table lookup (404 not_found on the harness's reader stub,
  * which reports no live tables).
  */
class RestoreAuthzSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val DuckLakeTenantDbId = "td-acmedl01"
  private val DuckLakeTenantDb   = "acme_dl"

  private def addFixtures(fix: SecurityFixtures.Fixture): Unit =
    val s = fix.store
    addTenantB(fix)
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
        dataPath = "/tmp/qod-restore-authz-test"
      )
    )
    s.upsertPool(
      Pool(
        id = "p-dlbi0002",
        tenantId = SecurityFixtures.TenantId,
        tenantDbId = DuckLakeTenantDbId,
        name = "dlbi",
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1)
      )
    )

  private def boot(
      staticApiKey: Option[String] = None
  ): (ManagerServerHarness.Harness, SecurityFixtures.Fixture) =
    val fix = SecurityFixtures.freshStore()
    addFixtures(fix)
    (ManagerServerHarness.boot(fix.store, staticApiKey = staticApiKey), fix)

  private def restoreBody(tenant: String, tenantDb: String): String =
    s"""{"tenant":"$tenant","tenantDb":"$tenantDb","schema":"main","table":"region","to":"5"}"""

  "restore endpoint" should "reject a credential-less POST with 401" in {
    val (h, _) = boot(staticApiKey = Some("static-key-for-restore-authz"))
    try
      val resp = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/restore",
        restoreBody(SecurityFixtures.TenantId, DuckLakeTenantDb)
      )
      withClue(s"credential-less POST restore body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin restoring in tenant-B with 403 tenant_forbidden" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/restore",
        restoreBody(GlobexTenantId, GlobexTenantDb),
        Some(token)
      )
      withClue(s"tenant-A admin -> tenant-B POST /restore body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("tenant_forbidden")
      }
    finally h.shutdown()
  }

  it should "404 an unknown tenant without leaking existence" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/restore",
        restoreBody("nope", "whatever_db"),
        Some(token)
      )
      withClue(s"superuser -> unknown tenant POST /restore body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
      }
    finally h.shutdown()
  }

  it should "let a superuser reach the handler (invalid_kind on an InMemory tenant-db)" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/restore",
        restoreBody(GlobexTenantId, GlobexTenantDb),
        Some(token)
      )
      withClue(s"superuser -> tenant-B POST /restore body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_kind")
      }
    finally h.shutdown()
  }

  it should "reach the live-table lookup for a superuser (404 on the harness's empty catalog)" in {
    val (h, _) = boot()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = post(
        h.httpClient,
        h.baseUrl + "/api/catalog/restore",
        restoreBody(SecurityFixtures.TenantId, DuckLakeTenantDb),
        Some(token)
      )
      // The harness wires a reader stub whose currentTableInfo always reports None: the
      // request clears the gate and the name screen, then 404s at the live-table lookup.
      // Not 401/403: handler reached.
      withClue(s"superuser -> own-tenant POST /restore body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
        resp.body() should include("not live")
      }
    finally h.shutdown()
  }
