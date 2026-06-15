package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.RoleRowPolicy
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the row-policy surface (`/api/role/row-policy/...`).
  *
  * Every mutating endpoint enforces a per-request tenant-scope check before touching the store
  * (resource-id path: resolve owning tenant via [[PoolSupervisor.tenantForRowPolicy]] /
  * [[PoolSupervisor.tenantForRole]], then gate via [[TenantScopeCheck.rejectForResource]]). See
  * [[TenantScopeCheck]].
  */
final class RoleRowPolicyHandlers(sup: PoolSupervisor):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def create(req: CreateRowPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RowPolicyDto] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRole(req.roleId))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup
          .createRowPolicy(
            req.roleId,
            req.catalogName,
            req.schemaName,
            req.tableName,
            req.predicateSql
          )
          .map {
            case Right(p)     => Right(toDto(p))
            case Left(reason) =>
              Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", reason)))
          }

  def update(req: UpdateRowPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRowPolicy(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.updateRowPolicy(req.id, req.predicateSql).map {
          case Right(())                          => Right(())
          case Left(r) if r.endsWith("not found") =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", r)))
          case Left(r) =>
            Left((StatusCode.BadRequest, ErrorResponse("invalid_policy", r)))
        }

  def delete(req: DeleteRowPolicyRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.rejectForResource(apiKey, sup.tenantForRowPolicy(req.id))(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        sup.deleteRowPolicy(req.id).map {
          case Right(()) => Right(())
          case Left(r)   => Left((StatusCode.NotFound, ErrorResponse("not_found", r)))
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
