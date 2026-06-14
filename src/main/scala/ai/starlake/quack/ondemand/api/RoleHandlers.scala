package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the role surface (`/api/role/...`). Resolves the `tenant` query/body field to
  * a surrogate id via the supervisor's tenant cache so the UI can keep typing display names while
  * the Postgres rows stay keyed by `qodstate_tenant.id`.
  *
  * Every mutating endpoint enforces a per-request tenant-scope check before touching the store
  * (body-tenant for create/list; id-of-resource for delete/grant/revoke/list). See
  * [[TenantScopeCheck]].
  */
final class RoleHandlers(sup: PoolSupervisor, mappers: UserHandlers):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def resolveTenantId(raw: String): Option[String] =
    val tenants = sup.listTenants()
    tenants
      .find(_.id == raw)
      .map(_.id)
      .orElse(tenants.find(_.displayName == raw.toLowerCase).map(_.id))

  def createRole(req: RoleCreateRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RoleResponse] =
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
            sup.createRole(tid, req.name, req.description).map {
              case Right(r)  => Right(mappers.toRoleResponse(r))
              case Left(err) =>
                val code =
                  if err.startsWith("role '") then StatusCode.Conflict else StatusCode.BadRequest
                Left((code, ErrorResponse("invalid_role", err)))
            }

  def deleteRole(req: RoleDeleteRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRole(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.deleteRole(req.id).map {
          case Right(_)  => Right(())
          case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err)))
        }

  def listRoles(tenant: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RoleListResponse] = IO.blocking {
    resolveTenantId(tenant) match
      case None      => Right(RoleListResponse(Nil))
      case Some(tid) =>
        TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
          case Some(err) => Left(err)
          case None      =>
            Right(RoleListResponse(sup.listRoles(tid).map(mappers.toRoleResponse)))
  }

  def grantPermission(req: RolePermissionGrantRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RolePermissionResponse] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRole(req.roleId))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.grantRolePermission(req.roleId, req.catalog, req.schema, req.table, req.verb).map {
          case Right(p)  => Right(mappers.toRolePermissionResponse(p))
          case Left(err) =>
            val code =
              if err.startsWith("role not found") then StatusCode.NotFound
              else StatusCode.BadRequest
            Left((code, ErrorResponse("invalid_permission", err)))
        }

  def revokePermission(req: RolePermissionRevokeRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRolePermission(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.revokeRolePermission(req.id).map {
          case Right(_)  => Right(())
          case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err)))
        }

  def listPermissions(roleId: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RolePermissionListResponse] = IO.blocking {
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRole(roleId))(scopeOf) match
      case Some(err) => Left(err)
      case None      =>
        Right(
          RolePermissionListResponse(
            sup.listRolePermissions(roleId).map(mappers.toRolePermissionResponse)
          )
        )
  }
