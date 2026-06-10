package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.state.FederatedSourceStore
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for the `qodstate_tenant_db` rows owned by each tenant. Identified by the natural
  * `(tenant, name)` pair; the surrogate `id` ships on responses for rename-stable callers.
  *
  * `federatedStore` is optional: file-mode deployments don't have a federation store wired up, so
  * the count is reported as 0.
  */
final class TenantDbHandlers(
    sup: PoolSupervisor,
    federatedStore: Option[FederatedSourceStore] = None
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def redact(m: Map[String, String]): Map[String, String] =
    m.filterNot(_._1.equalsIgnoreCase("pgPassword"))

  private def federatedCount(tenantDbId: String): Int =
    federatedStore.fold(0)(_.listSources(tenantDbId).size)

  private def toResponse(tenantName: String, td: TenantDb): TenantDbResponse =
    TenantDbResponse(
      id = td.id,
      tenant = tenantName,
      name = td.name,
      kind = td.kind.wireValue,
      metastore = redact(td.metastore),
      dataPath = td.dataPath,
      objectStore = redact(td.objectStore),
      defaultDatabase = td.defaultDatabase,
      defaultSchema = td.defaultSchema,
      disabled = td.disabled,
      federatedSourceCount = federatedCount(td.id)
    )

  def createTenantDb(req: TenantDbRequest): Out[TenantDbResponse] =
    if req.tenant.isEmpty || req.name.isEmpty then
      IO.pure(
        Left((StatusCode.BadRequest, ErrorResponse("invalid", "tenant and name must be non-empty")))
      )
    else
      TenantDbKind.fromWire(req.kind) match
        case Left(err) =>
          IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid_kind", err))))
        case Right(kind) =>
          sup
            .createTenantDb(
              tenantName = req.tenant,
              suffix = req.name,
              kind = kind,
              metastore = req.metastore,
              dataPath = req.dataPath,
              objectStore = req.objectStore,
              defaultDatabase = req.defaultDatabase,
              defaultSchema = req.defaultSchema
            )
            .map {
              case Right(td) => Right(toResponse(req.tenant, td))
              case Left(msg) if msg.startsWith("tenant not found") =>
                Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
              case Left(msg) if msg.startsWith("invalid kind=") =>
                Left((StatusCode.BadRequest, ErrorResponse("invalid_contract", msg)))
              case Left(msg) =>
                Left((StatusCode.Conflict, ErrorResponse("exists", msg)))
            }

  def listTenantDbs(tenant: String): Out[TenantDbListResponse] = IO.delay {
    Right(
      TenantDbListResponse(
        sup.listTenantDbsByTenant(tenant).map(td => toResponse(tenant, td))
      )
    )
  }

  def deleteTenantDb(req: TenantDbOpRequest): Out[Unit] =
    sup.deleteTenantDb(req.tenant, req.name).map {
      case Right(_)                                 => Right(())
      case Left(msg) if msg.contains("active pool") =>
        Left((StatusCode.Conflict, ErrorResponse("has_pools", msg)))
      case Left(msg) =>
        Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
    }
