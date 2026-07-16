// src/test/scala/ai/starlake/quack/security/HistoryScopeSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.telemetry.StatementEvent
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.security.SecurityFixtures.GlobexTenantId
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Tenant-scoped authZ on `GET /api/history/trends` and `GET /api/history/statements`.
  *
  * Seeds four statement events:
  *   - S1: acme / sales / ok / "SELECT * FROM orders"
  *   - S2: acme / sales / denied / "INSERT INTO orders VALUES (1)"
  *   - S3: acme / sales / ok / "SELECT count(*) FROM lineitem"
  *   - S4: t-globex01 / analytics / ok / "SELECT * FROM customers"
  *
  * Rollup buckets are recomputed after seeding so trends queries return real data.
  *
  * Invariants tested:
  *   1. Trends: superuser sees buckets for both tenants (granularity=hour).
  *   2. Trends: tenant-admin (alice) is pinned to acme; cross-tenant ?tenant= silently narrowed to
  *      acme, 200 (no globex bucket exposed).
  *   3. Trends: missing granularity returns 400 invalid_granularity; invalid value returns 400
  *      invalid_granularity; invalid ?from= returns 400 invalid_time.
  *   4. Statements: superuser sees all 4 rows; alice sees only 3 acme rows.
  *   5. Statements: ?status=denied filter works; ?q= substring filter works.
  *   6. Statements: limit=1 paginates without overlap; page-2 ids are strictly older; nextBefore
  *      round-trip produces the next page.
  *   7. Statements: static-key caller sees all 4 rows.
  */
class HistoryScopeSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers {

  private val AcmeTenantId = SecurityFixtures.TenantId // "acme"

  private val BaseTs = Instant.parse("2026-07-01T10:00:00Z")

  // Statements seeded for acme.
  private val S1 = StatementEvent(
    ts = BaseTs,
    username = "alice",
    tenant = AcmeTenantId,
    pool = "sales",
    nodeId = "n-001",
    sql = "SELECT * FROM orders",
    durationMs = 100L,
    prepareMs = None,
    status = "ok",
    error = None
  )
  private val S2 = StatementEvent(
    ts = BaseTs.plusSeconds(1),
    username = "alice",
    tenant = AcmeTenantId,
    pool = "sales",
    nodeId = "n-001",
    sql = "INSERT INTO orders VALUES (1)",
    durationMs = 200L,
    prepareMs = Some(10L),
    status = "denied",
    error = Some("permission denied")
  )
  private val S3 = StatementEvent(
    ts = BaseTs.plusSeconds(2),
    username = "bob",
    tenant = AcmeTenantId,
    pool = "sales",
    nodeId = "n-001",
    sql = "SELECT count(*) FROM lineitem",
    durationMs = 150L,
    prepareMs = None,
    status = "ok",
    error = None
  )

  // Statement seeded for globex.
  private val S4 = StatementEvent(
    ts = BaseTs.plusSeconds(3),
    username = "carol",
    tenant = GlobexTenantId,
    pool = "analytics",
    nodeId = "n-002",
    sql = "SELECT * FROM customers",
    durationMs = 80L,
    prepareMs = None,
    status = "ok",
    error = None
  )

  // Rollup watermark: one hour after BaseTs ensures all events fall within the window.
  private val RollupTs = BaseTs.plusSeconds(3600)

  private def seedAll(ts: RecordingTelemetryStore): Unit = {
    ts.appendStatements(List(S1, S2, S3, S4))
    ts.recomputeRollups(None, RollupTs)
  }

  // --- Trends JSON helpers ---

