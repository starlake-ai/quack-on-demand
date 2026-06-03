package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

final class TenantHandlers(sup: PoolSupervisor):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def toResponse(t: Tenant): TenantResponse =
    TenantResponse(
      name     = t.displayName,
      pools    = sup.listPoolsOfTenant(t.displayName),
      disabled = t.disabled
    )

  def createTenant(req: TenantRequest): Out[TenantResponse] =
    if req.name.isEmpty then
      IO.pure(Left((StatusCode.BadRequest,
        ErrorResponse("invalid_name", "tenant name must be non-empty"))))
    else
      sup.createTenant(Tenant(req.name)).map {
        case Right(t)  => Right(toResponse(t))
        case Left(msg) => Left((StatusCode.Conflict, ErrorResponse("exists", msg)))
      }

  def listTenants(): Out[TenantListResponse] = IO.delay(
    Right(TenantListResponse(sup.listTenants().map(toResponse)))
  )

  def deleteTenant(req: TenantOpRequest): Out[Unit] =
    sup.deleteTenant(req.name).map {
      case Right(_)  => Right(())
      case Left(msg) =>
        if msg.startsWith("tenant not found") then
          Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
        else
          Left((StatusCode.Conflict, ErrorResponse("has_pools", msg)))
    }

  def setTenantDisabled(req: SetTenantDisabledRequest): Out[TenantResponse] =
    sup.setTenantDisabled(req.name, req.disabled).map {
      case Right(t) => Right(toResponse(t))
      case Left(msg) =>
        if msg.startsWith("tenant not found") then
          Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
        else
          Left((StatusCode.Conflict, ErrorResponse("update_failed", msg)))
    }