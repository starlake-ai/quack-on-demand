package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.TenantLocation
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.{TenantLocationPush, TenantLocationSecrets}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** A tenant's authorized locations (in-engine authorization, decisions 15 and 18): the S3 buckets
  * and local folders its non-superuser users may read and write files in. Superusers and the
  * tenant's admins only (TenantScopeCheck); a regular user's profile session never gets here
  * (apiKeyGuard answers 403 admin_required). An s3 location's secret is write-only: no response
  * carries it and the audit rows name the location, never a credential. An add or a remove is
  * pushed to the tenant's running nodes at once; a node that misses it is named in the response.
  * `filesRoot` is QOD_TENANT_FILES_ROOT (None: no local folder can be authorized).
  */
final class TenantLocationHandlers(
    sup: PoolSupervisor,
    filesRoot: Option[String],
    push: TenantLocationPush,
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
        sup
          .addTenantLocation(
            req.tenant,
            req.location,
            req.objectStore.getOrElse(Map.empty),
            filesRoot,
            createdBy
          )
          .flatMap {
            case Left(e)  => IO.pure(Left(TenantLocationHandlers.errorOf(e)))
            case Right(l) =>
              push(
                l.tenantId,
                s"${TenantLocationSecrets.name(l)} add",
                TenantLocationSecrets.pushAdd(l)
              )
                .map { o =>
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.TenantLocationAdd,
                    "ok",
                    tenant = Some(l.tenantId),
                    target = Some(l.location),
                    detail = Map("kind" -> l.kind)
                  )
                  Right(TenantLocationHandlers.changed(l, o))
                }
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
        sup.removeTenantLocation(req.tenant, req.id).flatMap {
          case Left(e)  => IO.pure(Left(TenantLocationHandlers.errorOf(e)))
          case Right(l) =>
            push(
              l.tenantId,
              s"${TenantLocationSecrets.name(l)} remove",
              TenantLocationSecrets.pushRemove(l)
            ).map { o =>
              audit.rest(
                apiKey,
                "control-plane",
                AuditActions.TenantLocationRemove,
                "ok",
                tenant = Some(l.tenantId),
                target = Some(l.location),
                detail = Map("kind" -> l.kind)
              )
              Right(TenantLocationHandlers.changed(l, o))
            }
        }

object TenantLocationHandlers:

  def toDto(l: TenantLocation): TenantLocationDto =
    TenantLocationDto(
      id = l.id,
      tenant = l.tenantId,
      kind = l.kind,
      location = l.location,
      objectStore = TenantLocation.withoutSecrets(l.objectStore),
      secretSet = TenantLocation.SecretKeys.exists(l.objectStore.contains),
      createdAt = l.createdAt,
      createdBy = l.createdBy
    )

  private def changed(l: TenantLocation, o: TenantLocationPush.Outcome) =
    TenantLocationChangeResponse(toDto(l), o.updated.size, o.failed)

  def errorOf(e: SupervisorError): (StatusCode, ErrorResponse) = e match
    case SupervisorError.NotFound(m)      => StatusCode.NotFound -> ErrorResponse("not_found", m)
    case SupervisorError.AlreadyExists(m) =>
      StatusCode.Conflict -> ErrorResponse("location_exists", m)
    case SupervisorError.InvalidArgument(m) =>
      m.split(": ", 2) match
        case Array(code, msg) => StatusCode.BadRequest -> ErrorResponse(code, msg)
        case _                => StatusCode.BadRequest -> ErrorResponse("invalid_location", m)
    case other => StatusCode.Conflict -> ErrorResponse("update_failed", other.message)
