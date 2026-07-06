package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.{PoolSupervisor, TenantDbPatch}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.FederatedSourceStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
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
    federatedStore: Option[FederatedSourceStore] = None,
    catalog: Option[CatalogHandlers] = None,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def redact(m: Map[String, String]): Map[String, String] =
    m.filterNot(_._1.equalsIgnoreCase("pgPassword"))

  private def federatedCount(tenantDbId: String): Int =
    federatedStore.fold(0)(_.listSources(tenantDbId).size)

  /** Sum of per-schema table counts via the catalog reader. None for non-DuckLake kinds and on any
    * reader failure: the list call must never fail because a catalog is unreachable.
    */
  private def tableCountFor(tenantName: String, td: TenantDb): Option[Int] =
    if td.kind != TenantDbKind.DuckLake then None
    else
      catalog.flatMap { c =>
        scala.util.Try(c.listSchemas(tenantName, td.name).map(_.tableCount).sum).toOption
      }

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
      federatedSourceCount = federatedCount(td.id),
      initSql = td.initSql,
      effectiveDataPath = sup.effectiveMetastoreFor(tenantName, td.name).getOrElse("dataPath", ""),
      tableCount = tableCountFor(tenantName, td)
    )

  def createTenantDb(req: TenantDbRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantDbResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.DatabaseCreate,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        if req.tenant.isEmpty || req.name.isEmpty then
          IO.pure(
            Left(
              (StatusCode.BadRequest, ErrorResponse("invalid", "tenant and name must be non-empty"))
            )
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
                  defaultSchema = req.defaultSchema,
                  initSql = req.initSql
                )
                .flatMap {
                  case Right(td) =>
                    audit.rest(
                      apiKey,
                      "control-plane",
                      AuditActions.DatabaseCreate,
                      "ok",
                      tenant = Some(req.tenant),
                      target = Some(td.name),
                      detail = Map("kind" -> kind.wireValue)
                    )
                    IO.blocking(Right(toResponse(req.tenant, td)))
                  case Left(msg) if msg.startsWith("tenant not found") =>
                    IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", msg))))
                  case Left(msg) if msg.startsWith("invalid kind=") =>
                    IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid_contract", msg))))
                  case Left(msg) =>
                    IO.pure(Left((StatusCode.Conflict, ErrorResponse("exists", msg))))
                }

  def listTenantDbs(tenant: String): Out[TenantDbListResponse] = IO.blocking {
    Right(
      TenantDbListResponse(
        sup.listTenantDbsByTenant(tenant).map(td => toResponse(tenant, td))
      )
    )
  }

  def deleteTenantDb(req: TenantDbOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.DatabaseDelete,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        sup.deleteTenantDb(req.tenant, req.name).map {
          case Right(_) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.DatabaseDelete,
              "ok",
              tenant = Some(req.tenant),
              target = Some(req.name)
            )
            Right(())
          case Left(msg) if msg.contains("active pool") =>
            Left((StatusCode.Conflict, ErrorResponse("has_pools", msg)))
          case Left(msg) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
        }

  def update(req: UpdateTenantDbRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[UpdateTenantDbResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.DatabaseUpdate,
          "denied",
          tenant = Some(req.tenant)
        )
        IO.pure(Left(err))
      case None =>
        val patch = TenantDbPatch(
          metastore = req.metastore,
          objectStore = req.objectStore,
          defaultDatabase = req.defaultDatabase,
          defaultSchema = req.defaultSchema,
          initSql = req.initSql
        )
        sup.updateTenantDb(req.tenant, req.name, patch).flatMap {
          case Right(r) =>
            // Record field names ONLY (never values - metastore / objectStore may contain credentials).
            val editedFields = List(
              req.metastore.map(_ => "metastore"),
              req.objectStore.map(_ => "objectStore"),
              req.defaultDatabase.map(_ => "defaultDatabase"),
              req.defaultSchema.map(_ => "defaultSchema"),
              req.initSql.map(_ => "initSql")
            ).flatten.mkString(",")
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.DatabaseUpdate,
              "ok",
              tenant = Some(req.tenant),
              target = Some(req.name),
              detail =
                if editedFields.nonEmpty then Map("editedFields" -> editedFields) else Map.empty
            )
            IO.blocking(
              Right(
                UpdateTenantDbResponse(
                  db = toResponse(req.tenant, r.td),
                  restartedNodes = r.restartedNodes,
                  failedRestarts = r.failedRestarts.map(FailedRestart.apply.tupled)
                )
              )
            )
          case Left(msg) if msg.startsWith("invalid") =>
            IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid", msg))))
          case Left(msg) =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", msg))))
        }
