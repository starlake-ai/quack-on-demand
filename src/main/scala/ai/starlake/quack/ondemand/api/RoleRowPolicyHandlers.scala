package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.RoleRowPolicy
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the row-policy surface (`/api/role/row-policy/...`).
  *
  * Every mutating endpoint enforces a per-request tenant-scope check before touching the store
  * (resource-id path: resolve owning tenant via [[PoolSupervisor.tenantForRowPolicy]] /
  * [[PoolSupervisor.tenantForRole]], then gate via [[TenantScopeCheck.rejectForResource]]). See
  * [[TenantScopeCheck]].
  */
final class RoleRowPolicyHandlers(
    sup: PoolSupervisor,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def create(req: CreateRowPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RowPolicyDto] =
    val roleTenant = sup.tenantForRole(req.roleId)
    TenantScopeCheck.rejectForResource(apiKey, roleTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.RoleRowPolicySet,
          "denied",
          tenant = roleTenant
        )
        IO.pure(Left(err))
      case None =>
        sup
          .createRowPolicy(
            req.roleId,
            req.catalogName,
            req.schemaName,
            req.tableName,
            req.predicateSql
          )
          .map {
            case Right(p) =>
              audit.rest(
                apiKey,
                "control-plane",
                AuditActions.RoleRowPolicySet,
                "ok",
                tenant = roleTenant,
                target = Some(s"${req.roleId}/${req.tableName}"),
                detail = Map("table" -> req.tableName)
              )
              Right(toDto(p))
            case Left(err) =>
              Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", err.message)))
          }

  def update(req: UpdateRowPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val policyTenant = sup.tenantForRowPolicy(req.id)
    TenantScopeCheck.rejectForResource(apiKey, policyTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.RoleRowPolicySet,
          "denied",
          tenant = policyTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.updateRowPolicy(req.id, req.predicateSql).map {
          case Right(()) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.RoleRowPolicySet,
              "ok",
              tenant = policyTenant,
              target = Some(req.id)
            )
            Right(())
          case Left(err: SupervisorError.NotFound) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
          case Left(err) =>
            Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", err.message)))
        }

  def delete(req: DeleteRowPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val policyTenant = sup.tenantForRowPolicy(req.id)
    TenantScopeCheck.rejectForResource(apiKey, policyTenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.RoleRowPolicyDelete,
          "denied",
          tenant = policyTenant
        )
        IO.pure(Left(err))
      case None =>
        sup.deleteRowPolicy(req.id).map {
          case Right(()) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.RoleRowPolicyDelete,
              "ok",
              tenant = policyTenant,
              target = Some(req.id)
            )
            Right(())
          case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
        }

  def list(roleId: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RowPolicyListResponse] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRole(roleId))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup
          .listRowPoliciesByRole(roleId)
          .map(ps => Right(RowPolicyListResponse(ps.map(toDto))))

  private def toDto(p: RoleRowPolicy): RowPolicyDto =
    RowPolicyDto(
      p.id,
      p.roleId,
      p.catalogName,
      p.schemaName,
      p.tableName,
      p.predicateSql
    )
