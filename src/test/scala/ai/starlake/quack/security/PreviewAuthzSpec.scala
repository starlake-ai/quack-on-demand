// src/test/scala/ai/starlake/quack/security/PreviewAuthzSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.api.CatalogPreviewHandlers
import cats.effect.IO
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** AuthZ contract of the time-travel preview + schema-diff REST surface (Spec 00, Task 5).
  *
  * The preview and schema-diff endpoints live under `/api/catalog/.../preview` and
  * `/api/catalog/.../schema-diff` and must sit behind
  * [[ai.starlake.quack.ondemand.ManagerServer.apiKeyGuard]] (not in the public-path list) and
  * behind the handler-level [[ai.starlake.quack.ondemand.api.TenantScopeCheck]] gate, exactly like
  * [[TagAuthzSpec]] and [[CatalogAuthzSpec]]. This spec pins: credential-less 401, cross-tenant 403
  * tenant_forbidden, superuser pass-through to the handler, 404 on an unknown tenant (no existence
  * leak), the acl_denied case (a tenant principal with no data-plane grant reaches the executor,
  * which denies), and the schema-diff stub's gating (401/403 land the same way even though the
  * handler itself always answers 501 once past the gate).
  *
  * Mirrors [[TagAuthzSpec]]'s harness helpers (copied locally where private).
  */
