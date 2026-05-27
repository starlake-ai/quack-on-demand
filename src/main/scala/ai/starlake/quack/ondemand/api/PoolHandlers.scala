package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import sttp.model.StatusCode

final class PoolHandlers(sup: PoolSupervisor, tracker: NodeLoadTracker):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  /** Hide secret-like keys from the API response. Today: just `pgPassword`. */
  private def redact(metastore: Map[String, String]): Map[String, String] =
    metastore.filterNot(_._1.equalsIgnoreCase("pgPassword"))

  /** Build the response for an existing pool by looking up its supervisor state. */
  private def respond(key: PoolKey): Option[PoolResponse] =
    sup.get(key).map { p =>
      PoolResponse(
        tenant    = key.tenant,
        pool      = key.pool,
        nodes     = p.nodes.map { n =>
                      val load = tracker.snapshot(n.nodeId)
                      val (p50, p95, p99) = tracker.latencyPercentiles(n.nodeId)
                      NodeInfo(
                        nodeId        = n.nodeId,
                        role          = n.role.toString,
                        host          = n.host,
                        port          = n.port,
                        maxConcurrent = n.maxConcurrent,
                        inFlight      = load.inFlight,
                        totalServed   = load.totalServed,
                        avgDurationMs = load.ewmaMs,
                        p50Ms         = p50,
                        p95Ms         = p95,
                        p99Ms         = p99,
                        healthy       = load.healthy,
                        draining      = load.draining
                      )
                    },
        status    = "ready",
        metastore = redact(p.metastore)
      )
    }

  def createPool(req: CreatePoolRequest): Out[PoolResponse] =
    if !req.roleDistribution.isValidFor(req.size) then
      IO.pure(Left((StatusCode.BadRequest,
        ErrorResponse("invalid_distribution", "role counts do not sum to size"))))
    else
      val key = PoolKey(req.tenant, req.pool)
      sup.getTenant(req.tenant) match
        case None =>
          IO.pure(Left((StatusCode.NotFound,
            ErrorResponse("tenant_not_found", s"tenant '${req.tenant}' is not registered"))))
        case Some(_) =>
          sup.get(key) match
            case Some(_) =>
              IO.pure(Left((StatusCode.Conflict,
                ErrorResponse("exists", s"pool $key already exists"))))
            case None =>
              sup.createPool(key, req.roleDistribution, req.metastore, req.s3, req.maxConcurrentPerNode)
                .map(_ => Right(respond(key).getOrElse(
                  PoolResponse(req.tenant, req.pool, Nil, "ready", Map.empty))))
                .handleError(t => Left((StatusCode.InternalServerError,
                  ErrorResponse("start_failed", t.getMessage))))

  def scalePool(req: ScalePoolRequest): Out[PoolResponse] =
    val key = PoolKey(req.tenant, req.pool)
    sup.get(key) match
      case None =>
        IO.pure(Left((StatusCode.NotFound,
          ErrorResponse("not_found", s"pool $key not found"))))
      case Some(_) =>
        if !req.roleDistribution.isValidFor(req.targetSize) then
          IO.pure(Left((StatusCode.BadRequest,
            ErrorResponse("invalid_distribution", "role counts do not sum to targetSize"))))
        else
          sup.scale(key, req.targetSize, req.roleDistribution, req.force)
            .map(_ => Right(respond(key).getOrElse(
              PoolResponse(req.tenant, req.pool, Nil, "ready", Map.empty))))

  def stopPool(req: StopPoolRequest): Out[Unit] =
    val key = PoolKey(req.tenant, req.pool)
    sup.get(key) match
      case None =>
        IO.pure(Left((StatusCode.NotFound,
          ErrorResponse("not_found", s"pool $key not found"))))
      case Some(_) =>
        sup.stopPool(key, req.force).map(_ => Right(()))

  def listPools(): Out[PoolListResponse] = IO.delay(
    Right(PoolListResponse(sup.list().flatMap(p => respond(p.key))))
  )

  def poolStatus(tenant: String, pool: String): Out[PoolResponse] =
    val key = PoolKey(tenant, pool)
    respond(key) match
      case Some(r) => IO.pure(Right(r))
      case None    => IO.pure(Left((StatusCode.NotFound,
                                    ErrorResponse("not_found", s"pool $key not found"))))