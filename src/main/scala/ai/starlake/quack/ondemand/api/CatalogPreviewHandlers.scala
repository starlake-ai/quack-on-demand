package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.{PoolKey, Role}
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

  /** Identity contract for the executor call: a valid session token resolves to its
    * `(profile.username)`; no token, an unresolved token (static API key), or open/dev mode all
    * fall back to `"superuser"` as the executor `user` -- mirroring the data-plane's
    * superuser-bypass semantics (a static-key / no-session caller is treated as trusted, exactly
    * like every other REST handler in this codebase). The real executor adapter
    * ([[ai.starlake.quack.Main]]) maps this identity to a synthetic superuser
    * [[ai.starlake.quack.ondemand.rbac.EffectiveSet]] (`user.tenant = None`) so
    * `PostgresAclValidator`'s superuser bypass applies -- NOT `effectiveSet = None`, which the
    * validator treats as "no RBAC principal bound to this session" and denies fail-safe. Public
    * (not `private[api]`) so Main's adapter, in a different package, can match on it.
    *
    * SAFETY INVARIANT (perimeter-enforced): this fallback is only sound because
    * `ManagerServer.apiKeyGuard` guarantees that when a static API key is configured, the only
    * requests reaching this handler carry either a VALID admin session or the static key itself; an
    * expired/revoked/forged session token is rejected with 401 at the perimeter and never reaches
    * this fallback. In open mode (no static key) everything is trusted by definition. If the
    * perimeter contract ever changes (for example per-endpoint guards), this conflation of
    * "unresolved token" with "trusted static key" must be revisited: the durable shape is a typed
    * caller identity minted by the guard (see the authz-consolidation item in
    * docs/AUDIT-FOLLOWUPS.md P3).
    */
  val SuperuserIdentity = "superuser"

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
    catalogAlias: (String, String) => String = (_, td) => td,
    audit: AuditRecorder = AuditRecorder.noop
):
  import CatalogPreviewHandlers.SuperuserIdentity

  private type Out[T] = IO[Either[(StatusCode, ErrorResponse), T]]

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  private def quoteLit(v: String): String = "'" + v.replace("'", "''") + "'"

  // Verified live on the pinned DuckDB 1.5.4 / DuckLake 0.3 (2026-07-14, see
  // docs/duckdb-pin-bump-checklist.md section 5): ducklake_table_changes emits exactly
  // insert / delete / update_preimage / update_postimage (inline and flushed writes both
  // attribute to the logical DML snapshot; no inlined_* variants unlike changes_made); both
  // bounds are INCLUSIVE, hence the (from + 1, to) call convention for an exclusive-of-from
  // diff; and a bound naming a nonexistent snapshot is an engine ERROR ("No snapshot found at
  // version N"), not an empty result, which is why dataDiff short-circuits from == to.
  private val InsertTypes    = Set("insert")
  private val DeleteTypes    = Set("delete")
  private val UpdateTypes    = Set("update_preimage", "update_postimage")
  private val UpdatePostType = "update_postimage"

  private def diffFn(
      alias: String,
      schema: String,
      table: String,
      fromId: Long,
      toId: Long
  ): String =
    s"ducklake_table_changes(${quoteLit(alias)}, ${quoteLit(schema)}, ${quoteLit(table)}, " +
      s"${fromId + 1}, $toId)"

  private def parseCursor(raw: String): Option[(Long, Long)] =
    raw.split(':') match
      case Array(a, b) if a.nonEmpty && b.nonEmpty && a.forall(_.isDigit) && b.forall(_.isDigit) =>
        Some((a.toLong, b.toLong))
      case _ => None

  /** Map the request's `changeType` filter onto the raw change_type values; Left = unknown value.
    */
  private def typesFor(changeType: Option[String]): Either[Unit, Option[Set[String]]] =
    changeType match
      case None           => Right(None)
      case Some("insert") => Right(Some(InsertTypes))
      case Some("delete") => Right(Some(DeleteTypes))
      case Some("update") => Right(Some(UpdateTypes))
      case Some(_)        => Left(())

  /** [[TenantDbGate]] with the kind check: previews require a DuckLake tenant-db (400
    * `invalid_kind` otherwise), unlike the catalog browser's kind-tolerant reads.
    */
  private def gate(rawTenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Either[(StatusCode, ErrorResponse), (String, String)] =
    TenantDbGate(
      sup,
      rawTenant,
      tenantDb,
      apiKey,
      requireDuckLake = Some("data preview requires a ducklake tenant-db")
    )(scopeOf)

  /** Pool pick for `(tenant, tenantDb)`, stable across calls (Task 4 review carry-forward:
    * `sup.list()` iterates a mutable map, so picking "the first" without a deterministic sort could
    * return a different pool on every call). Sorted by pool name; among the candidates, prefer one
    * whose CURRENT [[ai.starlake.quack.route.PoolSnapshot]] carries at least one `ReadOnly` or
    * `Dual` node (a `SELECT` needs [[ai.starlake.quack.route.RoleMatcher]]'s read-eligible roles to
    * route at all) -- `sup.snapshot(key)` is an O(1) map lookup plus the tracker's
    * already-materialized load map, so this preference costs nothing extra to compute. A tenant-db
    * whose only pool is WriteOnly still resolves (falls through to the first candidate by name)
    * rather than 404 `no_pool`; `Router.pick` inside the real executor is the authority on whether
    * the query can actually route, and surfaces `RouterFailure.Unavailable` if it can't.
    */
  private def firstPoolKey(tenant: String, tenantDb: String): Option[PoolKey] =
    val candidates = sup
      .list()
      .map(_.key)
      .filter(k => k.tenant == tenant && k.tenantDb == tenantDb)
      .sortBy(_.pool)
    def hasReadEligibleNode(key: PoolKey): Boolean =
      sup
        .snapshot(key)
        .exists(_.nodes.exists(n => n.role == Role.ReadOnly || n.role == Role.Dual))
    candidates.find(hasReadEligibleNode).orElse(candidates.headOption)

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

  /** Parse one `from`/`to` bound: all-digits is a snapshot id (`asOf`), anything else is a tag name
    * (`asOfTag`). Fed into [[SnapshotSelector.resolve]] per bound so each side gets the same
    * expired/beyond-max/tag-not-found/empty-catalog semantics as [[preview]]'s single selector.
    */
  private def parseBound(raw: String): (Option[Long], Option[String]) =
    if raw.nonEmpty && raw.forall(_.isDigit) then (Some(raw.toLong), None) else (None, Some(raw))

  private def resolveBound(
      raw: String,
      reader: DuckLakeCatalogReader,
      tid: String,
      db: String
  ): Either[SnapshotSelector.SelectorError, Long] =
    val (asOf, asOfTag) = parseBound(raw)
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
      .map {
        case SnapshotSelector.Resolution.Current =>
          sys.error("unreachable: parseBound never yields no selector")
        case SnapshotSelector.Resolution.At(id, _) => id
      }

  /** Two-snapshot column-level schema diff (Spec 00 Task 6). `from`/`to` each accept EITHER a
    * snapshot id (all-digits) or a tag name, resolved independently through [[SnapshotSelector]] so
    * each side gets its own 410 `snapshot_expired` / 422 `invalid_snapshot` / 404 `not_found`
    * (unknown tag) outcome. Once both bounds resolve to concrete snapshot ids, the table is looked
    * up at `to` falling back to `from` (mirrors [[DuckLakeCatalogReader.schemaDiff]]'s own
    * resolution order, via the cheap [[DuckLakeCatalogReader.tableExistsAt]] probe) so an unknown
    * table surfaces as 404 `not_found` from the handler instead of the reader's silent empty-diff
    * fallback. `from == to` is a valid request and yields an all-empty diff
    * (added/removed/typeChanged/nullabilityChanged all empty).
    *
    * Audited unconditionally (denied + ok), same convention as [[preview]]. Once the bounds are
    * resolved, every audit event (ok, and the unknown-table denial) carries the resolved snapshot
    * ids as `detail = {from, to}`; pre-resolution denials (gate rejection, selector error) have no
    * ids yet and stay detail-less.
    */
  def schemaDiff(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String,
      from: String,
      to: String,
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[SchemaDiffResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogSchemaDiffRead, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        val reader = resolveReader(tid, db)
        val target = Some(s"$db/$schema/$table")

        def denyAudit(detail: Map[String, String] = Map.empty): Unit =
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogSchemaDiffRead,
            "denied",
            tenant = Some(tid),
            target = target,
            detail = detail
          )

        val resolved =
          for
            fromId <- resolveBound(from, reader, tid, db)
            toId   <- resolveBound(to, reader, tid, db)
          yield (fromId, toId)

        resolved match
          case Left(se) =>
            val (sc, code, msg) = selectorError(se)
            denyAudit()
            IO.pure(err(sc, code, msg))
          case Right((fromId, toId)) =>
            val bounds = Map("from" -> fromId.toString, "to" -> toId.toString)
            if !(reader.tableExistsAt(schema, table, toId) ||
                reader.tableExistsAt(schema, table, fromId))
            then
              denyAudit(bounds)
              IO.pure(
                err(StatusCode.NotFound, "not_found", s"table '$schema.$table' not found")
              )
            else
              val diff     = reader.schemaDiff(schema, table, fromId, toId)
              val response = SchemaDiffResponse(
                from = fromId,
                to = toId,
                added = diff.added,
                removed = diff.removed,
                typeChanged = diff.typeChanged.map { case (col, f, t) =>
                  SchemaDiffColumnType(col, f, t)
                },
                nullabilityChanged = diff.nullabilityChanged.map { case (col, f, t) =>
                  SchemaDiffNullability(col, f, t)
                }
              )
              audit.rest(
                apiKey,
                "control-plane",
                AuditActions.CatalogSchemaDiffRead,
                "ok",
                tenant = Some(tid),
                target = target,
                detail = bounds
              )
              IO.pure(Right(response))

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
                // Fetch one row beyond the cap so ArrowRowsDecoder's truncation check has
                // something to observe: a SQL `LIMIT effectiveLimit` would hand the decoder a
                // stream that never has more rows than the cap, so `truncated` could never be
                // true no matter how large the underlying table is. `decode` still stops
                // collecting at `effectiveLimit` -- the response never carries the extra row.
                val sql  = buildSql(schema, table, snapshotId, effectiveLimit + 1)
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

  /** Row-level data diff between two snapshots (Spec 02). Same execution shape as [[preview]] (gate
    * -> selector resolution -> pool pick -> executor -> Arrow decode), but two statements: a
    * whole-range aggregate so the summary counts stay exact under pagination, then a keyset page
    * over `ducklake_table_changes`. Update pre/post rows sharing (snapshot_id, rowid) are paired
    * into one `update` entry. The page fetches `2 * limit + 2` source rows: a pair renders one
    * entry per two source rows and a single one per one, so a full fetch always renders at least
    * `limit + 1` entries and the entry cap is reached before a truncated tail could strand half a
    * pair; a genuinely orphaned half-row at end-of-data passes through with its raw change type.
    * Audited unconditionally like [[preview]]; detail carries from/to/rowsReturned, never row
    * contents.
    */
  def dataDiff(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String,
      from: String,
      to: String,
      limit: Option[Int],
      cursor: Option[String],
      changeType: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[DataDiffResponse] =
    gate(tenant, tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogDataDiffRead, "denied")
        IO.pure(Left(e))
      case Right((tid, db)) =>
        val target = Some(s"$db/$schema/$table")
        def denied(
            sc: StatusCode,
            code: String,
            msg: String
        ): Either[(StatusCode, ErrorResponse), DataDiffResponse] =
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogDataDiffRead,
            "denied",
            tenant = Some(tid),
            target = target
          )
          err(sc, code, msg)

        typesFor(changeType) match
          case Left(_) =>
            IO.pure(
              denied(
                StatusCode.BadRequest,
                "invalid_filter",
                "changeType must be one of insert, delete, update"
              )
            )
          case Right(types) =>
            cursor.map(c => (c, parseCursor(c))) match
              case Some((raw, None)) =>
                IO.pure(
                  denied(
                    StatusCode.BadRequest,
                    "invalid_cursor",
                    s"cursor '$raw' is not <snapshotId>:<rowid>"
                  )
                )
              case parsedCursor =>
                val cur      = parsedCursor.flatMap(_._2)
                val reader   = resolveReader(tid, db)
                val resolved =
                  for
                    fromId <- resolveBound(from, reader, tid, db)
                    toId   <- resolveBound(to, reader, tid, db)
                  yield (fromId, toId)
                resolved match
                  case Left(se) =>
                    val (sc, code, msg) = selectorError(se)
                    IO.pure(denied(sc, code, msg))
                  case Right((fromId, toId)) if fromId > toId =>
                    IO.pure(
                      denied(
                        StatusCode.BadRequest,
                        "invalid_bounds",
                        s"from snapshot $fromId is after to snapshot $toId"
                      )
                    )
                  case Right((fromId, toId)) if fromId == toId =>
                    // Empty diff by definition; also load-bearing: the (from + 1, to) call would
                    // name snapshot to + 1, which the engine rejects as an error when it does not
                    // exist (verified live), not as an empty result.
                    audit.rest(
                      apiKey,
                      "control-plane",
                      AuditActions.CatalogDataDiffRead,
                      "ok",
                      tenant = Some(tid),
                      target = target,
                      detail = Map(
                        "from"         -> fromId.toString,
                        "to"           -> toId.toString,
                        "rowsReturned" -> "0"
                      )
                    )
                    IO.pure(
                      Right(
                        DataDiffResponse(
                          schema,
                          table,
                          fromId,
                          toId,
                          DataDiffSummary(0, 0, 0),
                          Nil,
                          Nil,
                          None,
                          truncated = false
                        )
                      )
                    )
                  case Right((fromId, toId)) =>
                    if !(reader.tableExistsAt(schema, table, toId) ||
                        reader.tableExistsAt(schema, table, fromId))
                    then
                      IO.pure(
                        denied(
                          StatusCode.NotFound,
                          "not_found",
                          s"table '$schema.$table' not found"
                        )
                      )
                    else
                      firstPoolKey(tid, db) match
                        case None =>
                          IO.pure(
                            denied(
                              StatusCode.NotFound,
                              "no_pool",
                              s"tenant-db '$db' has no running pool; data diff needs at least " +
                                "one pool with a live ReadOnly/Dual node"
                            )
                          )
                        case Some(poolKey) =>
                          runDiff(
                            tid,
                            db,
                            schema,
                            table,
                            fromId,
                            toId,
                            limit,
                            cur,
                            types,
                            poolKey,
                            apiKey,
                            denied
                          )

  private def runDiff(
      tid: String,
      db: String,
      schema: String,
      table: String,
      fromId: Long,
      toId: Long,
      limit: Option[Int],
      cur: Option[(Long, Long)],
      types: Option[Set[String]],
      poolKey: PoolKey,
      apiKey: Option[String],
      denied: (StatusCode, String, String) => Either[(StatusCode, ErrorResponse), DataDiffResponse]
  ): Out[DataDiffResponse] =
    val alias          = catalogAlias(tid, db)
    val fn             = diffFn(alias, schema, table, fromId, toId)
    val effectiveLimit = limit.map(_.max(1)).getOrElse(cfg.previewMaxRows).min(cfg.previewMaxRows)
    val fetch          = effectiveLimit * 2 + 2
    val filterPred     = types
      .map(ts => s" AND change_type IN (${ts.toList.sorted.map(quoteLit).mkString(", ")})")
      .getOrElse("")
    val cursorPred = cur.map((s, r) => s" AND (snapshot_id, rowid) > ($s, $r)").getOrElse("")
    val summarySql = s"SELECT change_type, count(*) AS n FROM $fn GROUP BY change_type"
    val pageSql    =
      s"SELECT * FROM $fn WHERE 1=1$cursorPred$filterPred " +
        s"ORDER BY snapshot_id, rowid, change_type LIMIT $fetch"
    val user = identityOf(apiKey)

    def run(sql: String): IO[Either[Throwable, Either[RouterFailure, QueryResult]]] =
      executor(s"diff-$tid-$db", user, poolKey, sql)
        .timeout(cfg.previewTimeoutSec.seconds)
        .attempt

    def failureArm(
        outcome: Either[Throwable, Either[RouterFailure, QueryResult]]
    ): Option[Either[(StatusCode, ErrorResponse), DataDiffResponse]] =
      outcome match
        case Left(_) =>
          Some(denied(StatusCode.BadGateway, "diff_failed", "diff query timed out"))
        case Right(Left(RouterFailure.AccessDenied(reason))) =>
          Some(denied(StatusCode.Forbidden, "acl_denied", reason))
        case Right(Left(failure)) =>
          Some(denied(StatusCode.BadGateway, "diff_failed", failure.reason))
        case Right(Right(_)) => None

    run(summarySql).flatMap { summaryOutcome =>
      failureArm(summaryOutcome) match
        case Some(e) => IO.pure(e)
        case None    =>
          val summaryResult = summaryOutcome.toOption.get.toOption.get
          val summary       =
            try
              val (_, rows, _) = ArrowRowsDecoder.decode(summaryResult.rows, 64)
              val counts       = rows.flatMap { r =>
                for
                  ct <- r.headOption.flatMap(_.asString)
                  n  <- r.lift(1).flatMap(_.asNumber).flatMap(_.toLong)
                yield ct -> n
              }.toMap
              DataDiffSummary(
                inserted = InsertTypes.toList.map(counts.getOrElse(_, 0L)).sum,
                deleted = DeleteTypes.toList.map(counts.getOrElse(_, 0L)).sum,
                updated = counts.getOrElse(UpdatePostType, 0L)
              )
            finally summaryResult.close()
          run(pageSql).map { pageOutcome =>
            failureArm(pageOutcome) match
              case Some(e) => e
              case None    =>
                val pageResult = pageOutcome.toOption.get.toOption.get
                try
                  val (cols, rawRows, decoderTruncated) =
                    ArrowRowsDecoder.decode(pageResult.rows, fetch)
                  val snapIdx = cols.indexWhere(_.name.equalsIgnoreCase("snapshot_id"))
                  val rowIdx  = cols.indexWhere(_.name.equalsIgnoreCase("rowid"))
                  val ctIdx   = cols.indexWhere(_.name.equalsIgnoreCase("change_type"))
                  if snapIdx < 0 || rowIdx < 0 || ctIdx < 0 then
                    denied(
                      StatusCode.BadGateway,
                      "diff_failed",
                      "unexpected ducklake_table_changes result shape"
                    )
                  else
                    val metaIdx  = Set(snapIdx, rowIdx, ctIdx)
                    val dataCols = cols.zipWithIndex.filterNot((_, i) => metaIdx(i)).map(_._1)
                    val src      = rawRows.flatMap { r =>
                      for
                        snap <- r.lift(snapIdx).flatMap(_.asNumber).flatMap(_.toLong)
                        rid  <- r.lift(rowIdx).flatMap(_.asNumber).flatMap(_.toLong)
                        ct   <- r.lift(ctIdx).flatMap(_.asString)
                      yield (
                        snap,
                        rid,
                        ct,
                        r.zipWithIndex.filterNot((_, i) => metaIdx(i)).map(_._1)
                      )
                    }
                    val entries = scala.collection.mutable.ListBuffer.empty[DataDiffEntry]
                    var lastKey: Option[(Long, Long)] = None
                    var i                             = 0
                    while i < src.length && entries.length < effectiveLimit do
                      val (snap, rid, ct, data) = src(i)
                      val paired                =
                        if UpdateTypes(ct) && i + 1 < src.length then
                          val (nSnap, nRid, nCt, nData) = src(i + 1)
                          Option.when(
                            UpdateTypes(nCt) && nCt != ct && nSnap == snap && nRid == rid
                          )((nCt, nData))
                        else None
                      paired match
                        case Some((nCt, nData)) =>
                          val (before, after) =
                            if ct == UpdatePostType then (nData, data) else (data, nData)
                          entries += DataDiffEntry(
                            "update",
                            snap,
                            before = Some(before),
                            after = Some(after)
                          )
                          lastKey = Some((snap, rid))
                          i += 2
                        case None =>
                          val rendered =
                            if InsertTypes(ct) then "insert"
                            else if DeleteTypes(ct) then "delete"
                            else ct
                          entries += DataDiffEntry(rendered, snap, row = Some(data))
                          lastKey = Some((snap, rid))
                          i += 1
                    val hasMore    = decoderTruncated || i < src.length
                    val nextCursor = if hasMore then lastKey.map((s, r) => s"$s:$r") else None
                    audit.rest(
                      apiKey,
                      "control-plane",
                      AuditActions.CatalogDataDiffRead,
                      "ok",
                      tenant = Some(tid),
                      target = Some(s"$db/$schema/$table"),
                      detail = Map(
                        "from"         -> fromId.toString,
                        "to"           -> toId.toString,
                        "rowsReturned" -> entries.length.toString
                      )
                    )
                    Right(
                      DataDiffResponse(
                        schema,
                        table,
                        fromId,
                        toId,
                        summary,
                        dataCols,
                        entries.toList,
                        nextCursor,
                        truncated = hasMore
                      )
                    )
                finally pageResult.close()
          }
    }