  private def bucketsOf(body: String): List[io.circe.Json] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("buckets").as[List[io.circe.Json]].toOption)
      .getOrElse(Nil)

  private def bucketTenantsOf(body: String): List[String] =
    bucketsOf(body).flatMap(_.hcursor.get[String]("tenant").toOption)

  // --- Statements JSON helpers ---

  private def statementsOf(body: String): List[io.circe.Json] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("statements").as[List[io.circe.Json]].toOption)
      .getOrElse(Nil)

  private def stmtTenantsOf(body: String): List[String] =
    statementsOf(body).flatMap(_.hcursor.get[String]("tenant").toOption)

  private def stmtStatusesOf(body: String): List[String] =
    statementsOf(body).flatMap(_.hcursor.get[String]("status").toOption)

  private def stmtSqlsOf(body: String): List[String] =
    statementsOf(body).flatMap(_.hcursor.get[String]("sql").toOption)

  private def stmtIdsOf(body: String): List[String] =
    statementsOf(body).flatMap(_.hcursor.get[String]("id").toOption)

  private def nextBeforeOf(body: String): Option[String] =
    parse(body).toOption
      .flatMap(_.hcursor.get[Option[String]]("nextBefore").toOption.flatten)

  private def bootHarness(staticApiKey: Option[String] = None) = {
    val ts  = new RecordingTelemetryStore
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    val h = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      telemetryStore = ts
    )
    seedAll(ts)
    (h, fix, ts)
  }

  // -------------------------------------------------------------------------
  // Trends
  // -------------------------------------------------------------------------

  "GET /api/history/trends" should "return buckets for both tenants for a superuser" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/trends?granularity=hour",
        apiKey = Some(token)
      )
      withClue(s"superuser trends body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val tenants = bucketTenantsOf(resp.body()).toSet
        tenants should contain(AcmeTenantId)
        tenants should contain(GlobexTenantId)
      }
    } finally h.shutdown()
  }

  it should "pin tenant-admin to own tenant even when cross-tenant ?tenant= is requested" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )
      // Explicitly requesting globex should be silently narrowed back to acme.
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/trends?granularity=hour&tenant=$GlobexTenantId",
        apiKey = Some(token)
      )
      withClue(s"alice cross-tenant trends body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val tenants = bucketTenantsOf(resp.body()).toSet
        tenants should contain(AcmeTenantId)
        tenants should not contain GlobexTenantId
      }
    } finally h.shutdown()
  }

  it should "return 400 invalid_granularity when granularity param is absent" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/trends",
        apiKey = Some(token)
      )
      withClue(s"missing granularity body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_granularity")
      }
    } finally h.shutdown()
  }

  it should "return 400 invalid_granularity when granularity value is not hour or day" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/trends?granularity=week",
        apiKey = Some(token)
      )
      withClue(s"invalid granularity body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_granularity")
      }
    } finally h.shutdown()
  }

  it should "return 400 invalid_time for an invalid ?from= value" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/trends?granularity=hour&from=not-a-date",
        apiKey = Some(token)
      )
      withClue(s"invalid from body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) shouldBe Some("invalid_time")
      }
    } finally h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Statements
  // -------------------------------------------------------------------------

  "GET /api/history/statements" should "return all 4 statements for a superuser" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements",
        apiKey = Some(token)
      )
      withClue(s"superuser statements body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        statementsOf(resp.body()) should have size 4
        val tenants = stmtTenantsOf(resp.body()).toSet
        tenants should contain(AcmeTenantId)
        tenants should contain(GlobexTenantId)
      }
    } finally h.shutdown()
  }

  it should "return only acme statements for alice (tenant admin)" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(AcmeTenantId)
      )
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements",
        apiKey = Some(token)
      )
      withClue(s"alice statements body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        statementsOf(resp.body()) should have size 3
        stmtTenantsOf(resp.body()).toSet shouldBe Set(AcmeTenantId)
      }
    } finally h.shutdown()
  }

  it should "filter by ?status=denied and return only denied rows" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements?status=denied",
        apiKey = Some(token)
      )
      withClue(s"status=denied body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val statuses = stmtStatusesOf(resp.body())
        statuses should not be empty
        statuses.forall(_ == "denied") shouldBe true
      }
    } finally h.shutdown()
  }

  it should "filter by ?q= substring match on sql" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements?q=orders",
        apiKey = Some(token)
      )
      withClue(s"q=orders body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val sqls = stmtSqlsOf(resp.body())
        sqls should not be empty
        sqls.forall(_.contains("orders")) shouldBe true
      }
    } finally h.shutdown()
  }

  it should "paginate with limit=1 without overlap and nextBefore round-trip yields strictly older ids" in {
    val (h, _, _) = bootHarness()
    try {
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)

      // Page 1: limit=1 returns the most-recently inserted statement.
      val resp1 = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements?limit=1",
        apiKey = Some(token)
      )
      withClue(s"page 1 body: ${resp1.body()}") {
        resp1.statusCode() shouldBe 200
        statementsOf(resp1.body()) should have size 1
      }
      val cursor = nextBeforeOf(resp1.body())
      cursor shouldBe defined

      // Page 2: limit=1 + before=cursor -> next statement, no id overlap.
      val resp2 = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements?limit=1&before=${cursor.get}",
        apiKey = Some(token)
      )
      withClue(s"page 2 body: ${resp2.body()}") {
        resp2.statusCode() shouldBe 200
        statementsOf(resp2.body()) should have size 1
      }

      val ids1 = stmtIdsOf(resp1.body())
      val ids2 = stmtIdsOf(resp2.body())
      ids1.intersect(ids2) shouldBe empty
      // Page 2 id is strictly older (smaller row id) than page 1 id.
      ids2.head.toLong < ids1.head.toLong shouldBe true
    } finally h.shutdown()
  }

  it should "return all 4 statements for a static-key call (superuser semantics)" in {
    val (h, _, _) = bootHarness(staticApiKey = Some("test-static-key"))
    try {
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements",
        apiKey = Some("test-static-key")
      )
      withClue(s"static-key statements body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        statementsOf(resp.body()) should have size 4
      }
    } finally h.shutdown()
  }
}
