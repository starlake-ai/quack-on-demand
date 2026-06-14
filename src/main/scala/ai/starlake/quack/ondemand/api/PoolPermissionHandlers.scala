package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for pool grants (`/api/pool/permission/...`). Resolves `tenant` (display name OR
  * id) the same way the role/group handlers do. The grant principal is either `userId` or
  * `groupId`; passing both is a 400.
  */
final class PoolPermissionHandlers(sup: PoolSupervisor, mappers: UserHandlers):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def resolveTenantId(raw: String): Option[String] =
    val tenants = sup.listTenants()
    tenants
      .find(_.id == raw)
      .map(_.id)
      .orElse(tenants.find(_.displayName == raw.toLowerCase).map(_.id))

  def grant(req: PoolPermissionGrantRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
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
          case Some(err) => IO.pure(Left(err))
          case None      =>
            sup.grantPoolPermission(tid, req.poolId, req.userId, req.groupId).map {
              case Right(p)  => Right(mappers.toPoolPermissionResponse(p))
              case Left(err) =>
                val code =
                  if err.contains("not found") then StatusCode.NotFound
                  else StatusCode.BadRequest
                Left((code, ErrorResponse("invalid_grant", err)))
            }

  def revoke(req: PoolPermissionRevokeRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForPoolPermission(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.revokePoolPermission(req.id).map {
          case Right(_)  => Right(())
          case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err)))
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
      scopeOf: String => Option[SessionScope]
  ): Out[PoolPermissionListResponse] = IO.blocking {
    val tenantId                                  = tenant.flatMap(resolveTenantId)
    val scope                                     = apiKey.flatMap(scopeOf)
    val gate: Option[(StatusCode, ErrorResponse)] = (scope, tenantId) match
      case (Some(s), Some(t)) if !s.superuser && !s.manageableTenants.contains(t) =>
        Some(
          StatusCode.Forbidden -> ErrorResponse(
            "tenant_forbidden",
            s"session has no admin grant on tenant '$t'"
          )
        )
      case _ => None
    gate match
      case Some(err) => Left(err)
      case None      =>
        val perms    = sup.listPoolPermissions(tenantId, userId, groupId)
        val filtered = scope match
          case Some(s) if !s.superuser =>
            perms.filter(p => s.manageableTenants.contains(p.tenantId))
          case _ => perms
        Right(PoolPermissionListResponse(filtered.map(mappers.toPoolPermissionResponse)))
  }
