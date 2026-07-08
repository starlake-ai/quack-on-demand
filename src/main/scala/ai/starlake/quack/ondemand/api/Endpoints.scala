package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** System surface (probes, config, manifest) plus the shared [[authToken]] input. The REST API is
  * split into one endpoint object per domain so each object's static initializer stays below the
  * JVM's 64KB `<clinit>` ceiling (a Scala 3 object turns every `val` into a static-field
  * initializer; the full surface in one object overflows it): [[PoolEndpoints]], [[NodeEndpoints]],
  * [[TenantEndpoints]], [[AuthEndpoints]], [[TelemetryEndpoints]], [[FederatedSourceEndpoints]],
  * [[CatalogEndpoints]], [[TagEndpoints]], [[RbacEndpoints]]. Every module MUST be registered in
  * [[EndpointModules.all]] - that registry feeds the OpenAPI generator and the
  * tenant-scope-completeness guardrail spec.
  */
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
