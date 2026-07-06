// src/test/scala/ai/starlake/quack/security/UsageScopeSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.telemetry.StatementEvent
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant

/** Tenant-scoped authZ on `GET /api/usage`.
  *
  * Seeds four statement events across two tenants and recomputes daily rollups:
  *   - E1: acme / sales / alice / ok / 100ms at d01+1h
  *   - E2: acme / sales / alice / denied / 50ms at d01+2h
  *   - E3: acme / sales / bob / ok / 200ms at d02+1h
  *   - E4: t-globex01 / analytics / carol / ok / 500ms at d01+1h
  *
  * Expected rollup totals:
  *   - acme: 3 stmts, 0 errors, 1 denied, 350 engineMs (d01: 2 stmts 150ms; d02: 1 stmt 200ms)
  *   - t-globex01: 1 stmt, 0 errors, 0 denied, 500 engineMs (d01: 1 stmt 500ms)
  *
  * Query window for data tests: from=2026-07-01T00:00:00Z to=2026-07-03T00:00:00Z
  *
  * Invariants tested:
  *   1. Superuser, no groupBy: defaults to "tenant"; two groups; globex first; acme totals exact;
  *      days arrays correct; pool/username null on every group.
  *   2. Superuser groupBy=pool with tenant filter: one group (acme, sales).
  *   3. Superuser groupBy=user with tenant filter: bob (200ms) then alice (150ms); username set.
  *   4. Tenant admin (alice) pinned to acme: sees only acme; cross-tenant ?tenant= silently
  *      narrowed.
  *   5. Tenant admin groupBy=user: acme users only.
  *   6. Static-key caller: sees both tenants.
  *   7. Validation: invalid groupBy -> 400 invalid_group_by; invalid from -> 400 invalid_time.
  *   8. Month defaults: no from/to/groupBy -> 200; groupBy=="tenant"; from/to match first-of-month
  *      regex.
  *   9. dataStart + empty period: range outside data -> groups=[]; dataStart==d01 (superuser +
  *      alice).
  *   10. Ordering: engineMs descending across all groups in test 1.
  */
class UsageScopeSpec extends AnyFlatSpec with Matchers {

  private val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  private val GlobexTenantId = "t-globex01"
  private val AcmeTenantId   = SecurityFixtures.TenantId // "acme"

  private val d01 = Instant.parse("2026-07-01T00:00:00Z")
  private val d02 = Instant.parse("2026-07-02T00:00:00Z")

  private val QueryFrom = "2026-07-01T00:00:00Z"
  private val QueryTo   = "2026-07-03T00:00:00Z"

  // Seeded events
  private val E1 = StatementEvent(
    ts = d01.plusSeconds(3600),
    username = "alice",
    tenant = AcmeTenantId,
    pool = "sales",
    nodeId = "n-001",
    sql = "SELECT 1",
    durationMs = 100L,
    prepareMs = None,
    status = "ok",
    error = None
  )
  private val E2 = StatementEvent(
    ts = d01.plusSeconds(7200),
    username = "alice",
    tenant = AcmeTenantId,
    pool = "sales",
    nodeId = "n-001",
    sql = "INSERT INTO t VALUES (1)",
    durationMs = 50L,
    prepareMs = None,
    status = "denied",
    error = Some("denied")
  )
  private val E3 = StatementEvent(
    ts = d02.plusSeconds(3600),
    username = "bob",
    tenant = AcmeTenantId,
    pool = "sales",
    nodeId = "n-001",
    sql = "SELECT 2",
    durationMs = 200L,
    prepareMs = None,
    status = "ok",
    error = None
  )
  private val E4 = StatementEvent(
    ts = d01.plusSeconds(3600),
    username = "carol",
    tenant = GlobexTenantId,
    pool = "analytics",
    nodeId = "n-002",
    sql = "SELECT 3",
    durationMs = 500L,
    prepareMs = None,
    status = "ok",
    error = None
  )

  private def addGlobex(
      store: ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
  ): Unit =
    store.upsertTenant(
      Tenant(id = GlobexTenantId, displayName = "globex", authProvider = "db")
    )

  private def seedAll(ts: RecordingTelemetryStore): Unit = {
    ts.appendStatements(List(E1, E2, E3, E4))
    ts.recomputeRollups(None, d02.plusSeconds(7200))
  }

