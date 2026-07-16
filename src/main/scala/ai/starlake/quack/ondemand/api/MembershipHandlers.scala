package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the membership surface (`/api/membership/...`). Every endpoint is idempotent
  * (the underlying store does ON CONFLICT DO NOTHING on adds, and remove returns 204 whether or not
  * a row existed) so retry-on-409 callers don't trip alarms in operator dashboards.
  *
  * Tenant-scope enforcement is layered:
  *   - This handler gates the CALLER: user-role and user-group are gated on the user's tenant,
  *     group-role and listGroupRoles on the group's tenant (a tenant-A admin cannot touch a
  *     tenant-B user or group).
  *   - The supervisor independently enforces TENANT ALIGNMENT of the edge itself
  *     ([[PoolSupervisor.addUserRole]] / `addUserGroup` / `addGroupRole`): the referenced
  *     role/group must share the user's/group's tenant. So even a multi-tenant or superuser caller
  *     that names a foreign-tenant role/group on a local principal is rejected there, closing the
  *     cross-tenant privilege-escalation path.
  */
final class MembershipHandlers(
    sup: PoolSupervisor,
    mappers: UserHandlers,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def mapErr(io: IO[Either[SupervisorError, Unit]]): Out[Unit] = io.map {
    case Right(_)  => Right(())
    case Left(err) =>
      val code = err match
        case SupervisorError.NotFound(_) => StatusCode.NotFound
        case _                           => StatusCode.BadRequest
      Left((code, ErrorResponse("invalid_membership", err.message)))
  }

  private def gateOnUser(apiKey: Option[String], userId: String)(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    TenantScopeCheck.rejectForUser(apiKey, sup.tenantForUser(userId))(scopeOf)

  private def gateOnGroup(apiKey: Option[String], groupId: String)(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForGroup(groupId))(scopeOf)

  /** Shared skeleton of every membership endpoint: gate the caller, audit "denied" (no target) on
    * rejection, otherwise run the supervisor op and audit "ok" with the edge as target on success.
    */
  private def membershipOp(
      action: String,
      apiKey: Option[String],
      ten: Option[String],
      gate: Option[(StatusCode, ErrorResponse)],
      target: String,
      op: => IO[Either[SupervisorError, Unit]]
  ): Out[Unit] =
    gate match
      case Some(err) =>
        audit.rest(apiKey, "control-plane", action, "denied", tenant = ten)
        IO.pure(Left(err))
      case None =>
        mapErr(op).map { r =>
          if r.isRight then
            audit.rest(
              apiKey,
              "control-plane",
              action,
              "ok",
              tenant = ten,
              target = Some(target)
            )
          r
        }

  def addUserRole(req: UserRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    membershipOp(
      AuditActions.MembershipUserRoleAdd,
      apiKey,
      sup.tenantForUser(req.userId).flatten,
      gateOnUser(apiKey, req.userId)(scopeOf),
      s"${req.userId}:${req.roleId}",
      sup.addUserRole(req.userId, req.roleId)
    )

  def removeUserRole(req: UserRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    membershipOp(
      AuditActions.MembershipUserRoleRemove,
      apiKey,
      sup.tenantForUser(req.userId).flatten,
      gateOnUser(apiKey, req.userId)(scopeOf),
      s"${req.userId}:${req.roleId}",
      sup.removeUserRole(req.userId, req.roleId)
    )

  def addUserGroup(req: UserGroupMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    membershipOp(
      AuditActions.MembershipUserGroupAdd,
      apiKey,
      sup.tenantForUser(req.userId).flatten,
      gateOnUser(apiKey, req.userId)(scopeOf),
      s"${req.userId}:${req.groupId}",
      sup.addUserGroup(req.userId, req.groupId)
    )

  def removeUserGroup(req: UserGroupMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    membershipOp(
      AuditActions.MembershipUserGroupRemove,
      apiKey,
      sup.tenantForUser(req.userId).flatten,
      gateOnUser(apiKey, req.userId)(scopeOf),
      s"${req.userId}:${req.groupId}",
      sup.removeUserGroup(req.userId, req.groupId)
    )

  def addGroupRole(req: GroupRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    membershipOp(
      AuditActions.MembershipGroupRoleAdd,
      apiKey,
      sup.tenantForGroup(req.groupId),
      gateOnGroup(apiKey, req.groupId)(scopeOf),
      s"${req.groupId}:${req.roleId}",
      sup.addGroupRole(req.groupId, req.roleId)
    )

  def removeGroupRole(req: GroupRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    membershipOp(
      AuditActions.MembershipGroupRoleRemove,
      apiKey,
      sup.tenantForGroup(req.groupId),
      gateOnGroup(apiKey, req.groupId)(scopeOf),
      s"${req.groupId}:${req.roleId}",
      sup.removeGroupRole(req.groupId, req.roleId)
    )

  def listGroupRoles(groupId: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RoleListResponse] = IO.blocking {
    gateOnGroup(apiKey, groupId)(scopeOf) match
      case Some(err) => Left(err)
      case None      =>
        Right(RoleListResponse(sup.listRolesForGroup(groupId).map(mappers.toRoleResponse)))
  }
