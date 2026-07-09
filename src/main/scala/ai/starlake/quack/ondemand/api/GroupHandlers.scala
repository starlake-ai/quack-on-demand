package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the group surface (`/api/group/...`). */
final class GroupHandlers(
    sup: PoolSupervisor,
    mappers: UserHandlers,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def resolveTenantId(raw: String): Option[String] =
    HandlerResolvers.resolveTenantId(sup, raw)

  def createGroup(req: GroupCreateRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[GroupResponse] =
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
        TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
          case Some(err) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.GroupCreate,
              "denied",
              tenant = Some(tid)
            )
            IO.pure(Left(err))
          case None =>
            sup.createGroup(tid, req.name, req.description).map {
              case Right(g) =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.GroupCreate,
                  "ok",
                  tenant = Some(tid),
                  target = Some(g.id),
                  detail = Map("name" -> req.name)
                )
                Right(mappers.toGroupResponse(g))
              case Left(err) =>
                val code = err match
                  case SupervisorError.AlreadyExists(_) => StatusCode.Conflict
                  case _                                => StatusCode.BadRequest
                Left((code, ErrorResponse("invalid_group", err.message)))
            }

  def deleteGroup(req: GroupDeleteRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val groupTenant = sup.tenantForGroup(req.id)
    TenantScopeCheck.rejectForResource(apiKey, groupTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.GroupDelete,
          "denied",
          tenant = groupTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.deleteGroup(req.id).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.GroupDelete,
              "ok",
              tenant = groupTenant,
              target = Some(req.id)
            )
            Right(())
          case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
        }

  def listGroups(tenant: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[GroupListResponse] = IO.blocking {
    resolveTenantId(tenant) match
      case None      => Right(GroupListResponse(Nil))
      case Some(tid) =>
        TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
          case Some(err) => Left(err)
          case None      =>
            Right(GroupListResponse(sup.listGroups(tid).map(mappers.toGroupResponse)))
  }
