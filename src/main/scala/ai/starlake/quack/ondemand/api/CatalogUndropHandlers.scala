package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{PoolKey, Role}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

import scala.concurrent.duration.DurationInt

/** Undrop (Spec 03): list recoverable dropped tables and recreate one at its last-live snapshot.
  *
  * Discovery is a catalog read ([[DuckLakeCatalogReader.listDroppedTables]]); recovery is a
  * `CREATE TABLE ... AS SELECT * FROM t AT (VERSION => n)` routed through the same executor shape
  * as [[CatalogPreviewHandlers]] but wired with `recordExecution = true` in Main, so the recovery
  * snapshot carries the principal's author stamp. The engine primitive (time-travel-reading a
  * DROPPED table by name) was verified live 2026-07-14 on DuckDB 1.5.4 / DuckLake 0.3; see the
  * undrop design doc.
  *
  * `recoverable` audits under the `cfg.auditCatalogReads` knob like the other catalog reads;
  * `undrop` is a mutation and audits unconditionally (never row contents; detail carries schema,
  * table, restoredAs, fromSnapshot).
  */
final class CatalogUndropHandlers(
    sup: PoolSupervisor,
    executor: CatalogPreviewHandlers.PreviewExecutor,
    resolveReader: (String, String) => DuckLakeCatalogReader,
    cfg: CatalogConfig,
    sessions: String => Option[SessionTokenStore.Session],
    audit: AuditRecorder = AuditRecorder.noop
):
  import CatalogPreviewHandlers.SuperuserIdentity

  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** Same unsafe-char screen as MaintenanceHandlers' scope fields: these values end up inside a SQL
    * text on the node session, so quotes, separators, and whitespace are refused outright.
    */
  private def hasUnsafeChars(s: String): Boolean =
    s.exists(c => c == '\'' || c == '"' || c == ';' || c == '\\' || c.isWhitespace || c.isControl)

  private def identityOf(apiKey: Option[String]): String =
    apiKey.flatMap(sessions) match
      case Some(session) => session.profile.username
      case None          => SuperuserIdentity

  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    TenantDbGate(
      sup,
      rawTenant,
      tenantDb,
      apiKey,
      requireDuckLake = Some("undrop requires a ducklake tenant-db")
    )(scopeOf)

  /** Pool pick preferring a WRITE-eligible node (a CTAS needs WriteOnly/Dual to route), the write
    * mirror of [[CatalogPreviewHandlers]]'s read-preferring pick; falls through to the first
    * candidate by name so the router stays the authority on routability.
    */
  private def writePoolKey(tenant: String, tenantDb: String): Option[PoolKey] =
    val candidates = sup
      .list()
      .map(_.key)
      .filter(k => k.tenant == tenant && k.tenantDb == tenantDb)
      .sortBy(_.pool)
    def hasWriteEligibleNode(key: PoolKey): Boolean =
      sup
        .snapshot(key)
        .exists(_.nodes.exists(n => n.role == Role.WriteOnly || n.role == Role.Dual))
    candidates.find(hasWriteEligibleNode).orElse(candidates.headOption)

  def recoverable(tenant: String, tenantDb: String, limit: Option[Int], apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RecoverableListResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        if cfg.auditCatalogReads then
          audit.rest(apiKey, "control-plane", AuditActions.CatalogRecoverableRead, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        val effectiveLimit = limit.map(_.max(1)).getOrElse(50).min(200)
        IO.blocking {
          val entries = resolveReader(tid, db).listDroppedTables(effectiveLimit).map { e =>
            RecoverableTableEntry(
              e.schema,
              e.table,
              e.droppedAtSnapshot,
              e.lastLiveSnapshot,
              e.droppedAt,
              e.recoverable
            )
          }
          if cfg.auditCatalogReads then
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.CatalogRecoverableRead,
              "ok",
              tenant = Some(tid),
              target = Some(db),
              detail = Map("count" -> entries.length.toString)
            )
          Right(RecoverableListResponse(entries))
        }

  def undrop(req: UndropRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[UndropResponse] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogUndrop, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        val target = req.asName.getOrElse(req.table)
        def denied(
            sc: StatusCode,
            code: String,
            msg: String
        ): Either[(StatusCode, ErrorResponse), UndropResponse] =
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogUndrop,
            "denied",
            tenant = Some(tid),
            target = Some(s"$db/${req.schema}.${req.table}")
          )
          err(sc, code, msg)

        if List(req.schema, req.table, target).exists(v => v.isEmpty || hasUnsafeChars(v)) then
          IO.pure(
            denied(
              StatusCode.BadRequest,
              "invalid_name",
              "schema, table, and asName must be non-empty and free of quotes, separators, " +
                "and whitespace"
            )
          )
        else
          val reader = resolveReader(tid, db)
          reader
            .listDroppedTables(limit = 200)
            .find(e => e.schema == req.schema && e.table == req.table) match
            case None =>
              IO.pure(
                denied(
                  StatusCode.NotFound,
                  "not_found",
                  s"no dropped table '${req.schema}.${req.table}'"
                )
              )
            case Some(entry) =>
              val fromSnapshot = req.fromSnapshot.getOrElse(entry.lastLiveSnapshot)
              if !reader.snapshotExists(fromSnapshot) then
                IO.pure(
                  denied(
                    StatusCode.Gone,
                    "snapshot_expired",
                    s"snapshot $fromSnapshot has been expired; the table is no longer recoverable"
                  )
                )
              else if reader
                  .maxSnapshotId()
                  .exists(m => reader.tableExistsAt(req.schema, target, m))
              then
                IO.pure(
                  denied(
                    StatusCode.Conflict,
                    "name_conflict",
                    s"table '${req.schema}.$target' exists; supply asName to restore under a " +
                      "different name"
                  )
                )
              else
                writePoolKey(tid, db) match
                  case None =>
                    IO.pure(
                      denied(
                        StatusCode.NotFound,
                        "no_pool",
                        s"tenant-db '$db' has no running pool; undrop needs at least one pool " +
                          "with a live WriteOnly/Dual node"
                      )
                    )
                  case Some(poolKey) =>
                    val sql =
                      s"CREATE TABLE ${quoteIdent(req.schema)}.${quoteIdent(target)} AS " +
                        s"SELECT * FROM ${quoteIdent(req.schema)}.${quoteIdent(req.table)} " +
                        s"AT (VERSION => $fromSnapshot)"
                    executor(s"undrop-$tid-$db", identityOf(apiKey), poolKey, sql)
                      .timeout(cfg.previewTimeoutSec.seconds)
                      .attempt
                      .map {
                        case Left(_) =>
                          denied(StatusCode.BadGateway, "undrop_failed", "undrop query timed out")
                        case Right(Left(RouterFailure.AccessDenied(reason))) =>
                          denied(StatusCode.Forbidden, "acl_denied", reason)
                        case Right(Left(failure)) =>
                          denied(StatusCode.BadGateway, "undrop_failed", failure.reason)
                        case Right(Right(result)) =>
                          // A CTAS result stream carries only a row count nobody reads.
                          result.close()
                          audit.rest(
                            apiKey,
                            "control-plane",
                            AuditActions.CatalogUndrop,
                            "ok",
                            tenant = Some(tid),
                            target = Some(s"$db/${req.schema}.${req.table}"),
                            detail = Map(
                              "schema"       -> req.schema,
                              "table"        -> req.table,
                              "restoredAs"   -> target,
                              "fromSnapshot" -> fromSnapshot.toString
                            )
                          )
                          Right(UndropResponse(req.schema, req.table, target, fromSnapshot))
                      }
