package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

final class NodeHandlers(
    sup: PoolSupervisor,
    tracker: NodeLoadTracker,
    store: ControlPlaneStore,
    publish: StateChangePublisher,
    audit: AuditRecorder = AuditRecorder.noop
):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  /** Map a raised store/backend error to a structured 502 instead of tapir's bodyless 500.
    * `onRaised` runs before the mapping (audit hook); pass () for actions with no audit trail.
    * Post-mutation raises (audit sink, topology publish) also land here: a 502 for an applied
    * mutation beats the bodyless 500 it replaced.
    */
  private def raisedToBadGateway[A](describe: String)(onRaised: => Unit)(io: => Out[A]): Out[A] =
    IO.defer(io).attempt.map {
      case Right(r) => r
      case Left(t)  =>
        onRaised
        Left(
          (
            StatusCode.BadGateway,
            ErrorResponse(
              "backend_error",
              s"$describe: ${Option(t.getMessage).getOrElse(t.toString)}"
            )
          )
        )
    }

  private def withNode[A](tenant: String, tenantDb: String, pool: String, nodeId: String)(
      f: => IO[A]
  ): Out[A] =
    sup.get(PoolKey(tenant, tenantDb, pool)).flatMap(_.nodes.find(_.nodeId == nodeId)) match
      case None =>
        IO.pure(Left((StatusCode.NotFound, ErrorResponse("not_found", "no such node"))))
      case Some(_) => f.map(Right(_))

  def setMaxConcurrent(req: SetMaxConcurrentRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        if req.max < 0 then
          IO.pure(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid_max", "max must be >= 0 (0 = unlimited)")
              )
            )
          )
        else
          raisedToBadGateway(s"setMaxConcurrent of ${req.nodeId} failed")(()) {
            sup
              .setMaxConcurrent(PoolKey(req.tenant, req.tenantDb, req.pool), req.nodeId, req.max)
              .map {
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
          }

  private def setQuarantine(req: NodeOpRequest, apiKey: Option[String], quarantined: Boolean)(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    val action = if quarantined then AuditActions.NodeQuarantine else AuditActions.NodeUnquarantine
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) =>
        audit.rest(apiKey, "control-plane", action, "denied", target = Some(req.nodeId))
        IO.pure(Left(err))
      case None =>
        raisedToBadGateway(
          s"${if quarantined then "quarantine" else "unquarantine"} of ${req.nodeId} failed"
        )(
          audit.rest(apiKey, "control-plane", action, "error", target = Some(req.nodeId))
        ) {
          withNode(req.tenant, req.tenantDb, req.pool, req.nodeId) {
            IO.blocking(store.setNodeQuarantined(req.nodeId, quarantined)) *>
              IO.delay {
                tracker.setQuarantined(req.nodeId, quarantined)
                publish.topologyChanged()
                audit.rest(apiKey, "control-plane", action, "ok", target = Some(req.nodeId))
              }
          }
        }

  def quarantineNode(req: NodeOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] = setQuarantine(req, apiKey, quarantined = true)(scopeOf)

  def unquarantineNode(req: NodeOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] = setQuarantine(req, apiKey, quarantined = false)(scopeOf)

  def restartNode(req: NodeOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) =>
        audit.rest(
          apiKey,
          "control-plane",
          AuditActions.NodeRestart,
          "denied",
          target = Some(req.nodeId)
        )
        IO.pure(Left(err))
      case None =>
        raisedToBadGateway(s"restart of ${req.nodeId} failed")(
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.NodeRestart,
            "error",
            target = Some(req.nodeId)
          )
        ) {
          sup.restartNode(PoolKey(req.tenant, req.tenantDb, req.pool), req.nodeId).map {
            case Right(()) =>
              audit.rest(
                apiKey,
                "control-plane",
                AuditActions.NodeRestart,
                "ok",
                target = Some(req.nodeId)
              )
              Right(())
            case Left(err) => Left((StatusCode.NotFound, ErrorResponse("not_found", err.message)))
          }
        }
