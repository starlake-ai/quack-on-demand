package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

// Explicit Schemas for the response types touched by the metrics fields.
// tapir's generic auto-derivation chokes on the bigger NodeInfo case class
// in Scala 3, so we materialize them once here and reuse.
private given Schema[NodeInfo]           = Schema.derived
private given Schema[PoolResponse]       = Schema.derived
private given Schema[PoolListResponse]   = Schema.derived

object Endpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val createPool: PublicEndpoint[CreatePoolRequest, (sttp.model.StatusCode, ErrorResponse), PoolResponse, Any] =
    base.post.in("pool" / "create").in(jsonBody[CreatePoolRequest]).out(jsonBody[PoolResponse])

  val scalePool: PublicEndpoint[ScalePoolRequest, (sttp.model.StatusCode, ErrorResponse), PoolResponse, Any] =
    base.post.in("pool" / "scale").in(jsonBody[ScalePoolRequest]).out(jsonBody[PoolResponse])

  val stopPool: PublicEndpoint[StopPoolRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("pool" / "stop").in(jsonBody[StopPoolRequest])

  val listPools: PublicEndpoint[Unit, (sttp.model.StatusCode, ErrorResponse), PoolListResponse, Any] =
    base.get.in("pool" / "list").out(jsonBody[PoolListResponse])

  val poolStatus: PublicEndpoint[(String, String, String), (sttp.model.StatusCode, ErrorResponse), PoolResponse, Any] =
    base.get.in(
      "pool" / path[String]("tenant") / path[String]("tenantDb") / path[String]("pool") / "status"
    ).out(jsonBody[PoolResponse])

  val setRole: PublicEndpoint[SetRoleRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "setRole").in(jsonBody[SetRoleRequest])

  val setMaxConcurrent: PublicEndpoint[SetMaxConcurrentRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "setMaxConcurrent").in(jsonBody[SetMaxConcurrentRequest])

  val quarantineNode: PublicEndpoint[NodeOpRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "quarantine").in(jsonBody[NodeOpRequest])

  val restartNode: PublicEndpoint[NodeOpRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("node" / "restart").in(jsonBody[NodeOpRequest])

  val health: PublicEndpoint[Unit, Unit, HealthResponse, Any] =
    endpoint.get.in("health").out(jsonBody[HealthResponse])

  /** Static client-connection info the UI needs to build JDBC/ODBC/ADBC URLs.
    * Uses bare `endpoint` (no /api prefix, no error envelope) to match `/health`. */
  val clientConfig: PublicEndpoint[Unit, Unit, ClientConfigResponse, Any] =
    endpoint.get.in("api" / "config" / "client").out(jsonBody[ClientConfigResponse])

  val createTenant: PublicEndpoint[TenantRequest, (sttp.model.StatusCode, ErrorResponse), TenantResponse, Any] =
    base.post.in("tenant" / "create").in(jsonBody[TenantRequest]).out(jsonBody[TenantResponse])

  val listTenants: PublicEndpoint[Unit, (sttp.model.StatusCode, ErrorResponse), TenantListResponse, Any] =
    base.get.in("tenant" / "list").out(jsonBody[TenantListResponse])

  val deleteTenant: PublicEndpoint[TenantOpRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("tenant" / "delete").in(jsonBody[TenantOpRequest])

  val setTenantDisabled: PublicEndpoint[SetTenantDisabledRequest, (sttp.model.StatusCode, ErrorResponse), TenantResponse, Any] =
    base.post.in("tenant" / "setDisabled").in(jsonBody[SetTenantDisabledRequest]).out(jsonBody[TenantResponse])

  val setTenantAuth: PublicEndpoint[SetTenantAuthRequest, (sttp.model.StatusCode, ErrorResponse), TenantResponse, Any] =
    base.post.in("tenant" / "setAuth").in(jsonBody[SetTenantAuthRequest]).out(jsonBody[TenantResponse])

  val setPoolDisabled: PublicEndpoint[SetPoolDisabledRequest, (sttp.model.StatusCode, ErrorResponse), PoolResponse, Any] =
    base.post.in("pool" / "setDisabled").in(jsonBody[SetPoolDisabledRequest]).out(jsonBody[PoolResponse])

  // ----- Tenant databases -----
  val createTenantDb: PublicEndpoint[TenantDbRequest, (sttp.model.StatusCode, ErrorResponse), TenantDbResponse, Any] =
    base.post.in("database" / "create").in(jsonBody[TenantDbRequest]).out(jsonBody[TenantDbResponse])

  val listTenantDbs: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), TenantDbListResponse, Any] =
    base.get.in("database" / "list").in(query[String]("tenant")).out(jsonBody[TenantDbListResponse])

  val deleteTenantDb: PublicEndpoint[TenantDbOpRequest, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("database" / "delete").in(jsonBody[TenantDbOpRequest])

  // ----- UI login -----
  val login: PublicEndpoint[LoginRequest, (sttp.model.StatusCode, ErrorResponse), LoginResponse, Any] =
    base.post.in("auth" / "login").in(jsonBody[LoginRequest]).out(jsonBody[LoginResponse])

  val logout: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("auth" / "logout").in(header[String]("X-API-Key"))

  val whoami: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), WhoamiResponse, Any] =
    base.get.in("auth" / "whoami").in(header[String]("X-API-Key")).out(jsonBody[WhoamiResponse])

  // Recent statement history (newest first), bounded by `limit` (default 50).
  val statementHistory: PublicEndpoint[Option[Int], (sttp.model.StatusCode, ErrorResponse), StatementHistoryResponse, Any] =
    base.get.in("node" / "statements")
        .in(query[Option[Int]]("limit"))
        .out(jsonBody[StatementHistoryResponse])

  // ----- Catalog browser -----

  val listSchemasEndpoint: PublicEndpoint[(String, String), Unit, List[CatalogSchemaEntry], Any] =
    endpoint
      .get
      .in("api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") / "schemas")
      .out(jsonBody[List[CatalogSchemaEntry]])
      .description("List schemas in the DuckLake catalog of the (tenant, tenantDb).")

  val listTablesEndpoint: PublicEndpoint[(String, String, String), Unit, List[CatalogTableEntry], Any] =
    endpoint
      .get
      .in("api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas"  / path[String]("schema") / "tables")
      .out(jsonBody[List[CatalogTableEntry]])
      .description("List tables in a schema of the (tenant, tenantDb)'s catalog.")

  val getTableEndpoint: PublicEndpoint[(String, String, String, String), String, CatalogTableDetailResponse, Any] =
    endpoint
      .get
      .in("api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas"  / path[String]("schema") /
          "tables"   / path[String]("table"))
      .out(jsonBody[CatalogTableDetailResponse])
      .errorOut(statusCode(sttp.model.StatusCode.NotFound).and(stringBody))
      .description("Get one table's columns + parquet data files.")