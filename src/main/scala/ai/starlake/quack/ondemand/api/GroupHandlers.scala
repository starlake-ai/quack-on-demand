package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the group surface (`/api/group/...`). */
final class GroupHandlers(sup: PoolSupervisor, mappers: UserHandlers):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def resolveTenantId(raw: String): Option[String] =
    val tenants = sup.listTenants()
    tenants
      .find(_.id == raw)
      .map(_.id)
      .orElse(tenants.find(_.displayName == raw.toLowerCase).map(_.id))

  def createGroup(req: GroupCreateRequest): Out[GroupResponse] =
    resolveTenantId(req.tenant) match
      case None =>
        IO.pure(
          Left(
            (
              StatusCode.NotFound,
              ErrorResponse("not_found", s"tenant '${req.tenant}' is not registered")
            )
          )
        )
      case Some(tid) =>
        sup.createGroup(tid, req.name, req.description).map {
          case Right(g)  => Right(mappers.toGroupResponse(g))
          case Left(err) =>
            val code =
              if err.startsWith("group '") then StatusCode.Conflict else StatusCode.BadRequest
            Left((code, ErrorResponse("invalid_group", err)))
        }

  def deleteGroup(req: GroupDeleteRequest): Out[Unit] =
    sup.deleteGroup(req.id).map {
      case Right(_)  => Right(())
      case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err)))
    }

  def listGroups(tenant: String): Out[GroupListResponse] = IO.blocking {
    resolveTenantId(tenant) match
      case None      => Right(GroupListResponse(Nil))
      case Some(tid) => Right(GroupListResponse(sup.listGroups(tid).map(mappers.toGroupResponse)))
  }
