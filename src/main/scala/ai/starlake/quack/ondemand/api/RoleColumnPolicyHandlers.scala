package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the column-policy surface (`/api/role/column-policy/...`).
  *
  * Every mutating endpoint enforces a per-request tenant-scope check before touching the store
  * (resource-id path: resolve owning tenant via [[PoolSupervisor.tenantForColumnPolicy]] /
  * [[PoolSupervisor.tenantForRole]], then gate via [[TenantScopeCheck.rejectForResource]]). See
  * [[TenantScopeCheck]].
  */
final class RoleColumnPolicyHandlers(
    sup: PoolSupervisor,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def create(req: CreateColumnPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[ColumnPolicyDto] =
    val roleTenant = sup.tenantForRole(req.roleId)
    TenantScopeCheck.rejectForResource(apiKey, roleTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          "role.columnPolicy.set",
          "denied",
          tenant = roleTenant
        )
        IO.pure(Left(err))
      case None =>
        sup
          .createColumnPolicy(
            req.roleId,
            req.catalogName,
            req.schemaName,
            req.tableName,
            req.columnName,
            req.action,
            req.transformSql
          )
          .map {
            case Right(p) =>
              audit.rest(
                apiKey,
                "control-plane",
                "role.columnPolicy.set",
                "ok",
                tenant = roleTenant,
                target = Some(s"${req.roleId}/${req.tableName}/${req.columnName}"),
                detail =
                  Map("table" -> req.tableName, "column" -> req.columnName, "action" -> req.action)
              )
              Right(toDto(p))
            case Left(reason) =>
              Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", reason)))
          }

  def update(req: UpdateColumnPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val policyTenant = sup.tenantForColumnPolicy(req.id)
    TenantScopeCheck.rejectForResource(apiKey, policyTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          "role.columnPolicy.set",
          "denied",
          tenant = policyTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.updateColumnPolicy(req.id, req.action, req.transformSql).map {
          case Right(()) =>
            audit.rest(
              apiKey,
              "control-plane",
              "role.columnPolicy.set",
              "ok",
              tenant = policyTenant,
              target = Some(req.id)
            )
            Right(())
          case Left(r) if r.endsWith("not found") =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", r)))
          case Left(r) =>
            Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", r)))
        }

  def delete(req: DeleteColumnPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val policyTenant = sup.tenantForColumnPolicy(req.id)
    TenantScopeCheck.rejectForResource(apiKey, policyTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          "role.columnPolicy.delete",
          "denied",
          tenant = policyTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.deleteColumnPolicy(req.id).map {
          case Right(()) =>
            audit.rest(
              apiKey,
              "control-plane",
              "role.columnPolicy.delete",
              "ok",
              tenant = policyTenant,
              target = Some(req.id)
            )
            Right(())
          case Left(r) => Left((StatusCode.NotFound, ErrorResponse("not_found", r)))
        }

  def list(roleId: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[ColumnPolicyListResponse] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRole(roleId))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup
          .listColumnPoliciesByRole(roleId)
          .map(ps => Right(ColumnPolicyListResponse(ps.map(toDto))))

  private def toDto(p: RoleColumnPolicy): ColumnPolicyDto =
    ColumnPolicyDto(
      p.id,
      p.roleId,
      p.catalogName,
      p.schemaName,
      p.tableName,
      p.columnName,
      p.action,
      p.transformSql
    )
