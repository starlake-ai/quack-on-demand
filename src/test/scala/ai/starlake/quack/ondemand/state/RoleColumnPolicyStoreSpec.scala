package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoleColumnPolicyStoreSpec extends AnyFlatSpec with Matchers:

  private def fresh: ControlPlaneStore = new InMemoryControlPlaneStore()

  private def policy(id: String, roleId: String, col: String, action: String,
                     transform: Option[String] = None) =
    RoleColumnPolicy(
      id = id, roleId = roleId,
      catalogName = "*", schemaName = "tpch1", tableName = "customer",
      columnName = col, action = action, transformSql = transform
    )

  "insertColumnPolicy" should "round-trip via listColumnPolicies(roleId)" in {
    val s = fresh
    s.insertColumnPolicy(policy("cp-1", "r-a", "c_email", "mask", Some("'***'")))
    s.listColumnPolicies("r-a") should contain only
      policy("cp-1", "r-a", "c_email", "mask", Some("'***'"))
  }

  it should "be visible through listAllColumnPolicies for snapshot restore" in {
    val s = fresh
    s.insertColumnPolicy(policy("cp-1", "r-a", "c_email", "mask", Some("'***'")))
    s.insertColumnPolicy(policy("cp-2", "r-b", "c_ssn",   "deny", None))
    s.listAllColumnPolicies().map(_.id) should contain theSameElementsAs List("cp-1", "cp-2")
  }

  "deleteColumnPolicy(id)" should "remove the row and return true; second delete returns false" in {
    val s = fresh
    s.insertColumnPolicy(policy("cp-1", "r-a", "c_email", "mask", Some("'***'")))
    s.deleteColumnPolicy("cp-1") shouldBe true
    s.deleteColumnPolicy("cp-1") shouldBe false
    s.listColumnPolicies("r-a") shouldBe empty
  }

  "getColumnPolicy(id)" should "return Some for known ids and None otherwise" in {
    val s = fresh
    s.insertColumnPolicy(policy("cp-1", "r-a", "c_email", "mask", Some("'***'")))
    s.getColumnPolicy("cp-1").map(_.id) shouldBe Some("cp-1")
    s.getColumnPolicy("nope")           shouldBe None
  }

  "updateColumnPolicy" should "replace action + transformSql, keep identity tuple" in {
    val s = fresh
    s.insertColumnPolicy(policy("cp-1", "r-a", "c_email", "mask", Some("'***'")))
    s.updateColumnPolicy("cp-1", "deny", None) shouldBe true
    s.getColumnPolicy("cp-1").map(p => (p.action, p.transformSql)) shouldBe Some(("deny", None))
    // identity tuple preserved
    s.getColumnPolicy("cp-1").map(_.columnName) shouldBe Some("c_email")
  }