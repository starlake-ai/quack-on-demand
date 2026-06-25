package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import sttp.model.StatusCode

final class PoolHandlers(sup: PoolSupervisor, tracker: NodeLoadTracker):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  /** Exposed for `/api/config/client` so the UI can hide the placement controls in non-K8s mode.
    */
  def supportsPlacement: Boolean = sup.supportsPlacement

  /** Hide secret-like keys from the API response. Today: just `pgPassword`. */
  private def redact(metastore: Map[String, String]): Map[String, String] =
    metastore.filterNot(_._1.equalsIgnoreCase("pgPassword"))

  /** Build the response for an existing pool by looking up its supervisor state. */
  private def respond(key: PoolKey): Option[PoolResponse] =
    sup.get(key).map { p =>
      val poolEntityCohorts = sup.poolId(key).flatMap(sup.poolEntity).map(_.cohorts).getOrElse(Nil)
      PoolResponse(
        tenant = key.tenant,
        tenantDb = key.tenantDb,
        pool = key.pool,
        nodes = p.nodes.map { n =>
          val load            = tracker.snapshot(n.nodeId)
          val (p50, p95, p99) = tracker.latencyPercentiles(n.nodeId)
          NodeInfo(
            nodeId = n.nodeId,
            role = n.role.toString,
            host = n.host,
            port = n.port,
            maxConcurrent = n.maxConcurrent,
            inFlight = load.inFlight,
            totalServed = load.totalServed,
            avgDurationMs = load.ewmaMs,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            healthy = load.healthy,
            draining = load.draining
          )
        },
        status = if p.disabled then "disabled" else "ready",
        metastore = redact(p.metastore),
        disabled = p.disabled,
        id = sup.poolId(key).getOrElse(""),
        cohorts = poolEntityCohorts.map(PoolCohortDto.fromModel),
        initSql = p.initSql
      )
    }

  def createPool(req: CreatePoolRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      => createPoolInner(req)

  private def createPoolInner(req: CreatePoolRequest): Out[PoolResponse] =
    if !req.roleDistribution.isValidFor(req.size) then
      IO.pure(
        Left(
          (
            StatusCode.BadRequest,
            ErrorResponse("invalid_distribution", "role counts do not sum to size")
          )
        )
      )
    else
      val key = PoolKey(req.tenant, req.tenantDb, req.pool)
      sup.getTenant(req.tenant) match
        case None =>
          IO.pure(
            Left(
              (
                StatusCode.NotFound,
                ErrorResponse("tenant_not_found", s"tenant '${req.tenant}' is not registered")
              )
            )
          )
        case Some(_) if sup.findTenantDb(req.tenant, req.tenantDb).isEmpty =>
          IO.pure(
            Left(
              (
                StatusCode.NotFound,
                ErrorResponse(
                  "tenant_db_not_found",
                  s"tenant-db '${req.tenant}/${req.tenantDb}' is not registered"
                )
              )
            )
          )
        case Some(_) =>
          sup.get(key) match
            case Some(_) =>
              IO.pure(
                Left((StatusCode.Conflict, ErrorResponse("exists", s"pool $key already exists")))
              )
            case None =>
              val cohorts = req.cohorts.map(PoolCohortDto.toModel)
              sup
                .createPool(
                  key,
                  req.roleDistribution,
                  req.maxConcurrentPerNode,
                  cohorts,
                  req.disabled,
                  req.initSql.getOrElse("")
                )
                .map(_ =>
                  Right(
                    respond(key).getOrElse(
                      PoolResponse(req.tenant, req.tenantDb, req.pool, Nil, "ready", Map.empty)
                    )
                  )
                )
                .handleError(t =>
                  Left(
                    (StatusCode.InternalServerError, ErrorResponse("start_failed", t.getMessage))
                  )
                )

  def scalePool(req: ScalePoolRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        val key = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.get(key) match
          case None =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case Some(_) =>
            if !req.roleDistribution.isValidFor(req.targetSize) then
              IO.pure(
                Left(
                  (
                    StatusCode.BadRequest,
                    ErrorResponse("invalid_distribution", "role counts do not sum to targetSize")
                  )
                )
              )
            else
              sup
                .scale(key, req.targetSize, req.roleDistribution, req.force)
                .map(_ =>
                  Right(
                    respond(key).getOrElse(
                      PoolResponse(req.tenant, req.tenantDb, req.pool, Nil, "ready", Map.empty)
                    )
                  )
                )

  def stopPool(req: StopPoolRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        val key = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.get(key) match
          case None =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case Some(_) =>
            sup.stopPool(key, req.force).map(_ => Right(()))

  def deletePool(req: DeletePoolRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        val key = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.get(key) match
          case None =>
            IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))
          case Some(_) =>
            sup.deletePool(key, req.force).map(_ => Right(()))

  def listPools(apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[PoolListResponse] = IO.delay {
    val all      = sup.list().flatMap(p => respond(p.key))
    val filtered = apiKey.flatMap(scopeOf) match
      case None                   => all // no session => static-key trusted admin
      case Some(s) if s.superuser => all
      case Some(s)                =>
        // PoolResponse.tenant holds the display name. Resolve each manageable
        // tenant id to a display name and filter by that set. Deleted tenants
        // simply drop out.
        val allowedDisplay = sup
          .listTenants()
          .collect {
            case t if s.manageableTenants.contains(t.id) => t.displayName
          }
          .toSet
        all.filter(p => allowedDisplay.contains(p.tenant))
    Right(PoolListResponse(filtered))
  }

  def poolStatus(tenant: String, tenantDb: String, pool: String): Out[PoolResponse] =
    val key = PoolKey(tenant, tenantDb, pool)
    respond(key) match
      case Some(r) => IO.pure(Right(r))
      case None    =>
        IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", s"pool $key not found"))))

  def setPoolDisabled(req: SetPoolDisabledRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[PoolResponse] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        val key = PoolKey(req.tenant, req.tenantDb, req.pool)
        sup.setPoolDisabled(key, req.disabled).map {
          case Right(_) =>
            respond(key) match
              case Some(r) => Right(r)
              case None    =>
                Left(
                  (
                    StatusCode.NotFound,
                    ErrorResponse("not_found", s"pool $key disappeared after update")
                  )
                )
          case Left(msg) =>
            if msg.startsWith("pool not found") then
              Left((StatusCode.NotFound, ErrorResponse("not_found", msg)))
            else Left((StatusCode.Conflict, ErrorResponse("update_failed", msg)))
        }
