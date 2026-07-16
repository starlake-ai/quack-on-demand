// src/test/scala/ai/starlake/quack/security/StatementHistoryScopeSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.StatementRecord
import ai.starlake.quack.security.SecurityFixtures.GlobexTenantId
import ai.starlake.quack.security.SecurityFixtures.GlobexTenantName as GlobexName
import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Cross-tenant authZ on `GET /api/node/statements`.
  *
  * Before the fix the endpoint returned every recorded SQL (user / tenant / pool / sql) to any
  * caller that cleared `apiKeyGuard`, including a tenant-scoped admin who could see every other
  * tenant's queries. The fix filters by `manageableTenants`. This spec pins down:
  *   - superuser sees all
  *   - tenant-A admin sees only A's rows
  *   - static-key bypass sees all
  *   - open mode sees all
  *   - records carrying either the id or the display-name form pass the filter (mirrors the
  *     FlightSQL handshake convention; the router records the display name today but future changes
  *     might switch)
  */
class StatementHistoryScopeSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private def seed(h: ManagerServerHarness.Harness): Unit =
    // Two records per tenant: one carrying the display-name form (today's
    // router output) and one carrying the id form (future-proofing).
    val now = Instant.parse("2026-06-12T10:00:00Z")
    List(
      StatementRecord(
        now,
        "alice",
        SecurityFixtures.TenantName,
        "sales",
        "n1",
        "SELECT 1 FROM acme_t",
        12L,
        "ok",
        None
      ),
      StatementRecord(
        now,
        "alice",
        SecurityFixtures.TenantId,
        "sales",
        "n1",
        "SELECT 2 FROM acme_t",
        13L,
        "ok",
        None
      ),
      StatementRecord(
        now,
        "carol",
        GlobexName,
        "ops",
        "n2",
        "SELECT 3 FROM globex_t",
        14L,
        "ok",
        None
      ),
      StatementRecord(
        now,
        "carol",
        GlobexTenantId,
        "ops",
        "n2",
        "SELECT 4 FROM globex_t",
        15L,
        "ok",
        None
      )
    ).foreach(h.stmtHistory.record)

  private def tenantsOf(body: String): List[String] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("statements").as[List[io.circe.Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("tenant").toOption)

  private def bootTwoTenants(staticApiKey: Option[String] = None) =
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    val h = ManagerServerHarness.boot(fix.store, staticApiKey = staticApiKey)
    seed(h)
    (h, fix)

  "GET /api/node/statements" should "return all rows for a superuser session" in {
    val (h, _) = bootTwoTenants()
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/node/statements", apiKey = Some(token))
      withClue(s"superuser body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        tenantsOf(resp.body()).toSet shouldBe Set(
          SecurityFixtures.TenantName,
          SecurityFixtures.TenantId,
          GlobexName,
          GlobexTenantId
        )
      }
    finally h.shutdown()
  }

  it should "only return tenant-A rows for alice (tenant-A admin)" in {
    val (h, _) = bootTwoTenants()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/node/statements", apiKey = Some(token))
      withClue(s"alice body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val ts = tenantsOf(resp.body()).toSet
        // Post slug-id refactor the canonical fixture's display-name and id are
        // the same slug ("acme"), so tenant-A has one form here; tenant-B keeps
        // its two distinct forms below for the exclusion check.
        ts should contain(SecurityFixtures.TenantId)
        ts should not contain GlobexName
        ts should not contain GlobexTenantId
      }
    finally h.shutdown()
  }

  it should "return all rows for a static-key call" in {
    val (h, _) = bootTwoTenants(staticApiKey = Some("static-key"))
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/node/statements", apiKey = Some("static-key"))
      withClue(s"static-key body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        tenantsOf(resp.body()).size shouldBe 4
      }
    finally h.shutdown()
  }

  it should "return all rows in open mode (no api key configured)" in {
    val (h, _) = bootTwoTenants(staticApiKey = None)
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/node/statements")
      withClue(s"open-mode body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        tenantsOf(resp.body()).size shouldBe 4
      }
    finally h.shutdown()
  }
