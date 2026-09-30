package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Read-only views over an external Iceberg REST catalog attached to a tenant-db (browse, table
  * detail with files, snapshot history, preview and data diff), split out of [[Endpoints]] for the
  * same reason as [[TimeTravelEndpoints]]: a Scala 3 object turns each `val` into a static-field
  * initializer and the combined `<clinit>` must stay below the JVM's 64KB method ceiling.
  *
  * Every route carries the session input ([[Endpoints.authToken]]); [[IcebergCatalogHandlers]] runs
  * [[TenantDbGate]] per request (tenant admin or superuser). Snapshot ids are strings on the wire.
  */
object IcebergCatalogEndpoints:

  private type Err = (sttp.model.StatusCode, ErrorResponse)

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  private val aliasPath =
    "catalog" / "tenant" / path[String]("tenant") /
      "database" / path[String]("tenantDb") /
      "iceberg" / path[String]("alias")

  private val tablePath =
    aliasPath / "schemas" / path[String]("schema") / "tables" / path[String]("table")

  val schemasEndpoint: PublicEndpoint[
    (String, String, String, Option[String]),
    Err,
    List[CatalogSchemaEntry],
    Any
  ] =
    base.get
      .in(aliasPath / "schemas")
      .in(Endpoints.authToken)
      .out(jsonBody[List[CatalogSchemaEntry]])
      .description(
        "Namespaces of an attached Iceberg REST catalog (`alias` is the federated source alias). " +
          "tableCount is -1 (not counted)."
      )

  val tablesEndpoint: PublicEndpoint[
    (String, String, String, String, Option[String]),
    Err,
    List[String],
    Any
  ] =
    base.get
      .in(aliasPath / "schemas" / path[String]("schema") / "tables")
      .in(Endpoints.authToken)
      .out(jsonBody[List[String]])
      .description("Tables of one namespace of an attached Iceberg REST catalog.")

  val detailEndpoint: PublicEndpoint[
    (String, String, String, String, String, Option[String]),
    Err,
    IcebergTableDetailResponse,
    Any
  ] =
    base.get
      .in(tablePath)
      .in(Endpoints.authToken)
      .out(jsonBody[IcebergTableDetailResponse])
      .description(
        "Columns, current data and delete files, and the current snapshot id of an Iceberg table."
      )

  val historyEndpoint: PublicEndpoint[
    (
        String,
        String,
        String,
        String,
        String,
        Option[Int],
        Option[String],
        Option[String],
        Option[
          String
        ]
    ),
    Err,
    IcebergHistoryResponse,
    Any
  ] =
    base.get
      .in(tablePath / "history")
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("before"))
      .in(query[Option[String]]("operation"))
      .in(Endpoints.authToken)
      .out(jsonBody[IcebergHistoryResponse])
      .description(
        "Snapshot history of an Iceberg table, newest first. `before` is a snapshot id (string) " +
          "to page past; `operation` filters to append | overwrite | delete | replace."
      )

  val previewEndpoint: PublicEndpoint[
    (
        String,
        String,
        String,
        String,
        String,
        Option[String],
        Option[String],
        Option[String],
        Option[Int],
        Option[String]
    ),
    Err,
    IcebergPreviewResponse,
    Any
  ] =
    base.get
      .in(tablePath / "preview")
      .in(query[Option[String]]("asOf"))
      .in(query[Option[String]]("asOfTag"))
      .in(query[Option[String]]("asOfTs"))
      .in(query[Option[Int]]("limit"))
      .in(Endpoints.authToken)
      .out(jsonBody[IcebergPreviewResponse])
      .description(
        "Bounded preview of an Iceberg table's rows, optionally as of a snapshot id (asOf, a " +
          "string) or a timestamp (asOfTs=<ISO-8601>); at most one selector. asOfTag is " +
          "unsupported for Iceberg. `limit` clamps the row cap downward."
      )

  val dataDiffEndpoint: PublicEndpoint[
    (
        String,
        String,
        String,
        String,
        String,
        String,
        String,
        Option[Int],
        Option[String],
        Option[
          String
        ]
    ),
    Err,
    IcebergDiffResponse,
    Any
  ] =
    base.get
      .in(tablePath / "data-diff")
      .in(query[String]("from"))
      .in(query[String]("to"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("changeType"))
      .in(Endpoints.authToken)
      .out(jsonBody[IcebergDiffResponse])
      .description(
        "Row-level diff of an Iceberg table between two snapshot ids (`from`, `to`, strings). " +
          "`changeType` filters to added | removed."
      )
