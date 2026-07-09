package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.{PoolKey, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

import java.time.Instant
import scala.concurrent.duration.DurationInt

object CatalogPreviewHandlers:

  /** `(connectionId, user, poolKey, sql) -> IO[Either[RouterFailure, QueryResult]]`, the same shape
    * as [[ai.starlake.quack.edge.FlightSqlRouter.execute]]'s first four positional params. Task 5
    * adapts the real router (plus effective-set resolution) to this shape; here it lets the handler
    * stay unit-testable and keeps `RouterFailure` as the single failure vocabulary the handler maps
    * to REST status codes (`AccessDenied` -> 403 `acl_denied`, everything else -> 502
    * `preview_failed`).
    */
  type PreviewExecutor = (String, String, PoolKey, String) => IO[Either[RouterFailure, QueryResult]]

  /** Identity contract for the executor call (documented here since Task 5 wires the real ACL
    * resolution): a valid session token resolves to its `(profile.username)`; no token, an
    * unresolved token (static API key), or open/dev mode all fall back to `"superuser"` as the
    * executor `user` -- mirroring the data-plane's superuser-bypass semantics (a static-key /
    * no-session caller is treated as trusted, exactly like every other REST handler in this
    * codebase). Task 5's real executor adapter is expected to pass `effectiveSet = None` for that
    * identity so `PostgresAclValidator`'s superuser bypass applies, rather than inventing a fake
    * ACL closure.
    */
  private[api] val SuperuserIdentity = "superuser"

/** Bounded, ACL-routed snapshot preview (Spec 00 time-travel viewer). `preview` runs the same
  * tenant-resolve -> [[TenantScopeCheck]] -> tenant-db-lookup gate as [[TagHandlers]] (a non-
  * DuckLake tenant-db is rejected with 400 `invalid_kind`: previews need the DuckLake `AT (VERSION
  * => n)` clause, unlike the catalog browser's tolerant empty-result UX), then resolves the
  * snapshot selector via [[SnapshotSelector]], picks the tenant-db's first pool (404 `no_pool` when
  * none is running), builds a bounded `SELECT * FROM "schema"."table" [AT (VERSION => n)] LIMIT k`
  * statement, and forwards it to the injected [[CatalogPreviewHandlers.PreviewExecutor]].
  *
  * The executor's `ArrowReader` is decoded through [[ArrowRowsDecoder]] with `maxRows =
  * cfg.previewMaxRows` (the request `limit`, when given, further clamps that cap downward, never
  * up) and the whole call is wrapped in `IO.timeout(cfg.previewTimeoutSec.seconds)`, mapping a
  * timeout to the same 502 `preview_failed` outcome as any other executor failure.
  *
  * A `CatalogPreviewRead` audit event fires unconditionally (unlike [[CatalogHandlers]]' reads,
  * which are gated behind `cfg.auditCatalogReads`): preview runs a real query against a live node,
  * so it is audited every time, on both "denied" (gate rejection) and "ok" (success, with
  * `rowsReturned` in the detail map) outcomes. A post-gate SQL failure surfaces as a REST error but
  * is still recorded "ok" from the gate's perspective (mirrors [[CatalogHandlers]]'s "the audit
  * outcome tracks the gate, not the read" convention) -- the 403 `acl_denied` / 502 `preview_failed`
  * distinction is carried in the HTTP response, not duplicated into a second audit outcome value.
  */
final class CatalogPreviewHandlers(
    sup: PoolSupervisor,
    store: ControlPlaneStore,
    sessions: String => Option[SessionTokenStore.Session],
    executor: CatalogPreviewHandlers.PreviewExecutor,
    resolveReader: (String, String) => DuckLakeCatalogReader,
    cfg: CatalogConfig,
    audit: AuditRecorder = AuditRecorder.noop
):
  import CatalogPreviewHandlers.SuperuserIdentity

  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def resolveTenantId(raw: String): Option[String] =
    sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id)

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** Tenant resolve -> scope gate -> tenant-db lookup, mirroring [[TagHandlers.gate]]: previews
    * require a DuckLake tenant-db (400 `invalid_kind` otherwise), unlike the catalog browser's
    * kind-tolerant reads.
    */
  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    resolveTenantId(rawTenant) match
      case None =>
        err(StatusCode.NotFound, "not_found", s"tenant '$rawTenant' is not registered")
      case Some(tid) =>
        TenantScopeCheck.reject(apiKey, tid)(scopeOf) match
          case Some(e) => Left(e)
          case None    =>
            sup.findTenantDb(tid, tenantDb) match
              case None =>
                err(StatusCode.NotFound, "not_found", s"tenant-db '$tenantDb' not found")
              case Some(td) if td.kind != TenantDbKind.DuckLake =>
                err(
                  StatusCode.BadRequest,
                  "invalid_kind",
                  "data preview requires a ducklake tenant-db"
                )
              case Some(td) => Right((tid, td.name))

  /** First pool registered under `(tenant, tenantDb)`. Preview needs a live pool with at least one
    * ReadOnly/Dual node to route the query to; `sup.list()` returns every pool across every
    * tenant-db, so this filters by key rather than relying on a narrower listing helper.
    */
  private def firstPoolKey(tenant: String, tenantDb: String): Option[PoolKey] =
    sup.list().map(_.key).find(k => k.tenant == tenant && k.tenantDb == tenantDb)

  private def identityOf(apiKey: Option[String]): String =
    apiKey.flatMap(sessions) match
      case Some(session) => session.profile.username
      case None          => SuperuserIdentity

  private def buildSql(
      schema: String,
      table: String,
      snapshotId: Option[Long],
      limit: Int
  ): String =
    val target   = s"${quoteIdent(schema)}.${quoteIdent(table)}"
    val atClause = snapshotId.fold("")(id => s" AT (VERSION => $id)")
    s"SELECT * FROM $target$atClause LIMIT $limit"

  private def selectorError(
      e: SnapshotSelector.SelectorError
  ): (StatusCode, String, String) = e match
    case SnapshotSelector.SelectorError.MultipleSelectors =>
      (StatusCode.BadRequest, "invalid_selector", "supply only one of asOf, asOfTag, or asOfTs")
    case SnapshotSelector.SelectorError.TagNotFound(tag) =>
      (StatusCode.NotFound, "not_found", s"tag '$tag' not found")
    case SnapshotSelector.SelectorError.BeforeFirstSnapshot =>
      (StatusCode.UnprocessableEntity, "invalid_snapshot", "timestamp is before the first snapshot")
    case SnapshotSelector.SelectorError.EmptyCatalog =>
      (StatusCode.UnprocessableEntity, "invalid_snapshot", "catalog has no snapshots")
    case SnapshotSelector.SelectorError.BeyondLatest(id) =>
      (
        StatusCode.UnprocessableEntity,
        "invalid_snapshot",
        s"snapshot $id is beyond the latest snapshot"
      )
    case SnapshotSelector.SelectorError.Expired(id) =>
      (StatusCode.Gone, "snapshot_expired", s"snapshot $id has been vacuumed")

  def preview(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String,
      asOf: Option[Long],
      asOfTag: Option[String],
      asOfTs: Option[Instant],
      limit: Option[Int],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[PreviewResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogPreviewRead, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        val reader      = resolveReader(tid, db)
        val selectorRes = SnapshotSelector.resolve(
          asOf,
          asOfTag,
          asOfTs,
          maxId = () => reader.maxSnapshotId(),
          atOrBefore = (ts: Instant) => reader.snapshotAtOrBefore(ts),
          tagSnapshot = (tag: String) => store.findSnapshotTag(tid, db, tag).map(_.snapshotId),
          exists = (id: Long) => reader.snapshotExists(id)
        )
        selectorRes match
          case Left(se) =>
            val (sc, code, msg) = selectorError(se)
            audit.rest(
              apiKey,
              "control-plane",
              AuditActions.CatalogPreviewRead,
              "denied",
              tenant = Some(tid),
              target = Some(s"$db/$schema/$table")
            )
            IO.pure(err(sc, code, msg))
          case Right(resolution) =>
            firstPoolKey(tid, db) match
              case None =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.CatalogPreviewRead,
                  "denied",
                  tenant = Some(tid),
                  target = Some(s"$db/$schema/$table")
                )
                IO.pure(
                  err(
                    StatusCode.NotFound,
                    "no_pool",
                    s"tenant-db '$db' has no running pool; preview needs at least one pool " +
                      "with a live ReadOnly/Dual node"
                  )
                )
              case Some(poolKey) =>
                val snapshotId = resolution match
                  case SnapshotSelector.Resolution.Current   => None
                  case SnapshotSelector.Resolution.At(id, _) => Some(id)
                val effectiveLimit =
                  limit.map(_.max(1)).getOrElse(cfg.previewMaxRows).min(cfg.previewMaxRows)
                val sql  = buildSql(schema, table, snapshotId, effectiveLimit)
                val user = identityOf(apiKey)

                executor(s"preview-$tid-$db", user, poolKey, sql)
                  .timeout(cfg.previewTimeoutSec.seconds)
                  .attempt
                  .map {
                    case Left(_) =>
                      audit.rest(
                        apiKey,
                        "control-plane",
                        AuditActions.CatalogPreviewRead,
                        "denied",
                        tenant = Some(tid),
                        target = Some(s"$db/$schema/$table")
                      )
                      err(StatusCode.BadGateway, "preview_failed", "preview query timed out")
                    case Right(Left(RouterFailure.AccessDenied(reason))) =>
                      audit.rest(
                        apiKey,
                        "control-plane",
                        AuditActions.CatalogPreviewRead,
                        "denied",
                        tenant = Some(tid),
                        target = Some(s"$db/$schema/$table")
                      )
                      err(StatusCode.Forbidden, "acl_denied", reason)
                    case Right(Left(failure)) =>
                      audit.rest(
                        apiKey,
                        "control-plane",
                        AuditActions.CatalogPreviewRead,
                        "denied",
                        tenant = Some(tid),
                        target = Some(s"$db/$schema/$table")
                      )
                      err(StatusCode.BadGateway, "preview_failed", failure.reason)
                    case Right(Right(result)) =>
                      try
                        val (columns, rows, truncated) =
                          ArrowRowsDecoder.decode(result.rows, effectiveLimit)
                        audit.rest(
                          apiKey,
                          "control-plane",
                          AuditActions.CatalogPreviewRead,
                          "ok",
                          tenant = Some(tid),
                          target = Some(s"$db/$schema/$table"),
                          detail = Map("rowsReturned" -> rows.size.toString)
                        )
                        Right(PreviewResponse(columns, rows, snapshotId, truncated))
                      finally result.close()
                  }
