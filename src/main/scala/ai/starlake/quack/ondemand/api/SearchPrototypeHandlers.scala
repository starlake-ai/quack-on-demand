package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{PoolKey, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import io.circe.Json
import sttp.model.StatusCode

import scala.util.control.NonFatal

/** `POST /api/search/prototype/build-segments` (search Phase 0 prototype only, plan Task 12; Phase
  * 2's indexer replaces it). `qod_search_build_segment` runs only for the system principal, which
  * only the manager is, so a superuser asks the manager to run fixed statements as
  * [[ExecCaller.system]] through the routed executor, the way `ModuleSystemSql` runs a module's.
  *
  * Superuser only: anyone else gets `403 superuser_required` and no statement runs. A database
  * encrypted at rest is `400 search_encrypted_unsupported`, since a bundle is plaintext until Phase
  * 2 seals it (spec 6.2). The handler builds every statement itself. Of the request, only `table`
  * and `column` reach the SQL, after [[SearchPrototypeHandlers.NameRe]], double-quoted where they
  * name an identifier and as string literals where a function takes a name; the catalog alias is
  * the tenant-db's own (`TenantDb.catalogAlias`); every path is a literal taken from the catalog's
  * own listing, `'` doubled. The statements, in order:
  *   1. `CALL ducklake_flush_inlined_data(<alias>, schema_name => 'main', table_name => <table>)`,
  *      so that no live row is inlined;
  *   1. the live data files, `ducklake_list_files`;
  *   1. per file, from the table's own scan at the current snapshot: the offset `rowid -
  *      file_row_number` (min and max: equal for an affine file) and the live rows' count and first
  *      and last positions;
  *   1. per live file, its Parquet column names and row count;
  *   1. per live file, `qod_search_build_segment` over the table's own scan of that file (`WHERE
  *      filename = <path>`), which projects the column by its DuckLake field id and gives each row
  *      its true `rowid` and `snapshot_id`. Never `read_parquet` of the file by column name: after
  *      a rename that would index whichever field held the name in the file (Task 11).
  *
  * Every file is checked before any bundle is written. A file with no live row needs no bundle and
  * is skipped. A non-affine file without `_ducklake_internal_row_id` (a catalog the prototype does
  * not understand) is `409 search_build_nonaffine`. A file with a deleted row is `409
  * search_build_deleted_rows`: the table scan skips deleted rows, so its positions would not be the
  * file's, which the builder refuses (`search_build_out_of_order`); the prototype's tables are
  * written once. Each call writes one `search.build` audit row: actor `system` (the statements run
  * as the system principal), the requester in `requestedBy`, target `<tenantDb>/<pool>`.
  */
final class SearchPrototypeHandlers(
    resolve: PoolKey => Either[(StatusCode, ErrorResponse), SearchPrototypeHandlers.Target],
    executor: CatalogPreviewHandlers.PreviewExecutor,
    audit: AuditRecorder,
    nowNs: () => Long = () => System.nanoTime()
):
  import SearchPrototypeHandlers.*

  private type Answer[A] = Either[(StatusCode, ErrorResponse), A]

  def buildSegments(req: SearchBuildRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): IO[Answer[SearchBuildResponse]] =
    val key       = PoolKey(req.tenant, req.database, req.pool)
    val requester = audit.actorOf(apiKey)._1
    def record(outcome: String, files: Int, reason: Option[String]): IO[Unit] =
      IO.blocking(
        audit.restAs(
          actor = "system",
          actorRealm = "system",
          family = "control-plane",
          action = AuditActions.SearchBuild,
          outcome = outcome,
          tenant = Some(req.tenant),
          target = Some(s"${req.database}/${req.pool}"),
          detail = Map(
            "table"       -> req.table,
            "column"      -> req.column,
            "requestedBy" -> requester,
            "files"       -> files.toString
          ) ++ reason.map("reason" -> _)
        )
      )
    val gate: Answer[Target] =
      SuperuserCheck.reject(apiKey)(scopeOf) match
        case Some(refusal) => Left(refusal)
        case None          =>
          TenantScopeCheck.reject(apiKey, req.tenant)(scopeOf) match
            case Some(refusal) => Left(refusal)
            case None          =>
              if !validName(req.table) || !validName(req.column) then
                Left(
                  StatusCode.BadRequest -> ErrorResponse(
                    "invalid_name",
                    "table and column must be plain identifiers ([A-Za-z_][A-Za-z0-9_]*)"
                  )
                )
              else resolve(key)
    gate match
      case Left((status, err)) =>
        val outcome = if status == StatusCode.Forbidden then "denied" else "error"
        record(outcome, 0, Some(err.error)).as(Left(status -> err))
      case Right(target) =>
        run(key, target, req).flatMap {
          case Right(resp)         => record("ok", resp.files.size, None).as(Right(resp))
          case Left((status, err)) =>
            record("error", 0, Some(s"${err.error}: ${err.message}")).as(Left(status -> err))
        }

  private def run(
      key: PoolKey,
      t: Target,
      req: SearchBuildRequest
  ): IO[Answer[SearchBuildResponse]] =
    val caller = ExecCaller.system(connectionIdOf(key), Identity)
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
    val alias = t.catalogAlias
    (for
      _      <- EitherIO(rows(flushSql(alias, req.table)))
      listed <- EitherIO(rows(listSql(alias, req.table)))
      live   <- EitherIO(rows(liveSql(alias, req.table)))
      files  = listed.map(r => ListedFile(str(r(0)), num(r(1)), num(r(2))))
      byName = live
        .map(r => str(r(0)) -> LiveRows(num(r(1)), num(r(2)), num(r(3)), num(r(4)), num(r(5))))
        .toMap
      (withRows, skipped) = files.partition(f => byName.contains(f.path))
      plans <- EitherIO(each(withRows) { f =>
        rows(schemaSql(f.path)).map(_.flatMap(schema => plan(req.table, f, byName(f.path), schema)))
      })
      built <- EitherIO(each(plans) { p =>
        val started = nowNs()
        rows(buildSql(alias, req.table, req.column, t.bundleRoot, p)).map(_.flatMap {
          case List(bundle, docs, bytes) :: Nil =>
            Right(
              SearchBuiltFile(
                p.file.path,
                str(bundle),
                num(docs),
                num(bytes),
                (nowNs() - started) / 1000000L
              )
            )
          case other =>
            Left(failed(s"the build of a data file of ${req.table} answered ${other.size} rows"))
        })
      })
    yield SearchBuildResponse(flushed = true, built, skipped.map(_.path))).value

object SearchPrototypeHandlers:

  /** What the handler needs of a pool: its tenant-db's catalog alias and the bundle root the
    * decider's verdicts name (the prototype's index record, search spec 6.1).
    */
  final case class Target(catalogAlias: String, bundleRoot: String)

  /** The label of the build's statements in logs and statement history; it decides nothing. */
  val Identity = "search-prototype-build"

  def connectionIdOf(key: PoolKey): String =
    s"search-build-${key.tenant}-${key.tenantDb}-${key.pool}"

  val NameRe = "[A-Za-z_][A-Za-z0-9_]*".r

  def validName(s: String): Boolean = NameRe.matches(s)

  private val MaxRows = 1000000

  /** A string literal, `'` doubled. */
  def lit(s: String): String = "'" + s.replace("'", "''") + "'"

  /** A quoted identifier, `"` doubled. */
  def ident(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

  def flushSql(alias: String, table: String): String =
    s"CALL ducklake_flush_inlined_data(${lit(alias)}, schema_name => 'main', table_name => ${lit(table)})"

  def listSql(alias: String, table: String): String =
    "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM " +
      s"ducklake_list_files(${lit(alias)}, ${lit(table)}, schema => 'main')"

  def liveSql(alias: String, table: String): String =
    "SELECT filename, min(rowid - file_row_number), max(rowid - file_row_number), count(*), " +
      s"min(file_row_number), max(file_row_number) FROM ${ident(alias)}.\"main\".${ident(table)} " +
      "GROUP BY filename"

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
            "prototype builds only files whose rows are all live"
        )
      )
    else Right(Plan(f, names.contains("_ducklake_internal_snapshot_id")))

  def buildSql(alias: String, table: String, column: String, bundleRoot: String, p: Plan): String =
    val snapshot = if p.snapshots then "snapshot_id" else "-1::BIGINT"
    s"SELECT path, docs, bytes FROM qod_search_build_segment((SELECT file_row_number AS pos, " +
      s"rowid AS row_id, $snapshot AS snapshot_id, ${ident(column)} AS text FROM " +
      s"${ident(alias)}.\"main\".${ident(table)} WHERE filename = ${lit(p.file.path)} ORDER BY pos), " +
      s"${lit(s"$alias.main.$table")}, ${lit(column)}, ${lit(bundleRoot)}, ${lit(p.file.path)}, " +
      s"${p.file.size}::UBIGINT, ${p.file.footer}::UBIGINT)"

  /** The prototype's resolution of a pool: a DuckLake tenant-db, the pool on it, its catalog alias
    * as the nodes attach it, and `bundleRootOf` (the resolver the decider uses).
    */
  def resolverOf(
      sup: PoolSupervisor,
      bundleRootOf: PoolKey => Option[String]
  ): PoolKey => Either[(StatusCode, ErrorResponse), Target] =
    key =>
      sup.findTenantDb(key.tenant, key.tenantDb) match
        case None =>
          Left(
            StatusCode.NotFound -> ErrorResponse(
              "tenant_db_not_found",
              s"no database ${key.tenantDb}"
            )
          )
        case Some(td) if td.kind != TenantDbKind.DuckLake =>
          Left(
            StatusCode.BadRequest -> ErrorResponse(
              "invalid_kind",
              "search needs a DuckLake database"
            )
          )
        case Some(td) if td.encrypted =>
          // A bundle holds the indexed text in the clear until Phase 2 seals bundles under the
          // HMAC subkey (search spec 6.2), so the prototype never writes one for a database
          // encrypted at rest (owner ruling 2026-11-16, Task 16).
          Left(
            StatusCode.BadRequest -> ErrorResponse(
              "search_encrypted_unsupported",
              "the search prototype does not build bundles for a database encrypted at rest"
            )
          )
        case Some(td) =>
          if sup.snapshot(key).isEmpty then
            Left(StatusCode.NotFound -> ErrorResponse("pool_not_found", s"no pool ${key.pool}"))
          else
            bundleRootOf(key) match
              case None =>
                Left(
                  StatusCode.Conflict -> ErrorResponse(
                    "search_index_unavailable",
                    "the database has no data path to hold the bundles"
                  )
                )
              case Some(root) =>
                Right(
                  Target(
                    TenantDb.catalogAlias(sup.effectiveMetastoreFor(key.tenant, td.name), td.name),
                    root
                  )
                )

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
