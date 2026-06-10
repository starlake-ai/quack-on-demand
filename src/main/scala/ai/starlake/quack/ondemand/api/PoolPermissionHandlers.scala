package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
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

  def grant(req: PoolPermissionGrantRequest): Out[PoolPermissionResponse] =
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
        sup.grantPoolPermission(tid, req.poolId, req.userId, req.groupId).map {
          case Right(p)  => Right(mappers.toPoolPermissionResponse(p))
          case Left(err) =>
            val code =
              if err.contains("not found") then StatusCode.NotFound
              else StatusCode.BadRequest
            Left((code, ErrorResponse("invalid_grant", err)))
        }

  def revoke(req: PoolPermissionRevokeRequest): Out[Unit] =
    sup.revokePoolPermission(req.id).map {
      case Right(_)  => Right(())
      case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err)))
    }

  def list(
      tenant: Option[String],
      userId: Option[String],
      groupId: Option[String]
  ): Out[PoolPermissionListResponse] = IO.blocking {
    val tenantId = tenant.flatMap(resolveTenantId)
    val perms    = sup.listPoolPermissions(tenantId, userId, groupId)
    Right(PoolPermissionListResponse(perms.map(mappers.toPoolPermissionResponse)))
  }
