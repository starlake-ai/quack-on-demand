package ai.starlake.quack.ondemand.api

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.fleet.ServerLiveness
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.runtime.FleetQuackBackend
import ai.starlake.quack.ondemand.state.{
  FleetAssignment,
  FleetServerRow,
  FleetServerStore,
  Heartbeat,
  HeartbeatOutcome,
  NodeReport
}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import sttp.model.StatusCode

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import scala.util.Try

/** Fleet agent surface. The heartbeat is machine-to-machine: `X-Fleet-Token` is the credential
  * (constant-time compare against the join token), never a session or API key. No clock here: every
  * timestamp is the store's.
  *
  * The admin server endpoints (list / drain / undrain / remove) are superuser or static key only
  * and answer `400 fleet_disabled` when `backend` is None (runtimeType is not fleet).
  */
final class FleetHandlers(
    store: FleetServerStore,
    cfg: FleetConfig,
    backend: Option[FleetQuackBackend] = None,
    publish: StateChangePublisher = StateChangePublisher.noop,
    audit: AuditRecorder = AuditRecorder.noop
) extends LazyLogging:
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val ValidStates = Set("none", "starting", "running", "failed", "stopped")

  private def tokenOk(provided: Option[String]): Boolean =
    cfg.joinToken.nonEmpty && provided.exists(p =>
      MessageDigest.isEqual(
        p.getBytes(StandardCharsets.UTF_8),
        cfg.joinToken.getBytes(StandardCharsets.UTF_8)
      )
    )

  private def fail[A](status: StatusCode, error: String, message: String): Out[A] =
    IO.pure(Left((status, ErrorResponse(error, message))))

  def heartbeat(req: FleetHeartbeatRequest, token: Option[String]): Out[FleetHeartbeatResponse] =
    val startedAt = req.node.startedAt.map(s => Try(Instant.parse(s)).toOption)
    if !tokenOk(token) then
      fail(StatusCode.Unauthorized, "fleet_unauthorized", "invalid or missing X-Fleet-Token")
    else if !ValidStates.contains(req.node.state) then
      fail(
        StatusCode.BadRequest,
        "invalid_node_state",
        s"unknown node state '${req.node.state}'; expected one of " +
          "none, starting, running, failed, stopped"
      )
    else if startedAt.contains(None) then
      fail(
        StatusCode.BadRequest,
        "invalid_started_at",
        s"node.startedAt '${req.node.startedAt.getOrElse("")}' is not an ISO-8601 instant"
      )
    else
      val report = NodeReport(
        req.node.assignmentEpoch,
        req.node.nodeId,
        req.node.state,
        req.node.pid,
        req.node.error,
        startedAt.flatten
      )
      val hb = Heartbeat(
        req.name,
        req.advertiseHost,
        req.nodePort,
        req.agentVersion,
        req.os,
        req.duckdbVersion,
        req.cpus,
        req.memoryBytes,
        report
      )
      val record: Out[FleetHeartbeatResponse] =
        IO.blocking(store.recordHeartbeat(hb)).flatMap {
          case HeartbeatOutcome.AddressChangeRefused =>
            fail(
              StatusCode.Conflict,
              "address_change_refused",
              s"server '${req.name}' is known with another address; " +
                "drain it before re-addressing"
            )
          case outcome =>
            if outcome == HeartbeatOutcome.Joined then
              logger.info(
                s"fleet: server '${req.name}' joined from ${req.advertiseHost}:${req.nodePort}"
              )
            IO.blocking(store.get(req.name)).map { row =>
              Right(
                FleetHeartbeatResponse(
                  cfg.heartbeatSec,
                  row.flatMap(_.assignment).map(FleetHandlers.toDto)
                )
              )
            }
        }
      // A store error (e.g. the server deleted concurrently, after the store's retry) must not
      // become a bodyless 500: answer 502 backend_error like NodeHandlers; the agent retries.
      HandlerErrors.raisedToBadGateway(s"heartbeat of '${req.name}' failed")(
        logger.warn(s"fleet: heartbeat of '${req.name}' failed")
      )(record)

  // --- Admin surface ---------------------------------------------------------------------------

  private def fleetEnabled[A](f: FleetQuackBackend => Out[A]): Out[A] =
    backend match
      case None    => fail(StatusCode.BadRequest, "fleet_disabled", "runtimeType is not fleet")
      case Some(b) => f(b)

  private def dto(b: FleetQuackBackend, r: FleetServerRow): FleetServerDto =
    FleetServerDto(
      name = r.name,
      advertiseHost = r.advertiseHost,
      nodePort = r.nodePort,
      liveness = FleetHandlers.livenessString(b.livenessOf(r)),
      silentSeconds = r.silentSeconds,
      unschedulable = r.unschedulable,
      assignedNodeId = r.assignedNodeId,
      tenant = r.assignment.map(_.poolKey.tenant),
      tenantDb = r.assignment.map(_.poolKey.tenantDb),
      pool = r.assignment.map(_.poolKey.pool),
      nodeState = r.nodeState,
      nodeError = r.nodeError,
      agentVersion = r.agentVersion,
      duckdbVersion = r.duckdbVersion,
      cpus = r.cpus,
      memoryBytes = r.memoryBytes,
      joinedAt = r.joinedAt.toString,
      lastHeartbeatAt = r.lastHeartbeatAt.toString
    )

  def listServers(apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[FleetServerListResponse] =
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        fleetEnabled { b =>
          HandlerErrors.raisedToBadGateway("listing fleet servers failed")(()) {
            IO.blocking(store.list())
              .map(rows => Right(FleetServerListResponse(rows.map(dto(b, _)))))
          }
        }

  /** Common shape of the three mutations: superuser gate (denied audit row), fleet gate, 404 on an
    * unknown name, then `f` with an ok / error audit row. A raised error maps to 502 with an error
    * audit row.
    */
  private def serverOp(req: FleetServerOpRequest, apiKey: Option[String], action: String)(
      scopeOf: String => Option[SessionScope]
  )(f: (FleetQuackBackend, FleetServerRow) => Out[Unit]): Out[Unit] =
    def auditAs(outcome: String): Unit =
      audit.rest(apiKey, "control-plane", action, outcome, target = Some(req.name))
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) =>
        auditAs("denied")
        IO.pure(Left(err))
      case None =>
        fleetEnabled { b =>
          HandlerErrors.raisedToBadGateway(s"$action of '${req.name}' failed")(auditAs("error")) {
            IO.blocking(store.get(req.name)).flatMap {
              case None =>
                fail(StatusCode.NotFound, "not_found", s"no such server '${req.name}'")
              case Some(row) =>
                f(b, row).flatTap(r => IO.delay(auditAs(if r.isRight then "ok" else "error")))
            }
          }
        }

  /** Stop scheduling onto the server and release its assignment; the next reconcile respawns the
    * node elsewhere or leaves the slot pending.
    */
  def drain(req: FleetServerOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    serverOp(req, apiKey, AuditActions.FleetDrain)(scopeOf) { (_, row) =>
      IO.blocking {
        store.setUnschedulable(row.name, true)
        row.assignedNodeId.foreach(store.release)
      } *> IO.delay(publish.topologyChanged()).as(Right(()))
    }

  def undrain(req: FleetServerOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    serverOp(req, apiKey, AuditActions.FleetUndrain)(scopeOf) { (_, row) =>
      IO.blocking(store.setUnschedulable(row.name, false)).as(Right(()))
    }

  /** Refused while the server is reachable and still schedulable: a live agent would re-register on
    * its next heartbeat and could be holding a node.
    */
  def remove(req: FleetServerOpRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[Unit] =
    serverOp(req, apiKey, AuditActions.FleetRemove)(scopeOf) { (b, row) =>
      if b.livenessOf(row) == ServerLiveness.Reachable && !row.unschedulable then
        fail(
          StatusCode.Conflict,
          "server_active",
          "drain the server and stop its agent before removing it"
        )
      else IO.blocking(store.delete(row.name)).as(Right(()))
    }

object FleetHandlers:
  def livenessString(l: ServerLiveness): String = l match
    case ServerLiveness.Reachable      => "reachable"
    case ServerLiveness.Unreachable(_) => "unreachable"
    case ServerLiveness.Dead           => "dead"

  def toDto(a: FleetAssignment): FleetAssignmentDto =
    FleetAssignmentDto(
      a.epoch,
      a.nodeId,
      FleetPoolKeyDto(a.poolKey.tenant, a.poolKey.tenantDb, a.poolKey.pool),
      a.port,
      a.token,
      a.kind,
      a.env,
      a.dbInitSql,
      a.objectStoreSql,
      a.extraSetupSql,
      a.lockdownSql
    )
