package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for pool grants (`/api/pool/permission/...`). Resolves `tenant` (display name OR
  * id) the same way the role/group handlers do. The grant principal is either `userId` or
  * `groupId`; passing both is a 400.
  */
final class PoolPermissionHandlers(
    sup: PoolSupervisor,
    mappers: UserHandlers,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def resolveTenantId(raw: String): Option[String] =
    HandlerResolvers.resolveTenantId(sup, raw)

  def grant(req: PoolPermissionGrantRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PoolPermissionResponse] =
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
              AuditActions.PoolPermissionGrant,
              "denied",
              tenant = Some(tid)
            )
            IO.pure(Left(err))
          case None =>
            sup.grantPoolPermission(tid, req.poolId, req.userId, req.groupId).map {
              case Right(p) =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.PoolPermissionGrant,
                  "ok",
                  tenant = Some(tid),
                  target = Some(p.id)
                )
                Right(mappers.toPoolPermissionResponse(p))
              case Left(err) =>
                val code = err match
                  case SupervisorError.NotFound(_) => StatusCode.NotFound
                  case _                           => StatusCode.BadRequest
                Left(
                  SupervisorErrorHttp
                    .special(err)
                    .getOrElse((code, ErrorResponse("invalid_grant", err.message)))
                )
            }

  def revoke(req: PoolPermissionRevokeRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    val permTenant = sup.tenantForPoolPermission(req.id)
    TenantScopeCheck.rejectForResource(apiKey, permTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.PoolPermissionRevoke,
          "denied",
          tenant = permTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.revokePoolPermission(req.id).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.PoolPermissionRevoke,
              "ok",
              tenant = permTenant,
              target = Some(req.id)
            )
            Right(())
          case Left(err) =>
            Left(
              SupervisorErrorHttp
                .special(err)
                .getOrElse((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
            )
        }

  /** Tenant-scope semantics: if `tenant` is supplied, check against that. If not, clamp the
    * response to permissions whose `tenantId` is in `manageableTenants` (superusers see all).
    */
  def list(
      tenant: Option[String],
      userId: Option[String],
      groupId: Option[String],
      apiKey: Option[String]
  )(
      scopeOf: SessionScope.Resolver
  ): Out[PoolPermissionListResponse] = IO.blocking {
    val tenantId = tenant.flatMap(resolveTenantId)
    val scope    = scopeOf(apiKey)
    tenantId.flatMap(TenantScopeCheck.forScope(scope, _)) match
      case Some(err) => Left(err)
      case None      =>
        val perms    = sup.listPoolPermissions(tenantId, userId, groupId)
        val filtered =
          if scope.superuser then perms
          else perms.filter(p => scope.manageableTenants.contains(p.tenantId))
        Right(PoolPermissionListResponse(filtered.map(mappers.toPoolPermissionResponse)))
  }
