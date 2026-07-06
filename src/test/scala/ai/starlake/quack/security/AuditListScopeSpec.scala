// src/test/scala/ai/starlake/quack/security/AuditListScopeSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.telemetry.AuditEvent
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant

/** Tenant-scoped authZ on `GET /api/audit/list`.
  *
  * Seeds three events:
  *   - e1: tenant = Some(acme) (tenant-A)
  *   - e2: tenant = Some(t-globex01) (tenant-B)
  *   - e3: tenant = None, family = "auth" (system-level event)
  *
  * Then pins seven invariants:
  *   1. Superuser sees all 3 events newest-first.
  *   2. Tenant-A admin sees only e1 (no globex, no null-tenant).
  *   3. Tenant-A admin with `?tenant=t-globex01` is pinned back to acme (cross-tenant no-leak).
  *   4. Static-key caller sees all 3 (superuser semantics).
  *   5. Superuser with `?family=auth` sees only e3.
  *   6. `limit=1` + `before=<nextBefore>` pages through without overlap.
  *   7. Invalid `?from=not-a-date` returns 400 with code `invalid_time`.
  */
class AuditListScopeSpec extends AnyFlatSpec with Matchers:

  private val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  private val GlobexTenantId = "t-globex01"
  private val AcmeTenantId   = SecurityFixtures.TenantId // "acme"

  private val BaseTs = Instant.parse("2026-07-01T10:00:00Z")

  // Three audit events seeded into the recording store.
  private val E1 = AuditEvent(
    ts = BaseTs,
    family = "control-plane",
    actor = "alice",
    actorRealm = "tenant",
    tenant = Some(AcmeTenantId),
    action = "role.create",
    target = Some("r-test"),
    outcome = "ok",
    origin = "rest",
    detail = Map("name" -> "analyst")
  )
  private val E2 = AuditEvent(
    ts = BaseTs.plusSeconds(1),
    family = "control-plane",
    actor = "carol",
    actorRealm = "tenant",
    tenant = Some(GlobexTenantId),
    action = "role.create",
    target = Some("r-globex"),
    outcome = "ok",
    origin = "rest",
    detail = Map("name" -> "ops")
  )
  private val E3 = AuditEvent(
    ts = BaseTs.plusSeconds(2),
    family = "auth",
    actor = "anonymous",
    actorRealm = "system",
    tenant = None,
    action = "auth.login.failure",
    target = None,
    outcome = "denied",
    origin = "rest",
    detail = Map("source" -> "127.0.0.1")
  )

  // Add globex as a second tenant in the control-plane store.
  private def addGlobex(store: ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore): Unit =
    store.upsertTenant(
      Tenant(id = GlobexTenantId, displayName = "globex", authProvider = "db")
    )

  private def seedEvents(ts: RecordingTelemetryStore): Unit =
    ts.appendAudit(List(E1, E2, E3))

  private def get(
      client: HttpClient,
      url: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(RequestTimeout)
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def eventsOf(body: String): List[io.circe.Json] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("events").as[List[io.circe.Json]].toOption)
      .getOrElse(Nil)

  private def tenantsOf(body: String): List[Option[String]] =
    eventsOf(body).map(_.hcursor.get[Option[String]]("tenant").toOption.flatten)

  private def familiesOf(body: String): List[String] =
    eventsOf(body).flatMap(_.hcursor.get[String]("family").toOption)

  private def nextBeforeOf(body: String): Option[String] =
    parse(body).toOption
      .flatMap(_.hcursor.get[Option[String]]("nextBefore").toOption.flatten)

  private def errorCodeOf(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("error").toOption)

  private def bootHarness(staticApiKey: Option[String] = None) =
    val ts  = new RecordingTelemetryStore
    val fix = SecurityFixtures.freshStore()
    addGlobex(fix.store)
    val h = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      telemetryStore = ts
    )
    seedEvents(ts)
    (h, fix, ts)

  "GET /api/audit/list" should "return all 3 events newest-first for a superuser session" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/audit/list", apiKey = Some(token))
      withClue(s"superuser body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val tens = tenantsOf(resp.body())
        tens should have size 3
        // newest-first: e3 (None), e2 (globex), e1 (acme)
        tens.head shouldBe None
        tens(1) shouldBe Some(GlobexTenantId)
        tens(2) shouldBe Some(AcmeTenantId)
      }
    finally h.shutdown()
  }

  it should "return only tenant-A events for alice (no globex, no null-tenant)" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/audit/list", apiKey = Some(token))
      withClue(s"alice body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val tens = tenantsOf(resp.body())
        tens shouldBe List(Some(AcmeTenantId))
      }
    finally h.shutdown()
  }

  it should "pin tenant-A admin back to acme when ?tenant=t-globex01 is requested (no cross-tenant leak)" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/audit/list?tenant=$GlobexTenantId",
        apiKey = Some(token)
      )
      withClue(s"alice + cross-tenant body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val tens = tenantsOf(resp.body())
        // Falls back to full manageable set (acme only) because globex not in manageableTenants
        tens shouldBe List(Some(AcmeTenantId))
      }
    finally h.shutdown()
  }

  it should "return all 3 events for a static-key call (superuser semantics)" in {
    val (h, _, _) = bootHarness(staticApiKey = Some("test-static-key"))
    try
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/audit/list",
        apiKey = Some("test-static-key")
      )
      withClue(s"static-key body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        eventsOf(resp.body()) should have size 3
      }
    finally h.shutdown()
  }

  it should "filter by ?family=auth and return only e3 for superuser" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?family=auth", apiKey = Some(token))
      withClue(s"family=auth body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val fams = familiesOf(resp.body())
        fams shouldBe List("auth")
      }
    finally h.shutdown()
  }

  it should "paginate with limit=1 and before cursor without overlap" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)

      // Page 1: limit=1 -> most recent event (e3)
      val resp1 = get(
        h.httpClient,
        s"${h.baseUrl}/api/audit/list?limit=1",
        apiKey = Some(token)
      )
      withClue(s"page 1 body: ${resp1.body()}") {
        resp1.statusCode() shouldBe 200
        eventsOf(resp1.body()) should have size 1
      }
      val cursor = nextBeforeOf(resp1.body())
      cursor shouldBe defined

      // Page 2: limit=1 + before=cursor -> next event (e2), different from page 1
      val resp2 = get(
        h.httpClient,
        s"${h.baseUrl}/api/audit/list?limit=1&before=${cursor.get}",
        apiKey = Some(token)
      )
      withClue(s"page 2 body: ${resp2.body()}") {
        resp2.statusCode() shouldBe 200
        eventsOf(resp2.body()) should have size 1
      }
      val ids1 = eventsOf(resp1.body()).flatMap(_.hcursor.get[String]("id").toOption)
      val ids2 = eventsOf(resp2.body()).flatMap(_.hcursor.get[String]("id").toOption)
      ids1.intersect(ids2) shouldBe empty
    finally h.shutdown()
  }

  it should "return 400 with code invalid_time for an invalid ?from= value" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/audit/list?from=not-a-date",
        apiKey = Some(token)
      )
      withClue(s"invalid from body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCodeOf(resp.body()) shouldBe Some("invalid_time")
      }
    finally h.shutdown()
  }

  it should "return only acme rows for superuser ?tenant= (null rows no longer mixed in)" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/audit/list?tenant=$AcmeTenantId",
        apiKey = Some(token)
      )
      resp.statusCode() shouldBe 200
      tenantsOf(resp.body()) shouldBe List(Some(AcmeTenantId))
    finally h.shutdown()
  }

  it should "return only null-tenant rows for superuser ?noTenant=true" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?noTenant=true", apiKey = Some(token))
      resp.statusCode() shouldBe 200
      tenantsOf(resp.body()) shouldBe List(None)
    finally h.shutdown()
  }

  it should "let noTenant win when both tenant and noTenant are supplied" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/audit/list?tenant=$AcmeTenantId&noTenant=true",
        apiKey = Some(token)
      )
      resp.statusCode() shouldBe 200
      tenantsOf(resp.body()) shouldBe List(None)
    finally h.shutdown()
  }

  it should "keep a tenant admin pinned to their tenant when ?noTenant=true is requested" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )
      val resp =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?noTenant=true", apiKey = Some(token))
      resp.statusCode() shouldBe 200
      tenantsOf(resp.body()) shouldBe List(Some(AcmeTenantId))
    finally h.shutdown()
  }

  "GET /api/audit/actions" should "return the sorted exhaustive action vocabulary" in {
    val (h, _, _) = bootHarness()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/audit/actions", apiKey = Some(token))
      resp.statusCode() shouldBe 200
      val actions = io.circe.parser
        .parse(resp.body())
        .flatMap(_.hcursor.get[List[String]]("actions"))
        .fold(e => fail(s"bad body: $e"), identity)
      actions shouldBe actions.sorted
      actions.size should be > 40
      actions should contain allOf ("auth.login.failure", "role.create", "sql.denied")
    finally h.shutdown()
  }

  it should "be guarded by the API-key perimeter like audit/list" in {
    val (h, _, _) = bootHarness(staticApiKey = Some("perimeter-key"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/audit/actions", apiKey = None)
      resp.statusCode() shouldBe 401
    finally h.shutdown()
  }
