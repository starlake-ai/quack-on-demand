package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GrantTargetTest extends AnyFunSuite with Matchers {

  test("All variant has no fields") {
    val target = GrantTarget.all
    target shouldBe GrantTarget.All
  }

  test("database factory normalizes to lowercase") {
    val target = GrantTarget.database("ANALYTICS")
    target shouldBe GrantTarget.Database("analytics")
  }

  test("schema factory normalizes both parts") {
    val target = GrantTarget.schema("ANALYTICS", "RAW")
    target shouldBe GrantTarget.Schema("analytics", "raw")
  }

  test("table factory normalizes all three parts") {
    val target = GrantTarget.table("ANALYTICS", "RAW", "Events")
    target shouldBe GrantTarget.Table("analytics", "raw", "events")
  }

  test("different GrantTarget variants are not equal") {
    val db     = GrantTarget.database("analytics")
    val schema = GrantTarget.schema("analytics", "raw")
    val table  = GrantTarget.table("analytics", "raw", "events")
    val all    = GrantTarget.all

    db should not equal schema
    schema should not equal table
    table should not equal all
    all should not equal db
  }
}
