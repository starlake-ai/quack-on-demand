package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

// Explicit Schemas for the response types touched by the metrics fields.
// tapir's generic auto-derivation chokes on the bigger NodeInfo case class
// in Scala 3, so we materialize them once here and reuse.
private given Schema[NodeInfo] = Schema.derived
// Placement DTOs (transitively reachable from PoolResponse and
// CreatePoolRequest). Anchor each one explicitly so magnolia derives
// them once instead of expanding the chain twice through `auto._`,
// which in Scala 3 blows up with an inline budget error.
private given Schema[NodeTolerationDto] = Schema.derived
private given Schema[NodePlacementDto]  = Schema.derived
private given Schema[PoolCohortDto]     = Schema.derived
private given Schema[PoolResponse]      = Schema.derived
private given Schema[PoolListResponse]  = Schema.derived
private given Schema[CreatePoolRequest] = Schema.derived

object Endpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val createPool: PublicEndpoint[
    (CreatePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "create")
      .in(jsonBody[CreatePoolRequest])
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[PoolResponse])

  val scalePool: PublicEndpoint[
    (ScalePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "scale")
      .in(jsonBody[ScalePoolRequest])
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[PoolResponse])

  val stopPool: PublicEndpoint[
    (StopPoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("pool" / "stop")
      .in(jsonBody[StopPoolRequest])
      .in(header[Option[String]]("X-API-Key"))

  val listPools: PublicEndpoint[Option[
    String
  ], (sttp.model.StatusCode, ErrorResponse), PoolListResponse, Any] =
    base.get
      .in("pool" / "list")
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[PoolListResponse])

  val poolStatus: PublicEndpoint[
    (String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.get
      .in(
        "pool" / path[String]("tenant") / path[String]("tenantDb") / path[String]("pool") / "status"
      )
      .out(jsonBody[PoolResponse])

  val setRole: PublicEndpoint[
    (SetRoleRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "setRole")
      .in(jsonBody[SetRoleRequest])
      .in(header[Option[String]]("X-API-Key"))

  val setMaxConcurrent: PublicEndpoint[
    (SetMaxConcurrentRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "setMaxConcurrent")
      .in(jsonBody[SetMaxConcurrentRequest])
      .in(header[Option[String]]("X-API-Key"))

  val quarantineNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "quarantine")
      .in(jsonBody[NodeOpRequest])
      .in(header[Option[String]]("X-API-Key"))

  val restartNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "restart")
      .in(jsonBody[NodeOpRequest])
      .in(header[Option[String]]("X-API-Key"))

  val health: PublicEndpoint[Unit, Unit, HealthResponse, Any] =
    endpoint.get.in("health").out(jsonBody[HealthResponse])

  /** Static client-connection info the UI needs to build JDBC/ODBC/ADBC URLs. Uses bare `endpoint`
    * (no /api prefix, no error envelope) to match `/health`.
    */
  val clientConfig: PublicEndpoint[Unit, Unit, ClientConfigResponse, Any] =
    endpoint.get.in("api" / "config" / "client").out(jsonBody[ClientConfigResponse])

  /** Resolved server-side `application.conf` values for the admin UI's Config page. Goes through
    * `apiKeyGuard`, so it's admin-gated in the same way as the rest of the api namespace. The
    * handler additionally rejects tenant-scoped sessions (`X-API-Key` resolves to a session whose
    * profile carries a non-None `tenant`) with 403 `superuser_required` so a non-superuser admin
    * can't list cross-tenant config even with a stolen URL.
    */
  val serverConfig
      : PublicEndpoint[Option[String], (sttp.model.StatusCode, ErrorResponse), ConfigListResponse, Any] =
    base.get
      .in("config" / "server")
      .in(sttp.tapir.header[Option[String]]("X-API-Key"))
      .out(jsonBody[ConfigListResponse])

  val manifestExport
      : PublicEndpoint[Option[String], (sttp.model.StatusCode, ErrorResponse), String, Any] =
    base.get
      .in("manifest" / "export")
      .in(sttp.tapir.header[Option[String]]("X-API-Key"))
      .out(stringBody)
      .out(header("Content-Type", "application/yaml"))

  val manifestImport: PublicEndpoint[
    (String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    ManifestImportSummary,
    Any
  ] =
    base.post
      .in("manifest" / "import")
      .in(stringBody)
      .in(sttp.tapir.header[Option[String]]("X-API-Key"))
      .out(jsonBody[ManifestImportSummary])

  val createTenant: PublicEndpoint[
    (TenantRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantResponse,
    Any
  ] =
    base.post
      .in("tenant" / "create")
      .in(jsonBody[TenantRequest])
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[TenantResponse])

  val listTenants: PublicEndpoint[Option[
    String
  ], (sttp.model.StatusCode, ErrorResponse), TenantListResponse, Any] =
    base.get
      .in("tenant" / "list")
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[TenantListResponse])

  val deleteTenant: PublicEndpoint[
    (TenantOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("tenant" / "delete")
      .in(jsonBody[TenantOpRequest])
      .in(header[Option[String]]("X-API-Key"))

  val setTenantDisabled: PublicEndpoint[
    (SetTenantDisabledRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantResponse,
    Any
  ] =
    base.post
      .in("tenant" / "setDisabled")
      .in(jsonBody[SetTenantDisabledRequest])
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[TenantResponse])

  val setTenantAuth: PublicEndpoint[
    (SetTenantAuthRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantResponse,
    Any
  ] =
    base.post
      .in("tenant" / "setAuth")
      .in(jsonBody[SetTenantAuthRequest])
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[TenantResponse])

  val setPoolDisabled: PublicEndpoint[
    (SetPoolDisabledRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "setDisabled")
      .in(jsonBody[SetPoolDisabledRequest])
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[PoolResponse])

  // ----- Tenant databases -----
  val createTenantDb: PublicEndpoint[
    (TenantDbRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantDbResponse,
    Any
  ] =
    base.post
      .in("database" / "create")
      .in(jsonBody[TenantDbRequest])
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[TenantDbResponse])

  val listTenantDbs
      : PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), TenantDbListResponse, Any] =
    base.get.in("database" / "list").in(query[String]("tenant")).out(jsonBody[TenantDbListResponse])

  val deleteTenantDb: PublicEndpoint[
    (TenantDbOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("database" / "delete")
      .in(jsonBody[TenantDbOpRequest])
      .in(header[Option[String]]("X-API-Key"))

  // ----- UI login -----
  val login
      : PublicEndpoint[LoginRequest, (sttp.model.StatusCode, ErrorResponse), LoginResponse, Any] =
    base.post.in("auth" / "login").in(jsonBody[LoginRequest]).out(jsonBody[LoginResponse])

  val logout: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), Unit, Any] =
    base.post.in("auth" / "logout").in(header[String]("X-API-Key"))

  val whoami: PublicEndpoint[String, (sttp.model.StatusCode, ErrorResponse), WhoamiResponse, Any] =
    base.get.in("auth" / "whoami").in(header[String]("X-API-Key")).out(jsonBody[WhoamiResponse])

  // Recent statement history (newest first), bounded by `limit` (default 50).
  // Tenant-scoped: the handler clamps the response to rows whose tenant is in
  // the calling session's `manageableTenants` (superuser / static-key / open
  // mode return the unfiltered window).
  val statementHistory: PublicEndpoint[
    (Option[Int], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    StatementHistoryResponse,
    Any
  ] =
    base.get
      .in("node" / "statements")
      .in(query[Option[Int]]("limit"))
      .in(header[Option[String]]("X-API-Key"))
      .out(jsonBody[StatementHistoryResponse])

  // ----- Federated sources -----

  private val fedBase =
    base.in(
      "tenants" / path[String]("tenant") / "tenant-dbs" / path[String](
        "tenantDb"
      ) / "federated-sources"
    )

  val createFederatedSource: PublicEndpoint[
    (String, String, FederatedSourceCreateRequest),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSourceResponse,
    Any
  ] =
    fedBase.post.in(jsonBody[FederatedSourceCreateRequest]).out(jsonBody[FederatedSourceResponse])

  val listFederatedSources: PublicEndpoint[
    (String, String),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSourceListResponse,
    Any
  ] =
    fedBase.get.out(jsonBody[FederatedSourceListResponse])

  val getFederatedSource: PublicEndpoint[
    (String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSourceResponse,
    Any
  ] =
    fedBase.get.in(path[String]("alias")).out(jsonBody[FederatedSourceResponse])

  val deleteFederatedSource: PublicEndpoint[
    (String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    fedBase.delete.in(path[String]("alias"))

  val listFederatedSecrets: PublicEndpoint[
    (String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSecretListResponse,
    Any
  ] =
    fedBase.get
      .in(path[String]("alias") / "secrets")
      .out(jsonBody[FederatedSecretListResponse])

  val upsertFederatedSecret: PublicEndpoint[
    (String, String, String, FederatedSecretUpsertRequest),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSecretResponse,
    Any
  ] =
    fedBase.put
      .in(path[String]("alias") / "secrets")
      .in(jsonBody[FederatedSecretUpsertRequest])
      .out(jsonBody[FederatedSecretResponse])

  val deleteFederatedSecret: PublicEndpoint[
    (String, String, String, String),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    fedBase.delete.in(path[String]("alias") / "secrets" / path[String]("name"))

  // ----- Catalog browser -----

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

  val getTableEndpoint
      : PublicEndpoint[(String, String, String, String), String, CatalogTableDetailResponse, Any] =
    endpoint.get
      .in(
        "api" / "catalog" / "tenant" / path[String]("tenant") /
          "database" / path[String]("tenantDb") /
          "schemas" / path[String]("schema") /
          "tables" / path[String]("table")
      )
      .out(jsonBody[CatalogTableDetailResponse])
      .errorOut(statusCode(sttp.model.StatusCode.NotFound).and(stringBody))
      .description("Get one table's columns + parquet data files.")
