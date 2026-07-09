package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** DuckLake catalog browser (read-only GETs), split out of [[Endpoints]] to stay below the JVM's
  * 64KB `<clinit>` ceiling (see [[RbacEndpoints]] for the full rationale). Registered in
  * [[EndpointModules.all]].
  *
  * Every endpoint carries the session input ([[Endpoints.authToken]]) and the standard
  * [[ErrorResponse]] envelope: the handlers enforce [[TenantScopeCheck]] per request, same as
  * [[TagEndpoints]] (Spec 00 closed the former ungated PublicEndpoint drift).
  */
object CatalogEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val listSchemasEndpoint: PublicEndpoint[
    (String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    List[CatalogSchemaEntry],
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") / "schemas"
      )
      .in(Endpoints.authToken)
      .out(jsonBody[List[CatalogSchemaEntry]])
      .description("List schemas in the DuckLake catalog of the (tenant, tenantDb).")

  val listTablesEndpoint: PublicEndpoint[
    (String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    List[CatalogTableEntry],
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") / "tables"
      )
      .in(Endpoints.authToken)
      .out(jsonBody[List[CatalogTableEntry]])
      .description("List tables in a schema of the (tenant, tenantDb)'s catalog.")

  val getTableEndpoint: PublicEndpoint[
    (String, String, String, String, Option[Long], Option[String], Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    CatalogTableDetailResponse,
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") /
          "tables" / path[String]("table")
      )
      .in(query[Option[Long]]("asOf"))
      .in(query[Option[String]]("asOfTag"))
      .in(query[Option[String]]("asOfTs"))
      .in(Endpoints.authToken)
      .out(jsonBody[CatalogTableDetailResponse])
      .description(
        "Get one table's columns + parquet data files, optionally as of a DuckLake snapshot " +
          "(asOf=<id>), a snapshot tag (asOfTag=<name>), or a timestamp (asOfTs=<ISO-8601>); " +
          "supplying more than one is a 400. Returns resolvedSnapshot + resolvedAt when a selector was used."
      )

  val listSnapshotsEndpoint: PublicEndpoint[
    (String, String, Option[Int], Option[Long], Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    List[CatalogSnapshotEntry],
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") / "snapshots"
      )
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Long]]("before"))
      .in(query[Option[String]]("table"))
      .in(Endpoints.authToken)
      .out(jsonBody[List[CatalogSnapshotEntry]])
      .description(
        "List DuckLake snapshots of the (tenant, tenantDb), newest first; keyset pagination via limit + before=snapshotId. " +
          "Optional table=<schema>.<table> filters to snapshots affecting that table."
      )

  val tableHistoryEndpoint: PublicEndpoint[
    (
        String,
        String,
        String,
        String,
        Option[Int],
        Option[Long],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    CatalogHistoryResponse,
    Any
  ] =
    base.get
      .in(
        "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") /
          "tables" / path[String]("table") / "history"
      )
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Long]]("before"))
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[String]]("operation"))
      .in(query[Option[String]]("author"))
      .in(Endpoints.authToken)
      .out(jsonBody[CatalogHistoryResponse])
      .description(
        "Per-table DuckLake commit history, newest first (EPIC Spec 01): operation, author, " +
          "commit message, schema-changed flag, and row/file deltas attributable to the table. " +
          "Keyset pagination via limit (default 50, max 200) + before=snapshotId; optional " +
          "from/to (ISO-8601), operation, and author filters."
      )
