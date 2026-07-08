package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** DuckLake catalog browser (read-only GETs), split out of [[Endpoints]] to stay below the JVM's
  * 64KB `<clinit>` ceiling (see [[RbacEndpoints]] for the full rationale). Registered in
  * [[EndpointModules.all]].
  *
  * These endpoints carry no session input; they are admitted per-request by
  * `ManagerServer.apiKeyGuard` + the perimeter URL-tenant check only. Known drift, tracked in
  * docs/AUDIT-FOLLOWUPS.md; new catalog-adjacent surfaces (see [[TagEndpoints]]) must NOT copy this
  * shape.
  */
object CatalogEndpoints:

  val listSchemasEndpoint: PublicEndpoint[(String, String), Unit, List[CatalogSchemaEntry], Any] =
    endpoint.get
      .in(
        "api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") / "schemas"
      )
      .out(jsonBody[List[CatalogSchemaEntry]])
      .description("List schemas in the DuckLake catalog of the (tenant, tenantDb).")

  val listTablesEndpoint
      : PublicEndpoint[(String, String, String), Unit, List[CatalogTableEntry], Any] =
    endpoint.get
      .in(
        "api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") / "tables"
      )
      .out(jsonBody[List[CatalogTableEntry]])
      .description("List tables in a schema of the (tenant, tenantDb)'s catalog.")

  val getTableEndpoint: PublicEndpoint[
    (String, String, String, String, Option[Long]),
    String,
    CatalogTableDetailResponse,
    Any
  ] =
    endpoint.get
      .in(
        "api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") /
          "tables" / path[String]("table")
      )
      .in(query[Option[Long]]("asOf"))
      .out(jsonBody[CatalogTableDetailResponse])
      .errorOut(statusCode(sttp.model.StatusCode.NotFound).and(stringBody))
      .description(
        "Get one table's columns + parquet data files, optionally as of a DuckLake snapshot."
      )

  val listSnapshotsEndpoint: PublicEndpoint[(String, String, Option[Int], Option[Long]), Unit, List[
    CatalogSnapshotEntry
  ], Any] =
    endpoint.get
      .in(
        "api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") / "snapshots"
      )
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Long]]("before"))
      .out(jsonBody[List[CatalogSnapshotEntry]])
      .description(
        "List DuckLake snapshots of the (tenant, tenantDb), newest first; keyset pagination via limit + before=snapshotId."
      )
