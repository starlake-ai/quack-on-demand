package ai.starlake.quack.ondemand.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ManagementIdentitySourceSpec extends AnyFlatSpec, Matchers:

  "fromConfig" should "select Db for 'db'" in {
    ManagementIdentitySource.fromConfig("db") shouldBe ManagementIdentitySource.Db
  }

  it should "select Oidc for 'oidc' (case-insensitive)" in {
    ManagementIdentitySource.fromConfig("OIDC") shouldBe ManagementIdentitySource.Oidc
  }

  it should "throw on unknown values" in {
    val ex = intercept[IllegalArgumentException](ManagementIdentitySource.fromConfig("ldap"))
    ex.getMessage should include("ldap")
  }