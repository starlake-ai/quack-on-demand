package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TenantIdTest extends AnyFunSuite with Matchers {

  // Valid parsing tests
  test("parse accepts simple alphanumeric tenant ID") {
    val result = TenantId.parse("tenant1")
    result shouldBe a[Right[?, ?]]
    result.toOption.get.canonical shouldBe "tenant1"
  }

  test("parse accepts tenant ID with hyphens") {
    val result = TenantId.parse("my-tenant")
    result shouldBe a[Right[?, ?]]
    result.toOption.get.canonical shouldBe "my-tenant"
  }

  test("parse accepts tenant ID with underscores") {
    val result = TenantId.parse("my_tenant")
    result shouldBe a[Right[?, ?]]
    result.toOption.get.canonical shouldBe "my_tenant"
  }

  test("parse accepts mixed alphanumeric, hyphens, and underscores") {
    val result = TenantId.parse("my-tenant_123")
    result shouldBe a[Right[?, ?]]
    result.toOption.get.canonical shouldBe "my-tenant_123"
  }

  // Normalization tests
  test("parse normalizes to lowercase for canonical") {
    val result = TenantId.parse("MyTenant")
    result shouldBe a[Right[?, ?]]
    val tenantId = result.toOption.get
    tenantId.canonical shouldBe "mytenant"
  }

  test("parse preserves original casing for display") {
    val result = TenantId.parse("MyTenant")
    result shouldBe a[Right[?, ?]]
    val tenantId = result.toOption.get
    tenantId.display shouldBe "MyTenant"
  }

  test("parse handles uppercase input correctly") {
    val result = TenantId.parse("TENANT")
    result shouldBe a[Right[?, ?]]
    val tenantId = result.toOption.get
    tenantId.canonical shouldBe "tenant"
    tenantId.display shouldBe "TENANT"
  }

  // Rejection tests
  test("parse rejects empty string") {
    val result = TenantId.parse("")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("non-empty")
  }

  test("parse rejects tenant ID with dots") {
    val result = TenantId.parse("my.tenant")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("[a-z0-9_-]+")
  }

  test("parse rejects tenant ID with spaces") {
    val result = TenantId.parse("my tenant")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("[a-z0-9_-]+")
  }

  test("parse rejects tenant ID with special characters") {
    val result = TenantId.parse("tenant@1")
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get should include("[a-z0-9_-]+")
  }

  // Edge cases for position
  test("parse accepts hyphen at start") {
    val result = TenantId.parse("-start")
    result shouldBe a[Right[?, ?]]
    result.toOption.get.canonical shouldBe "-start"
  }

  test("parse accepts hyphen at end") {
    val result = TenantId.parse("end-")
    result shouldBe a[Right[?, ?]]
    result.toOption.get.canonical shouldBe "end-"
  }

  test("parse accepts underscores at any position") {
    val result = TenantId.parse("_underscore_")
    result shouldBe a[Right[?, ?]]
    result.toOption.get.canonical shouldBe "_underscore_"
  }

  // Equality tests
  test("equality is case-insensitive") {
    val result1 = TenantId.parse("MyTenant")
    val result2 = TenantId.parse("mytenant")
    result1.toOption.get shouldEqual result2.toOption.get
  }

  test("same hashCode for different casings") {
    val result1 = TenantId.parse("MyTenant")
    val result2 = TenantId.parse("mytenant")
    result1.toOption.get.hashCode() shouldEqual result2.toOption.get.hashCode()
  }

  test("different tenant IDs are not equal") {
    val result1 = TenantId.parse("tenant1")
    val result2 = TenantId.parse("tenant2")
    result1.toOption.get should not equal result2.toOption.get
  }

  // toString test
  test("toString returns canonical") {
    val result = TenantId.parse("MyTenant")
    result.toOption.get.toString shouldBe "mytenant"
  }

  // Additional edge cases
  test("equality is based on normalized id only, not original") {
    val result1 = TenantId.parse("ANALYTICS")
    val result2 = TenantId.parse("analytics")
    val result3 = TenantId.parse("Analytics")

    val t1 = result1.toOption.get
    val t2 = result2.toOption.get
    val t3 = result3.toOption.get

    t1 shouldEqual t2
    t2 shouldEqual t3
    t1 shouldEqual t3

    t1.hashCode() shouldEqual t2.hashCode()
    t2.hashCode() shouldEqual t3.hashCode()
  }

  test("display preserves each variant of original casing") {
    val result1 = TenantId.parse("ANALYTICS")
    val result2 = TenantId.parse("Analytics")
    result1.toOption.get.display shouldBe "ANALYTICS"
    result2.toOption.get.display shouldBe "Analytics"
  }
}
