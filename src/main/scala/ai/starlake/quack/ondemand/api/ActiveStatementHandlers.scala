package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.{ActiveStatementRegistry, StatementHistoryStore, StatementRecord}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.*
import sttp.model.StatusCode

import java.time.{Duration, Instant}

/** qod_kill NOTIFY payload. `tenants = None` means an unrestricted (superuser or static-key) kill;
  * `Some(list)` restricts the kill to statements owned by one of those tenants, carrying the
  * broadcaster's scope to the owning replica.
  */
final case class KillBroadcast(id: String, tenants: Option[List[String]])

object KillBroadcast:
  given Codec[KillBroadcast] = deriveCodec
  val Channel                = "qod_kill"

  def encode(id: String, tenants: Option[List[String]]): String =
    KillBroadcast(id, tenants).asJson.noSpaces

final class ActiveStatementHandlers(
    registry: ActiveStatementRegistry,
    history: StatementHistoryStore,
    store: ControlPlaneStore,
    haEnabled: Boolean
):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def tenantFilter(
      apiKey: Option[String],
      scopeOf: String => Option[SessionScope]
  ): Option[Set[String]] =
    apiKey.flatMap(scopeOf) match
      case Some(s) if !s.superuser => Some(s.manageableTenants)
      case _                       => None // superuser, static key, or no key: unrestricted

  def list(apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[ActiveStatementsResponse] =
    IO.delay {
      val filter = tenantFilter(apiKey, scopeOf)
      val now    = Instant.now()
      val infos  = registry
        .list()
        .filter(a => filter.forall(_.contains(a.tenant)))
        .map { a =>
          ActiveStatementInfo(
            id = a.id,
            user = a.user,
            tenant = a.tenant,
            pool = a.pool,
            nodeId = a.nodeId,
            sql = a.sql,
            startedAt = a.startedAt.toString,
            elapsedMs = math.max(0L, Duration.between(a.startedAt, now).toMillis)
          )
        }
      Right(ActiveStatementsResponse(infos))
    }

  def kill(req: KillStatementRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[KillStatementResponse] =
    IO.blocking {
      val filter = tenantFilter(apiKey, scopeOf)
      registry.list().find(_.id == req.id) match
        case Some(s) if filter.exists(t => !t.contains(s.tenant)) =>
          // Cross-tenant: 404, never 403 (no existence leak across tenants).
          Left((StatusCode.NotFound, ErrorResponse("not_found", s"statement ${req.id} not found")))
        case Some(_) =>
          killAndRecord(req.id)
          Right(KillStatementResponse("accepted"))
        case None if haEnabled =>
          // Another replica may own it: fan out, carrying the caller's tenant scope.
          store.notifyListeners(
            KillBroadcast.Channel,
            KillBroadcast.encode(req.id, filter.map(_.toList))
          )
          Right(KillStatementResponse("accepted"))
        case None =>
          Right(KillStatementResponse("already-completed"))
    }

  /** qod_kill channel receiver, wired into the HaCoordinator handler map by Main. */
  def onKillBroadcast(payload: String): Unit =
    io.circe.parser.decode[KillBroadcast](payload) match
      case Left(_)   => ()
      case Right(kb) =>
        val owned   = registry.list().find(_.id == kb.id)
        val allowed = owned.exists(s => kb.tenants.forall(_.contains(s.tenant)))
        if allowed then killAndRecord(kb.id)

  private def killAndRecord(id: String): Unit =
    registry.kill(id).foreach { s =>
      val now = Instant.now()
      history.record(
        StatementRecord(
          ts = now,
          user = s.user,
          tenant = s.tenant,
          pool = s.pool,
          nodeId = s.nodeId,
          sql = s.sql,
          durationMs = math.max(0L, Duration.between(s.startedAt, now).toMillis),
          status = "killed",
          error = None
        )
      )
    }
