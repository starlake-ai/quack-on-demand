package ai.starlake.quack.ondemand.api

import ai.starlake.quack.FleetConfig
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.fleet.{Cidr, ClientAddress, ServerLiveness}
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.runtime.FleetQuackBackend
import ai.starlake.quack.ondemand.state.{
  ApproveResult,
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

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import scala.util.Try

/** Fleet surface. The heartbeat (from `qod fleet join`) is machine-to-machine: `X-Fleet-Token` is
  * the credential (constant-time compare against the join token), never a session or a personal
  * access token. No clock here: every timestamp is the store's.
  *
  * The admin server endpoints (list / drain / undrain / remove / approve) are superuser only and
  * answer `400 fleet_disabled` when `backend` is None (runtimeType is not fleet).
  */
final class FleetHandlers(
    store: FleetServerStore,
    cfg: FleetConfig,
    backend: Option[FleetQuackBackend] = None,
    publish: StateChangePublisher = StateChangePublisher.noop,
    audit: AuditRecorder = AuditRecorder.noop,
    nodeKeys: ai.starlake.quack.edge.authz.NodeKeys =
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
    nodeExtensions: ai.starlake.quack.NodeExtensionsConfig =
      ai.starlake.quack.NodeExtensionsConfig()
) extends LazyLogging:
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val ValidStates = Set("none", "starting", "running", "failed", "stopped")

  // Parsed once; FleetConfig validated both at boot.
  private val autoApproveList = cfg.autoApproveCidrs
  private val trustedProxies  = cfg.trustedProxyCidrs

  private def tokenOk(provided: Option[String]): Boolean =
    cfg.joinToken.nonEmpty && provided.exists(p =>
      MessageDigest.isEqual(
        p.getBytes(StandardCharsets.UTF_8),
        cfg.joinToken.getBytes(StandardCharsets.UTF_8)
      )
    )

  private val disabled = FleetErrors.disabled

  /** A raised store error becomes `502 backend_error` with a fixed message: the cause (SQL text,
    * constraint names, hosts) is logged at WARN, never echoed to the caller. `onRaised` runs after
    * the log line, e.g. to write an "error" audit row.
    */
  private def storeErrorTo502[A](what: String)(onRaised: Throwable => Unit)(
      io: => Out[A]
  ): Out[A] =
    IO.defer(io).attempt.map {
      case Right(r) => r
      case Left(t)  =>
        logger.warn(s"fleet: $what failed: ${t.getMessage}", t)
        onRaised(t)
        Left(FleetErrors.backendError)
    }

  /** `peer` is the TCP peer; `forwardedFor` is believed only when the peer is a trusted proxy. The
    * resolved address decides auto-approval (QOD_FLEET_AUTO_APPROVE); an unknown one never does.
    */
  def heartbeat(
      req: FleetHeartbeatRequest,
      token: Option[String],
      peer: Option[InetAddress],
      forwardedFor: Option[String]
  ): Out[FleetHeartbeatResponse] =
    val startedAt = req.node.startedAt.map(s => Try(Instant.parse(s)).toOption)
    // Not a fleet manager: no server rows may be written, whoever the caller (on a local or K8s
    // manager the route sits behind the API-key guard, so any admitted caller reaches it).
    if backend.isEmpty then IO.pure(Left(disabled))
    else if !tokenOk(token) then IO.pure(Left(FleetErrors.unauthorized))
    else if !ValidStates.contains(req.node.state) then
      IO.pure(Left(FleetErrors.invalidNodeState(req.node.state)))
    else if startedAt.contains(None) then
      IO.pure(Left(FleetErrors.invalidStartedAt(req.node.startedAt.getOrElse(""))))
    else
      val report = NodeReport(
        req.node.assignmentEpoch,
        req.node.nodeId,
        req.node.state,
        req.node.pid,
        req.node.error,
        startedAt.flatten,
        action = req.node.action
      )
      val source = ClientAddress.resolve(peer, forwardedFor, trustedProxies)
      val autoOk = source.exists(a => Cidr.anyContains(autoApproveList, a))
      val hb     = Heartbeat(
        req.name,
        req.advertiseHost,
        req.nodePort,
        req.qodVersion,
        req.os,
        req.duckdbVersion,
        req.cpus,
        req.memoryBytes,
        report,
        sourceAddr = source.map(_.getHostAddress),
        autoApprove = autoOk
      )
      // A refusal writes nothing, so the row read right after it is the one that decided it.
      def refuse(build: Option[FleetServerRow] => FleetErrors.Err): Out[FleetHeartbeatResponse] =
        IO.blocking(store.get(req.name)).map(row => Left(build(row)))
      val record: Out[FleetHeartbeatResponse] =
        IO.blocking(store.recordHeartbeat(hb)).flatMap {
          case HeartbeatOutcome.AddressChangeRefused =>
            refuse(row =>
              FleetErrors.addressChangeRefused(
                req.name,
                s"${req.advertiseHost}:${req.nodePort}",
                row
              )
            )
          case HeartbeatOutcome.SourceChangeRefused =>
            refuse(row =>
              FleetErrors.sourceChangeRefused(req.name, hb.sourceAddr, row, cfg.autoApprove)
            )
          case HeartbeatOutcome.ApprovalUnbound =>
            IO.pure(Left(FleetErrors.approvalUnbound(req.name, hb.sourceAddr, cfg.autoApprove)))
          case outcome =>
            IO.blocking(store.get(req.name)).map { row =>
              val approval = if row.exists(_.approved) then "approved" else "pending"
              if outcome == HeartbeatOutcome.Joined then
                val from = hb.sourceAddr.getOrElse("an unknown address")
                logger.info(
                  s"fleet: server '${req.name}' joined from $from " +
                    s"(advertises ${req.advertiseHost}:${req.nodePort}): " +
                    (if approval == "approved" then "auto-approved"
                     else "pending approval (source not in QOD_FLEET_AUTO_APPROVE)")
                )
                audit.restAs(
                  req.name,
                  "fleet",
                  "control-plane",
                  AuditActions.FleetJoin,
                  "ok",
                  target = Some(req.name),
                  detail = Map("source" -> from, "approval" -> approval)
                )
              Right(
                FleetHeartbeatResponse(
                  cfg.heartbeatSec,
                  // Only an approved server is ever handed its assignment (which carries the
                  // node token, the node key and pgPassword). A pending row should hold none,
                  // but a row can be reset to pending while it still holds one (e.g. a
                  // re-address racing a drain); the reply must not leak it then. Likewise only
                  // to the source the approval is bound to: the row is read afresh here, after
                  // the store's check, so a concurrent rebind must not hand the assignment to
                  // the old source.
                  row
                    .filter(r =>
                      r.approved && r.approvedSource.forall(b => hb.sourceAddr.contains(b))
                    )
                    .flatMap(_.assignment)
                    .map(a => FleetHandlers.toDto(a, nodeKeys.keyHex(a.nodeId), nodeExtensions)),
                  approval
                )
              )
            }
        }
      // A store error (e.g. the server deleted concurrently, after the store's retry) must not
      // become a bodyless 500: answer 502 backend_error; the server retries.
      storeErrorTo502(s"heartbeat of '${req.name}'")(_ => ())(record)

  // --- Admin surface ---------------------------------------------------------------------------

  private def fleetEnabled[A](f: FleetQuackBackend => Out[A]): Out[A] =
    backend match
      case None    => IO.pure(Left(disabled))
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
      qodVersion = r.qodVersion,
      duckdbVersion = r.duckdbVersion,
      cpus = r.cpus,
      memoryBytes = r.memoryBytes,
      joinedAt = r.joinedAt.toString,
      lastHeartbeatAt = r.lastHeartbeatAt.toString,
      approval = if r.approved then "approved" else "pending",
      approvedBy = r.approvedBy,
      approvedAt = r.approvedAt.map(_.toString),
      sourceAddr = r.sourceAddr,
      approvedSource = r.approvedSource,
      nodeAction = r.nodeAction
    )

  def listServers(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[FleetServerListResponse] =
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) => IO.pure(Left(err))
      case None      =>
        fleetEnabled { b =>
          storeErrorTo502("listing servers")(_ => ()) {
            IO.blocking(store.list())
              .map(rows => Right(FleetServerListResponse(rows.map(dto(b, _)))))
          }
        }

  /** Common shape of the server mutations: superuser gate (denied audit row), fleet gate, 404 on an
    * unknown name, then `f` with an ok / error audit row. A raised error maps to 502 with an error
    * audit row.
    */
  private def serverOp(req: FleetServerOpRequest, apiKey: Option[String], action: String)(
      scopeOf: SessionScope.Resolver
  )(f: (FleetQuackBackend, FleetServerRow) => Out[Unit]): Out[Unit] =
    def auditAs(outcome: String): Unit =
      audit.rest(apiKey, "control-plane", action, outcome, target = Some(req.name))
    SuperuserCheck.reject(apiKey)(scopeOf) match
      case Some(err) =>
        auditAs("denied")
        IO.pure(Left(err))
      case None =>
        fleetEnabled { b =>
          storeErrorTo502(s"$action of '${req.name}'")(_ => auditAs("error")) {
            IO.blocking(store.get(req.name)).flatMap {
              case None =>
                IO.pure(Left(FleetErrors.notFound(req.name)))
              case Some(row) =>
                f(b, row).flatTap(r => IO.delay(auditAs(if r.isRight then "ok" else "error")))
            }
          }
        }

  /** Stop scheduling onto the server and release its assignment; the next reconcile respawns the
    * node elsewhere or leaves the slot pending. The router keeps routing to the node until that
    * reconcile tick drops it (up to `reconcileIntervalSec`).
    */
  def drain(req: FleetServerOpRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    serverOp(req, apiKey, AuditActions.FleetDrain)(scopeOf) { (_, row) =>
      IO.blocking {
        store.setUnschedulable(row.name, true)
        // Release from a read taken AFTER the flip, not the pre-flip snapshot: a claim landing in
        // between would otherwise leave a drained server holding a node. Once unschedulable, no
        // further claim can land.
        store.get(row.name).flatMap(_.assignedNodeId).foreach(store.release)
      } *> IO.delay(publish.topologyChanged()).as(Right(()))
    }

  def undrain(req: FleetServerOpRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    serverOp(req, apiKey, AuditActions.FleetUndrain)(scopeOf) { (_, row) =>
      // Published like drain: the server is schedulable again, peers should see it.
      IO.blocking(store.setUnschedulable(row.name, false)) *>
        IO.delay(publish.topologyChanged()).as(Right(()))
    }

  /** Let a pending server take nodes. Idempotent; the first approver (the calling user, as the
    * audit row names it) and time are kept. The next reconcile pass fills pending slots onto it.
    * Refused (409 source_unknown) while the server's latest heartbeat has no known source address:
    * the approval is bound to that source.
    */
  def approve(req: FleetServerOpRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    serverOp(req, apiKey, AuditActions.FleetApprove)(scopeOf) { (_, row) =>
      IO.blocking(store.approve(row.name, audit.actorOf(apiKey)._1)).flatMap {
        case ApproveResult.NotFound =>
          IO.pure(Left(FleetErrors.notFound(row.name)))
        // An approval with no known source would bind to whoever heartbeats first.
        case ApproveResult.SourceUnknown =>
          IO.pure(Left(FleetErrors.sourceUnknown(row, cfg.trustedProxies)))
        case _ => IO.delay(publish.topologyChanged()).as(Right(()))
      }
    }

  /** Refused while an approved server is reachable and still schedulable: a live join process would
    * re-register on its next heartbeat and could be holding a node. A pending server holds no node
    * by construction, so it can be removed at any time (its join process re-joins as pending unless
    * it is stopped).
    */
  def remove(req: FleetServerOpRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    serverOp(req, apiKey, AuditActions.FleetRemove)(scopeOf) { (b, row) =>
      if row.approved && b.livenessOf(row) == ServerLiveness.Reachable && !row.unschedulable
      then IO.pure(Left(FleetErrors.serverActive(row, b.livenessOf(row))))
      else IO.blocking(store.delete(row.name)).as(Right(()))
    }

object FleetHandlers:

  /** The fixed message of every fleet `502 backend_error`; the cause is in the manager log. */
  val StoreError = "fleet store error, see manager log"

  /** One store listing, every server's liveness by name (the pool listing's serverState). */
  def livenessByName(store: FleetServerStore, backend: FleetQuackBackend): Map[String, String] =
    store.list().map(r => r.name -> livenessString(backend.livenessOf(r))).toMap

  def livenessString(l: ServerLiveness): String = l match
    case ServerLiveness.Reachable      => "reachable"
    case ServerLiveness.Unreachable(_) => "unreachable"
    case ServerLiveness.Dead           => "dead"

  /** The heartbeat's assignment, with the node key derived for its node id and the manager's
    * extension policy (neither stored).
    */
  def toDto(
      a: FleetAssignment,
      authzKey: String,
      extensions: ai.starlake.quack.NodeExtensionsConfig
  ): FleetAssignmentDto =
    FleetAssignmentDto(
      a.epoch,
      a.nodeId,
      FleetPoolKeyDto(a.poolKey.tenant, a.poolKey.tenantDb, a.poolKey.pool),
      a.port,
      a.token,
      authzKey,
      a.kind,
      a.env,
      a.resourceSql,
      a.objectStoreSql,
      a.extraSetupSql,
      a.lockdownSql,
      FleetNodeExtensionsDto(
        extensions.allowCommunity,
        extensions.allowUnsigned,
        extensions.autoinstallKnown
      ),
      a.tenantDbInitSql,
      a.tenantDbInitTrusted,
      a.tenantPoolInitSql,
      a.tenantPoolInitTrusted
    )
