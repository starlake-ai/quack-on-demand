package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PrincipalTest extends AnyFunSuite with Matchers {

  test("user factory normalizes to lowercase") {
    val p = Principal.user("Alice")
    p shouldBe Principal.User("alice")
  }

  test("group factory normalizes to lowercase") {
    val p = Principal.group("Engineering")
    p shouldBe Principal.Group("engineering")
  }

  test("AllUsers has no parameters") {
    val p = Principal.AllUsers
    p shouldBe a[Principal]
  }

  test("different Principal variants are not equal") {
    val user  = Principal.user("alice")
    val group = Principal.group("alice")
    user should not equal group
    user should not equal Principal.AllUsers
    group should not equal Principal.AllUsers
  }
}