  private def get(
      client: HttpClient,
      url: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] = {
    val b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(RequestTimeout)
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())
  }

  // --- Usage JSON helpers ---

  private def groupsOf(body: String): List[io.circe.Json] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("groups").as[List[io.circe.Json]].toOption)
      .getOrElse(Nil)

  private def groupByOf(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("groupBy").toOption)

  private def fromFieldOf(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("from").toOption)

  private def toFieldOf(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("to").toOption)

  private def dataStartOf(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[Option[String]]("dataStart").toOption.flatten)

  private def errorCodeOf(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("error").toOption)

  private def tenantOf(g: io.circe.Json): Option[String] = g.hcursor.get[String]("tenant").toOption

  private def poolOf(g: io.circe.Json): Option[Option[String]] =
    g.hcursor.get[Option[String]]("pool").toOption

  private def usernameOf(g: io.circe.Json): Option[Option[String]] =
    g.hcursor.get[Option[String]]("username").toOption

  private def statementsOf(g: io.circe.Json): Option[Long] =
    g.hcursor.get[Long]("statements").toOption

  private def errorsOf(g: io.circe.Json): Option[Long] =
    g.hcursor.get[Long]("errors").toOption

  private def deniedOf(g: io.circe.Json): Option[Long] =
    g.hcursor.get[Long]("denied").toOption

  private def engineMsOf(g: io.circe.Json): Option[Long] =
    g.hcursor.get[Long]("engineMs").toOption

  private def daysOf(g: io.circe.Json): List[io.circe.Json] =
    g.hcursor.downField("days").as[List[io.circe.Json]].getOrElse(Nil)

  private def bootHarness(staticApiKey: Option[String] = None) = {
    val ts  = new RecordingTelemetryStore
    val fix = SecurityFixtures.freshStore()
    addGlobex(fix.store)
    val h = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      telemetryStore = ts
    )
    seedAll(ts)
    (h, fix, ts)
  }

  // -------------------------------------------------------------------------
  // Test 1: Superuser, defaults for groupBy
  // -------------------------------------------------------------------------

  "GET /api/usage" should "return two groups sorted by engineMs desc for a superuser with default groupBy" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?from=$QueryFrom&to=$QueryTo",
        apiKey = Some(token)
      )
      withClue(s"superuser default groupBy body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        groupByOf(resp.body()) shouldBe Some("tenant")
        val groups = groupsOf(resp.body())
        groups should have size 2

        // globex first (engineMs 500 > acme 350)
        tenantOf(groups(0)) shouldBe Some(GlobexTenantId)
        tenantOf(groups(1)) shouldBe Some(AcmeTenantId)

        // acme totals
        val acme = groups(1)
        statementsOf(acme) shouldBe Some(3L)
        errorsOf(acme) shouldBe Some(0L)
        deniedOf(acme) shouldBe Some(1L)
        engineMsOf(acme) shouldBe Some(350L)

        // acme days: d01 then d02 (ascending)
        val days = daysOf(acme)
        days should have size 2
        days(0).hcursor.get[String]("day").toOption shouldBe Some(d01.toString)
        days(0).hcursor.get[Long]("statements").toOption shouldBe Some(2L)
        days(0).hcursor.get[Long]("engineMs").toOption shouldBe Some(150L)
        days(1).hcursor.get[String]("day").toOption shouldBe Some(d02.toString)
        days(1).hcursor.get[Long]("statements").toOption shouldBe Some(1L)
        days(1).hcursor.get[Long]("engineMs").toOption shouldBe Some(200L)

        // pool and username null on every group (groupBy=tenant)
        groups.foreach { g =>
          poolOf(g) shouldBe Some(None)
          usernameOf(g) shouldBe Some(None)
        }
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 2: Superuser groupBy=pool with tenant filter
  // -------------------------------------------------------------------------

  it should "return exactly one group for groupBy=pool with tenant=acme" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?groupBy=pool&tenant=$AcmeTenantId&from=$QueryFrom&to=$QueryTo",
        apiKey = Some(token)
      )
      withClue(s"groupBy=pool body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val groups = groupsOf(resp.body())
        groups should have size 1
        tenantOf(groups(0)) shouldBe Some(AcmeTenantId)
        poolOf(groups(0)) shouldBe Some(Some("sales"))
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 3: Superuser groupBy=user
  // -------------------------------------------------------------------------

  it should "return groups for bob then alice (engineMs desc) for groupBy=user on acme" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?groupBy=user&tenant=$AcmeTenantId&from=$QueryFrom&to=$QueryTo",
        apiKey = Some(token)
      )
      withClue(s"groupBy=user body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        groupByOf(resp.body()) shouldBe Some("user")
        val groups = groupsOf(resp.body())
        groups should have size 2
        // bob first (200ms > 150ms)
        usernameOf(groups(0)) shouldBe Some(Some("bob"))
        engineMsOf(groups(0)) shouldBe Some(200L)
        usernameOf(groups(1)) shouldBe Some(Some("alice"))
        engineMsOf(groups(1)) shouldBe Some(150L)
        // pool is null for groupBy=user
        groups.foreach(g => poolOf(g) shouldBe Some(None))
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 4: Tenant admin pinned to acme
  // -------------------------------------------------------------------------

  it should "pin tenant-admin alice to acme and silently narrow a cross-tenant ?tenant= request" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )

      // Direct request without tenant filter: only acme visible
      val resp1 = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?groupBy=tenant&from=$QueryFrom&to=$QueryTo",
        apiKey = Some(token)
      )
      withClue(s"alice no-filter body: ${resp1.body()}") {
        resp1.statusCode() shouldBe 200
        val tenants = groupsOf(resp1.body()).flatMap(tenantOf)
        tenants shouldBe List(AcmeTenantId)
      }

      // Cross-tenant request: silently narrowed to acme, HTTP 200
      val resp2 = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?groupBy=tenant&tenant=$GlobexTenantId&from=$QueryFrom&to=$QueryTo",
        apiKey = Some(token)
      )
      withClue(s"alice cross-tenant body: ${resp2.body()}") {
        resp2.statusCode() shouldBe 200
        val tenants = groupsOf(resp2.body()).flatMap(tenantOf)
        tenants shouldBe List(AcmeTenantId)
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 5: Tenant admin groupBy=user
  // -------------------------------------------------------------------------

  it should "show only acme users for alice with groupBy=user" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?groupBy=user&from=$QueryFrom&to=$QueryTo",
        apiKey = Some(token)
      )
      withClue(s"alice groupBy=user body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val groups = groupsOf(resp.body())
        groups should not be empty
        // All groups are scoped to acme
        groups.foreach(g => tenantOf(g) shouldBe Some(AcmeTenantId))
        // carol (globex user) is absent
        groups.flatMap(usernameOf).flatten should not contain "carol"
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 6: Static-key caller sees both tenants
  // -------------------------------------------------------------------------

  it should "return both tenants for a static-key caller" in {
    val (h, _, _) = bootHarness(staticApiKey = Some("test-static-key"))
    try {
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?from=$QueryFrom&to=$QueryTo",
        apiKey = Some("test-static-key")
      )
      withClue(s"static-key body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val tenants = groupsOf(resp.body()).flatMap(tenantOf).toSet
        tenants should contain(AcmeTenantId)
        tenants should contain(GlobexTenantId)
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 7: Validation
  // -------------------------------------------------------------------------

  it should "return 400 invalid_group_by for an unknown groupBy value" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?groupBy=bogus",
        apiKey = Some(token)
      )
      withClue(s"invalid groupBy body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCodeOf(resp.body()) shouldBe Some("invalid_group_by")
      }
    } finally h.shutdown()
  }

  it should "return 400 invalid_time for an invalid ?from= value" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?from=not-a-date",
        apiKey = Some(token)
      )
      withClue(s"invalid from body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCodeOf(resp.body()) shouldBe Some("invalid_time")
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 8: Month defaults (no from/to/groupBy)
  // -------------------------------------------------------------------------

  it should "default groupBy to tenant and from/to to the current calendar month (UTC)" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage",
        apiKey = Some(token)
      )
      withClue(s"defaults body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        groupByOf(resp.body()) shouldBe Some("tenant")
        val firstOfMonthRe = "^\\d{4}-\\d{2}-01T00:00:00Z$".r
        val fromStr        = fromFieldOf(resp.body())
        val toStr          = toFieldOf(resp.body())
        fromStr shouldBe defined
        toStr shouldBe defined
        firstOfMonthRe.findFirstIn(fromStr.get) shouldBe defined
        firstOfMonthRe.findFirstIn(toStr.get) shouldBe defined
        Instant.parse(fromStr.get).isBefore(Instant.parse(toStr.get)) shouldBe true
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 9: dataStart and empty period
  // -------------------------------------------------------------------------

  it should "return empty groups but dataStart for a query period with no data (superuser)" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?from=2025-01-01T00:00:00Z&to=2025-02-01T00:00:00Z",
        apiKey = Some(token)
      )
      withClue(s"empty period body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        groupsOf(resp.body()) shouldBe empty
        dataStartOf(resp.body()) shouldBe Some(d01.toString)
      }
    } finally h.shutdown()
  }

  it should "return dataStart == d01 for alice (acme scope) on an empty period" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?from=2025-01-01T00:00:00Z&to=2025-02-01T00:00:00Z",
        apiKey = Some(token)
      )
      withClue(s"alice empty period body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        groupsOf(resp.body()) shouldBe empty
        dataStartOf(resp.body()) shouldBe Some(d01.toString)
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Test 10: Ordering (engineMs descending)
  // -------------------------------------------------------------------------

  it should "return groups in non-increasing engineMs order" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/usage?from=$QueryFrom&to=$QueryTo",
        apiKey = Some(token)
      )
      withClue(s"ordering body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val engineMsList = groupsOf(resp.body()).flatMap(engineMsOf)
        engineMsList should have size 2
        // Non-increasing: each value <= the previous
        engineMsList.zip(engineMsList.tail).foreach { case (a, b) =>
          a should be >= b
        }
      }
    } finally h.shutdown()
  }
}
