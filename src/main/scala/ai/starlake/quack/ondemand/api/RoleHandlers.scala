package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
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
final class RoleHandlers(
    sup: PoolSupervisor,
    mappers: UserHandlers,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def resolveTenantId(raw: String): Option[String] =
    HandlerResolvers.resolveTenantId(sup, raw)

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
          case Some(err) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.RoleCreate,
              "denied",
              tenant = Some(tid)
            )
            IO.pure(Left(err))
          case None =>
            sup.createRole(tid, req.name, req.description).map {
              case Right(r) =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.RoleCreate,
                  "ok",
                  tenant = Some(tid),
                  target = Some(r.id),
                  detail = Map("name" -> req.name)
                )
                Right(mappers.toRoleResponse(r))
              case Left(err) =>
                val code =
                  if err.startsWith("role '") then StatusCode.Conflict else StatusCode.BadRequest
                Left((code, ErrorResponse("invalid_role", err)))
            }

  def deleteRole(req: RoleDeleteRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val roleTenant = sup.tenantForRole(req.id)
    TenantScopeCheck.rejectForResource(apiKey, roleTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.RoleDelete,
          "denied",
          tenant = roleTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.deleteRole(req.id).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.RoleDelete,
              "ok",
              tenant = roleTenant,
              target = Some(req.id)
            )
            Right(())
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
    val roleTenant = sup.tenantForRole(req.roleId)
    TenantScopeCheck.rejectForResource(apiKey, roleTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.RolePermissionGrant,
          "denied",
          tenant = roleTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.grantRolePermission(req.roleId, req.catalog, req.schema, req.table, req.verb).map {
          case Right(p) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.RolePermissionGrant,
              "ok",
              tenant = roleTenant,
              target = Some(p.id),
              detail = Map(
                "catalog" -> req.catalog,
                "schema"  -> req.schema,
                "table"   -> req.table,
                "verb"    -> req.verb
              )
            )
            Right(mappers.toRolePermissionResponse(p))
          case Left(err) =>
            val code =
              if err.startsWith("role not found") then StatusCode.NotFound
              else StatusCode.BadRequest
            Left((code, ErrorResponse("invalid_permission", err)))
        }

  def revokePermission(req: RolePermissionRevokeRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val permTenant = sup.tenantForRolePermission(req.id)
    TenantScopeCheck.rejectForResource(apiKey, permTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.RolePermissionRevoke,
          "denied",
          tenant = permTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.revokeRolePermission(req.id).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.RolePermissionRevoke,
              "ok",
              tenant = permTenant,
              target = Some(req.id)
            )
            Right(())
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
