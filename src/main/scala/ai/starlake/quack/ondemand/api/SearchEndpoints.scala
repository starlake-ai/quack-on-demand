package ai.starlake.quack.ondemand.api

import Dtos.given
import Endpoints.authToken
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.{deriveCodec, deriveDecoder, deriveEncoder}
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** `POST /api/search/index/create`. `table` is `<table>` or `<schema>.<table>`; the schema defaults
  * to the database's session path. k1 and b are fixed through Phase 1 (OWNER RULING 2026-11-16,
  * D10), so the request has no option: the decoder reads `options`, `k1` and `b` on purpose
  * (`namesOptions`), so that a body naming any of them is refused (`400 invalid_search_options`),
  * not ignored. `namesOptions` is never on the wire nor in the API's schema.
  */
final case class SearchIndexCreateRequest(
    tenant: String,
    database: String,
    table: String,
    columns: List[String],
    namesOptions: Boolean = false
)

object SearchIndexCreateRequest:

  /** The request as the API documents it. */
  final case class Wire(tenant: String, database: String, table: String, columns: List[String])

  /** Keys a body may not hold: no option exists in this release (D10). */
  val RefusedKeys: Set[String] = Set("options", "k1", "b")

  given Decoder[SearchIndexCreateRequest] = Decoder.instance { c =>
    for
      tenant   <- c.get[String]("tenant")
      database <- c.get[String]("database")
      table    <- c.get[String]("table")
      columns  <- c.get[List[String]]("columns")
    yield SearchIndexCreateRequest(
      tenant,
      database,
      table,
      columns,
      namesOptions = c.keys.exists(_.exists(RefusedKeys))
    )
  }

  given Encoder[SearchIndexCreateRequest] =
    deriveEncoder[Wire].contramap(r => Wire(r.tenant, r.database, r.table, r.columns))

  given Schema[SearchIndexCreateRequest] =
    Schema
      .derived[Wire]
      .as[SearchIndexCreateRequest]
      .name(Schema.SName("SearchIndexCreateRequest"))

/** `POST /api/search/index/list`: every database of the tenant when `database` is absent. */
final case class SearchIndexListRequest(tenant: String, database: Option[String] = None)

/** `POST /api/search/index/delete`: the index by its table's current name, or by its id (which also
  * reaches a disabled index whose name a recreated table now holds); exactly one of them.
  */
final case class SearchIndexDeleteRequest(
    tenant: String,
    database: String,
    table: Option[String] = None,
    indexId: Option[String] = None
)

/** `POST /api/search/index/build` (interim, D7): bundles of the index's table, built on `pool`. */
final case class SearchIndexBuildRequest(
    tenant: String,
    database: String,
    table: String,
    pool: String
)

final case class SearchIndexFieldDto(column: String, fieldId: Long)

/** One index. `schema` and `table` are the names at create; `currentSchema` / `currentTable` the
  * table's names now (absent when it is gone or the catalog could not be read). `bundleRoot` is
  * shown to superusers only: a tenant caller reads `null`, as no data path QoD placed is shown to a
  * tenant (`HandlerResolvers.metastoreFor`'s rule).
  */
final case class SearchIndexResponse(
    indexId: String,
    tenant: String,
    database: String,
    schema: String,
    table: String,
    tableId: Long,
    fields: List[SearchIndexFieldDto],
    bundleRoot: Option[String],
    state: String,
    segmentsVersion: Long,
    createdBy: String,
    createdAt: String,
    currentSchema: Option[String] = None,
    currentTable: Option[String] = None
)

final case class SearchIndexListResponse(indexes: List[SearchIndexResponse])

final case class SearchIndexDeleteResponse(deleted: Boolean)

/** One data file's bundle: the data file, the bundle written, its documents and bytes, and the
  * build statement's wall time in milliseconds.
  */
final case class SearchBuiltFile(path: String, bundle: String, docs: Long, bytes: Long, ms: Long)

