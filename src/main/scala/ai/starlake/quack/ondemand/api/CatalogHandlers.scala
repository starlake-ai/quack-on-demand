package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.TenantDbKind
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import sttp.model.StatusCode

/** Catalog browser handlers. Catalog reads are scoped to a specific `(tenant, tenantDb)` pair
  * because each tenant-db owns its own DuckLake catalog. Tests stub `resolveReader`; production
  * wires it to a `(tenant, tenantDb) -> reader` cache backed by
  * `PoolSupervisor.effectiveMetastoreFor`.
  *
  * Every read is session-gated (Spec 00 closed the former ungated drift): tenant resolve ->
  * [[TenantScopeCheck]] -> tenant-db lookup, mirroring [[TagHandlers]]' gate. Unlike tags, a
  * non-DuckLake tenant-db is NOT rejected with `invalid_kind`: `kindOf` short-circuits the DuckLake
  * JDBC query for `duckdb-file` / `memory` kinds (those have no `ducklake_schema` metadata table to
  * query, so the reader would 500 with a Postgres "relation does not exist" error) and the browser
  * keeps its empty-result UX; admins can still browse via FlightSQL.
  *
  * When `auditReads` is on, each gated read emits one [[AuditActions.CatalogRead]] event (endpoint
  * + tenant + db); off by default because reads are chatty.
  */
