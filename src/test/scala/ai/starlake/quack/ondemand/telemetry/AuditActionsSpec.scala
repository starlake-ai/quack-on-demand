package ai.starlake.quack.ondemand.telemetry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the audit action registry invariants the /api/audit/actions endpoint relies on. */
class AuditActionsSpec extends AnyFlatSpec with Matchers:

  "AuditActions.all" should "be sorted, distinct, and non-empty" in {
    AuditActions.all shouldBe AuditActions.all.sorted
    AuditActions.all.distinct should have size AuditActions.all.size.toLong
    AuditActions.all should not be empty
  }

  it should "contain known members from every family" in {
    AuditActions.all should contain allOf (
      "auth.login.failure",
      "tenant.create",
      "pool.scale",
      "role.permission.grant",
      "membership.user-group.add",
      "node.restart",
      "federation.secret.upsert",
      "manifest.import",
      "sql.denied"
    )
  }

  it should "expose constants that match their dotted string" in {
    AuditActions.AuthLoginFailure shouldBe "auth.login.failure"
    AuditActions.SqlDdl shouldBe "sql.ddl"
    AuditActions.MembershipGroupRoleRemove shouldBe "membership.group-role.remove"
  }
