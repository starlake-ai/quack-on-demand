package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the column-policy surface (`/api/role/column-policy/...`).
  *
  * Every mutating endpoint enforces a per-request tenant-scope check before touching the store
  * (resource-id path: resolve owning tenant via [[PoolSupervisor.tenantForColumnPolicy]] /
  * [[PoolSupervisor.tenantForRole]], then gate via [[TenantScopeCheck.rejectForResource]]). See
  * [[TenantScopeCheck]].
  */
final class RoleColumnPolicyHandlers(sup: PoolSupervisor):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def create(req: CreateColumnPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[ColumnPolicyDto] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRole(req.roleId))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
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
            case Right(p)     => Right(toDto(p))
            case Left(reason) =>
              Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", reason)))
          }

  def update(req: UpdateColumnPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForColumnPolicy(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.updateColumnPolicy(req.id, req.action, req.transformSql).map {
          case Right(())                          => Right(())
          case Left(r) if r.endsWith("not found") =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", r)))
          case Left(r) =>
            Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", r)))
        }

  def delete(req: DeleteColumnPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForColumnPolicy(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.deleteColumnPolicy(req.id).map {
          case Right(()) => Right(())
          case Left(r)   => Left((StatusCode.NotFound, ErrorResponse("not_found", r)))
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
