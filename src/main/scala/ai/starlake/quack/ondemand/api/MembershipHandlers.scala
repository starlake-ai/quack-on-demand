package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the membership surface (`/api/membership/...`). Every endpoint is idempotent
  * (the underlying store does ON CONFLICT DO NOTHING on adds, and remove returns 204 whether or not
  * a row existed) so retry-on-409 callers don't trip alarms in operator dashboards.
  *
  * Tenant-scope enforcement:
  *   - user-role and user-group: scoped by the user's tenant. The supervisor refuses cross-tenant
  *     edges at the store layer, so checking one endpoint is sufficient (a tenant-A admin who
  *     somehow names a tenant-B role on a tenant-A user gets the store-level rejection).
  *   - group-role: scoped by the group's tenant (same argument).
  *   - listGroupRoles: scoped by the group's tenant.
  */
final class MembershipHandlers(sup: PoolSupervisor, mappers: UserHandlers):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def mapErr(io: IO[Either[String, Unit]]): Out[Unit] = io.map {
    case Right(_)  => Right(())
    case Left(err) =>
      val code = if err.contains("not found") then StatusCode.NotFound else StatusCode.BadRequest
      Left((code, ErrorResponse("invalid_membership", err)))
  }

  private def gateOnUser(apiKey: Option[String], userId: String)(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    TenantScopeCheck.rejectForUser(apiKey, sup.tenantForUser(userId))(scopeOf)

  private def gateOnGroup(apiKey: Option[String], groupId: String)(
      scopeOf: String => Option[SessionScope]
  ): Option[(StatusCode, ErrorResponse)] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForGroup(groupId))(scopeOf)

  def addUserRole(req: UserRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    gateOnUser(apiKey, req.userId)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      => mapErr(sup.addUserRole(req.userId, req.roleId))

  def removeUserRole(req: UserRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    gateOnUser(apiKey, req.userId)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      => mapErr(sup.removeUserRole(req.userId, req.roleId))

  def addUserGroup(req: UserGroupMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    gateOnUser(apiKey, req.userId)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      => mapErr(sup.addUserGroup(req.userId, req.groupId))

  def removeUserGroup(req: UserGroupMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    gateOnUser(apiKey, req.userId)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      => mapErr(sup.removeUserGroup(req.userId, req.groupId))

  def addGroupRole(req: GroupRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    gateOnGroup(apiKey, req.groupId)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      => mapErr(sup.addGroupRole(req.groupId, req.roleId))

  def removeGroupRole(req: GroupRoleMembershipRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    gateOnGroup(apiKey, req.groupId)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      => mapErr(sup.removeGroupRole(req.groupId, req.roleId))

  def listGroupRoles(groupId: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RoleListResponse] = IO.blocking {
    gateOnGroup(apiKey, groupId)(scopeOf) match
      case Some(err) => Left(err)
      case None      =>
        Right(RoleListResponse(sup.listRolesForGroup(groupId).map(mappers.toRoleResponse)))
  }