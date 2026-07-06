package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.OidcSsoService
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
private given Schema[NodeTolerationDto]        = Schema.derived
private given Schema[NodePlacementDto]         = Schema.derived
private given Schema[PoolCohortDto]            = Schema.derived
private given Schema[PoolResponse]             = Schema.derived
private given Schema[PoolListResponse]         = Schema.derived
private given Schema[CreatePoolRequest]        = Schema.derived
private given Schema[SetPoolResourcesRequest]  = Schema.derived
private given Schema[SetPoolTemplateRequest]   = Schema.derived
private given Schema[ActiveStatementInfo]      = Schema.derived
private given Schema[ActiveStatementsResponse] = Schema.derived
private given Schema[KillStatementRequest]     = Schema.derived
private given Schema[KillStatementResponse]    = Schema.derived
private given Schema[AuditEventEntry]          = Schema.derived
private given Schema[AuditListResponse]        = Schema.derived
private given Schema[AuditActionsResponse]     = Schema.derived
private given Schema[TrendBucketEntry]         = Schema.derived
private given Schema[TrendsResponse]           = Schema.derived
private given Schema[StatementHistoryRowEntry] = Schema.derived
private given Schema[StatementSearchResponse]  = Schema.derived
private given Schema[UsageDayEntry]            = Schema.derived
private given Schema[UsageGroupEntry]          = Schema.derived
private given Schema[UsageResponse]            = Schema.derived

object Endpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  // Secured endpoints accept the session token via either the X-API-Key header
  // (CLI / static key) or the qod_session cookie (browser). Resolved to one
  // Option[String], header taking precedence. Using this input means an endpoint
  // physically cannot forget the cookie transport.
  val authToken: sttp.tapir.EndpointInput[Option[String]] =
    header[Option[String]]("X-API-Key")
      .and(cookie[Option[String]](SessionTokenStore.CookieName))
      .map { case (h, c) => h.orElse(c) }(t => (t, None))

  val createPool: PublicEndpoint[
    (CreatePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "create")
      .in(jsonBody[CreatePoolRequest])
      .in(authToken)
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
      .in(authToken)
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
      .in(authToken)

  val deletePool: PublicEndpoint[
    (DeletePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("pool" / "delete")
      .in(jsonBody[DeletePoolRequest])
      .in(authToken)

  val listPools: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    PoolListResponse,
    Any
  ] =
    base.get
      .in("pool" / "list")
      .in(authToken)
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

  val setMaxConcurrent: PublicEndpoint[
    (SetMaxConcurrentRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "setMaxConcurrent")
      .in(jsonBody[SetMaxConcurrentRequest])
      .in(authToken)

  val quarantineNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "quarantine")
      .in(jsonBody[NodeOpRequest])
      .in(authToken)

  val unquarantineNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "unquarantine")
      .in(jsonBody[NodeOpRequest])
      .in(authToken)

  val restartNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "restart")
      .in(jsonBody[NodeOpRequest])
      .in(authToken)

  /** Liveness probe: always 200 while the JVM is alive. No Postgres gate. */
  val health: PublicEndpoint[Unit, sttp.model.StatusCode, HealthResponse, Any] =
    endpoint.get
      .in("health")
      .errorOut(statusCode)
      .out(jsonBody[HealthResponse])

  /** Readiness probe: 503 until Postgres is reachable; 200 once it is. */
  val ready: PublicEndpoint[Unit, sttp.model.StatusCode, HealthResponse, Any] =
    endpoint.get
      .in("ready")
      .errorOut(statusCode)
      .out(jsonBody[HealthResponse])

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
  val serverConfig: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    ConfigListResponse,
    Any
  ] =
    base.get
      .in("config" / "server")
      .in(authToken)
      .out(jsonBody[ConfigListResponse])

  val manifestExport: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    String,
    Any
  ] =
    base.get
      .in("manifest" / "export")
      .in(authToken)
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
      .in(authToken)
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
      .in(authToken)
      .out(jsonBody[TenantResponse])

  val listTenants: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    TenantListResponse,
    Any
  ] =
    base.get
      .in("tenant" / "list")
      .in(authToken)
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
      .in(authToken)

  val setTenantDisabled: PublicEndpoint[
    (SetTenantDisabledRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    TenantResponse,
    Any
  ] =
    base.post
      .in("tenant" / "setDisabled")
      .in(jsonBody[SetTenantDisabledRequest])
      .in(authToken)
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
      .in(authToken)
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
      .in(authToken)
      .out(jsonBody[PoolResponse])

  val setPoolResources: PublicEndpoint[
    (SetPoolResourcesRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "setResources")
      .in(jsonBody[SetPoolResourcesRequest])
      .in(authToken)
      .out(jsonBody[PoolResponse])

  val setPoolTemplate: PublicEndpoint[
    (SetPoolTemplateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PoolResponse,
    Any
  ] =
    base.post
      .in("pool" / "setPodTemplate")
      .in(jsonBody[SetPoolTemplateRequest])
      .in(authToken)
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
      .in(authToken)
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
      .in(authToken)

  val updateTenantDb: PublicEndpoint[
    (UpdateTenantDbRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    UpdateTenantDbResponse,
    Any
  ] =
    base.post
      .in("database" / "update")
      .in(jsonBody[UpdateTenantDbRequest])
      .in(authToken)
      .out(jsonBody[UpdateTenantDbResponse])

  // ----- UI login -----
  // Login also sets the qod_session cookie (HttpOnly, SameSite=Lax) so the
  // browser auto-attaches the JWT on subsequent `/api/...` calls without the
  // UI having to stash a token in localStorage. CLI / static-key callers
  // still get `LoginResponse.token` in the JSON body and can send it via
  // X-API-Key as before.
  //
  // `X-Forwarded-Proto` (injected by any TLS-terminating ingress) feeds the
  // handler's auto-derive for the cookie's `Secure` flag: present + `https`
  // -> Secure cookie; absent or `http` -> non-Secure (so plaintext local-jar
  // runs don't silently drop the Set-Cookie). The operator can still force
  // either value via `QOD_SESSION_COOKIE_SECURE`.
  val login: PublicEndpoint[
    (LoginRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    (sttp.model.headers.CookieValueWithMeta, LoginResponse),
    Any
  ] =
    base.post
      .in("auth" / "login")
      .in(jsonBody[LoginRequest])
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(setCookie(SessionTokenStore.CookieName))
      .out(jsonBody[LoginResponse])

  // Logout accepts the token via cookie OR header so the browser path works
  // even though JS can't read the HttpOnly cookie. The response always
  // clears the cookie (Max-Age=0); the handler also adds the jti to the
  // denylist while the manager process stays up. Same `X-Forwarded-Proto`
  // gate as `login` so the clear-cookie response carries the same `Secure`
  // attribute the browser saw at set time (mismatched attributes prevent
  // the clear from landing on some browsers).
  val logout: PublicEndpoint[
    (Option[String], Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    sttp.model.headers.CookieValueWithMeta,
    Any
  ] =
    base.post
      .in("auth" / "logout")
      .in(header[Option[String]]("X-API-Key"))
      .in(cookie[Option[String]](SessionTokenStore.CookieName))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(setCookie(SessionTokenStore.CookieName))

  val whoami: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    WhoamiResponse,
    Any
  ] =
    base.get
      .in("auth" / "whoami")
      .in(authToken)
      .out(jsonBody[WhoamiResponse])

  // Unauthenticated: the SPA calls this before login (tenant from the URL) to decide whether to
  // render the password form ("db") or redirect to /api/auth/oidc/start ("oidc"). A db tenant or a
  // fully-configured OIDC tenant returns 200; an unknown / misconfigured tenant returns 400 with a
  // stable error code.
  val authMode: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    AuthModeResponse,
    Any
  ] =
    base.get
      .in("auth" / "mode")
      .in(query[Option[String]]("tenant"))
      .out(jsonBody[AuthModeResponse])

  // OIDC SSO redirect endpoints. start returns Either (Left = JSON error, Right = 302 redirect),
  // so it uses `base` (which carries the JSON errorOut). callback/logout always 302 (no error
  // channel) so they use bare `endpoint`; failures are encoded in the Location query string.
  val oidcStart: PublicEndpoint[
    (Option[String], Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    (sttp.model.StatusCode, String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    base.get
      .in("auth" / "oidc" / "start")
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("returnTo"))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie(OidcSsoService.STATE_COOKIE))

  val oidcCallback: PublicEndpoint[
    (Option[String], Option[String], Option[String], Option[String]),
    Unit,
    (
        sttp.model.StatusCode,
        String,
        sttp.model.headers.CookieValueWithMeta,
        sttp.model.headers.CookieValueWithMeta
    ),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "oidc" / "callback")
      .in(query[Option[String]]("code"))
      .in(query[Option[String]]("state"))
      .in(cookie[Option[String]](OidcSsoService.STATE_COOKIE))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie(SessionTokenStore.CookieName))
      .out(setCookie(OidcSsoService.STATE_COOKIE))

  val oidcLogout: PublicEndpoint[
    (Option[String], Option[String]),
    Unit,
    (sttp.model.StatusCode, String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "oidc" / "logout")
      .in(cookie[Option[String]](SessionTokenStore.CookieName))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie(SessionTokenStore.CookieName))

  // Browser SQL-token flow on the manager port: log in via the data-plane OIDC provider and render
  // the access token for pasting into a JDBC `token` property. start = 302 to the IdP (+ state
  // cookie); callback = an HTML page showing the token (+ clears the state cookie). Both are
  // guard-exempt (see ManagerServer.apiKeyGuard). The state cookie is `qod_sqltoken_state`.
  val sqlTokenStart: PublicEndpoint[
    Option[String],
    Unit,
    (sttp.model.StatusCode, String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "sql-token" / "start")
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(statusCode)
      .out(header[String]("Location"))
      .out(setCookie("qod_sqltoken_state"))

  val sqlTokenCallback: PublicEndpoint[
    (Option[String], Option[String], Option[String], Option[String], Option[String]),
    Unit,
    (String, sttp.model.headers.CookieValueWithMeta),
    Any
  ] =
    endpoint.get
      .in("api" / "auth" / "sql-token" / "callback")
      .in(query[Option[String]]("code"))
      .in(query[Option[String]]("state"))
      .in(query[Option[String]]("error"))
      .in(cookie[Option[String]]("qod_sqltoken_state"))
      .in(header[Option[String]]("X-Forwarded-Proto"))
      .out(htmlBodyUtf8)
      .out(setCookie("qod_sqltoken_state"))

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
      .in(authToken)
      .out(jsonBody[StatementHistoryResponse])

  // Tenant-scoped audit log with keyset pagination. All query params are optional.
  // `before` is an opaque cursor (last row's id as a string from a prior response).
  // `from`/`to` must be ISO-8601 instants; invalid values return 400 invalid_time.
  val auditList: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[Int],
        Option[String],
        Option[Boolean],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    AuditListResponse,
    Any
  ] =
    base.get
      .in("audit" / "list")
      .in(query[Option[String]]("family"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("actor"))
      .in(query[Option[String]]("action"))
      .in(query[Option[String]]("q"))
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("before"))
      .in(query[Option[Boolean]]("noTenant"))
      .in(authToken)
      .out(jsonBody[AuditListResponse])

  // Exhaustive audit action vocabulary (static registry, no store access). Feeds the
  // Audit page's action select.
  val auditActions: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    AuditActionsResponse,
    Any
  ] =
    base.get
      .in("audit" / "actions")
      .in(authToken)
      .out(jsonBody[AuditActionsResponse])

  // Tenant-scoped rollup trends (hourly or daily aggregates). granularity is required ("hour" |
  // "day"); invalid values return 400 invalid_granularity. from/to must be ISO-8601 instants.
  val historyTrends: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    TrendsResponse,
    Any
  ] =
    base.get
      .in("history" / "trends")
      .in(query[Option[String]]("granularity"))
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("pool"))
      .in(authToken)
      .out(jsonBody[TrendsResponse])

  // Tenant-scoped statement search with keyset pagination. `before` is an opaque cursor (last row
  // id as a string from a prior response). `limit` defaults to 50, clamped to [1, 500].
  val historyStatements: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[Int],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    StatementSearchResponse,
    Any
  ] =
    base.get
      .in("history" / "statements")
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("pool"))
      .in(query[Option[String]]("user"))
      .in(query[Option[String]]("status"))
      .in(query[Option[String]]("q"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[String]]("before"))
      .in(authToken)
      .out(jsonBody[StatementSearchResponse])

  // Tenant-scoped usage accounting over daily rollups. Defaults: current calendar month (UTC),
  // groupBy=tenant. groupBy must be tenant | pool | user (400 invalid_group_by otherwise);
  // from/to must be ISO-8601 instants (400 invalid_time).
  val usage: PublicEndpoint[
    (
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String]
    ),
    (sttp.model.StatusCode, ErrorResponse),
    UsageResponse,
    Any
  ] =
    base.get
      .in("usage")
      .in(query[Option[String]]("from"))
      .in(query[Option[String]]("to"))
      .in(query[Option[String]]("groupBy"))
      .in(query[Option[String]]("tenant"))
      .in(query[Option[String]]("pool"))
      .in(authToken)
      .out(jsonBody[UsageResponse])

  // ----- Federated sources -----

  private val fedBase =
    base.in(
      "tenants" / path[String]("tenant") / "tenant-dbs" / path[String](
        "tenantDb"
      ) / "federated-sources"
    )

  val createFederatedSource: PublicEndpoint[
    (String, String, FederatedSourceCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSourceResponse,
    Any
  ] =
    fedBase.post
      .in(jsonBody[FederatedSourceCreateRequest])
      .in(authToken)
      .out(jsonBody[FederatedSourceResponse])

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
    (String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    fedBase.delete.in(path[String]("alias")).in(authToken)

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
    (String, String, String, FederatedSecretUpsertRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    FederatedSecretResponse,
    Any
  ] =
    fedBase.put
      .in(path[String]("alias") / "secrets")
      .in(jsonBody[FederatedSecretUpsertRequest])
      .in(authToken)
      .out(jsonBody[FederatedSecretResponse])

  val deleteFederatedSecret: PublicEndpoint[
    (String, String, String, String, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    fedBase.delete.in(path[String]("alias") / "secrets" / path[String]("name")).in(authToken)

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

  val activeStatements: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    ActiveStatementsResponse,
    Any
  ] =
    base.get
      .in("node" / "active-statements")
      .in(authToken)
      .out(jsonBody[ActiveStatementsResponse])

  val killStatement: PublicEndpoint[
    (KillStatementRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    KillStatementResponse,
    Any
  ] =
    base.post
      .in("statement" / "kill")
      .in(jsonBody[KillStatementRequest])
      .in(authToken)
      .out(jsonBody[KillStatementResponse])
