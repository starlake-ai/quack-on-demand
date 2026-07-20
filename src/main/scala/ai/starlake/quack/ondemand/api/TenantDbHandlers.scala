package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError, TenantDbPatch}
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
    HandlerResolvers.redactPassword(m)

  private def federatedCount(tenantDbId: String): Int =
    federatedStore.fold(0)(_.listSources(tenantDbId).size)

  /** Sum of per-schema table counts via the catalog reader. None for non-DuckLake kinds and on any
    * reader failure: the list call must never fail because a catalog is unreachable.
    */
  private def tableCountFor(tenantName: String, td: TenantDb): Option[Int] =
    if td.kind != TenantDbKind.DuckLake then None
    else
      catalog.flatMap { c =>
        // Unscoped on purpose: the calling handler has already scope-checked
        // the tenant, and this is an in-process aggregation, not a route.
        scala.util.Try(c.listSchemasUnscoped(tenantName, td.name).map(_.tableCount).sum).toOption
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
              val gateBypass = SuperuserCheck.reject(apiKey)(scopeOf).isEmpty
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
                  initSql = req.initSql,
                  gateBypass = gateBypass
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
                  case Left(err: SupervisorError.NotFound) =>
                    IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", err.message))))
                  case Left(err: SupervisorError.InvalidArgument) =>
                    IO.pure(
                      Left((StatusCode.BadRequest, ErrorResponse("invalid_contract", err.message)))
                    )
                  case Left(err: SupervisorError.QuotaExceeded) =>
                    audit.rest(
                      apiKey,
                      "control-plane",
                      AuditActions.DatabaseCreate,
                      "denied",
                      tenant = Some(req.tenant),
                      detail = Map("reason" -> "quota")
                    )
                    IO.pure(
                      Left(
                        (StatusCode.TooManyRequests, ErrorResponse("quota_exceeded", err.message))
                      )
                    )
                  case Left(err) =>
                    IO.pure(Left((StatusCode.Conflict, ErrorResponse("exists", err.message))))
                }

  def listTenantDbs(tenant: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[TenantDbListResponse] =
    // Resolve to the tenant id before the scope check (the query param may be a
    // display name); an unknown tenant falls through on the raw value, so an
    // out-of-scope session gets the same 403 whether the tenant exists or not
    // (no existence leak) while superuser / static-key / open callers keep
    // today's empty-list response.
    val tid = sup.getTenantById(tenant).orElse(sup.getTenant(tenant)).map(_.id).getOrElse(tenant)
    TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        IO.blocking {
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
          case Left(err: SupervisorError.Conflict) =>
            Left((StatusCode.Conflict, ErrorResponse("has_pools", err.message)))
          case Left(err) =>
            Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
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
          case Left(err: SupervisorError.InvalidArgument) =>
            IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid", err.message))))
          case Left(err) =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", err.message))))
        }
