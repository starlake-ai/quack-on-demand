package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

import java.time.Instant
import scala.concurrent.duration.DurationInt

/** Restore (Spec 04): roll a live table back to a prior snapshot as a NEW forward snapshot.
  *
  * Execution is a single `CREATE OR REPLACE TABLE s.t AS SELECT * FROM s.t AT (VERSION => n)`
  * routed through the write executor (`recordExecution = true` in Main, so the replace snapshot
  * carries the principal's author stamp and the statement is ACL-checked per table: the CTAS emits
  * Ddl + Read on the table, so the caller needs ALL, or DDL plus RO/RW). The dry run is the Spec 02
  * aggregate over `(toSnapshot, currentSnapshot]` on the read executor: exactly the changes the
  * restore will undo. Verified-live engine facts (Spec 04 Task 1): the replace assigns a new
  * table_id whose begin_snapshot is the replace snapshot (the basis of both the `newSnapshot` in
  * the response and the committed-despite-timeout probe), and a failed replace leaves the current
  * state intact.
  *
  * Restore is a mutation: audited unconditionally, dry-runs included (detail carries dryRun). Known
  * race, by design (no auto-pin): a maintenance expiry between the probes and the CTAS fails the
  * statement transactionally; the caller retries into a clean 410.
  */
final class CatalogRestoreHandlers(
    sup: PoolSupervisor,
    store: ControlPlaneStore,
    readExecutor: CatalogPreviewHandlers.PreviewExecutor,
    writeExecutor: CatalogPreviewHandlers.PreviewExecutor,
    resolveReader: (String, String) => DuckLakeCatalogReader,
    cfg: CatalogConfig,
    sessions: String => Option[SessionTokenStore.Session],
    catalogAlias: (String, String) => String = (_, td) => td,
    audit: AuditRecorder = AuditRecorder.noop
):
  import CatalogPreviewHandlers.SuperuserIdentity

  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** Same unsafe-char screen as CatalogUndropHandlers: these values end up inside SQL text. */
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
      requireDuckLake = Some("restore requires a ducklake tenant-db")
    )(scopeOf)

  /** Same bound grammar as the data diff (all-digits = snapshot id, else tag name), with an
    * explicit overflow guard the shared copy lacks (all-digit beyond Long.MaxValue must be a 400,
    * not a NumberFormatException).
    */
  private def resolveTo(
      raw: String,
      reader: DuckLakeCatalogReader,
      tid: String,
      db: String
  ): Either[(StatusCode, String, String), Long] =
    val parsed: Either[Unit, (Option[Long], Option[String])] =
      if raw.isEmpty then Left(())
      else if raw.forall(_.isDigit) then
        scala.util.Try(raw.toLong).toOption match
          case Some(id) => Right((Some(id), None))
          case None     => Left(())
      else Right((None, Some(raw)))
    parsed match
      case Left(_) =>
        Left(
          (
            StatusCode.BadRequest,
            "invalid_snapshot_ref",
            s"'$raw' is not a snapshot id or tag name"
          )
        )
      case Right((asOf, asOfTag)) =>
        SnapshotSelector
          .resolve(
            asOf,
            asOfTag,
            None,
            maxId = () => reader.maxSnapshotId(),
            atOrBefore = (ts: Instant) => reader.snapshotAtOrBefore(ts),
            tagSnapshot = (tag: String) => store.findSnapshotTag(tid, db, tag).map(_.snapshotId),
            exists = (id: Long) => reader.snapshotExists(id)
          )
          .left
          .map(SnapshotSelector.httpError)
          .map {
            case SnapshotSelector.Resolution.Current   => 0L // unreachable: a bound always parses
            case SnapshotSelector.Resolution.At(id, _) => id
          }

  def restore(req: RestoreRequest, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[RestoreResponse] =
    gate(req.tenant, req.tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogRestore, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        val dryRun = req.dryRun.getOrElse(false)
        val target = Some(s"$db/${req.schema}.${req.table}")
        def denied(
            sc: StatusCode,
            code: String,
            msg: String
        ): Either[(StatusCode, ErrorResponse), RestoreResponse] =
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogRestore,
            "denied",
            tenant = Some(tid),
            target = target
          )
          err(sc, code, msg)
        def okAudit(detail: Map[String, String]): Unit =
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogRestore,
            "ok",
            tenant = Some(tid),
            target = target,
            detail = detail
          )

        if List(req.schema, req.table).exists(v => v.isEmpty || hasUnsafeChars(v)) then
          IO.pure(
            denied(
              StatusCode.BadRequest,
              "invalid_name",
              "schema and table must be non-empty and free of quotes, separators, and whitespace"
            )
          )
        else if req.expectedCurrentSnapshot.exists(_ <= 0) then
          IO.pure(
            denied(
              StatusCode.BadRequest,
              "invalid_snapshot_ref",
              "expectedCurrentSnapshot must be a positive snapshot id"
            )
          )
        else
          val reader = resolveReader(tid, db)
          resolveTo(req.to, reader, tid, db) match
            case Left((sc, code, msg)) => IO.pure(denied(sc, code, msg))
            case Right(toSnapshot)     =>
              reader.currentTableInfo(req.schema, req.table) match
                case None =>
                  IO.pure(
                    denied(
                      StatusCode.NotFound,
                      "not_found",
                      s"table '${req.schema}.${req.table}' is not live at the current snapshot; " +
                        "for dropped tables use undrop"
                    )
                  )
                case Some((preTableId, _)) =>
                  if !reader.tableExistsAt(req.schema, req.table, toSnapshot) then
                    IO.pure(
                      denied(
                        StatusCode.UnprocessableEntity,
                        "invalid_snapshot",
                        s"table '${req.schema}.${req.table}' does not resolve at snapshot " +
                          s"$toSnapshot"
                      )
                    )
                  else
                    val currentSnapshot = reader
                      .latestTableSnapshot(req.schema, req.table)
                      .orElse(reader.maxSnapshotId())
                      .getOrElse(0L)
                    if req.expectedCurrentSnapshot.exists(_ != currentSnapshot) then
                      IO.pure(
                        denied(
                          StatusCode.Conflict,
                          "concurrent_write",
                          s"table has advanced to snapshot $currentSnapshot (expected " +
                            s"${req.expectedCurrentSnapshot.get}); re-run the dry run and " +
                            "confirm against the fresh state"
                        )
                      )
                    else if dryRun then
                      runDryRun(tid, db, req, toSnapshot, currentSnapshot, apiKey, denied, okAudit)
                    else
                      runExecute(
                        tid,
                        db,
                        req,
                        toSnapshot,
                        currentSnapshot,
                        preTableId,
                        reader,
                        apiKey,
                        denied,
                        okAudit
                      )

  private def runDryRun(
      tid: String,
      db: String,
      req: RestoreRequest,
      toSnapshot: Long,
      currentSnapshot: Long,
      apiKey: Option[String],
      denied: (StatusCode, String, String) => Either[(StatusCode, ErrorResponse), RestoreResponse],
      okAudit: Map[String, String] => Unit
  ): Out[RestoreResponse] =
    def ok(summary: DataDiffSummary): Either[(StatusCode, ErrorResponse), RestoreResponse] =
      okAudit(
        Map(
          "schema"     -> req.schema,
          "table"      -> req.table,
          "toSnapshot" -> toSnapshot.toString,
          "dryRun"     -> "true"
        )
      )
      Right(
        RestoreResponse(
          req.schema,
          req.table,
          toSnapshot,
          currentSnapshot,
          summary = Some(summary),
          newSnapshot = None,
          dryRun = true
        )
      )
    if toSnapshot >= currentSnapshot then
      // Nothing to undo (also load-bearing: DataDiffSql.diffFn(to, current) would name snapshot
      // to + 1, an engine ERROR when it does not exist; same convention as dataDiff's from == to
      // short-circuit).
      IO.pure(ok(DataDiffSummary(0, 0, 0)))
    else
      PoolPicks.readPoolKey(sup, tid, db) match
        case None =>
          IO.pure(
            denied(
              StatusCode.NotFound,
              "no_pool",
              s"tenant-db '$db' has no running pool; the restore dry run needs at least one " +
                "pool with a live ReadOnly/Dual node"
            )
          )
        case Some(poolKey) =>
          val sql = DataDiffSql.summarySql(
            catalogAlias(tid, db),
            req.schema,
            req.table,
            toSnapshot,
            currentSnapshot
          )
          readExecutor(s"restore-dryrun-$tid-$db", identityOf(apiKey), poolKey, sql)
            .timeout(cfg.previewTimeoutSec.seconds)
            .attempt
            .map {
              case Left(_) =>
                denied(StatusCode.BadGateway, "restore_failed", "dry-run summary query timed out")
              case Right(Left(RouterFailure.AccessDenied(reason))) =>
                denied(StatusCode.Forbidden, "acl_denied", reason)
              case Right(Left(failure)) =>
                denied(StatusCode.BadGateway, "restore_failed", failure.reason)
              case Right(Right(result)) =>
                try
                  val (_, rows, _) = ArrowRowsDecoder.decode(result.rows, 64)
                  ok(DataDiffSql.foldSummary(rows))
                finally result.close()
            }

  private def runExecute(
      tid: String,
      db: String,
      req: RestoreRequest,
      toSnapshot: Long,
      currentSnapshot: Long,
      preTableId: Long,
      reader: DuckLakeCatalogReader,
      apiKey: Option[String],
      denied: (StatusCode, String, String) => Either[(StatusCode, ErrorResponse), RestoreResponse],
      okAudit: Map[String, String] => Unit
  ): Out[RestoreResponse] =
    PoolPicks.writePoolKey(sup, tid, db) match
      case None =>
        IO.pure(
          denied(
            StatusCode.NotFound,
            "no_pool",
            s"tenant-db '$db' has no running pool; restore needs at least one pool with a " +
              "live WriteOnly/Dual node"
          )
        )
      case Some(poolKey) =>
        val sql =
          s"CREATE OR REPLACE TABLE ${quoteIdent(req.schema)}.${quoteIdent(req.table)} AS " +
            s"SELECT * FROM ${quoteIdent(req.schema)}.${quoteIdent(req.table)} " +
            s"AT (VERSION => $toSnapshot)"
        def committedResponse(): Either[(StatusCode, ErrorResponse), RestoreResponse] =
          val newSnapshot = reader.currentTableInfo(req.schema, req.table).map(_._2)
          okAudit(
            Map(
              "schema"      -> req.schema,
              "table"       -> req.table,
              "toSnapshot"  -> toSnapshot.toString,
              "dryRun"      -> "false",
              "newSnapshot" -> newSnapshot.map(_.toString).getOrElse("unknown")
            )
          )
          Right(
            RestoreResponse(
              req.schema,
              req.table,
              toSnapshot,
              currentSnapshot,
              summary = None,
              newSnapshot = newSnapshot,
              dryRun = false
            )
          )
        // Heuristic pinned by Task 1's live probe: DuckLake's optimistic-concurrency loser error
        // mentions a conflict. A false negative surfaces as 502 instead of 409 (retry guidance is
        // the same); a false positive requires "conflict" in an unrelated engine error.
        def isConflict(reason: String): Boolean = reason.toLowerCase.contains("conflict")
        writeExecutor(s"restore-$tid-$db", identityOf(apiKey), poolKey, sql)
          .timeout(cfg.restoreTimeoutSec.seconds)
          .attempt
          .map {
            case Left(e) =>
              // Cancellation of the blocking HTTP leg is best-effort, so a replace that outlives
              // the timeout may still have committed: probe by table_id change before answering.
              if reader.currentTableInfo(req.schema, req.table).exists(_._1 != preTableId) then
                committedResponse()
              else
                val msg = e match
                  case _: java.util.concurrent.TimeoutException =>
                    s"restore timed out after ${cfg.restoreTimeoutSec}s"
                  case other => s"restore failed: ${other.getMessage}"
                denied(StatusCode.BadGateway, "restore_failed", msg)
            case Right(Left(RouterFailure.AccessDenied(reason))) =>
              denied(StatusCode.Forbidden, "acl_denied", reason)
            case Right(Left(failure)) if isConflict(failure.reason) =>
              denied(StatusCode.Conflict, "concurrent_write", failure.reason)
            case Right(Left(failure)) =>
              denied(StatusCode.BadGateway, "restore_failed", failure.reason)
            case Right(Right(result)) =>
              // A CTAS result stream carries only a row count nobody reads.
              result.close()
              committedResponse()
          }