final class CatalogHandlers(
    resolveReader: (String, String) => DuckLakeCatalogReader,
    sup: PoolSupervisor,
    store: ControlPlaneStore,
    kindOf: (String, String) => Option[TenantDbKind] = (_, _) => Some(TenantDbKind.DuckLake),
    resolveAsOfTag: (String, String, Option[Long], Option[String]) => Either[
      (StatusCode, String),
      Option[Long]
    ] = CatalogHandlers.asOfOnly,
    audit: AuditRecorder = AuditRecorder.noop,
    auditReads: Boolean = false
):

  private type Res[T] = Either[(StatusCode, ErrorResponse), T]

  private def resolveTenantId(raw: String): Option[String] =
    sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id)

  private def err(code: StatusCode, error: String, msg: String) =
    Left((code, ErrorResponse(error, msg)))

  private def isDuckLake(tenant: String, tenantDb: String): Boolean =
    kindOf(tenant, tenantDb).contains(TenantDbKind.DuckLake)

  /** Tenant resolve -> scope gate -> tenant-db lookup shared by the four reads. Left = ready-made
    * rejection; Right = (tenantId, tenantDbName).
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
              case Some(td) => Right((tid, td.name))

  /** Gate + selective read audit around `read`. The audit outcome tracks the gate, not the read: a
    * table-level 404 after an admitted gate is still an "ok" (authorized) read attempt.
    */
  private def gated[T](
      endpoint: String,
      rawTenant: String,
      tenantDb: String,
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope])(read: (String, String) => Res[T]): Res[T] =
    gate(rawTenant, tenantDb, apiKey)(scopeOf) match
      case Left(e) =>
        if auditReads then
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogRead,
            "denied",
            detail = Map("endpoint" -> endpoint)
          )
        Left(e)
      case Right((tid, db)) =>
        if auditReads then
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogRead,
            "ok",
            tenant = Some(tid),
            target = Some(db),
            detail = Map("endpoint" -> endpoint)
          )
        read(tid, db)

  def listSchemas(tenant: String, tenantDb: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Res[List[CatalogSchemaEntry]] =
    gated("schemas", tenant, tenantDb, apiKey)(scopeOf) { (tid, db) =>
      Right(listSchemasUnscoped(tid, db))
    }

  def listTables(tenant: String, tenantDb: String, schema: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Res[List[CatalogTableEntry]] =
    gated("tables", tenant, tenantDb, apiKey)(scopeOf) { (tid, db) =>
      Right(if !isDuckLake(tid, db) then Nil else resolveReader(tid, db).listTables(schema))
    }

  def listSnapshots(
      tenant: String,
      tenantDb: String,
      limit: Option[Int],
      before: Option[Long],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Res[List[CatalogSnapshotEntry]] =
    gated("snapshots", tenant, tenantDb, apiKey)(scopeOf) { (tid, db) =>
      Right(
        if !isDuckLake(tid, db) then Nil
        else
          val effectiveLimit = limit.getOrElse(200).max(1).min(1000)
          resolveReader(tid, db).listSnapshots(effectiveLimit, before)
      )
    }

  /** Spec 00: at most one of asOf / asOfTag / asOfTs; a tag resolves to its snapshot id, asOfTs
    * resolves via timestamp lookup. A dangling tag/id (snapshot vacuumed) returns 410 expired; a
    * timestamp before first snapshot returns 422 invalid_snapshot.
    */
  def getTable(
      tenant: String,
      tenantDb: String,
      schema: String,
      table: String,
      asOf: Option[Long],
      asOfTag: Option[String],
      asOfTs: Option[java.time.Instant],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Res[CatalogTableDetailResponse] =
    gated("table", tenant, tenantDb, apiKey)(scopeOf) { (tid, db) =>
      if !isDuckLake(tid, db) then
        val detail = resolveReader(tid, db).getTable(schema, table, None)
        detail match
          case Some(d) => Right(d)
          case None    =>
            err(
              StatusCode.NotFound,
              "not_found",
              s"table $schema.$table not found"
            )
      else
        val reader      = resolveReader(tid, db)
        val selectorRes = SnapshotSelector.resolve(
          asOf,
          asOfTag,
          asOfTs,
          maxId = () => reader.maxSnapshotId(),
          atOrBefore = (ts: java.time.Instant) => reader.snapshotAtOrBefore(ts),
          tagSnapshot = (tag: String) =>
            sup
              .findTenantDb(tid, tenantDb)
              .flatMap(td => store.findSnapshotTag(tid, td.name, tag).map(_.snapshotId)),
          exists = (id: Long) => reader.snapshotExists(id)
        )
        selectorRes match
          case Left(e) =>
            val (sc, errCode) = e match
              case SnapshotSelector.SelectorError.MultipleSelectors =>
                (StatusCode.BadRequest, "invalid_selector")
              case SnapshotSelector.SelectorError.TagNotFound(_) =>
                (StatusCode.NotFound, "not_found")
              case SnapshotSelector.SelectorError.BeforeFirstSnapshot =>
                (StatusCode.UnprocessableEntity, "invalid_snapshot")
              case SnapshotSelector.SelectorError.BeyondLatest(_) =>
                (StatusCode.UnprocessableEntity, "invalid_snapshot")
              case SnapshotSelector.SelectorError.Expired(_) =>
                (StatusCode.Gone, "snapshot_expired")
            val msg = e match
              case SnapshotSelector.SelectorError.MultipleSelectors =>
                "supply only one of asOf, asOfTag, or asOfTs"
              case SnapshotSelector.SelectorError.TagNotFound(tag) =>
                s"tag '$tag' not found"
              case SnapshotSelector.SelectorError.BeforeFirstSnapshot =>
                "timestamp is before the first snapshot"
              case SnapshotSelector.SelectorError.BeyondLatest(id) =>
                s"snapshot $id is beyond the latest snapshot"
              case SnapshotSelector.SelectorError.Expired(id) =>
                s"snapshot $id has been vacuumed"
            err(sc, errCode, msg)
          case Right(res) =>
            res match
              case SnapshotSelector.Resolution.Current =>
                val detail = reader.getTable(schema, table, None)
                detail match
                  case Some(d) => Right(d)
                  case None    =>
                    err(
                      StatusCode.NotFound,
                      "not_found",
                      s"table $schema.$table not found"
                    )
              case SnapshotSelector.Resolution.At(snapshotId, committedAt) =>
                val detail = reader.getTable(schema, table, Some(snapshotId))
                detail match
                  case Some(d) =>
                    Right(d.copy(resolvedSnapshot = Some(snapshotId), resolvedAt = committedAt))
                  case None =>
                    err(
                      StatusCode.NotFound,
                      "not_found",
                      s"table $schema.$table not found at snapshot $snapshotId"
                    )
    }

  /** Ungated schema listing for in-process composition ([[TenantDbHandlers]]' table counts). The
    * caller must already have scope-checked the tenant; never wire this to a route.
    */
  private[api] def listSchemasUnscoped(tenant: String, tenantDb: String): List[CatalogSchemaEntry] =
    if !isDuckLake(tenant, tenantDb) then Nil else resolveReader(tenant, tenantDb).listSchemas()

object CatalogHandlers:

  /** Fallback AS OF resolution when no tag handler is wired: a numeric asOf passes through, asOfTag
    * is rejected.
    */
  val asOfOnly: (String, String, Option[Long], Option[String]) => Either[
    (StatusCode, String),
    Option[Long]
  ] =
    (_, _, asOf, asOfTag) =>
      if asOfTag.isDefined then
        Left(
          (
            StatusCode.BadRequest,
            "asOfTag is not supported on this manager (no tag handler wired)"
          )
        )
      else Right(asOf)
