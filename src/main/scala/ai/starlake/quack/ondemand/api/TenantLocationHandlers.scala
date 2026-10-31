package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.TenantLocation
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** A tenant's authorized locations (in-engine authorization, decision 15): the local folders its
  * non-superuser users may read and write files in, under the operator's root. Superusers and the
  * tenant's admins only (TenantScopeCheck); a regular user's profile session never gets here
  * (apiKeyGuard answers 403 admin_required). S3 is per user (decision 23,
  * UserS3CredentialHandlers). `filesRoot` is QOD_TENANT_FILES_ROOT (None: no local folder can be
  * authorized).
  */
final class TenantLocationHandlers(
    sup: PoolSupervisor,
    filesRoot: Option[String],
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def list(req: TenantLocationListRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[TenantLocationListResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        IO.blocking(sup.listTenantLocations(req.tenant)).map {
          case Right(ls) => Right(TenantLocationListResponse(ls.map(TenantLocationHandlers.toDto)))
          case Left(e)   => Left(TenantLocationHandlers.errorOf(e))
        }

  def add(req: AddTenantLocationRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[TenantLocationChangeResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.TenantLocationAdd,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val createdBy = Some(audit.actorOf(apiKey)._1).filter(_ != "anonymous")
        sup.addTenantLocation(req.tenant, req.location, filesRoot, createdBy).map {
          case Left(e)  => Left(TenantLocationHandlers.errorOf(e))
          case Right(l) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.TenantLocationAdd,
              "ok",
              tenant = Some(l.tenantId),
              target = Some(l.location),
              detail = Map("kind" -> l.kind)
            )
            Right(TenantLocationChangeResponse(TenantLocationHandlers.toDto(l)))
        }

  def remove(req: RemoveTenantLocationRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[TenantLocationChangeResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.TenantLocationRemove,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        sup.removeTenantLocation(req.tenant, req.id).map {
          case Left(e)  => Left(TenantLocationHandlers.errorOf(e))
          case Right(l) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.TenantLocationRemove,
              "ok",
              tenant = Some(l.tenantId),
              target = Some(l.location),
              detail = Map("kind" -> l.kind)
            )
            Right(TenantLocationChangeResponse(TenantLocationHandlers.toDto(l)))
        }

object TenantLocationHandlers:

  def toDto(l: TenantLocation): TenantLocationDto =
    TenantLocationDto(
      id = l.id,
      tenant = l.tenantId,
      kind = l.kind,
      location = l.location,
      createdAt = l.createdAt,
      createdBy = l.createdBy
    )

  def errorOf(e: SupervisorError): (StatusCode, ErrorResponse) = e match
    case SupervisorError.NotFound(m)      => StatusCode.NotFound -> ErrorResponse("not_found", m)
    case SupervisorError.AlreadyExists(m) =>
      StatusCode.Conflict -> ErrorResponse("location_exists", m)
    case SupervisorError.InvalidArgument(m) =>
      m.split(": ", 2) match
        case Array(code, msg) => StatusCode.BadRequest -> ErrorResponse(code, msg)
        case _                => StatusCode.BadRequest -> ErrorResponse("invalid_location", m)
    case other => StatusCode.Conflict -> ErrorResponse("update_failed", other.message)
