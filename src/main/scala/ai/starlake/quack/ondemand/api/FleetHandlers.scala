package ai.starlake.quack.ondemand.api

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.ondemand.state.{
  FleetAssignment,
  FleetServerStore,
  Heartbeat,
  HeartbeatOutcome,
  NodeReport
}
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
  */
final class FleetHandlers(
    store: FleetServerStore,
    cfg: FleetConfig
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
      record.attempt.map {
        case Right(r) => r
        case Left(t)  =>
          logger.warn(s"fleet: heartbeat of '${req.name}' failed: ${t.getMessage}")
          Left(
            (
              StatusCode.BadGateway,
              ErrorResponse(
                "backend_error",
                s"heartbeat of '${req.name}' failed: " +
                  Option(t.getMessage).getOrElse(t.toString)
              )
            )
          )
      }

object FleetHandlers:
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
