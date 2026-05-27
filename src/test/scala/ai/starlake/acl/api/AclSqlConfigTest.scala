package ai.starlake.acl.api

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AclSqlConfigTest extends AnyFunSuite with Matchers {

  test("default values are correct") {
    val config = AclSqlConfig()

    config.maxTenants shouldBe None
  }

  test("AclSqlConfig.default returns expected defaults") {
    val config = AclSqlConfig.default

    config.maxTenants shouldBe None
  }

  test("copy with custom maxTenants works correctly") {
    val config = AclSqlConfig.default.copy(
      maxTenants = Some(100)
    )

    config.maxTenants shouldBe Some(100)
  }

  test("maxTenants with Some value enables LRU eviction concept") {
    val config = AclSqlConfig(maxTenants = Some(10))

    config.maxTenants shouldBe Some(10)
    config.maxTenants.isDefined shouldBe true
  }
}