/** `flushed`: the table's inlined rows were flushed first. `skipped`: live-listed data files with
  * no live row, which need no bundle.
  */
final case class SearchBuildResponse(
    flushed: Boolean,
    files: List[SearchBuiltFile],
    skipped: List[String]
)

/** QoD Search's index registry (spec 6.1), registered in [[EndpointModules.all]]. Every route takes
  * a session or a PAT (`apiKeyGuard`: a non-admin's profile session reads `403 admin_required`),
  * then the tenant scope (`403 tenant_forbidden` across tenants); build is superuser only.
  */
object SearchEndpoints:

  given Codec[SearchIndexListRequest]    = deriveCodec
  given Codec[SearchIndexDeleteRequest]  = deriveCodec
  given Codec[SearchIndexBuildRequest]   = deriveCodec
  given Codec[SearchIndexFieldDto]       = deriveCodec
  given Codec[SearchIndexResponse]       = deriveCodec
  given Codec[SearchIndexListResponse]   = deriveCodec
  given Codec[SearchIndexDeleteResponse] = deriveCodec
  given Codec[SearchBuiltFile]           = deriveCodec
  given Codec[SearchBuildResponse]       = deriveCodec

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val createIndex: PublicEndpoint[
    (SearchIndexCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    SearchIndexResponse,
    Any
  ] =
    base.post
      .in("search" / "index" / "create")
      .description(
        "Create a full-text search index on a DuckLake table's VARCHAR columns (1 to 32). The " +
          "index is keyed by the table's DuckLake id, so a rename keeps it. k1 and b are fixed at " +
          "1.2 and 0.75: a body naming options, k1 or b is 400 invalid_search_options. Refusals: " +
          "404 not_found, 400 search_branch_unsupported, 400 search_ducklake_only, 400 " +
          "search_encrypted_unsupported, 400 invalid_name, 400 invalid_search_columns, 503 " +
          "catalog_unavailable, 404 table_not_found, 400 search_column_not_found, 400 " +
          "search_column_type, 409 search_index_exists."
      )
      .in(jsonBody[SearchIndexCreateRequest])
      .in(authToken)
      .out(jsonBody[SearchIndexResponse])

  val listIndexes: PublicEndpoint[
    (SearchIndexListRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    SearchIndexListResponse,
    Any
  ] =
    base.post
      .in("search" / "index" / "list")
      .description(
        "List a tenant's search indexes, of one database or all. An index whose table was " +
          "dropped reads disabled; a renamed table shows its current name."
      )
      .in(jsonBody[SearchIndexListRequest])
      .in(authToken)
      .out(jsonBody[SearchIndexListResponse])

  val deleteIndex: PublicEndpoint[
    (SearchIndexDeleteRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    SearchIndexDeleteResponse,
    Any
  ] =
    base.post
      .in("search" / "index" / "delete")
      .description(
        "Drop a search index, named by its table's current name (an index whose table is gone, " +
          "by its name at create) or by its indexId, exactly one of them (400 " +
          "invalid_search_target). Its bundles stay until they are collected. 404 not_found; 403 " +
          "token_scope for a database the token does not allow."
      )
      .in(jsonBody[SearchIndexDeleteRequest])
      .in(authToken)
      .out(jsonBody[SearchIndexDeleteResponse])

  val buildIndex: PublicEndpoint[
    (SearchIndexBuildRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    SearchBuildResponse,
    Any
  ] =
    base.post
      .in("search" / "index" / "build")
      .description(
        "Interim operator tool until the indexer: flushes the table's inlined rows and builds " +
          "the bundle of each live data file for its search index, on the named pool, as the " +
          "system principal. Superuser only (403 superuser_required). 409 " +
          "search_build_deleted_rows for a file holding deleted rows."
      )
      .in(jsonBody[SearchIndexBuildRequest])
      .in(authToken)
      .out(jsonBody[SearchBuildResponse])
