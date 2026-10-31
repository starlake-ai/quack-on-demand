package ai.starlake.quack.security

import ai.starlake.quack.model.{Pool, RoleDistribution}
import ai.starlake.quack.ondemand.state.PoolPermission
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `GET /api/profile/pools` through the real guard: the path is on the profile allowlist, so a
  * non-admin session reaches it, and the body carries exactly the four fields of each pool the
  * caller may use.
  */
class ProfilePoolsRestSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  "GET /api/profile/pools" should "admit a non-admin session and list the pools it may use" in {
    val fix = SecurityFixtures.freshStore()
    // bob, a plain acme user, is granted `sales`; `ops`, beside it, he is not.
    fix.store.insertPoolPermission(
      PoolPermission(
        id = "pp-bob-sales",
        tenantId = SecurityFixtures.TenantId,
        poolId = Some(SecurityFixtures.PoolId),
        userId = Some(fix.bobUserId)
      )
    )
    fix.store.upsertPool(
      Pool(
        id = "p-ops0001",
        tenantId = SecurityFixtures.TenantId,
        tenantDbId = SecurityFixtures.TenantDbId,
        name = "ops",
        size = 1,
        distribution = RoleDistribution(0, 0, 1)
      )
    )
    val h = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/profile/pools", Some(token))
      withClue(s"GET /api/profile/pools status=${resp.statusCode()} body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        resp.body() shouldBe
          """{"pools":[{"tenant":"acme","tenantDb":"acme_main","pool":"sales","status":"ready"}]}"""
      }
    finally h.shutdown()
  }
