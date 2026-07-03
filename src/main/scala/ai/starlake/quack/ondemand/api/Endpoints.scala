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

  val deletePool: PublicEndpoint[
    (DeletePoolRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("pool" / "delete")
      .in(jsonBody[DeletePoolRequest])
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

  val unquarantineNode: PublicEndpoint[
    (NodeOpRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("node" / "unquarantine")
      .in(jsonBody[NodeOpRequest])
      .in(header[Option[String]]("X-API-Key"))

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
  val serverConfig: PublicEndpoint[Option[
    String
  ], (sttp.model.StatusCode, ErrorResponse), ConfigListResponse, Any] =
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
    (Option[String], Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    WhoamiResponse,
    Any
  ] =
    base.get
      .in("auth" / "whoami")
      .in(header[Option[String]]("X-API-Key"))
      .in(cookie[Option[String]](SessionTokenStore.CookieName))
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
