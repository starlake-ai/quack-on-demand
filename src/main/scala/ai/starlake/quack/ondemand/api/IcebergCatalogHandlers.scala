package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{FederatedAlias, FederatedSource, FederatedSourceType, PoolKey}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.iceberg.{
  IcebergCatalogSql,
  IcebergSnapshot,
  IcebergSnapshots,
  MetadataFailure,
  NodeMetadataQuery
}
import ai.starlake.quack.ondemand.catalog.iceberg.IcebergCatalogSql.SnapshotFilter
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import io.circe.Json
import sttp.model.StatusCode

import java.time.Instant
import scala.concurrent.duration.DurationInt

object IcebergCatalogHandlers:

  val FormatV1Message =
    "table uses Iceberg format v1 (no snapshot sequence numbers); these views need format v2 or later"

  val DefaultHistoryLimit = 50
  val MaxHistoryLimit     = 200

/** Read-only management views over an external Iceberg REST catalog attached to a tenant-db:
  * schema/table browsing, table detail (columns + files), snapshot history, preview and a row-level
  * data diff between two snapshots.
  *
  * Every method runs [[TenantDbGate]] (admin-only, no kind check: an Iceberg alias can hang off any
  * tenant-db kind), then resolves `alias` to an enabled `iceberg_rest` federated source of that
  * tenant-db (case-insensitive, 404 `catalog_not_found` otherwise) and uses the source's folded
  * alias in SQL. Metadata statements (browse, detail, history, snapshot-selector resolution) run
  * privileged through [[NodeMetadataQuery]]; the preview and diff DATA statements go through the
  * routed [[CatalogPreviewHandlers.PreviewExecutor]] as the caller, exactly like the DuckLake
  * preview, so the ACL applies to them: `callerOf` ([[RestCaller]]) turns the `apiKey` into the
  * [[ExecCaller]] (static key: system; session: its user; PAT: its owner with the token's
  * restriction and id; any other token: 401 `unauthorized` and the executor is never called).
  *
  * Audit: browse/detail/history emit [[AuditActions.CatalogRead]] only under
  * `cfg.auditCatalogReads` (outcome tracks the gate, like [[CatalogHandlers]]); preview and diff
  * are audited every time, like [[CatalogPreviewHandlers]].
  */
