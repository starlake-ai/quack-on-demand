package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class UserIdentityTest extends AnyFunSuite with Matchers {

  test("normalizes username to lowercase") {
    val user = UserIdentity("Alice", Set("eng"))
    user.name shouldBe "alice"
  }

  test("normalizes group names to lowercase") {
    val user = UserIdentity("bob", Set("Engineering", "DEVOPS", "Data-Science"))
    user.groups shouldBe Set("engineering", "devops", "data-science")
  }

  test("preserves original name for display") {
    val user = UserIdentity("Alice", Set.empty)
    user.display shouldBe "Alice"
  }

  test("equality uses normalized name and groups") {
    val user1 = UserIdentity("Alice", Set("ENG"))
    val user2 = UserIdentity("alice", Set("eng"))
    user1 shouldEqual user2
    user1.hashCode() shouldEqual user2.hashCode()
  }

  test("users with different groups are not equal") {
    val user1 = UserIdentity("alice", Set("eng"))
    val user2 = UserIdentity("alice", Set("marketing"))
    user1 should not equal user2
  }
}
