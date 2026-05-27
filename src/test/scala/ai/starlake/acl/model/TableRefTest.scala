package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TableRefTest extends AnyFunSuite with Matchers {

  test("normalizes database, schema, table to lowercase") {
    val ref = TableRef("MyDB", "Public", "Orders")
    ref.database shouldBe "mydb"
    ref.schema shouldBe "public"
    ref.table shouldBe "orders"
  }

  test("preserves original case for display") {
    val ref = TableRef("MyDB", "Public", "Orders")
    ref.display shouldBe "MyDB.Public.Orders"
  }

  test("canonical is lowercase dot-separated") {
    val ref = TableRef("MyDB", "Public", "Orders")
    ref.canonical shouldBe "mydb.public.orders"
  }

  test("equality is case-insensitive") {
    val ref1 = TableRef("MyDB", "Public", "Orders")
    val ref2 = TableRef("mydb", "public", "orders")
    ref1 shouldEqual ref2
  }

  test("hashCode is same for different case inputs") {
    val ref1 = TableRef("MyDB", "Public", "Orders")
    val ref2 = TableRef("mydb", "public", "orders")
    ref1.hashCode() shouldEqual ref2.hashCode()
  }

  test("toString returns canonical") {
    val ref = TableRef("MyDB", "Public", "Orders")
    ref.toString shouldBe "mydb.public.orders"
  }

  test("two refs with different original case but same normalized fields are equal") {
    val ref1 = TableRef("ANALYTICS", "RAW", "EVENTS")
    val ref2 = TableRef("analytics", "raw", "events")
    val ref3 = TableRef("Analytics", "Raw", "Events")

    ref1 shouldEqual ref2
    ref2 shouldEqual ref3
    ref1 shouldEqual ref3

    ref1.hashCode() shouldEqual ref2.hashCode()
    ref2.hashCode() shouldEqual ref3.hashCode()
  }

  test("refs with different identifiers are not equal") {
    val ref1 = TableRef("db1", "schema1", "table1")
    val ref2 = TableRef("db1", "schema1", "table2")
    ref1 should not equal ref2
  }

  test("display preserves each variant of original casing") {
    val ref1 = TableRef("ANALYTICS", "RAW", "EVENTS")
    val ref2 = TableRef("Analytics", "Raw", "Events")
    ref1.display shouldBe "ANALYTICS.RAW.EVENTS"
    ref2.display shouldBe "Analytics.Raw.Events"
  }
}
