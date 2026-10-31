package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

import scala.concurrent.duration.DurationInt

/** Undrop (Spec 03): list recoverable dropped tables and recreate one at its last-live snapshot.
  *
  * Discovery is a catalog read (`DuckLakeCatalogReader.listDroppedTables`); recovery is a
  * `CREATE TABLE ... AS SELECT * FROM t AT (VERSION => n)` routed through the same executor shape
  * as [[CatalogPreviewHandlers]] but wired with `recordExecution = true` in Main, so the recovery
  * snapshot carries the principal's author stamp. The engine primitive (time-travel-reading a
  * DROPPED table by name) was verified live 2026-07-14 on DuckDB 1.5.4 / DuckLake 0.3 and
  * re-verified on 1.5.5 (2026-07-25, DuckLakeCatalogReaderDroppedSpec); see the undrop design doc.
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
    callerOf: RestCaller,
    audit: AuditRecorder = AuditRecorder.noop
):
  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** Same unsafe-char screen as MaintenanceHandlers' scope fields: these values end up inside a SQL
    * text on the node session, so quotes, separators, and whitespace are refused outright.
    */
  private def hasUnsafeChars(s: String): Boolean =
    s.exists(c => c == '\'' || c == '"' || c == ';' || c == '\\' || c.isWhitespace || c.isControl)

  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    TenantDbGate(
      sup,
      rawTenant,
      tenantDb,
      apiKey,
      requireDuckLake = Some("undrop requires a ducklake tenant-db")
    )(scopeOf)

  /** Pool pick preferring a WRITE-eligible node (a CTAS needs WriteOnly/Dual to route); delegates
    * to [[PoolPicks.writePoolKey]].
    */
  private def writePoolKey(tenant: String, tenantDb: String): Option[PoolKey] =
    PoolPicks.writePoolKey(sup, tenant, tenantDb)

  def recoverable(tenant: String, tenantDb: String, limit: Option[Int], apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
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
      scopeOf: SessionScope.Resolver
  ): Out[UndropResponse] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogUndrop, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        recover(
          tid,
          db,
          req.schema,
          req.table,
          req.asName,
          req.fromSnapshot,
          writePoolKey(tid, db),
          callerOf(s"undrop-$tid-$db", apiKey)
        ).map { outcome =>
          auditUndrop(outcome, tid, db, req.schema, req.table) { (outcomeTag, target, detail) =>
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.CatalogUndrop,
              outcomeTag,
              tenant = Some(tid),
              target = Some(target),
              detail = detail
            )
          }
          outcome
        }

  /** `UNDROP TABLE [schema.]t [AS new_name]` (the gateway's admin dialect), on the caller's own
    * session: the same recovery as [[undrop]], run as `user` (`eff` the session's principal) on the
    * session's pool, so the node decides the read of the dropped table (its last live name's grant)
    * and the create (DDL in the schema). `schema` defaults to the database's default schema. The
    * answer is the dialect's (status, detail) row; a refusal is a plain-words [[RouterFailure]].
    */
  def undropStatement(
      user: String,
      poolKey: PoolKey,
      eff: Option[EffectiveSet],
      schema: Option[String],
      table: String,
      asName: Option[String]
  ): IO[Either[RouterFailure, ai.starlake.quack.edge.QueryResult]] =
    eff match
      case None => IO.pure(Left(RouterFailure.AccessDenied("UNDROP TABLE needs a signed-in user")))
      case Some(e) =>
        val tid = TenantDbGate.resolveTenantId(sup, poolKey.tenant).getOrElse(poolKey.tenant)
        sup.findTenantDb(tid, poolKey.tenantDb) match
          case None =>
            IO.pure(Left(RouterFailure.NotFound(s"database '${poolKey.tenantDb}' not found")))
          case Some(td) if td.kind != ai.starlake.quack.model.TenantDbKind.DuckLake =>
            IO.pure(
              Left(RouterFailure.BadRequest("UNDROP TABLE needs a DuckLake database"))
            )
          case Some(td) =>
            val s = schema.getOrElse(
              sup.effectiveMetastoreFor(tid, td.name).getOrElse("schemaName", "main")
            )
            val caller =
              ExecCaller.unrestricted(
                s"undrop-$tid-${td.name}",
                user,
                superuserAdmissible = e.user.tenant.isEmpty
              )
            recover(tid, td.name, s, table, asName, None, Some(poolKey), Right(caller)).map {
              outcome =>
                auditUndrop(outcome, tid, td.name, s, table) { (outcomeTag, target, detail) =>
                  audit.restAs(
                    user,
                    if e.user.tenant.isEmpty then "system" else "tenant",
                    "control-plane",
                    AuditActions.CatalogUndrop,
                    outcomeTag,
                    tenant = Some(tid),
                    target = Some(target),
                    detail = detail
                  )
                }
                outcome match
                  case Right(r) =>
                    Right(
                      ai.starlake.quack.edge.admin.AdminResults.ok(
                        s"table ${r.schema}.${r.table} restored as ${r.schema}.${r.restoredAs} " +
                          s"from snapshot ${r.fromSnapshot}"
                      )
                    )
                  case Left((code, err)) => Left(statementFailure(code, err, s, table, asName))
            }

  /** A refusal of the recovery in plain words, for the admin dialect. */
  private def statementFailure(
      code: StatusCode,
      err: ErrorResponse,
      schema: String,
      table: String,
      asName: Option[String]
  ): RouterFailure =
    err.error match
      case "not_found" =>
        RouterFailure.NotFound(s"there is no dropped table $schema.$table")
      case "name_conflict" =>
        val target = asName.getOrElse(table)
        RouterFailure.AlreadyExists(
          s"a table $schema.$target exists; use UNDROP TABLE $schema.$table AS <new name>"
        )
      case "acl_denied" =>
        RouterFailure.AccessDenied(s"not permitted: ${err.message}")
      case "invalid_name" =>
        RouterFailure.BadRequest(
          "UNDROP TABLE names must be plain names, free of quotes, separators and whitespace"
        )
      case _ if code == StatusCode.Gone =>
        RouterFailure.NotFound(s"$schema.$table is no longer recoverable: ${err.message}")
      case _ => RouterFailure.Internal(err.message)

  /** Audits one recovery: ok with its detail, or denied. */
  private def auditUndrop(
      outcome: Either[(StatusCode, ErrorResponse), UndropResponse],
      tid: String,
      db: String,
      schema: String,
      table: String
  )(write: (String, String, Map[String, String]) => Unit): Unit =
    outcome match
      case Right(r) =>
        write(
          "ok",
          s"$db/$schema.$table",
          Map(
            "schema"       -> r.schema,
            "table"        -> r.table,
            "restoredAs"   -> r.restoredAs,
            "fromSnapshot" -> r.fromSnapshot.toString
          )
        )
      case Left(_) => write("denied", s"$db/$schema.$table", Map.empty)

  /** The recovery itself, shared by the REST endpoint, the MCP tool and `UNDROP TABLE`: name
    * screen, the dropped table's last live snapshot, the name check, then `CREATE TABLE <target> AS
    * SELECT * FROM <table> AT (VERSION => n)` as `caller` on `pool`. `pool` and `caller` are
    * consulted only once the catalog checks pass, in that order. No audit: the callers audit.
    */
  def recover(
      tid: String,
      db: String,
      schema: String,
      table: String,
      asName: Option[String],
      fromSnapshotReq: Option[Long],
      pool: => Option[PoolKey],
      caller: => Either[(StatusCode, ErrorResponse), ExecCaller]
  ): Out[UndropResponse] =
    val target = asName.getOrElse(table)
    if List(schema, table, target).exists(v => v.isEmpty || hasUnsafeChars(v)) then
      IO.pure(
        err(
          StatusCode.BadRequest,
          "invalid_name",
          "schema, table, and asName must be non-empty and free of quotes, separators, " +
            "and whitespace"
        )
      )
    else
      val reader = resolveReader(tid, db)
      reader.findDroppedTable(schema, table) match
        case None =>
          IO.pure(err(StatusCode.NotFound, "not_found", s"no dropped table '$schema.$table'"))
        case Some(entry) =>
          val fromSnapshot = fromSnapshotReq.getOrElse(entry.lastLiveSnapshot)
          if fromSnapshotReq.exists(_ > entry.lastLiveSnapshot) then
            IO.pure(
              err(
                StatusCode.UnprocessableEntity,
                "invalid_snapshot",
                s"fromSnapshot must be at most the last-live snapshot " +
                  s"${entry.lastLiveSnapshot} (the table does not resolve at later versions)"
              )
            )
          else if !reader.snapshotExists(fromSnapshot) then
            IO.pure(
              err(
                StatusCode.Gone,
                "snapshot_expired",
                s"snapshot $fromSnapshot has been expired; the table is no longer recoverable"
              )
            )
          else if reader.maxSnapshotId().exists(m => reader.tableExistsAt(schema, target, m)) then
            IO.pure(
              err(
                StatusCode.Conflict,
                "name_conflict",
                s"table '$schema.$target' exists; supply asName to restore under a " +
                  "different name"
              )
            )
          else
            pool match
              case None =>
                IO.pure(
                  err(
                    StatusCode.NotFound,
                    "no_pool",
                    s"tenant-db '$db' has no running pool; undrop needs at least one pool " +
                      "with a live WriteOnly/Dual node"
                  )
                )
              case Some(poolKey) =>
                caller match
                  case Left(e)  => IO.pure(Left(e))
                  case Right(c) =>
                    val sql =
                      s"CREATE TABLE ${quoteIdent(schema)}.${quoteIdent(target)} AS " +
                        s"SELECT * FROM ${quoteIdent(schema)}.${quoteIdent(table)} " +
                        s"AT (VERSION => $fromSnapshot)"
                    val committed: Either[(StatusCode, ErrorResponse), UndropResponse] =
                      Right(UndropResponse(schema, table, target, fromSnapshot))
                    executor(c, poolKey, sql)
                      .timeout(cfg.undropTimeoutSec.seconds)
                      .attempt
                      .map {
                        case Left(e) =>
                          // Cancellation of the blocking HTTP leg is best-effort, so a CTAS that
                          // outlives the timeout may still have committed on the node: probe the
                          // catalog before answering so the response and audit reflect reality.
                          if reader
                              .maxSnapshotId()
                              .exists(m => reader.tableExistsAt(schema, target, m))
                          then committed
                          else
                            val msg = e match
                              case _: java.util.concurrent.TimeoutException =>
                                s"undrop timed out after ${cfg.undropTimeoutSec}s"
                              case other => s"undrop failed: ${other.getMessage}"
                            err(StatusCode.BadGateway, "undrop_failed", msg)
                        case Right(Left(RouterFailure.AccessDenied(reason))) =>
                          err(StatusCode.Forbidden, "acl_denied", reason)
                        case Right(Left(failure)) =>
                          err(StatusCode.BadGateway, "undrop_failed", failure.reason)
                        case Right(Right(result)) =>
                          // A CTAS result stream carries only a row count nobody reads.
                          result.close()
                          committed
                      }
