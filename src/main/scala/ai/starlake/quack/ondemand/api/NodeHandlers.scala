package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, Role}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import cats.effect.IO
import sttp.model.StatusCode

final class NodeHandlers(
    sup: PoolSupervisor,
    tracker: NodeLoadTracker,
    backend: QuackBackend
):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def withNode[A](tenant: String, tenantDb: String, pool: String, nodeId: String)(
      f: => IO[A]
  ): Out[A] =
    sup.get(PoolKey(tenant, tenantDb, pool)).flatMap(_.nodes.find(_.nodeId == nodeId)) match
      case None =>
        IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", "no such node"))))
      case Some(_) => f.map(Right(_))

  def setRole(req: SetRoleRequest): Out[Unit] =
    Role.parse(req.role) match
      case Left(msg) =>
        IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid_role", msg))))
      case Right(_role) =>
        // Role mutation is metadata-only at runtime (the router reads role from
        // RunningNode). For v1 we don't restart the node - just acknowledge.
        // A future task may add a CRD-style restart that re-syncs the role label.
        withNode(req.tenant, req.tenantDb, req.pool, req.nodeId)(IO.unit)

  def setMaxConcurrent(req: SetMaxConcurrentRequest): Out[Unit] =
    if req.max < 0 then
      IO.pure(
        Left(
          (StatusCode.BadRequest, ErrorResponse("invalid_max", "max must be >= 0 (0 = unlimited)"))
        )
      )
    else
      sup.setMaxConcurrent(PoolKey(req.tenant, req.tenantDb, req.pool), req.nodeId, req.max).map {
        case Some(_) => Right(())
        case None    =>
          Left(
            (
              StatusCode.NotFound,
              ErrorResponse(
                "not_found",
                s"node ${req.nodeId} not found in ${req.tenant}/${req.tenantDb}/${req.pool}"
              )
            )
          )
      }

  def quarantineNode(req: NodeOpRequest): Out[Unit] =
    withNode(req.tenant, req.tenantDb, req.pool, req.nodeId) {
      IO.delay(tracker.setHealthy(req.nodeId, false))
    }

  def restartNode(req: NodeOpRequest): Out[Unit] =
    withNode(req.tenant, req.tenantDb, req.pool, req.nodeId) {
      IO.delay(tracker.setDraining(req.nodeId, true)) *>
        backend.stop(req.nodeId) *>
        IO.delay(tracker.setDraining(req.nodeId, false))
      // Re-start handled via `scale` + ID reuse; out of scope for v1.
    }
