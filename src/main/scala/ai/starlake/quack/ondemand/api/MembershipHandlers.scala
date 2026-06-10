package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the membership surface (`/api/membership/...`). Every endpoint is idempotent
  * (the underlying store does ON CONFLICT DO NOTHING on adds, and remove returns 204 whether or not
  * a row existed) so retry-on-409 callers don't trip alarms in operator dashboards.
  */
final class MembershipHandlers(sup: PoolSupervisor, mappers: UserHandlers):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def mapErr(io: IO[Either[String, Unit]]): Out[Unit] = io.map {
    case Right(_)  => Right(())
    case Left(err) =>
      val code = if err.contains("not found") then StatusCode.NotFound else StatusCode.BadRequest
      Left((code, ErrorResponse("invalid_membership", err)))
  }

  def addUserRole(req: UserRoleMembershipRequest): Out[Unit] = mapErr(
    sup.addUserRole(req.userId, req.roleId)
  )
  def removeUserRole(req: UserRoleMembershipRequest): Out[Unit] = mapErr(
    sup.removeUserRole(req.userId, req.roleId)
  )

  def addUserGroup(req: UserGroupMembershipRequest): Out[Unit] = mapErr(
    sup.addUserGroup(req.userId, req.groupId)
  )
  def removeUserGroup(req: UserGroupMembershipRequest): Out[Unit] = mapErr(
    sup.removeUserGroup(req.userId, req.groupId)
  )

  def addGroupRole(req: GroupRoleMembershipRequest): Out[Unit] = mapErr(
    sup.addGroupRole(req.groupId, req.roleId)
  )
  def removeGroupRole(req: GroupRoleMembershipRequest): Out[Unit] = mapErr(
    sup.removeGroupRole(req.groupId, req.roleId)
  )

  def listGroupRoles(groupId: String): Out[RoleListResponse] = IO.blocking {
    Right(RoleListResponse(sup.listRolesForGroup(groupId).map(mappers.toRoleResponse)))
  }
