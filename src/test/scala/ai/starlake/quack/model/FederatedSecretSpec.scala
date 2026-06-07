package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FederatedSecretSpec extends AnyFlatSpec with Matchers {

  "FederatedSecret" should "construct with value only" in {
    val s = FederatedSecret("s-1", "src-1", "PWD", Some("hunter2"), None)
    s.name shouldBe "PWD"
  }

  it should "construct with externalRef only" in {
    val s = FederatedSecret("s-2", "src-1", "PWD", None, Some("vault:secret/data/x#k"))
    s.externalRef shouldBe Some("vault:secret/data/x#k")
  }

  it should "reject both value AND externalRef set" in {
    val ex = intercept[IllegalArgumentException] {
      FederatedSecret("s-3", "src-1", "PWD", Some("v"), Some("vault:x"))
    }
    ex.getMessage should include("exactly one")
  }

  it should "reject neither value NOR externalRef set" in {
    val ex = intercept[IllegalArgumentException] {
      FederatedSecret("s-4", "src-1", "PWD", None, None)
    }
    ex.getMessage should include("exactly one")
  }
}