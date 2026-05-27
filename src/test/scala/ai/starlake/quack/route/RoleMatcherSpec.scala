package ai.starlake.quack.route

import ai.starlake.quack.model.{Role, StatementKind}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RoleMatcherSpec extends AnyFlatSpec with Matchers:

  "RoleMatcher.preferred" should "list RO and DUAL for SELECT" in:
    RoleMatcher.preferred(StatementKind.Select) shouldBe List(Role.ReadOnly, Role.Dual)

  it should "list WO and DUAL for DML, DDL and BEGIN" in:
    RoleMatcher.preferred(StatementKind.Dml)   shouldBe List(Role.WriteOnly, Role.Dual)
    RoleMatcher.preferred(StatementKind.Ddl)   shouldBe List(Role.WriteOnly, Role.Dual)
    RoleMatcher.preferred(StatementKind.Begin) shouldBe List(Role.WriteOnly, Role.Dual)

  "RoleMatcher.fallback" should "promote to DUAL when RO is exhausted" in:
    RoleMatcher.fallback(StatementKind.Select, available = Set(Role.Dual)) shouldBe List(Role.Dual)

  it should "return empty when nothing is compatible" in:
    RoleMatcher.fallback(StatementKind.Dml, available = Set(Role.ReadOnly)) shouldBe List.empty
