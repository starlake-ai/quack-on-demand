package ai.starlake.quack.ondemand.api

import Dtos.given
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Search Phase 0 prototype only (plan Task 12): builds the full-text bundles of one table's live
  * data files through the manager, as the system principal, since `qod_search_build_segment` runs
  * for no other principal. Phase 2's indexer (the `search-index` maintenance task, search spec 10)
  * replaces it. Superuser only, checked in [[SearchPrototypeHandlers]]; excluded from CLI parity
  * and from `McpCoverageSpec` as prototype only.
  */
final case class SearchBuildRequest(
    tenant: String,
    database: String,
    pool: String,
    table: String,
    column: String
)

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

object SearchPrototypeEndpoints:

  given Codec[SearchBuildRequest]  = deriveCodec
  given Codec[SearchBuiltFile]     = deriveCodec
  given Codec[SearchBuildResponse] = deriveCodec

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val buildSegments: PublicEndpoint[
    (SearchBuildRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    SearchBuildResponse,
    Any
  ] =
    base.post
      .in("search" / "prototype" / "build-segments")
      .in(jsonBody[SearchBuildRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[SearchBuildResponse])
      .description(
        "Search Phase 0 prototype only: flushes a DuckLake table's inlined rows and builds the " +
          "full-text bundle of each live data file for one column, as the system principal. " +
          "Superuser only (403 superuser_required). 400 invalid_name for a table or column that " +
          "is not a plain identifier; 409 search_build_nonaffine or search_build_deleted_rows " +
          "for a file the prototype cannot build. Phase 2's indexer replaces it."
      )
