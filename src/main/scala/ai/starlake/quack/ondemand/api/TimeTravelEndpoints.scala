package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Time-travel viewer REST surface (Spec 00), split out of [[Endpoints]] for the same reason as
  * [[TagEndpoints]] / [[MaintenanceEndpoints]]: a Scala 3 object turns each `val` into a
  * static-field initializer and the combined `<clinit>` must stay below the JVM's 64KB method
  * ceiling, which [[Endpoints]] already saturates.
  *
  * Both endpoints carry the session input ([[Endpoints.authToken]]): the handlers enforce
  * [[TenantScopeCheck]] per request, same as every other catalog-adjacent surface gated since Spec
  * 00.
  */
object TimeTravelEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val previewEndpoint: PublicEndpoint[
    (
        String,
        String,
        String,
        String,
        Option[Long],
        Option[String],
        Option[String],
        Option[
          Int
        ],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    PreviewResponse,
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") /
          "tables" / path[String]("table") / "preview"
      )
      .in(query[Option[Long]]("asOf"))
      .in(query[Option[String]]("asOfTag"))
      .in(query[Option[String]]("asOfTs"))
      .in(query[Option[Int]]("limit"))
      .in(Endpoints.authToken)
      .out(jsonBody[PreviewResponse])
      .description(
        "Bounded preview of a table's rows, optionally as of a DuckLake snapshot (asOf=<id>), a " +
          "snapshot tag (asOfTag=<name>), or a timestamp (asOfTs=<ISO-8601>); supplying more than " +
          "one selector is a 400. `limit` clamps the row cap downward (never above the server's " +
          "configured max)."
      )

  val dataDiffEndpoint: PublicEndpoint[
    (
        String,
        String,
        String,
        String,
        String,
        String,
        Option[Int],
        Option[String],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    DataDiffResponse,
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") /
          "tables" / path[String]("table") / "data-diff"
      )
      .in(query[String]("from"))
      .in(query[String]("to"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("cursor"))
      .in(query[Option[String]]("changeType"))
      .in(Endpoints.authToken)
      .out(jsonBody[DataDiffResponse])
      .description(
        "Row-level data diff between two snapshot selectors (`from`, `to`; each a snapshot id " +
          "or a tag name). `changeType` filters to insert | delete | update; pages via the " +
          "opaque `cursor` echoed back as nextCursor."
      )

  val schemaDiffEndpoint: PublicEndpoint[
    (String, String, String, String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    SchemaDiffResponse,
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") /
          "tables" / path[String]("table") / "schema-diff"
      )
      .in(query[String]("from"))
      .in(query[String]("to"))
      .in(Endpoints.authToken)
      .out(jsonBody[SchemaDiffResponse])
      .description(
        "Column-level schema diff between two snapshot selectors (`from`, `to`; each a snapshot " +
          "id or a tag name)."
      )
