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
    (String, String, String, String, Option[Long], Option[String], Option[String]),
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
      .in(Endpoints.authToken)
      .out(jsonBody[CatalogTableDetailResponse])
      .description(
        "Get one table's columns + parquet data files, optionally as of a DuckLake snapshot " +
          "(asOf=<id>) or a snapshot tag (asOfTag=<name>); supplying both is a 400."
      )

  val listSnapshotsEndpoint: PublicEndpoint[
    (String, String, Option[Int], Option[Long], Option[String]),
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
      .in(Endpoints.authToken)
      .out(jsonBody[List[CatalogSnapshotEntry]])
      .description(
        "List DuckLake snapshots of the (tenant, tenantDb), newest first; keyset pagination via limit + before=snapshotId."
      )
