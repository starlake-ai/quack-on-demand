package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{PoolKey, SearchIndex, TenantDb}
import ai.starlake.quack.ondemand.auth.{SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.search.{
  BuildTarget,
  DeleteTarget,
  ListedIndex,
  SearchError,
  SearchIndexService
}
import ai.starlake.quack.ondemand.state.SearchIndexStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import io.circe.Json
import sttp.model.StatusCode

/** The QoD Search index registry's REST surface (spec 6.1). `apiKeyGuard` admits a session or a PAT
  * (a non-admin's profile session reads `403 admin_required` there); every handler then runs the
  * tenant scope on the body's tenant (`403 tenant_forbidden` across tenants, superusers pass)
  * before the service sees the request, and audits the outcome. A database another tenant holds
  * answers as one that does not exist (the service looks inside the caller's tenant only).
  *
  * `bundleRoot` is shown to superusers only (`HandlerResolvers.metastoreFor`'s rule: no data path
  * QoD placed reaches a tenant).
  *
  * `build` (OWNER RULING 2026-11-16, D7) is the Phase 0 prototype's build ported onto the registry,
  * superuser only, until Phase 2's indexer replaces it: `qod_search_build_segment` runs only for
  * the system principal, so the handler runs fixed statements as [[ExecCaller.system]] through the
  * routed executor, and records one `qodstate_search_segment` row per bundle. Of the request only
  * the table name reaches the SQL, after [[SearchIndexService.NameRe]]; the columns are the index's
  * fields under their current names, read from the catalog by field id; every identifier is
  * double-quoted and every path a literal from the catalog's own listing, `'` doubled. The
  * statements, in order:
  *   1. `CALL ducklake_flush_inlined_data(<alias>, schema_name => <schema>, table_name =>
  *      <table>)`, so that no live row is inlined;
  *   1. the live data files, `ducklake_list_files`;
  *   1. per file, from the table's own scan at the current snapshot: the offset `rowid -
  *      file_row_number` (min and max) and the live rows' count and first and last positions;
  *   1. per live file, its Parquet column names and row count;
  *   1. per live file, `qod_search_build_segment` over the table's own scan of that file, which
  *      reads the index's record (Task 7's record route) for its fields, bundle root and key.
  *
  * Every file is checked before any bundle is written: a file with no live row is skipped; a
  * non-affine file without `_ducklake_internal_row_id` is `409 search_build_nonaffine`; a file with
  * a deleted row is `409 search_build_deleted_rows`, since the scan skips deleted rows and its
  * positions would not be the file's.
  */
final class SearchHandlers(
    service: SearchIndexService,
    store: SearchIndexStore,
    /** The pool `key` exists (the build's target). */
    poolExists: PoolKey => Boolean,
    /** The catalog alias a tenant-db's nodes attach it under (`TenantDb.catalogAlias` of its
      * effective metastore).
      */
    aliasOf: TenantDb => String,
    executor: CatalogPreviewHandlers.PreviewExecutor,
    audit: AuditRecorder,
    /** Resolves the credential to its principal, whose token restriction applies (security I1). */
    callerOf: RestCaller,
    /** A token's databases axis on tenant `tenant`'s database `db` (`TokenScope.databaseRefusal`, a
      * branch judged as its parent): `403 token_scope`.
      */
    databaseRefusal: (String, TokenRestriction, String) => Option[(StatusCode, ErrorResponse)],
    nowNs: () => Long = () => System.nanoTime()
):
  import SearchHandlers.*

  private type Answer[A] = Either[(StatusCode, ErrorResponse), A]

  private def answer(e: SearchError): (StatusCode, ErrorResponse) =
    e.status -> ErrorResponse(e.code, e.message)

  private def record(
      apiKey: Option[String],
      action: String,
      outcome: String,
      tenant: String,
      target: String,
      detail: Map[String, String] = Map.empty
  ): IO[Unit] =
    IO.blocking(
      audit.rest(
        apiKey,
        "control-plane",
        action,
        outcome,
        tenant = Some(tenant),
        target = Some(target),
        detail = detail
      )
    )

  private def outcomeOf(status: StatusCode): String =
    if status == StatusCode.Forbidden then "denied" else "error"

  /** The caller's token restriction (a session's is unrestricted); `401 unauthorized` for a
    * credential that stopped resolving after the guard.
    */
  private def restrictionOf(apiKey: Option[String]): Answer[TokenRestriction] =
    callerOf.resolve(apiKey).map(p => RestCaller.callerOf("search-index", p).restriction)

  /** The tenant scope, then the token's databases axis on `database` (security I1). */
  private def admit(
      scope: SessionScope,
      apiKey: Option[String],
      tenant: String,
      database: String
  ): Answer[TokenRestriction] =
    TenantScopeCheck.forScope(scope, tenant).toLeft(()).flatMap { _ =>
      restrictionOf(apiKey).flatMap(r => databaseRefusal(tenant, r, database).toLeft(r))
    }

  def create(req: SearchIndexCreateRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Answer[SearchIndexResponse]] =
    val scope  = scopeOf(apiKey)
    val target = s"${req.database}/${req.table}"
    admit(scope, apiKey, req.tenant, req.database) match
      case Left((status, err)) =>
        record(apiKey, AuditActions.SearchIndexCreate, outcomeOf(status), req.tenant, target)
          .as(Left(status -> err))
      case Right(_) =>
        val createdBy = audit.actorOf(apiKey)._1
        service
          .create(createdBy, req.tenant, req.database, req.table, req.columns, req.namesOptions)
          .flatMap {
            case Left(e) =>
              record(
                apiKey,
                AuditActions.SearchIndexCreate,
                outcomeOf(e.status),
                req.tenant,
                target,
                Map("reason" -> e.code)
              ).as(Left(answer(e)))
            case Right(ix) =>
              record(
                apiKey,
                AuditActions.SearchIndexCreate,
                "ok",
                req.tenant,
                target,
                Map(
                  "indexId" -> ix.indexId.toString,
                  "columns" -> ix.fields.map(_.column).mkString(",")
                )
              ).as(
                Right(
                  responseOf(
                    ix,
                    req.tenant,
                    req.database.toLowerCase(java.util.Locale.ROOT),
                    None,
                    scope
                  )
                )
              )
          }

  /** A named database outside the token's databases axis is `403 token_scope`; with none named, the
    * databases the token does not allow are left out.
    */
  def list(req: SearchIndexListRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Answer[SearchIndexListResponse]] =
    val scope    = scopeOf(apiKey)
    val database = req.database.map(_.trim).filter(_.nonEmpty)
    val admitted = database match
      case Some(d) => admit(scope, apiKey, req.tenant, d)
      case None    =>
        TenantScopeCheck.forScope(scope, req.tenant).toLeft(()).flatMap(_ => restrictionOf(apiKey))
    admitted match
      case Left(refusal) => IO.pure(Left(refusal))
      case Right(r)      =>
        service
          .list(req.tenant, database, td => databaseRefusal(req.tenant, r, td.name).isEmpty)
          .map {
            case Left(e)   => Left(answer(e))
            case Right(xs) => Right(SearchIndexListResponse(xs.map(listedOf(_, scope))))
          }

  /** The index by its table (`table`) or by its id (`indexId`), exactly one of them. */
  def delete(req: SearchIndexDeleteRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Answer[SearchIndexDeleteResponse]] =
    val scope                        = scopeOf(apiKey)
    val named                        = req.table.map(_.trim).filter(_.nonEmpty)
    val byId                         = req.indexId.map(_.trim).filter(_.nonEmpty)
    val target                       = s"${req.database}/${named.orElse(byId).getOrElse("")}"
    val chosen: Answer[DeleteTarget] = (named, byId) match
      case (Some(t), None)  => Right(DeleteTarget.ByTable(t))
      case (None, Some(id)) => Right(DeleteTarget.ByIndexId(id))
      case _                =>
        Left(
          StatusCode.BadRequest -> ErrorResponse(
            "invalid_search_target",
            "name the index by its table or by its indexId, exactly one of them"
          )
        )
    admit(scope, apiKey, req.tenant, req.database).flatMap(_ => chosen) match
      case Left((status, err)) =>
        record(apiKey, AuditActions.SearchIndexDelete, outcomeOf(status), req.tenant, target)
          .as(Left(status -> err))
      case Right(how) =>
        service.delete(req.tenant, req.database, how).flatMap {
          case Left(e) =>
            record(
              apiKey,
              AuditActions.SearchIndexDelete,
              outcomeOf(e.status),
              req.tenant,
              target,
              Map("reason" -> e.code)
            ).as(Left(answer(e)))
          case Right(ix) =>
            record(
              apiKey,
              AuditActions.SearchIndexDelete,
              "ok",
              req.tenant,
              target,
              Map("indexId" -> ix.indexId.toString)
            ).as(Right(SearchIndexDeleteResponse(deleted = true)))
        }

  /** Superuser only (D7): anyone else gets `403 superuser_required` and no statement runs. A
    * superuser passes every tenant scope, so no tenant check follows; the token's databases and
    * pools axes still apply (`403 token_scope`).
    */
  def build(req: SearchIndexBuildRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Answer[SearchBuildResponse]] =
    val scope     = scopeOf(apiKey)
    val requester = audit.actorOf(apiKey)._1
    def recordBuild(outcome: String, files: Int, reason: Option[String]): IO[Unit] =
      // The statements run as the system principal: the row's actor is `system`, the requester in
      // `requestedBy`.
      IO.blocking(
        audit.restAs(
          actor = "system",
          actorRealm = "system",
          family = "control-plane",
          action = AuditActions.SearchIndexBuild,
          outcome = outcome,
          tenant = Some(req.tenant),
          target = Some(s"${req.database}/${req.pool}"),
          detail = Map(
            "table"       -> req.table,
            "requestedBy" -> requester,
            "files"       -> files.toString
          ) ++ reason.map("reason" -> _)
        )
      )
    val gate: Answer[Unit] =
      SuperuserCheck
        .forScope(scope, "building search bundles requires a superuser session")
        .toLeft(())
        .flatMap(_ => restrictionOf(apiKey))
        .flatMap { r =>
          databaseRefusal(req.tenant, r, req.database).toLeft(()).flatMap { _ =>
            Either.cond(
              r.allowsPool(req.pool),
              (),
              StatusCode.Forbidden -> ErrorResponse(
                "token_scope",
                s"pool '${req.pool}' is not permitted for this token"
              )
            )
          }
        }
    gate match
      case Left((status, err)) =>
        recordBuild(outcomeOf(status), 0, Some(err.error)).as(Left(status -> err))
      case Right(_) =>
        service.buildTarget(req.tenant, req.database, req.table).flatMap {
          case Left(e) =>
            recordBuild(outcomeOf(e.status), 0, Some(e.code)).as(Left(answer(e)))
          case Right(t) =>
            val key = PoolKey(t.tenantDb.tenantId, t.tenantDb.name, req.pool)
            if !poolExists(key) then
              val err =
                StatusCode.NotFound -> ErrorResponse("pool_not_found", s"no pool ${req.pool}")
              recordBuild("error", 0, Some("pool_not_found")).as(Left(err))
            else
              run(key, t).flatMap {
                case Right(resp)         => recordBuild("ok", resp.files.size, None).as(Right(resp))
                case Left((status, err)) =>
                  recordBuild(outcomeOf(status), 0, Some(s"${err.error}: ${err.message}"))
                    .as(Left(status -> err))
              }
        }

  private def run(key: PoolKey, t: BuildTarget): IO[Answer[SearchBuildResponse]] =
    // Its statements ask for the index's record (qod_search_build_segment's bind) as the system
    // principal: the call site flags them so the record route serves their handles.
    val caller = ExecCaller.systemSearch(connectionIdOf(key), Identity)
    def rows(sql: String): IO[Answer[List[List[Json]]]] =
      executor(caller, key, sql).attempt.flatMap {
        case Left(e)                                    => IO.pure(Left(failed(messageOf(e))))
        case Right(Left(RouterFailure.AccessDenied(r))) =>
          IO.pure(Left(StatusCode.Forbidden -> ErrorResponse("search_build_denied", r)))
        case Right(Left(f))       => IO.pure(Left(failed(f.reason)))
        case Right(Right(result)) =>
          IO.blocking(ArrowRowsDecoder.decode(result.rows, MaxRows)._2)
            .attempt
            .guarantee(IO(result.close()).handleError(_ => ()))
            .map(_.left.map(e => failed(messageOf(e))))
      }
    def each[A, B](as: List[A])(f: A => IO[Answer[B]]): IO[Answer[List[B]]] =
      as.foldLeft(IO.pure[Answer[List[B]]](Right(Nil))) { (acc, a) =>
        acc.flatMap {
          case Left(e)   => IO.pure(Left(e))
          case Right(bs) => f(a).map(_.map(b => bs :+ b))
        }
      }
    if !validName(t.schema) || !validName(t.table) then
      IO.pure(
        Left(
          StatusCode.Conflict -> ErrorResponse(
            "invalid_name",
            "the table's current schema and name must be plain identifiers to be built"
          )
        )
      )
    else
      val alias = aliasOf(t.tenantDb)
      val n     = Names(alias, t.schema, t.table)
      (for
        _      <- EitherIO(rows(flushSql(n)))
        listed <- EitherIO(rows(listSql(n)))
        live   <- EitherIO(rows(liveSql(n)))
        files  = listed.map(r => ListedFile(str(r(0)), num(r(1)), num(r(2))))
        byName = live
          .map(r => str(r(0)) -> LiveRows(num(r(1)), num(r(2)), num(r(3)), num(r(4)), num(r(5))))
          .toMap
        (withRows, skipped) = files.partition(f => byName.contains(f.path))
        plans <- EitherIO(each(withRows) { f =>
          rows(schemaSql(f.path)).map(_.flatMap(schema => plan(t.table, f, byName(f.path), schema)))
        })
        built <- EitherIO(each(plans) { p =>
          val started = nowNs()
          rows(buildSql(n, t.columns, p)).flatMap {
            case Right(List(bundle, docs, bytes) :: Nil) =>
              val file = SearchBuiltFile(
                p.file.path,
                str(bundle),
                num(docs),
                num(bytes),
                (nowNs() - started) / 1000000L
              )
              store
                .recordSegment(
                  t.index.indexId,
                  pathKeyOf(p.file.path),
                  p.file.path,
                  file.bytes,
                  file.docs,
                  file.ms,
                  SearchIndex.BundleFormatVersion
                )
                .attempt
                .map(_.left.map(e => failed(s"the segment row was not written: ${messageOf(e)}")))
                .map(_.map(_ => file))
            case Right(other) =>
              IO.pure(
                Left(failed(s"the build of a data file of ${t.table} answered ${other.size} rows"))
              )
            case Left(e) => IO.pure(Left(e))
          }
        })
      // `flushed` is always true on success: the flush runs first and its failure answers an error.
      yield SearchBuildResponse(flushed = true, built, skipped.map(_.path))).value

  private def listedOf(l: ListedIndex, scope: SessionScope): SearchIndexResponse =
    responseOf(l.index, l.tenantDb.tenantId, l.tenantDb.name, l.current, scope)

object SearchHandlers:

  /** The response of an index; `bundleRoot` for a superuser only. */
  def responseOf(
      ix: SearchIndex,
      tenant: String,
      database: String,
      current: Option[(String, String)],
      scope: SessionScope
  ): SearchIndexResponse =
    SearchIndexResponse(
      indexId = ix.indexId.toString,
      tenant = tenant,
      database = database,
      schema = ix.schemaName,
      table = ix.tableName,
      tableId = ix.tableId,
      fields = ix.fields.map(f => SearchIndexFieldDto(f.column, f.fieldId)),
      bundleRoot = Option.when(scope.superuser)(ix.bundleRoot),
      state = ix.state,
      segmentsVersion = ix.segmentsVersion,
      createdBy = ix.createdBy,
      createdAt = ix.createdAt.toString,
      currentSchema = current.map(_._1),
      currentTable = current.map(_._2)
    )

  /** The label of the build's statements in logs and statement history; it decides nothing. */
  val Identity = "search-index-build"

  def connectionIdOf(key: PoolKey): String =
    s"search-build-${key.tenant}-${key.tenantDb}-${key.pool}"

  def validName(s: String): Boolean = SearchIndexService.validName(s)

  private val MaxRows = 1000000

  /** The node's key of a data file's bundle: the lowercase hex SHA-256 of its path. */
  def pathKeyOf(path: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** A string literal, `'` doubled. */
  def lit(s: String): String = "'" + s.replace("'", "''") + "'"

  /** A quoted identifier, `"` doubled. */
  def ident(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

  /** The catalog alias, the schema and the table a build names. */
  final case class Names(alias: String, schema: String, table: String):
    def qualified: String = s"${ident(alias)}.${ident(schema)}.${ident(table)}"

  def flushSql(n: Names): String =
    s"CALL ducklake_flush_inlined_data(${lit(n.alias)}, schema_name => ${lit(n.schema)}, " +
      s"table_name => ${lit(n.table)})"

  def listSql(n: Names): String =
    "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM " +
      s"ducklake_list_files(${lit(n.alias)}, ${lit(n.table)}, schema => ${lit(n.schema)})"

  def liveSql(n: Names): String =
    "SELECT filename, min(rowid - file_row_number), max(rowid - file_row_number), count(*), " +
      s"min(file_row_number), max(file_row_number) FROM ${n.qualified} GROUP BY filename"

  def schemaSql(path: String): String =
    s"SELECT s.name, m.num_rows FROM parquet_schema(${lit(path)}) s, " +
      s"parquet_file_metadata(${lit(path)}) m"

  final case class ListedFile(path: String, size: Long, footer: Long)
  final case class LiveRows(
      minOffset: Long,
      maxOffset: Long,
      count: Long,
      firstPos: Long,
      lastPos: Long
  )
  final case class Plan(file: ListedFile, snapshots: Boolean)

  /** Checks one live file against its Parquet schema and row count. */
  def plan(
      table: String,
      f: ListedFile,
      live: LiveRows,
      schema: List[List[Json]]
  ): Either[(StatusCode, ErrorResponse), Plan] =
    val names   = schema.map(r => str(r.head)).toSet
    val numRows = schema.headOption.map(r => num(r(1))).getOrElse(-1L)
    if live.minOffset != live.maxOffset && !names.contains("_ducklake_internal_row_id") then
      Left(
        StatusCode.Conflict -> ErrorResponse(
          "search_build_nonaffine",
          s"a data file of $table has rowids that are not its positions plus one offset and no " +
            "_ducklake_internal_row_id column"
        )
      )
    else if live.count != numRows || live.firstPos != 0 || live.lastPos != numRows - 1 then
      Left(
        StatusCode.Conflict -> ErrorResponse(
          "search_build_deleted_rows",
          s"a data file of $table holds deleted rows (${numRows - live.count} of $numRows); the " +
            "interim build covers only files whose rows are all live"
        )
      )
    else Right(Plan(f, names.contains("_ducklake_internal_snapshot_id")))

  /** The build of one file: the table's own scan of it, which projects each column by its DuckLake
    * field id and gives each row its true `rowid` and `snapshot_id`, the index's fields in index
    * order under their current names. Never `read_parquet` of the file by column name: after a
    * rename that would index whichever field held the name in the file.
    */
  def buildSql(n: Names, columns: List[String], p: Plan): String =
    val snapshot = if p.snapshots then "snapshot_id" else "-1::BIGINT"
    val texts    = columns.zipWithIndex.map((c, i) => s"${ident(c)} AS ${ident(s"t$i")}")
    s"SELECT path, docs, bytes FROM qod_search_build_segment((SELECT file_row_number AS pos, " +
      s"rowid AS row_id, $snapshot AS snapshot_id, ${texts.mkString(", ")} FROM ${n.qualified} " +
      s"WHERE filename = ${lit(p.file.path)} ORDER BY pos), " +
      s"${lit(s"${n.alias}.${n.schema}.${n.table}")}, ${lit(p.file.path)}, " +
      s"${p.file.size}::UBIGINT, ${p.file.footer}::UBIGINT)"

  private def failed(reason: String): (StatusCode, ErrorResponse) =
    StatusCode.BadGateway -> ErrorResponse("search_build_failed", reason)

  private def messageOf(t: Throwable): String =
    Option(t.getMessage).getOrElse(t.getClass.getSimpleName)

  private def str(j: Json): String = j.asString.getOrElse(j.noSpaces)

  private def num(j: Json): Long =
    j.asNumber.flatMap(_.toLong).orElse(j.asString.flatMap(_.toLongOption)).getOrElse(-1L)

  /** A small EitherT over IO, so the steps read in order and stop at the first refusal. */
  private final case class EitherIO[A](value: IO[Either[(StatusCode, ErrorResponse), A]]):
    def flatMap[B](f: A => EitherIO[B]): EitherIO[B] =
      EitherIO(value.flatMap {
        case Left(e)  => IO.pure(Left(e))
        case Right(a) => f(a).value
      })
    def map[B](f: A => B): EitherIO[B] = EitherIO(value.map(_.map(f)))
