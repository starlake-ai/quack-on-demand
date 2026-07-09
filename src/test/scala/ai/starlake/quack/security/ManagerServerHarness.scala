// src/test/scala/ai/starlake/quack/security/ManagerServerHarness.scala
package ai.starlake.quack.security

import ai.starlake.quack._
import ai.starlake.quack.edge.{ActiveStatementRegistry, StatementHistoryStore}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.observability.metrics.MetricsEndpoint
import ai.starlake.quack.ondemand.{ManagerServer, PoolSupervisor}
import ai.starlake.quack.ondemand.api._
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.telemetry.{
  AuditRateLimiter,
  AuditRecorder,
  NoopTelemetryStore,
  TelemetryStore
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant

/** Test harness: boots the real [[ManagerServer]] on an ephemeral port backed by an
  * [[InMemoryControlPlaneStore]].
  *
  * Auth chain (option a): [[AuthenticationService]] is subclassed with `emptyConfig` (all external
  * providers disabled) and `authenticateBasic` is overridden to look up the bcrypt password hash
  * directly from the [[InMemoryControlPlaneStore]]. No Postgres or network call is made for
  * authentication.
  *
  * [[UserStore]] (for the create/update user REST endpoints): backed by an in-memory DuckDB
  * instance with the `qodstate_user` table pre-created. This keeps the harness self-contained while
  * still wiring a valid [[UserHandlers]] instance.
  */
object ManagerServerHarness:

  // ------------------------------------------------------------------
  // Minimal ManagerConfig for the harness. Port 0 = OS-assigned.
  // All fields that affect boot must be set to safe no-op values.
  // ------------------------------------------------------------------
  private def minimalManagerConfig(port: Int = 0): ManagerConfig = ManagerConfig(
    host = "127.0.0.1",
    port = port,
    apiKey = None, // open REST namespace
    runtimeType = "local",
    minPort = 40000,
    maxPort = 41000,
    maxNodesTotal = 0,
    nativeClient = false,
    stampWrites = true,
    nodeDisableSsl = true,
    spawnScript = "",
    spawnScriptWindows = "",
    drainTimeoutSec = 5,
    healthCheckIntervalSec = 30,
    reconcileIntervalSec = 0,
    sessionIdleTtlSec = 28800,
    defaultMetastore = DefaultMetastoreConfig(
      pgHost = "localhost",
      pgPort = "5432",
      pgUser = "postgres",
      pgPassword = "postgres",
      dbName = "qod",
      schemaName = "main",
      dataPath = ""
    ),
    admin = AdminConfig(username = "", password = "", role = "admin"),
    k8s = K8sConfig(
      namespace = "default",
      image = "",
      serviceAccount = None,
      serviceType = "ClusterIP",
      quackPort = 21900,
      startupTimeoutSec = 60,
      podLabel = "app=quack-node",
      podTemplateEnabled = false
    ),
    federation = FederationConfig(secretStore = "env"),
    auth = ManagerAuthConfig(
      management = ManagementAuthConfig(
        identitySource = "db",
        identityClaim = "preferred_username",
        // 44-char base64 = 32 raw bytes; meets HS256 min-key-length.
        sessionJwtSecret = "test-harness-jwt-secret-padding-padding-pad=",
        sessionCookieSecure = "false",
        sessionCookiePath = "/api"
      )
    )
  )

  // ------------------------------------------------------------------
  // Minimal FlightConfig: not used by the REST server at all but required
  // by the ManagerServer constructor (it surfaced in /api/config/client).
  // ------------------------------------------------------------------
  private val minimalFlightConfig = FlightConfig(
    host = "127.0.0.1",
    port = 31338,
    tlsEnabled = false,
    tlsCertChain = "",
    tlsPrivateKey = "",
    sessionTtlSec = 3600L
  )

  // ------------------------------------------------------------------
  // Stub QuackBackend: no real child processes.
  // Identical pattern to ManifestHandlersSpec.
  // ------------------------------------------------------------------
  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(
        RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21000,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
      )
    def stop(id: String)    = IO.unit
    def isAlive(id: String) = true
    def discoverExisting()  = IO.pure(Nil)
    def cleanup()           = IO.unit

  // Private alias so the existing `new InMemoryAuthSvc(...)` call site
  // below compiles without renaming everything.
  private type InMemoryAuthSvc = InMemoryAuthService.Service

  // ------------------------------------------------------------------
  // DuckDB-backed UserStore for create/update user REST endpoints.
  // Uses an in-memory DuckDB connection with a minimal qodstate_user
  // table compatible with UserStore's SQL.
  //
  // The JDBC URL "jdbc:duckdb:" creates a private in-memory database;
  // each new DriverManager.getConnection call would open a fresh DB.
  // To share state we keep a single Connection open and pass its URL
  // back.  DuckDB supports ON CONFLICT and NOW() so UserStore.upsertUser
  // compiles without changes.
  // ------------------------------------------------------------------
  private def makeDuckDbUserStore(): UserStore =
    Class.forName("org.duckdb.DuckDBDriver")
    // Open a persistent in-memory connection (the shared DB lives as
    // long as this connection is open).  We create the table then hand
    // a JDBC URL to UserStore that re-uses the same in-memory catalog
    // via the DuckDB shared-memory protocol (:memory:?...).
    //
    // Simpler approach: use a temp file-based DuckDB (auto-deleted).
    val tmpFile = java.nio.file.Files.createTempFile("qod-harness-users", ".duckdb")
    // DuckDB treats any existing file (even a zero-byte one) as an existing
    // database and rejects it if the header is missing.  Delete the empty
    // placeholder created by createTempFile so DuckDB can initialise fresh.
    tmpFile.toFile.delete()
    tmpFile.toFile.deleteOnExit()
    val jdbcUrl = s"jdbc:duckdb:${tmpFile.toAbsolutePath}"
    val c       = DriverManager.getConnection(jdbcUrl)
    try
      c.createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS qodstate_user (
          |  id            TEXT PRIMARY KEY,
          |  tenant        TEXT,
          |  username      TEXT NOT NULL,
          |  password_hash TEXT NOT NULL,
          |  role          TEXT NOT NULL DEFAULT 'user',
          |  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
        )
    finally c.close()
    new UserStore(jdbcUrl, "", "")

  // ------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------

  final case class Harness(
      baseUrl: String,
      tokens: SessionTokenStore,
      httpClient: HttpClient,
      stmtHistory: ai.starlake.quack.edge.StatementHistoryStore,
      activeRegistry: ActiveStatementRegistry,
      telemetryStore: TelemetryStore,
      shutdown: () => Unit
  ):

    /** Mint a UI session token via real POST /api/auth/login. Returns the `token` field from
      * LoginResponse. Throws on non-200.
      */
    def mintToken(
        username: String,
        password: String,
        tenant: Option[String] = None
    ): String =
      val tenantJson = tenant.fold("")(t => s""","tenant":"$t"""")
      val body       =
        s"""{"username":"$username","password":"$password"$tenantJson}"""
      val req = HttpRequest
        .newBuilder(URI.create(s"$baseUrl/api/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build()
      val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() != 200 then
        throw new RuntimeException(
          s"login failed (${resp.statusCode()}): ${resp.body()}"
        )
      // Parse the token field from JSON. Using simple string extraction
      // avoids an extra circe dep in test scope (circe is on the main
      // classpath but importing it from test scope avoids adding a new
      // import block).
      val bodyStr = resp.body()
      val tokenRe = """"token"\s*:\s*"([^"]+)"""".r
      tokenRe
        .findFirstMatchIn(bodyStr)
        .map(_.group(1))
        .getOrElse(throw new RuntimeException(s"no token in response: $bodyStr"))

  /** Boot a ManagerServer on an ephemeral port backed by the supplied
    * [[InMemoryControlPlaneStore]].
    *
    * The returned [[Harness]] holds `baseUrl`, `tokens` (for direct test introspection), and
    * `shutdown()` to stop the server.
    *
    *   - `staticApiKey` flows into [[ManagerConfig.apiKey]] so the [[ManagerServer.apiKeyGuard]]
    *     enforces it (`Some("...")` enables the static-key path; `None`, the default, leaves the
    *     REST namespace open).
    *   - `enableProviders = false` makes [[AuthenticationService.hasProviders]] report `false`,
    *     driving the `auth_disabled` 503 branch in [[AuthHandlers.login]].
    *
    * Call [[Harness.shutdown]] in `afterAll` / `afterEach` to release the port.
    */
  def boot(
      store: InMemoryControlPlaneStore,
      staticApiKey: Option[String] = None,
      enableProviders: Boolean = true,
      audit: AuditRecorder = AuditRecorder.noop,
      auditLimiter: AuditRateLimiter = new AuditRateLimiter(),
      telemetryStore: TelemetryStore = NoopTelemetryStore,
      // Snapshot existence for TagHandlers. Defaults say "no snapshot
      // exists": authz specs never get past the scope/kind gates, and
      // tag-CRUD specs override these to whitelist their fixture ids.
      tagSnapshotExists: (String, String, Long) => Boolean = (_, _, _) => false,
      tagSnapshotsExist: (String, String, Set[Long]) => Set[Long] = (_, _, _) => Set.empty,
      // Catalog browser reader; specs exercising the /api/catalog GETs
      // (e.g. asOfTag resolution, authz) pass a stub reader. The harness
      // builds the CatalogHandlers itself so the handlers share the
      // harness's supervisor (tenant resolution + scope gate), mirroring
      // how tags are wired. kindOf defaults to always-DuckLake so stub
      // readers are reached even though the fixture tenant-dbs are InMemory.
      catalogReader: Option[(String, String) => DuckLakeCatalogReader] = None,
      auditCatalogReads: Boolean = false
  ): Harness =
    val mgrCfg =
      minimalManagerConfig(port = 0).copy(apiKey = staticApiKey)
    val edgeCfg = minimalFlightConfig
    val tracker = new NodeLoadTracker
    val backend = stubBackend
    val sup     = new PoolSupervisor(backend, tracker, store)

    // Restore the in-memory store into the supervisor's caches.
    sup.restore()

    val userStore            = makeDuckDbUserStore()
    val userHandlers         = new UserHandlers(sup, userStore)
    val roleHandlers         = new RoleHandlers(sup, userHandlers)
    val groupHandlers        = new GroupHandlers(sup, userHandlers)
    val membershipHandlers   = new MembershipHandlers(sup, userHandlers)
    val poolPermHandlers     = new PoolPermissionHandlers(sup, userHandlers)
    val columnPolicyHandlers = new RoleColumnPolicyHandlers(sup)
    val rowPolicyHandlers    = new RoleRowPolicyHandlers(sup)

    val sessions     = new SessionTokenStore
    val authSvc      = new InMemoryAuthService.Service(store, providersEnabled = enableProviders)
    val authHandlers = new AuthHandlers(
      authService = authSvc,
      tokens = sessions,
      identitySource = ai.starlake.quack.ondemand.auth.ManagementIdentitySource.Db,
      grantsForIdentity = (_, _) => Nil,
      // Mirror Main: resolve per-tenant login mode from the registry so a db
      // tenant login resolves to Db instead of the no-op default's TenantNotFound.
      authModeResolver = new ai.starlake.quack.ondemand.auth.ManagementAuthModeResolver(
        id => sup.getTenantById(id),
        ai.starlake.quack.ondemand.auth.ManagementAuthMode.Db
      ),
      audit = audit
    )

    val statementStore     = new StatementHistoryStore()
    val historyHandlers    = new StatementHistoryHandlers(statementStore, sup)
    val auditHandlers      = new AuditHandlers(telemetryStore)
    val historyApiHandlers = new HistoryHandlers(telemetryStore)
    val usageHandlers      = new UsageHandlers(telemetryStore)
    val activeRegistry     = new ActiveStatementRegistry()
    val activeStmtHandlers = new ai.starlake.quack.ondemand.api.ActiveStatementHandlers(
      activeRegistry,
      statementStore,
      store,
      haEnabled = false
    )

    val pools = new PoolHandlers(sup, tracker)
    val nodes =
      new NodeHandlers(sup, tracker, store, ai.starlake.quack.ondemand.ha.StateChangePublisher.noop)
    val tenants   = new TenantHandlers(sup)
    val tenantDbs = new TenantDbHandlers(sup, federatedStore = None, catalog = None)
    val health    = new HealthHandler(sup)

    val tagHandlers = new TagHandlers(
      sup,
      store,
      snapshotExists = tagSnapshotExists,
      snapshotsExist = tagSnapshotsExist,
      audit = audit
    )

    // Catalog browser handlers over the harness's supervisor: tenant
    // resolution + TenantScopeCheck run against the fixture store, asOfTag
    // resolution against the tag handlers above. kindOf stays always-DuckLake
    // so the stub reader is reached despite InMemory fixture tenant-dbs.
    val catalogHandlers: Option[CatalogHandlers] =
      catalogReader.map { rr =>
        new CatalogHandlers(
          rr,
          sup,
          resolveAsOfTag = tagHandlers.resolveAsOf,
          audit = audit,
          auditReads = auditCatalogReads
        )
      }

    val maintenanceHandlers = new MaintenanceHandlers(sup, store, audit = audit)

    val liveConfig    = ConfigFactory.load()
    val configEntries = ConfigRegistry.collect(List.empty) // empty: no annotations to reflect
    val serverConfigHandlers = new ConfigHandlers(liveConfig, configEntries)

    val manifestHandlers = new ManifestHandlers(
      store = store,
      supervisor = sup,
      managerVersion = "test-harness",
      hostname = "localhost"
    )

    val metricsEndpoint = new MetricsEndpoint(prometheus = None, beforeScrape = () => ())

    val mgr = new ManagerServer(
      mgrCfg,
      edgeCfg,
      pools,
      nodes,
      tenants,
      tenantDbs,
      health,
      authHandlers,
      sessions,
      authEnabled = enableProviders,
      historyHandlers,
      catalog = catalogHandlers,
      tags = Some(tagHandlers),
      maintenance = Some(maintenanceHandlers),
      metricsEndpoint,
      userHandlers,
      roleHandlers,
      groupHandlers,
      membershipHandlers,
      poolPermHandlers,
      serverConfigHandlers,
      manifestHandlers,
      federatedSources = None,
      columnPolicies = columnPolicyHandlers,
      rowPolicies = rowPolicyHandlers,
      activeStmts = activeStmtHandlers,
      audit = audit,
      auditLimiter = auditLimiter,
      auditHandlers = auditHandlers,
      history = historyApiHandlers,
      usage = usageHandlers
    )

    // Bound the boot. http4s Ember on macOS occasionally stalls binding port
    // 0 under IPv4/IPv6 dual-stack confusion; without this the test suite
    // hangs indefinitely instead of failing fast.
    val (server, release) =
      try
        scala.concurrent.Await.result(
          mgr.serve.allocated.unsafeToFuture(),
          scala.concurrent.duration.FiniteDuration(15, java.util.concurrent.TimeUnit.SECONDS)
        )
      catch
        case e: java.util.concurrent.TimeoutException =>
          throw new RuntimeException(
            "ManagerServerHarness.boot: http4s server failed to bind within 15s; " +
              "see Ember logs or check for port-bind contention.",
            e
          )
    val port    = server.address.getPort
    val baseUrl = s"http://127.0.0.1:$port"

    // One HttpClient per harness so we can close its internal thread
    // pool in shutdown(). Java 21's HttpClient implements AutoCloseable
    // but the project still compiles against JDK 17's class-path API
    // where `close()` is absent -- reach it reflectively so the harness
    // works under both. We swallow any close failure so it can't mask
    // a server release failure.
    val httpClient = HttpClient.newHttpClient()

    def closeHttpClient(): Unit =
      try
        val m = httpClient.getClass.getMethod("close")
        m.invoke(httpClient)
        ()
      catch case _: Throwable => ()

    Harness(
      baseUrl = baseUrl,
      tokens = sessions,
      httpClient = httpClient,
      stmtHistory = statementStore,
      activeRegistry = activeRegistry,
      telemetryStore = telemetryStore,
      shutdown = () => {
        closeHttpClient()
        // Release the server. Ember's default shutdown drain is 30 s which
        // makes the test suite unacceptably slow. We run the release on a
        // background thread and wait at most 3 s; if it doesn't finish in
        // time we abandon the fiber (the port will be released by the OS
        // when the JVM exits, and each test uses a fresh ephemeral port).
        val future = release.unsafeToFuture()
        try
          scala.concurrent.Await.result(
            future,
            scala.concurrent.duration.FiniteDuration(3, java.util.concurrent.TimeUnit.SECONDS)
          )
        catch case _: Throwable => ()
      }
    )