class PreviewAuthzSpec extends AnyFlatSpec with Matchers:

  private val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  // Tenant-B fixture constants (same shape as TagAuthzSpec's addTenantB), InMemory: the
  // superuser-reaches-handler case relies on the handler's invalid_kind arm firing AFTER the
  // scope gate, same as TagAuthzSpec.
  private val GlobexTenantId   = "t-globex01"
  private val GlobexTenantDbId = "td-globex01"
  private val GlobexTenantDb   = "globex_main"

  // Tenant-A's own DuckLake tenant-db + pool, seeded directly on the store (bypassing
  // PoolSupervisor.createTenantDb / createPool, which would provision a real Postgres
  // database): a data preview needs a DuckLake kind to clear the handler's invalid_kind gate,
  // and a registered pool (even with zero live nodes -- PoolSupervisor.restore() still
  // materializes a PoolState from the row) to clear the no_pool gate and reach the injected
  // executor stub.
  private val DuckLakeTenantDbId = "td-acmedl01"
  private val DuckLakeTenantDb   = "acme_dl"
  private val DuckLakePoolId     = "p-dlbi0001"
  private val DuckLakePoolName   = "dlbi"

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

  /** DuckLake tenant-db + pool under tenant A (acme), so preview calls against it clear the gate
    * (invalid_kind, no_pool) and reach the injected [[CatalogPreviewHandlers.PreviewExecutor]]
    * stub. Nodes are intentionally NOT seeded: `firstPoolKey`'s ReadOnly/Dual preference degrades
    * to "first candidate by name" when no node exists in the snapshot, so an authz-only spec (which
    * never cares about routing) doesn't need a fake [[ai.starlake.quack.model.RunningNode]] too.
    */
  private def addDuckLakeTenantDb(fix: SecurityFixtures.Fixture): Unit =
    val s = fix.store
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
        dataPath = "/tmp/qod-preview-authz-test"
      )
    )
    s.upsertPool(
      Pool(
        id = DuckLakePoolId,
        tenantId = SecurityFixtures.TenantId,
        tenantDbId = DuckLakeTenantDbId,
        name = DuckLakePoolName,
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1)
      )
    )

  // ---------- HTTP helpers (mirroring TagAuthzSpec) -------------------------

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

  private def bootWithFixtures(
      staticApiKey: Option[String] = None,
      previewExecutor: CatalogPreviewHandlers.PreviewExecutor = (_, _, _, _) =>
        IO.pure(Left(RouterFailure.Unavailable("no preview executor wired in this harness")))
  ): (ManagerServerHarness.Harness, SecurityFixtures.Fixture) =
    val fix = SecurityFixtures.freshStore()
    addTenantB(fix)
    addDuckLakeTenantDb(fix)
    val h = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      previewExecutor = previewExecutor
    )
    (h, fix)

  private def previewUrl(tenant: String, tenantDb: String, schema: String, table: String): String =
    s"/api/catalog/tenant/$tenant/database/$tenantDb/schemas/$schema/tables/$table/preview"

  private def schemaDiffUrl(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String
  ): String =
    s"/api/catalog/tenant/$tenant/database/$tenantDb/schemas/$schema/tables/$table/schema-diff" +
      "?from=1&to=2"

  // ---------- Spec: preview -------------------------------------------------

  "preview endpoint" should "reject a credential-less GET with 401" in {
    val (h, _) = bootWithFixtures(staticApiKey = Some("static-key-for-preview-authz"))
    try
      val resp = get(
        h.httpClient,
        h.baseUrl + previewUrl(
          SecurityFixtures.TenantId,
          DuckLakeTenantDb,
          "main",
          "region"
        )
      )
      withClue(s"credential-less GET preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin previewing tenant-B's table with 403 tenant_forbidden" in {
    val (h, _) = bootWithFixtures()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = get(
        h.httpClient,
        h.baseUrl + previewUrl(GlobexTenantId, GlobexTenantDb, "main", "region"),
        apiKey = Some(token)
      )
      expectForbidden(resp, "tenant-A admin -> tenant-B /preview")
    finally h.shutdown()
  }

  it should "let a superuser reach the handler on any tenant" in {
    val (h, _) = bootWithFixtures()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        h.baseUrl + previewUrl(GlobexTenantId, GlobexTenantDb, "main", "region"),
        apiKey = Some(token)
      )
      // globex_main is an InMemory tenant-db, so a superuser who passes the scope gate hits
      // the handler's invalid_kind (400) arm -- proves the handler was reached: NOT 401/403.
      withClue(s"superuser -> tenant-B /preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_kind")
      }
    finally h.shutdown()
  }

  it should "404 a preview on an unknown tenant without leaking existence" in {
    val (h, _) = bootWithFixtures()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        h.baseUrl + previewUrl("nope", "whatever_db", "main", "region"),
        apiKey = Some(token)
      )
      withClue(s"superuser -> unknown tenant /preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 404
        errorCode(resp.body()) shouldBe Some("not_found")
      }
    finally h.shutdown()
  }

  it should "403 acl_denied when the executor denies the tenant principal's own-tenant preview" in {
    // bob (SecurityFixtures' no-grant tenant user) cannot even mint a UI session: the
    // manager's login gate is admin-only (see AuthHandlers.login's admin_required arm), so a
    // plain "user" role never reaches the preview endpoint at all via the normal login path.
    // alice (tenant-A admin) mints fine and clears TenantScopeCheck on her own tenant; her
    // REAL role permission would pass a real ACL check, but this case wires a stub executor
    // that unconditionally denies -- standing in for a real FlightSqlRouter.execute call whose
    // data-plane grant doesn't cover this table (e.g. a column/row policy, or a pool-permission
    // gap distinct from the RBAC role grant alice holds). The point under test is CatalogPreviewHandlers'
    // RouterFailure.AccessDenied -> 403 acl_denied mapping, which is executor-outcome-driven and
    // therefore independent of alice's actual permission graph.
    val deniedExecutor: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _, _) => IO.pure(Left(RouterFailure.AccessDenied("access denied: no grant")))
    val (h, _) = bootWithFixtures(previewExecutor = deniedExecutor)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = get(
        h.httpClient,
        h.baseUrl + previewUrl(SecurityFixtures.TenantId, DuckLakeTenantDb, "main", "region"),
        apiKey = Some(token)
      )
      withClue(s"alice (executor denies) -> own-tenant DuckLake /preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("acl_denied")
      }
    finally h.shutdown()
  }

  it should "let a real query succeed for a principal the executor stub allows (past every gate)" in {
    val allowingExecutor: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _, _) => IO.pure(Right(QueryResult(emptyArrowReader(), () => (), "node-1", 1L)))
    val (h, _) = bootWithFixtures(previewExecutor = allowingExecutor)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        h.baseUrl + previewUrl(SecurityFixtures.TenantId, DuckLakeTenantDb, "main", "region"),
        apiKey = Some(token)
      )
      withClue(s"superuser -> own-tenant DuckLake /preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  // ---------- Spec: schema-diff (Task 6 stub) -------------------------------

  "schema-diff endpoint" should "reject a credential-less GET with 401" in {
    val (h, _) = bootWithFixtures(staticApiKey = Some("static-key-for-schemadiff-authz"))
    try
      val resp = get(
        h.httpClient,
        h.baseUrl + schemaDiffUrl(SecurityFixtures.TenantId, DuckLakeTenantDb, "main", "region")
      )
      withClue(s"credential-less GET schema-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "reject a tenant-A admin diffing tenant-B's table with 403 tenant_forbidden" in {
    val (h, _) = bootWithFixtures()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = get(
        h.httpClient,
        h.baseUrl + schemaDiffUrl(GlobexTenantId, GlobexTenantDb, "main", "region"),
        apiKey = Some(token)
      )
      expectForbidden(resp, "tenant-A admin -> tenant-B /schema-diff")
    finally h.shutdown()
  }

  it should "reach the real handler for a superuser once past the gate" in {
    val (h, _) = bootWithFixtures()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        h.baseUrl + schemaDiffUrl(SecurityFixtures.TenantId, DuckLakeTenantDb, "main", "region"),
        apiKey = Some(token)
      )
      // The harness wires a no-snapshot reader stub (empty catalog): `from=1` resolves via
      // SnapshotSelector to EmptyCatalog -> 422 invalid_snapshot. That 422 (not 401/403) proves
      // the gate was cleared and the real schema-diff handler was reached.
      withClue(s"superuser -> own-tenant /schema-diff body: ${resp.body()}") {
        resp.statusCode() shouldBe 422
        errorCode(resp.body()) shouldBe Some("invalid_snapshot")
      }
    finally h.shutdown()
  }

  /** Minimal empty Arrow stream: zero columns, zero rows. Only used by the "executor allows" case
    * above, which asserts on the HTTP status, not the decoded payload shape.
    */
  private def emptyArrowReader(): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new org.apache.arrow.memory.RootAllocator()
    val schema    = new org.apache.arrow.vector.types.pojo.Schema(java.util.List.of())
    val root      = org.apache.arrow.vector.VectorSchemaRoot.create(schema, allocator)
    val out       = new java.io.ByteArrayOutputStream()
    val writer    = new org.apache.arrow.vector.ipc.ArrowStreamWriter(root, null, out)
    writer.start()
    root.setRowCount(0)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new org.apache.arrow.vector.ipc.ArrowStreamReader(
      new java.io.ByteArrayInputStream(out.toByteArray),
      allocator
    )
