package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

final class TenantHandlers(sup: PoolSupervisor):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  /** Hide secret-like keys from the API response. Today: just `pgPassword`. */
  private def redact(m: Map[String, String]): Map[String, String] =
    m.filterNot(_._1.equalsIgnoreCase("pgPassword"))

  private def toResponse(t: Tenant): TenantResponse =
    TenantResponse(
      name               = t.name,
      metastore          = t.metastore,
      pools              = sup.listPoolsOfTenant(t.name),
      effectiveMetastore = redact(sup.effectiveMetastoreFor(t.name))
    )

  def createTenant(req: TenantRequest): Out[TenantResponse] =
    if req.name.isEmpty then
      IO.pure(Left((StatusCode.BadRequest,
        ErrorResponse("invalid_name", "tenant name must be non-empty"))))
    else
      sup.createTenant(Tenant(req.name, req.metastore)).map {
        case Right(t)  => Right(toResponse(t))
        case Left(msg) => Left((StatusCode.Conflict, ErrorResponse("exists", msg)))
      }

  def listTenants(): Out[TenantListResponse] = IO.delay(
    Right(TenantListResponse(sup.listTenants().map(toResponse)))
  )

  def setTenantMetastore(req: TenantRequest): Out[TenantResponse] =
    sup.setTenantMetastore(req.name, req.metastore).map {
      case Some(t) => Right(toResponse(t))
      case None    =>
        Left((StatusCode.NotFound,
              ErrorResponse("not_found", s"tenant '${req.name}' not found")))
    }

  def deleteTenant(req: TenantOpRequest): Out[Unit] =
    sup.deleteTenant(req.name).map {
      case Right(_)  => Right(())
      case Left(msg) =>
        // Distinguish "not found" from "still has pools" by message prefix so
        // the client can choose the right status. Both are 4xx conditions.
        if msg.startsWith("tenant not found") then
          Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
        else
          Left((StatusCode.Conflict, ErrorResponse("has_pools", msg)))
    }
