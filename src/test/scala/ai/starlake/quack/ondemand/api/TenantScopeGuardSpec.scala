package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantScopeGuardSpec extends AnyFlatSpec, Matchers:

  import TenantScopeGuard.extractTenant

  "extractTenant" should "pull a tenant from /api/pool/:tenant/:tdb/:pool/status" in {
    extractTenant("/api/pool/t-abc/td1/p1/status", queryTenant = None) shouldBe Some("t-abc")
  }

  it should "pull from /api/catalog/tenant/:tenant/database/..." in {
    extractTenant(
      "/api/catalog/tenant/t-abc/database/td1/schemas",
      queryTenant = None
    ) shouldBe Some("t-abc")
  }

  it should "pull from /api/tenants/:tenant/tenant-dbs/..." in {
    extractTenant(
      "/api/tenants/t-abc/tenant-dbs/td1/federated-sources",
      queryTenant = None
    ) shouldBe Some("t-abc")
  }

  it should "pull from query string when no path tenant" in {
    extractTenant("/api/database/list", queryTenant = Some("t-q")) shouldBe Some("t-q")
  }

  it should "return None when no tenant is encoded" in {
    extractTenant("/api/pool/list", queryTenant = None) shouldBe None
  }

  it should "ignore /api/auth/login (public)" in {
    extractTenant("/api/auth/login", queryTenant = None) shouldBe None
  }