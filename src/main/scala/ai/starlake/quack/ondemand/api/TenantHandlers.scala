package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

final class TenantHandlers(sup: PoolSupervisor):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def toResponse(t: Tenant): TenantResponse =
    TenantResponse(
      name         = t.displayName,
      pools        = sup.listPoolsOfTenant(t.displayName),
      disabled     = t.disabled,
      authProvider = t.authProvider,
      authConfig   = t.authConfig
    )

  def createTenant(req: TenantRequest): Out[TenantResponse] =
    if req.name.isEmpty then
      IO.pure(Left((StatusCode.BadRequest,
        ErrorResponse("invalid_name", "tenant name must be non-empty"))))
    else if !Tenant.ValidAuthProviders.contains(req.authProvider) then
      IO.pure(Left((StatusCode.BadRequest,
        ErrorResponse("invalid_auth_provider",
          s"authProvider must be one of ${Tenant.ValidAuthProviders.toList.sorted.mkString(", ")}"))))
    else
      sup.createTenant(Tenant(
        name         = req.name,
        authProvider = req.authProvider,
        authConfig   = req.authConfig
      )).map {
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

  def setTenantAuth(req: SetTenantAuthRequest): Out[TenantResponse] =
    sup.setTenantAuth(req.name, req.authProvider, req.authConfig).map {
      case Right(t) => Right(toResponse(t))
      case Left(msg) =>
        if msg.startsWith("tenant not found") then
          Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
        else if msg.startsWith("authProvider must be") then
          Left((StatusCode.BadRequest, ErrorResponse("invalid_auth_provider", msg)))
        else
          Left((StatusCode.Conflict, ErrorResponse("update_failed", msg)))
    }