package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InMemoryRowPolicyStoreSpec extends AnyFlatSpec with Matchers:

  private def policy(id: String, roleId: String) = RoleRowPolicy(
    id, roleId, catalogName = "*", schemaName = "tpch1", tableName = "orders",
    predicateSql = "region = ${tenant}"
  )

  "InMemoryControlPlaneStore" should "insert, list by role, get, delete row policies" in {
    val s = new InMemoryControlPlaneStore()
    s.insertRowPolicy(policy("rp-1", "r-1"))
    s.insertRowPolicy(policy("rp-2", "r-2").copy(tableName = "lineitem"))
    s.listRowPolicies("r-1").map(_.id) shouldBe List("rp-1")
    s.getRowPolicy("rp-1").map(_.tableName) shouldBe Some("orders")
    s.deleteRowPolicy("rp-1") shouldBe true
    s.listRowPolicies("r-1") shouldBe Nil
  }

  it should "include row policies in snapshot()" in {
    val s = new InMemoryControlPlaneStore()
    s.insertRowPolicy(policy("rp-9", "r-9"))
    s.snapshot().rowPolicies.map(_.id) shouldBe List("rp-9")
  }