final class IcebergCatalogHandlers(
    sup: PoolSupervisor,
    sourcesOf: String => List[FederatedSource],
    meta: NodeMetadataQuery,
    executor: CatalogPreviewHandlers.PreviewExecutor,
    callerOf: RestCaller,
    cfg: CatalogConfig,
    audit: AuditRecorder = AuditRecorder.noop
):
  import IcebergCatalogHandlers.*

  private type Err    = (StatusCode, ErrorResponse)
  private type Out[T] = IO[Either[Err, T]]

  private def err(code: StatusCode, error: String, msg: String): Err =
    (code, ErrorResponse(error, msg))

  extension [A](io: IO[Either[Err, A]])
    private def andThen[B](f: A => IO[Either[Err, B]]): IO[Either[Err, B]] =
      io.flatMap {
        case Left(e)  => IO.pure(Left(e))
        case Right(a) => f(a)
      }

  /** A gated, resolved request: tenant id, tenant-db name and the source's folded alias. */
  private final case class Target(tid: String, db: String, alias: String)

  private def resolve(
      tenant: String,
      tenantDb: String,
      alias: String,
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Either[Err, Target] =
    TenantDbGate(sup, tenant, tenantDb, apiKey)(scopeOf).flatMap { (tid, db) =>
      val wanted = FederatedAlias.fold(alias)
      sup
        .findTenantDb(tid, db)
        .toList
        .flatMap(td => sourcesOf(td.id))
        .find(s =>
          s.sourceType == FederatedSourceType.IcebergRest && !s.disabled &&
            FederatedAlias.fold(s.alias) == wanted
        )
        .map(s => Target(tid, db, FederatedAlias.fold(s.alias)))
        .toRight(
          err(
            StatusCode.NotFound,
            "catalog_not_found",
            s"no enabled Iceberg catalog '$alias' on tenant-db '$db'"
          )
        )
    }

  private def metadataError(alias: String)(f: MetadataFailure): Err = f match
    case MetadataFailure.NoPool =>
      err(
        StatusCode.NotFound,
        "no_pool",
        "the tenant-db has no pool; Iceberg views need a pool with a live read node"
      )
    case MetadataFailure.NoRunningNode =>
      err(StatusCode.Conflict, "pool_unavailable", "the tenant-db's read pool has no running node")
    case MetadataFailure.NotAttached(detail) =>
      err(
        StatusCode.ServiceUnavailable,
        "catalog_unavailable",
        s"Iceberg catalog '$alias' is not attached on any running node" +
          detail.fold("")(d => s": $d")
      )
    case MetadataFailure.Remote(m) => err(StatusCode.BadGateway, "catalog_error", m)
    case MetadataFailure.TimedOut  =>
      err(StatusCode.BadGateway, "catalog_error", "metadata query timed out")

  private def parseError(e: IcebergSnapshots.ParseError): Err = e match
    case IcebergSnapshots.ParseError.FormatV1 =>
      err(StatusCode.BadRequest, "unsupported_for_iceberg", FormatV1Message)
    case IcebergSnapshots.ParseError.Malformed(m) =>
      err(StatusCode.BadGateway, "catalog_error", s"unexpected snapshot metadata: $m")

  private def runMeta(t: Target, sql: String): Out[List[List[Json]]] =
    meta.run(t.tid, t.db, t.alias, sql).map(_.left.map(metadataError(t.alias)))

  private def snapshots(
      t: Target,
      schema: String,
      table: String,
      filter: SnapshotFilter,
      limit: Int
  ): Out[List[IcebergSnapshot]] =
    runMeta(t, IcebergCatalogSql.snapshots(t.alias, schema, table, filter, limit))
      .map(_.flatMap(rows => IcebergSnapshots.parse(rows).left.map(parseError)))

  private def invalidSelector(msg: String): Err =
    err(StatusCode.BadRequest, "invalid_selector", msg)

  private def invalidSnapshot(msg: String): Err =
    err(StatusCode.UnprocessableEntity, "invalid_snapshot", msg)

  private def str(j: Json): String = j.asString.getOrElse(j.noSpaces)
  private def long(j: Json): Long  = j.asNumber.flatMap(_.toLong).getOrElse(0L)

  // ---- metadata reads -------------------------------------------------------------------------

  /** Gate + alias + selective [[AuditActions.CatalogRead]] audit around a metadata read. */
  private def metaRead[T](
      endpoint: String,
      tenant: String,
      tenantDb: String,
      alias: String,
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope])(read: Target => Out[T]): Out[T] =
    resolve(tenant, tenantDb, alias, apiKey)(scopeOf) match
      case Left(e) =>
        if cfg.auditCatalogReads then
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogRead,
            "denied",
            detail = Map("endpoint" -> endpoint)
          )
        IO.pure(Left(e))
      case Right(t) =>
        if cfg.auditCatalogReads then
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogRead,
            "ok",
            tenant = Some(t.tid),
            target = Some(s"${t.db}/${t.alias}"),
            detail = Map("endpoint" -> endpoint)
          )
        read(t)

  /** `tableCount = -1` (unknown): counting costs one metadata query per schema, and the UI loads
    * tables on expand anyway.
    */
  def schemas(tenant: String, tenantDb: String, alias: String, apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[List[CatalogSchemaEntry]] =
    metaRead("iceberg.schemas", tenant, tenantDb, alias, apiKey)(scopeOf) { t =>
      runMeta(t, IcebergCatalogSql.schemas(t.alias)).map(
        _.map(_.flatMap(_.headOption).map(j => CatalogSchemaEntry(str(j), -1)))
      )
    }

  def tables(
      tenant: String,
      tenantDb: String,
      alias: String,
      schema: String,
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[List[String]] =
    metaRead("iceberg.tables", tenant, tenantDb, alias, apiKey)(scopeOf) { t =>
      runMeta(t, IcebergCatalogSql.tables(t.alias, schema)).map(
        _.map(_.flatMap(_.headOption).map(str))
      )
    }

  def detail(
      tenant: String,
      tenantDb: String,
      alias: String,
      schema: String,
      table: String,
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[IcebergTableDetailResponse] =
    metaRead("iceberg.detail", tenant, tenantDb, alias, apiKey)(scopeOf) { t =>
      runMeta(t, IcebergCatalogSql.columns(t.alias, schema, table)).andThen { colRows =>
        runMeta(t, IcebergCatalogSql.files(t.alias, schema, table)).andThen { fileRows =>
          snapshots(t, schema, table, SnapshotFilter.Current, 1).map(_.map { snaps =>
            val columns = colRows.zipWithIndex.collect { case (name :: tpe :: nul :: _, i) =>
              CatalogColumnEntry(
                ordinal = i + 1,
                name = str(name),
                typeName = str(tpe),
                nullable = nul.asString.contains("YES"),
                isPrimaryKey = false
              )
            }
            val files = fileRows.collect { case path :: content :: fmt :: count :: seq :: _ =>
              IcebergFileEntry(str(path), str(content), str(fmt), long(count), long(seq))
            }
            // The row `current-snapshot-id` names, not the newest by sequence number: a
            // rollback or a staged/WAP snapshot on top can leave those different.
            val current = snaps.headOption.map(_.snapshotId)
            IcebergTableDetailResponse(t.alias, schema, table, columns, files, current)
          })
        }
      }
    }

  private def toEntry(s: IcebergSnapshot): IcebergSnapshotEntry =
    IcebergSnapshotEntry(
      snapshotId = s.snapshotId,
      parentId = s.parentId,
      sequenceNumber = s.sequence,
      committedAt = Instant.ofEpochMilli(s.timestampMs),
      operation = s.operation,
      addedRecords = s.count("added-records"),
      deletedRecords = s.count("deleted-records"),
      addedDataFiles = s.count("added-data-files"),
      deletedDataFiles = s.count("deleted-data-files"),
      addedPositionDeletes = s.count("added-position-deletes"),
      totalDataFiles = s.count("total-data-files"),
      current = s.current
    )

  def history(
      tenant: String,
      tenantDb: String,
      alias: String,
      schema: String,
      table: String,
      limit: Option[Int],
      before: Option[String],
      operation: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[IcebergHistoryResponse] =
    metaRead("iceberg.history", tenant, tenantDb, alias, apiKey)(scopeOf) { t =>
      val lim = limit.getOrElse(DefaultHistoryLimit).max(1).min(MaxHistoryLimit)
      if operation.exists(o => !IcebergSnapshots.Operations.contains(o)) then
        IO.pure(
          Left(
            invalidSelector(
              s"operation must be one of ${IcebergSnapshots.Operations.toList.sorted.mkString(", ")}"
            )
          )
        )
      else if before.exists(b => !IcebergSnapshots.validId(b)) then
        IO.pure(Left(invalidSelector(s"before '${before.get}' is not a snapshot id")))
      else
        val beforeSeq: Out[Option[Long]] = before match
          case None     => IO.pure(Right(None))
          case Some(id) =>
            snapshots(t, schema, table, SnapshotFilter.ById(List(id)), 1).map(_.flatMap {
              case s :: _ => Right(Some(s.sequence))
              case Nil    => Left(invalidSnapshot(s"snapshot $id not found"))
            })
        beforeSeq.andThen { seq =>
          snapshots(t, schema, table, SnapshotFilter.Page(seq, operation), lim + 1).map(
            _.map(page =>
              IcebergHistoryResponse(
                t.alias,
                schema,
                table,
                page.take(lim).map(toEntry),
                hasMore = page.size > lim
              )
            )
          )
        }
    }

  // ---- preview and diff (routed executor, always audited) -------------------------------------

  /** Runs `sqlFor(cap + 1)` through the routed executor as the caller (resolved by `callerOf`, 401
    * without calling the executor when the token does not resolve), decodes at most `cap` rows and
    * hands the decoded result to `build`. `cap` is the request `limit` clamped to
    * `cfg.previewMaxRows` and to the caller's own maxRows (a PAT lowers it, never raises it); one
    * row past it is fetched so the decoder can observe truncation. Failures: `AccessDenied` 403
    * `acl_denied`, anything else or a timeout 502 `preview_failed`. Audits `action` ok (with
    * `detail` + rowsReturned) or denied.
    */
  private def execute[T](
      t: Target,
      action: String,
      label: String,
      target: String,
      limit: Option[Int],
      sqlFor: Int => String,
      apiKey: Option[String],
      detail: Map[String, String]
  )(build: (List[PreviewColumn], List[List[Json]], Boolean) => Either[Err, (T, Int)]): Out[T] =
    def denied(e: Err): Either[Err, T] =
      audit.rest(
        apiKey,
        "control-plane",
        action,
        "denied",
        tenant = Some(t.tid),
        target = Some(target)
      )
      Left(e)
    def run(caller: ExecCaller, poolKey: PoolKey): Out[T] =
      // A PAT's own maxRows lowers the cap (never raises it), as on the DuckLake preview.
      val cap = caller.effectiveMaxRows(
        cfg.previewMaxRows,
        limit.map(_.max(1)).getOrElse(cfg.previewMaxRows)
      )
      executor(caller, poolKey, sqlFor(cap + 1))
        .timeout(cfg.previewTimeoutSec.seconds)
        .attempt
        .map {
          case Left(_: java.util.concurrent.TimeoutException) =>
            denied(err(StatusCode.BadGateway, "preview_failed", "query timed out"))
          case Left(other) =>
            val message =
              Option(other.getMessage).filter(_.nonEmpty).getOrElse("preview query failed")
            denied(err(StatusCode.BadGateway, "preview_failed", message))
          case Right(Left(RouterFailure.AccessDenied(reason))) =>
            denied(err(StatusCode.Forbidden, "acl_denied", reason))
          case Right(Left(failure)) =>
            denied(err(StatusCode.BadGateway, "preview_failed", failure.reason))
          case Right(Right(result)) =>
            try
              val (columns, rows, truncated) = ArrowRowsDecoder.decode(result.rows, cap)
              build(columns, rows, truncated) match
                case Left(e)             => denied(e)
                case Right((out, count)) =>
                  audit.rest(
                    apiKey,
                    "control-plane",
                    action,
                    "ok",
                    tenant = Some(t.tid),
                    target = Some(target),
                    detail = detail + ("rowsReturned" -> count.toString)
                  )
                  Right(out)
            finally result.close()
        }

    PoolPicks.readPoolKey(sup, t.tid, t.db) match
      case None =>
        IO.pure(
          denied(
            err(
              StatusCode.NotFound,
              "no_pool",
              s"tenant-db '${t.db}' has no running pool; this view needs at least one pool " +
                "with a live ReadOnly/Dual node"
            )
          )
        )
      case Some(poolKey) =>
        // 401 on an unresolvable token: the executor is never called, never as the superuser.
        callerOf(s"$label-${t.tid}-${t.db}", apiKey) match
          case Left(e)       => IO.pure(denied(e))
          case Right(caller) => run(caller, poolKey)

  def preview(
      tenant: String,
      tenantDb: String,
      alias: String,
      schema: String,
      table: String,
      asOf: Option[String],
      asOfTag: Option[String],
      asOfTs: Option[Instant],
      limit: Option[Int],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[IcebergPreviewResponse] =
    resolve(tenant, tenantDb, alias, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogPreviewRead, "denied")
        IO.pure(Left(e))
      case Right(t) =>
        val target                                    = s"${t.db}/${t.alias}.$schema.$table"
        def deny(e: Err): Out[IcebergPreviewResponse] =
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogPreviewRead,
            "denied",
            tenant = Some(t.tid),
            target = Some(target)
          )
          IO.pure(Left(e))
        if asOfTag.isDefined then
          deny(
            err(
              StatusCode.BadRequest,
              "unsupported_for_iceberg",
              "snapshot tags are not supported for Iceberg tables; select a snapshot by id or time"
            )
          )
        else if asOf.isDefined && asOfTs.isDefined then
          deny(invalidSelector("give at most one of asOf / asOfTs"))
        else if asOf.exists(a => !IcebergSnapshots.validId(a)) then
          deny(invalidSelector(s"asOf '${asOf.get}' is not a snapshot id"))
        else
          val selected: Out[Option[String]] = (asOf, asOfTs) match
            case (Some(id), _) =>
              snapshots(t, schema, table, SnapshotFilter.ById(List(id)), 1).map(_.flatMap {
                case s :: _ => Right(Some(s.snapshotId))
                case Nil    => Left(invalidSnapshot(s"snapshot $id not found"))
              })
            case (None, Some(ts)) =>
              snapshots(t, schema, table, SnapshotFilter.AtOrBefore(ts.toEpochMilli), 1).map(
                _.flatMap {
                  case s :: _ => Right(Some(s.snapshotId))
                  case Nil    => Left(invalidSnapshot(s"no snapshot at or before $ts"))
                }
              )
            case (None, None) => IO.pure(Right(None))
          selected.flatMap {
            case Left(e)           => deny(e)
            case Right(snapshotId) =>
              execute(
                t,
                AuditActions.CatalogPreviewRead,
                "iceberg-preview",
                target,
                limit,
                fetch => IcebergCatalogSql.preview(t.alias, schema, table, snapshotId, fetch),
                apiKey,
                Map.empty
              ) { (columns, rows, truncated) =>
                Right((IcebergPreviewResponse(columns, rows, snapshotId, truncated), rows.size))
              }
          }

  def dataDiff(
      tenant: String,
      tenantDb: String,
      alias: String,
      schema: String,
      table: String,
      from: String,
      to: String,
      limit: Option[Int],
      changeType: Option[String],
      apiKey: Option[String]
  )(scopeOf: String => Option[SessionScope]): Out[IcebergDiffResponse] =
    resolve(tenant, tenantDb, alias, apiKey)(scopeOf) match
      case Left(e) =>
        audit.rest(apiKey, "control-plane", AuditActions.CatalogDataDiffRead, "denied")
        IO.pure(Left(e))
      case Right(t) =>
        val target                                 = s"${t.db}/${t.alias}.$schema.$table"
        def deny(e: Err): Out[IcebergDiffResponse] =
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.CatalogDataDiffRead,
            "denied",
            tenant = Some(t.tid),
            target = Some(target)
          )
          IO.pure(Left(e))
        if !IcebergSnapshots.validId(from) then
          deny(invalidSelector(s"from '$from' is not a snapshot id"))
        else if !IcebergSnapshots.validId(to) then
          deny(invalidSelector(s"to '$to' is not a snapshot id"))
        else if changeType.exists(c => c != "added" && c != "removed") then
          deny(invalidSelector("changeType must be one of added, removed"))
        else
          snapshots(t, schema, table, SnapshotFilter.ById(List(from, to).distinct), 2).flatMap {
            case Left(e)     => deny(e)
            case Right(both) =>
              val found = both.map(_.snapshotId).toSet
              List(from, to).find(id => !found(id)) match
                case Some(missing) => deny(invalidSnapshot(s"snapshot $missing not found"))
                case None          =>
                  if IcebergSnapshots.tooLargeForDiff(both, cfg.icebergDiffMaxFiles) then
                    deny(
                      err(
                        StatusCode.PayloadTooLarge,
                        "diff_too_large",
                        s"a snapshot has more than ${cfg.icebergDiffMaxFiles} data files; the " +
                          "diff scans both versions in full (QOD_CATALOG_ICEBERG_DIFF_MAX_FILES)"
                      )
                    )
                  else
                    execute(
                      t,
                      AuditActions.CatalogDataDiffRead,
                      "iceberg-diff",
                      target,
                      limit,
                      fetch =>
                        IcebergCatalogSql.diff(t.alias, schema, table, from, to, changeType, fetch),
                      apiKey,
                      Map("from" -> from, "to" -> to)
                    ) { (columns, rows, truncated) =>
                      if !columns.headOption.exists(_.name == IcebergCatalogSql.ChangeColumn) then
                        Left(
                          err(
                            StatusCode.BadGateway,
                            "preview_failed",
                            "unexpected diff result shape"
                          )
                        )
                      else
                        val diffRows =
                          rows.map(r => IcebergDiffRow(r.head.asString.getOrElse(""), r.tail))
                        Right(
                          (
                            IcebergDiffResponse(
                              t.alias,
                              schema,
                              table,
                              from,
                              to,
                              columns.tail,
                              diffRows,
                              truncated
                            ),
                            diffRows.size
                          )
                        )
                    }
          }
