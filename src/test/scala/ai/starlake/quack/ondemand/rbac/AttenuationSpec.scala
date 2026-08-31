package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.state._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Attenuating an EffectiveSet: permissions and pool grants shrink, policies never do. */
class AttenuationSpec extends AnyFlatSpec with Matchers:

  private val user = RbacUser(id = "u1", tenant = Some("acme"), username = "alice", role = "user")

  private val roleA = RbacRole(id = "r-a", tenantId = "acme", name = "analyst")
  private val roleB = RbacRole(id = "r-b", tenantId = "acme", name = "reader")

  private def perm(id: String, roleId: String, table: String, verb: String) =
    RolePermission(id = id, roleId = roleId, catalogName = "acme_db",
      schemaName = "public", tableName = table, verb = verb)

  private val rowPolicyOnCustomer = RoleRowPolicy(
    id = "rp-1", roleId = roleA.id, catalogName = "acme_db",
    schemaName = "public", tableName = "customer", predicateSql = "region = 'EU'"
  )

  private val base = EffectiveSet(
    user = user,
    roles = List(roleA, roleB),
    groups = Nil,
    permissions = List(
      perm("p1", roleA.id, "orders", "ALL"),
      perm("p2", roleB.id, "customer", "RW")
    ),
    poolPerms = Nil,
    columnPolicies = Nil,
    rowPolicies = List(rowPolicyOnCustomer)
  )

  "attenuatedBy" should "keep everything under an unrestricted token" in {
    Attenuation.attenuatedBy(base, TokenRestriction.Unrestricted) shouldBe base
  }

  it should "drop permissions whose role is not in the subset" in {
    val out = Attenuation.attenuatedBy(
      base, TokenRestriction.Unrestricted.copy(roles = Some(Set("reader"))))
    out.permissions.map(_.id) shouldBe List("p2")
    out.roles.map(_.name) shouldBe List("reader")
  }

  // THE regression test this design exists for. Role A carries the row policy on
  // `customer`; role B carries the grant. Row policies combine with OR and a table
  // with no matching policy is passthrough, so subtracting role A would leave the
  // grant in place with no filter, and the child would see MORE rows than its parent.
  it should "never subtract row policies, even when their role is dropped" in {
    val out = Attenuation.attenuatedBy(
      base, TokenRestriction.Unrestricted.copy(roles = Some(Set("reader"))))
    out.rowPolicies shouldBe base.rowPolicies
    out.rowPolicies.map(_.tableName) should contain("customer")
  }

  it should "never subtract column policies" in {
    val cls = RoleColumnPolicy(id = "cp-1", roleId = roleA.id, catalogName = "acme_db",
      schemaName = "public", tableName = "customer", columnName = "ssn",
      action = "mask", transformSql = None)
    val withCls = base.copy(columnPolicies = List(cls))
    Attenuation.attenuatedBy(
      withCls, TokenRestriction.Unrestricted.copy(roles = Some(Set("reader")))
    ).columnPolicies shouldBe List(cls)
  }

  it should "clip a permission verb to the ceiling by set intersection" in {
    val out = Attenuation.attenuatedBy(
      base, TokenRestriction.Unrestricted.copy(verbCeiling = Some("RO")))
    out.permissions.map(p => p.id -> p.verb).toMap shouldBe Map("p1" -> "RO", "p2" -> "RO")
  }

  it should "drop a permission whose clipped coverage is empty" in {
    // RW covers {Read, Write}; a DDL ceiling covers {Ddl}; the intersection is empty.
    val out = Attenuation.attenuatedBy(
      base, TokenRestriction.Unrestricted.copy(verbCeiling = Some("DDL")))
    out.permissions.map(_.id) shouldBe List("p1") // ALL clips to DDL; RW disappears
    out.permissions.head.verb shouldBe "DDL"
  }

  it should "never produce a permission the input did not cover" in {
    val out = Attenuation.attenuatedBy(
      base, TokenRestriction.Unrestricted.copy(verbCeiling = Some("RW")))
    out.permissions.foreach { p =>
      val original = base.permissions.find(_.id == p.id).get
      TokenRestriction.covers(p.verb).subsetOf(TokenRestriction.covers(original.verb)) shouldBe true
    }
  }
