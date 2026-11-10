// src/test/scala/ai/starlake/quack/security/TenantPolicyVersionSpec.scala
package ai.starlake.quack.security

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `GET /api/tenant/list` reports each tenant's `policy_version`, inside the usual tenant scoping.
  */
class TenantPolicyVersionSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val Acme   = SecurityFixtures.TenantId
  private val Globex = SecurityFixtures.GlobexTenantId

  private def tenantsOf(body: String): Map[String, Json] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("tenants").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(j => j.hcursor.get[String]("id").toOption.map(_ -> j))
      .toMap

  private def versionOf(tenant: Json): Option[Long] =
    tenant.hcursor.get[Long]("policyVersion").toOption

  "GET /api/tenant/list" should "return the stored policy version of each tenant" in {
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    fix.store.bumpPolicyVersion(Acme)
    fix.store.bumpPolicyVersion(Globex)
    val stored = fix.store.policyVersions()
    val h      = ManagerServerHarness.boot(fix.store)
    try
      val token =
        h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
      withClue(resp.body()) {
        resp.statusCode() shouldBe 200
        val tenants = tenantsOf(resp.body())
        versionOf(tenants(Acme)) shouldBe stored.get(Acme)
        versionOf(tenants(Globex)) shouldBe stored.get(Globex)
      }
    finally h.shutdown()
  }

  it should "report a higher version after a policy change" in {
    val fix = SecurityFixtures.freshStore()
    fix.store.bumpPolicyVersion(Acme)
    val h = ManagerServerHarness.boot(fix.store)
    try
      val token =
        h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      def current: Option[Long] =
        val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
        tenantsOf(resp.body()).get(Acme).flatMap(versionOf)
      val before = current
      fix.store.bumpPolicyVersion(Acme)
      val after = current
      before shouldBe defined
      after shouldBe defined
      after.get should be > before.get
    finally h.shutdown()
  }

  it should "show a tenant admin only the versions of the tenants they manage" in {
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    fix.store.bumpPolicyVersion(Acme)
    val h = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(Acme)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
      withClue(resp.body()) {
        resp.statusCode() shouldBe 200
        val tenants = tenantsOf(resp.body())
        tenants.keySet shouldBe Set(Acme)
        versionOf(tenants(Acme)) shouldBe fix.store.policyVersions().get(Acme)
      }
    finally h.shutdown()
  }
