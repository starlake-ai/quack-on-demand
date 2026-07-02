package ai.starlake.quack

import ai.starlake.quack.edge._
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.auth.{
  AuthenticationService,
  OidcBearerAuthenticator,
  OidcDiscovery,
  OidcEndpointResolver,
  OidcEndpoints,
  OidcSsoService,
  OidcStateCodec
}
import ai.starlake.quack.secrets.SecretRefResolver
import ai.starlake.quack.edge.config.{
  AclConfig,
  AuthenticationConfig,
  AwsAuthConfig,
  AzureAuthConfig,
  DatabaseAuthConfig,
  GoogleAuthConfig,
  JwtAuthConfig,
  KeycloakAuthConfig
}
import ai.starlake.quack.edge.sql.{PostgresAclValidator, StatementValidator}
import ai.starlake.quack.model.Names
import ai.starlake.quack.route.{StatementClassifier, StatementClassifierConfig}
import ai.starlake.quack.observability.metrics.{
  MetricsBindings,
  MetricsConfig,
  MetricsConfigCodec,
  MetricsEndpoint,
  MetricsRegistry,
  StatementInstruments
}
import ai.starlake.quack.ondemand._
import ai.starlake.quack.ondemand.api._
import ai.starlake.quack.ondemand.bootstrap.DemoBootstrapHook
import ai.starlake.quack.ondemand.ha.{
  HaCoordinator,
  HaPreconditions,
  PgPoolLocker,
  PgStateChangePublisher,
  PoolLocker,
  StateChangePublisher
}
import ai.starlake.quack.ondemand.auth.{
  GrantsLookup,
  ManagementAuthMode,
  ManagementAuthModeResolver,
  ManagementIdentitySource
}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.federation.{
  AwsSecretsManagerResolver,
  AzureSecretsManagerResolver,
  DispatchingSecretResolver,
  EnvSecretResolver,
  FederationBlobBuilder,
  GcpSecretsManagerResolver,
  PostgresSecretResolver,
  SecretResolver,
  VaultSecretResolver
}
import ai.starlake.quack.ondemand.state.FederatedSourceStore
import ai.starlake.quack.ondemand.runtime._
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  LiquibaseRunner,
  PostgresControlPlaneStore,
  PostgresDbAdmin,
  UserStore
}
import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import pureconfig._
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

object Main extends IOApp with LazyLogging:

  // Match the camelCase keys used in application.conf rather than pureconfig's
  // default kebab-case mapping. Affects our own ManagerConfig / FlightConfig
  // AND the edge AuthenticationConfig types (which `derives ConfigReader`
  // at class-definition time - we shadow those defaults here).
  private val camelMapping: ConfigFieldMapping = ConfigFieldMapping(CamelCase, CamelCase)
  given ProductHint[K8sConfig]                 = ProductHint[K8sConfig](camelMapping)
  given ProductHint[AdminConfig]               = ProductHint[AdminConfig](camelMapping)
  given ProductHint[FederationConfig]          = ProductHint[FederationConfig](camelMapping)
  given ProductHint[ManagementOidcConfig]      = ProductHint[ManagementOidcConfig](camelMapping)
  given ProductHint[ManagementAuthConfig]      = ProductHint[ManagementAuthConfig](camelMapping)
  given ProductHint[ManagerAuthConfig]         = ProductHint[ManagerAuthConfig](camelMapping)
  given ProductHint[DefaultMetastoreConfig]    = ProductHint[DefaultMetastoreConfig](camelMapping)
  given ProductHint[HaConfig]                  = ProductHint[HaConfig](camelMapping)
  given ProductHint[ManagerConfig]             = ProductHint[ManagerConfig](camelMapping)
  given ProductHint[FlightConfig]              = ProductHint[FlightConfig](camelMapping)
  given ProductHint[DatabaseAuthConfig]        = ProductHint[DatabaseAuthConfig](camelMapping)
  given ProductHint[KeycloakAuthConfig]        = ProductHint[KeycloakAuthConfig](camelMapping)
  given ProductHint[GoogleAuthConfig]          = ProductHint[GoogleAuthConfig](camelMapping)
  given ProductHint[AzureAuthConfig]           = ProductHint[AzureAuthConfig](camelMapping)
  given ProductHint[AwsAuthConfig]             = ProductHint[AwsAuthConfig](camelMapping)
  given ProductHint[JwtAuthConfig]             = ProductHint[JwtAuthConfig](camelMapping)
  given ProductHint[AuthenticationConfig]      = ProductHint[AuthenticationConfig](camelMapping)

  given ConfigReader[K8sConfig]              = deriveReader[K8sConfig]
  given ConfigReader[AdminConfig]            = deriveReader[AdminConfig]
  given ConfigReader[FederationConfig]       = deriveReader[FederationConfig]
  given ConfigReader[ManagementOidcConfig]   = deriveReader[ManagementOidcConfig]
  given ConfigReader[ManagementAuthConfig]   = deriveReader[ManagementAuthConfig]
  given ConfigReader[ManagerAuthConfig]      = deriveReader[ManagerAuthConfig]
  given ConfigReader[DefaultMetastoreConfig] = deriveReader[DefaultMetastoreConfig]
  given ConfigReader[HaConfig]               = deriveReader[HaConfig]
  given ConfigReader[ManagerConfig]          = deriveReader[ManagerConfig]
  given ConfigReader[FlightConfig]           = deriveReader[FlightConfig]
  given ConfigReader[DatabaseAuthConfig]     = deriveReader[DatabaseAuthConfig]
  given ConfigReader[KeycloakAuthConfig]     = deriveReader[KeycloakAuthConfig]
  given ConfigReader[GoogleAuthConfig]       = deriveReader[GoogleAuthConfig]
  given ConfigReader[AzureAuthConfig]        = deriveReader[AzureAuthConfig]
  given ConfigReader[AwsAuthConfig]          = deriveReader[AwsAuthConfig]
  given ConfigReader[JwtAuthConfig]          = deriveReader[JwtAuthConfig]
  given ConfigReader[AuthenticationConfig]   = deriveReader[AuthenticationConfig]
  import MetricsConfigCodec.given

  private val DevSessionJwtSecret = "qod-dev-session-secret-rotate-in-production-x9k2v7p3m8q1"

  def run(args: List[String]): IO[ExitCode] =
    args match
      case "manifest" :: "export" :: Nil =>
        IO.blocking {
          val mgrCfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
          val store  = PostgresControlPlaneStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
          ai.starlake.quack.cli.ManifestCli.exportTo(store, System.out)
        }.map(rc => if rc == 0 then ExitCode.Success else ExitCode.Error)
      case "manifest" :: "import" :: Nil =>
        IO.blocking {
          val mgrCfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
          val store  = PostgresControlPlaneStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
          ai.starlake.quack.cli.ManifestCli.importFrom(store, System.in)
        }.map(rc => if rc == 0 then ExitCode.Success else ExitCode.Error)
      case _ =>
        normalManagerRun

  private def normalManagerRun: IO[ExitCode] =
    val source     = ConfigSource.default
    val mgrCfg     = source.at("quack-on-demand").loadOrThrow[ManagerConfig]
    val edgeCfg    = source.at("quack-flightsql").loadOrThrow[FlightConfig]
    val authCfg    = source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]
    val aclCfg     = source.at("quack-flightsql.acl").loadOrThrow[AclConfig]
    val metricsCfg = source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig]

    HaPreconditions
      .validate(
        mgrCfg.ha.enabled,
        mgrCfg.runtimeType,
        mgrCfg.auth.management.sessionJwtSecret,
        DevSessionJwtSecret
      )
      .left
      .foreach(msg => sys.error(msg))

    // AuthenticationService construction is deferred until after `sup` is built
    // so the optional per-tenant OIDC registry can read tenant authConfig rows
    // out of the supervisor.

    // Bootstrap admin users at startup so the DB auth backend has at least
    // one credential. Re-hashed on every boot: changing QOD_ADMIN_PASSWORD
    // + restart rotates. All names in QOD_ADMIN_USERNAME (comma-separated)
    // get the same password + role.
    // Apply the Liquibase changelog first: `qodstate_user` (and the rest
    // of the control plane) must exist before we upsert the admin row.
    // Idempotent: DATABASECHANGELOG records skip already-applied changesets.
    LiquibaseRunner.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap).run()
    val bootstrapUserStore = UserStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
    val admins             = mgrCfg.admin.usernameList
    if admins.isEmpty then
      logger.warn("quack-on-demand.admin.username is empty - no admin user seeded.")
    else
      admins.foreach { name =>
        // Superuser scope: tenant=NULL. The qodstate_user_scope_consistency
        // CHECK only forbids empty-string tenants now; NULL alone is fine.
        val out = bootstrapUserStore.upsertUser(
          tenant = None,
          username = name,
          plaintext = mgrCfg.admin.password,
          role = mgrCfg.admin.role
        )
        val verb = if out.inserted then "created" else "updated"
        logger.info(
          s"admin user $verb: $name (id=${out.id}, role=${mgrCfg.admin.role}) in qodstate_user"
        )
      }

    val backend: QuackBackend = mgrCfg.runtimeType.toLowerCase match
      case "local" =>
        new LocalQuackBackend(
          mgrCfg.minPort,
          mgrCfg.maxPort,
          mgrCfg.defaultMetastore.asMap,
          commandFor = LocalQuackBackend.defaultCommand(mgrCfg.spawnScript)
        )
      case "kubernetes" | "k8s" =>
        val k8s = new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()
        new KubernetesQuackBackend(
          k8s,
          mgrCfg.k8s.namespace,
          mgrCfg.k8s.image,
          mgrCfg.k8s.quackPort,
          mgrCfg.k8s.podLabel,
          mgrCfg.k8s.startupTimeoutSec,
          mgrCfg.defaultMetastore.asMap
        )
      case other => sys.error(s"unknown runtime: $other")

    // `dispatch` routes per-secret based on the row's shape (value -> Postgres,
    // externalRef prefix -> matching cloud / env / vault resolver). Other
    // values force a single backend; useful only when an operator wants to
    // hard-restrict the manager to one secret store.
    // `dispatch` keeps the stub resolvers wired so a deployment that only
    // uses postgres/env secrets still comes up; runtime errors surface
    // only for secrets that actually carry a stub-backed externalRef
    // prefix (aws-sm: / gcp-sm: / azure-kv: / vault:). Selecting a stub
    // directly is refused here because every secret resolved through it
    // would crash at handshake time -- caller's mistake should fail at
    // boot, not in production.
    val UnimplementedSingleBackends    = Set("aws-sm", "gcp-sm", "azure-kv", "vault")
    val secretResolver: SecretResolver = mgrCfg.federation.secretStore match {
      case "dispatch" | "auto" =>
        new DispatchingSecretResolver(
          postgres = new PostgresSecretResolver,
          env = new EnvSecretResolver(),
          awsSm = new AwsSecretsManagerResolver,
          gcpSm = new GcpSecretsManagerResolver,
          azureKv = new AzureSecretsManagerResolver,
          vault = new VaultSecretResolver
        )
      case "postgres"                                   => new PostgresSecretResolver
      case "env"                                        => new EnvSecretResolver()
      case s if UnimplementedSingleBackends.contains(s) =>
        sys.error(
          s"federation.secretStore = '$s' is not implemented (the resolver is a stub). " +
            "Set QOD_FEDERATION_SECRET_STORE to 'postgres' (inline secret values), " +
            "'env' (resolve \\$VARS), or 'dispatch' (route per-secret by externalRef " +
            s"prefix -- the dispatch mode keeps the $s stub wired; only sources whose " +
            s"secrets actually carry an '$s:' externalRef will fail at resolve time)."
        )
      case other => sys.error(s"unknown federation.secretStore: '$other'")
    }
    logger.info(
      s"federation: secretStore=${mgrCfg.federation.secretStore}, resolver=${secretResolver.getClass.getSimpleName}"
    )

    val tracker = new NodeLoadTracker
    logger.info("state storage: postgres (normalized qodstate_* tables via Liquibase)")
    val store: ControlPlaneStore =
      PostgresControlPlaneStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
    // Control-plane JDBC coordinates, derived once from the same keys
    // `fromDefaultMetastore` uses. HA leader election and cross-replica
    // NOTIFY (topology / RBAC / revocation) run against this database.
    val meta      = mgrCfg.defaultMetastore.asMap
    val cpJdbcUrl = s"jdbc:postgresql://${meta("pgHost")}:${meta("pgPort")}/${meta("dbName")}"
    val haOn      = mgrCfg.ha.enabled
    // Per-tenant-db Postgres provisioning. The admin connection opens
    // against the `postgres` system DB and issues CREATE/DROP DATABASE
    // for each `qodstate_tenant_db` row the supervisor manages.
    val dbAdmin = PostgresDbAdmin.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)

    // Shared FederatedSourceStore: used by federationBlobOf (resolves
    // sources + secrets into spawn SQL), by TenantDbHandlers (counts
    // sources per tenant-db for the UI), by FederatedSourceHandlers
    // (CRUD), and by ManifestHandlers (YAML round-trip).
    val manifestFedStore: Option[FederatedSourceStore] =
      val dm      = mgrCfg.defaultMetastore
      val jdbcUrl = s"jdbc:postgresql://${dm.pgHost}:${dm.pgPort}/${dm.dbName}"
      Some(new FederatedSourceStore(jdbcUrl, dm.pgUser, dm.pgPassword))

    // Federation blob lookup: resolves enabled federated sources + their
    // secrets into a ready-to-execute SQL blob for each tenant-db.
    val federationBlobOf: String => IO[Option[String]] =
      manifestFedStore match
        case Some(federatedStore) =>
          val builder = new FederationBlobBuilder(
            loadEnabled = tdId => IO.blocking(federatedStore.listEnabledSources(tdId)),
            loadSecrets = sid => IO.blocking(federatedStore.listSecrets(sid)),
            resolver = secretResolver
          )
          tdId => builder.build(tdId)
        case None =>
          _ => IO.pure(None)

    // The DuckLake catalog cache is allocated up-front (not inside the
    // catalogHandlers branch below) so we can plug its eviction callback
    // into the supervisor's onTenantDbDeleted hook. Tenant-db delete
    // (or cascaded delete via deleteTenant) calls evict, which both
    // removes the entry and closes the underlying HikariCP pool. Same
    // hook covers an operator deleting + recreating a tenant-db to
    // rotate Postgres credentials -- the new reader picks up the new
    // metastore on the next call.
    val catalogReaderCache =
      new java.util.concurrent.ConcurrentHashMap[(String, String), DuckLakeCatalogReader]()
    def evictCatalogReader(tenant: String, tenantDb: String): Unit =
      val removed = catalogReaderCache.remove((tenant, tenantDb))
      if removed != null then
        try removed.close()
        catch case _: Throwable => ()

    // HA collaborators are wired only when ha.enabled=true. With HA off they
    // stay at their process-local no-op defaults: no advisory locks are taken
    // and no NOTIFY is emitted, so single-manager mode opens no extra
    // connection and behaves exactly as before.
    val poolLocks =
      if haOn then new PgPoolLocker(cpJdbcUrl, meta("pgUser"), meta("pgPassword"))
      else PoolLocker.noop
    val publisher =
      if haOn then new PgStateChangePublisher(store) else StateChangePublisher.noop
    val sup = new PoolSupervisor(
      backend,
      tracker,
      store,
      mgrCfg.defaultMetastore.asMap,
      dbAdmin,
      federationBlobOf,
      onTenantDbDeleted = evictCatalogReader,
      locks = poolLocks,
      publish = publisher
    )

    // Per-tenant OIDC registry: each tenant on `authProvider = google` with a
    // per-tenant clientId / clientSecretRef gets its own Google
    // OidcBearerAuthenticator (substituted into the bearer chain when the
    // FlightSQL handshake resolves to that tenant). Other tenants fall back
    // to the manager-wide `quack-flightsql.auth.google` block.
    val tenantOidcRegistry = new ai.starlake.quack.edge.auth.TenantOidcRegistry(
      loadTenant = id => sup.getTenantById(id),
      secrets = ai.starlake.quack.secrets.SecretRefResolver.default,
      roleClaim = authCfg.roleClaim
    )
    val authService = new AuthenticationService(
      authCfg,
      authCfg.jwt.secretKey,
      Some(tenantOidcRegistry)
    )

    val pools     = new PoolHandlers(sup, tracker)
    val nodes     = new NodeHandlers(sup, tracker)
    val tenants   = new TenantHandlers(sup, onAuthChanged = tenantOidcRegistry.invalidate)
    val tenantDbs = new TenantDbHandlers(sup, manifestFedStore)

    val healthCache =
      new java.util.concurrent.atomic.AtomicReference[(Long, Boolean)]((0L, true))
    def dbHealthy(): Boolean =
      val (ts, ok) = healthCache.get()
      val now      = System.nanoTime()
      if now - ts < 5_000_000_000L then ok
      else
        val fresh = store.ping()
        healthCache.set((now, fresh))
        fresh

    val health = new HealthHandler(sup, dbHealthy)

    // Catalog browser handlers. Only mounted in postgres mode: the DuckLake
    // catalog tables (ducklake_schema, ducklake_table, ...) only exist in a
    // Postgres metastore. One reader per tenant, cached so we don't reopen
    // Hikari on every request. The resolver reads the effective metastore
    // (default <- tenant overrides) the same way PoolSupervisor does for
    // spawn-node.
    val catalogHandlers: Option[CatalogHandlers] =
      def reader(tenant: String, tenantDb: String): DuckLakeCatalogReader =
        catalogReaderCache.computeIfAbsent(
          (tenant, tenantDb),
          { case (t, td) => DuckLakeCatalogReader(sup.effectiveMetastoreFor(t, td)) }
        )
      def kindOf(tenant: String, tenantDb: String): Option[ai.starlake.quack.model.TenantDbKind] =
        sup.findTenantDb(tenant, tenantDb).map(_.kind)
      Some(new CatalogHandlers(reader, kindOf))

    if mgrCfg.auth.management.sessionJwtSecret == DevSessionJwtSecret then
      logger.warn(
        "USING THE DEV DEFAULT session JWT secret. Anyone with the source can forge admin " +
          "sessions on this manager. Override QOD_SESSION_JWT_SECRET before exposing the " +
          "manager beyond localhost."
      )
    val sessionTokens = new SessionTokenStore(
      secret = mgrCfg.auth.management.sessionJwtSecret,
      maxLifetime = scala.concurrent.duration.DurationInt(mgrCfg.sessionIdleTtlSec).seconds,
      onRevoke = (jti, exp) =>
        if haOn then
          store.insertRevokedJti(jti, exp)
          store.notifyListeners("qod_revocation", s"$jti|${exp.getEpochSecond}")
    )

    // Leader elector + LISTEN dispatcher. Present only under HA. Handlers close
    // over `sup` and `sessionTokens`: topology/RBAC NOTIFYs re-restore the
    // supervisor cache and reseed the revocation denylist; a revocation NOTIFY
    // adds the single jti directly (falling back to a full refresh on a
    // malformed payload).
    val coordinator = Option.when(haOn) {
      def refreshFromStore(): Unit =
        sup.restore()
        sessionTokens.seedRevoked(store.listRevokedJti())
      new HaCoordinator(
        cpJdbcUrl,
        meta("pgUser"),
        meta("pgPassword"),
        scala.concurrent.duration.DurationInt(mgrCfg.ha.leaderRetrySec).seconds,
        handlers = Map(
          "qod_topology"   -> (_ => refreshFromStore()),
          "qod_rbac"       -> (_ => refreshFromStore()),
          "qod_revocation" -> { payload =>
            payload.split('|') match
              case Array(jti, epoch) =>
                sessionTokens.addRevoked(jti, java.time.Instant.ofEpochSecond(epoch.toLong))
              case _ => refreshFromStore()
          }
        )
      )
    }
    val identitySource = ManagementIdentitySource.fromConfig(mgrCfg.auth.management.identitySource)
    // System scope (bare /ui/) login mode mirrors identitySource; per-tenant logins resolve their
    // mode from the tenant's authProvider via the resolver below.
    val systemAuthMode = identitySource match
      case ManagementIdentitySource.Oidc => ManagementAuthMode.Oidc
      case ManagementIdentitySource.Db   => ManagementAuthMode.Db
    val authModeResolver = new ManagementAuthModeResolver(
      loadTenant = id => sup.getTenantById(id),
      systemMode = systemAuthMode
    )
    val authUserStore: Option[UserStore] =
      Some(UserStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap))
    val grantsForIdentity: GrantsLookup =
      (identity, email) => authUserStore.map(_.grantsForIdentity(identity, email)).getOrElse(Nil)
    // 'auto' -> None (handler derives Secure from X-Forwarded-Proto per request); 'true' / 'false'
    // -> explicit override. Unknown values fall back to auto with a warning so a typo in the env
    // var doesn't accidentally weaken the cookie.
    val cookieSecureOverride: Option[Boolean] =
      mgrCfg.auth.management.sessionCookieSecure.trim.toLowerCase match
        case "auto"  => None
        case "true"  => Some(true)
        case "false" => Some(false)
        case other   =>
          logger.warn(
            s"QOD_SESSION_COOKIE_SECURE='$other' not recognized; expected auto|true|false. " +
              "Treating as 'auto' (derive from X-Forwarded-Proto)."
          )
          None
    // Build the admin-UI OIDC SSO service only in oidc mode. Discovery + token exchange use a shared
    // java.net.http client; id_token validation reuses OidcBearerAuthenticator against the discovered
    // jwks_uri. redirect_uri is built from the public base URL (must match the IdP client's
    // registered redirect URI).
    val oidcSso: Option[OidcSsoService] =
      if identitySource == ManagementIdentitySource.Oidc then
        val httpClient = java.net.http.HttpClient
          .newBuilder()
          .connectTimeout(java.time.Duration.ofSeconds(10))
          .build()
        val discovery = new OidcDiscovery(httpGet =
          url =>
            try
              val req = java.net.http.HttpRequest
                .newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .timeout(java.time.Duration.ofSeconds(15))
                .build()
              val resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
              if resp.statusCode() == 200 then Right(resp.body())
              else Left(s"discovery HTTP ${resp.statusCode()}")
            catch case e: Exception => Left(e.getMessage)
        )
        val resolver = new OidcEndpointResolver(
          loadTenant = id => sup.getTenantById(id),
          secrets = SecretRefResolver.default,
          discovery = discovery
        )
        val codec = new OidcStateCodec(mgrCfg.auth.management.sessionJwtSecret, 600000L)
        if mgrCfg.auth.management.publicBaseUrl.trim.isEmpty then
          logger.warn(
            "identitySource=oidc but QOD_MGMT_PUBLIC_BASE_URL is unset; OIDC redirect_uri defaults " +
              s"to http://localhost:${mgrCfg.port}, which must match the IdP client's registered " +
              "redirect URI. Set QOD_MGMT_PUBLIC_BASE_URL for any non-localhost deploy."
          )
        val publicBaseUrlOf = () =>
          val base = mgrCfg.auth.management.publicBaseUrl
          if base.trim.nonEmpty then base.trim else s"http://localhost:${mgrCfg.port}"
        val httpExchange = (url: String, form: String) =>
          try
            val req = java.net.http.HttpRequest
              .newBuilder()
              .uri(java.net.URI.create(url))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form))
              .timeout(java.time.Duration.ofSeconds(15))
              .build()
            val resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if resp.statusCode() == 200 then Right(resp.body())
            else Left(s"token endpoint HTTP ${resp.statusCode()}")
          catch case e: Exception => Left(e.getMessage)
        val buildValidator = (ep: OidcEndpoints) =>
          new OidcBearerAuthenticator(
            ep.provider,
            ep.jwksUrl,
            ep.issuer,
            ep.clientId,
            authCfg.roleClaim
          )
        Some(
          new OidcSsoService(
            resolver = resolver,
            mgmt = mgrCfg.auth.management.oidc,
            codec = codec,
            roleClaim = authCfg.roleClaim,
            publicBaseUrlOf = publicBaseUrlOf,
            httpExchange = httpExchange,
            buildValidator = buildValidator
          )
        )
      else None
    // Data-plane SQL-token flow (/api/auth/sql-token): an auth-code login against the EDGE OIDC
    // provider (not the management one) that hands a JDBC client a bearer to paste into DBeaver's
    // `token` property. None when no edge OIDC provider is enabled; handlers gate on `.enabled`.
    val sqlTokenPublicBaseUrl = () =>
      val base = mgrCfg.auth.management.publicBaseUrl
      if base.trim.nonEmpty then base.trim else s"http://localhost:${mgrCfg.port}"
    val sqlTokenSvc =
      if authCfg.keycloak.enabled || authCfg.google.enabled || authCfg.azure.enabled then
        // Discovery is fetched server-side from the in-cluster issuer, but yields the provider's
        // browser-facing authorization_endpoint (so the 302 the user follows is reachable) and the
        // back-channel token_endpoint (for the server-side code exchange).
        val sqlTokenHttp = java.net.http.HttpClient
          .newBuilder()
          .connectTimeout(java.time.Duration.ofSeconds(10))
          .build()
        val sqlTokenDiscovery = new ai.starlake.quack.edge.auth.OidcDiscovery(httpGet =
          url =>
            try
              val req = java.net.http.HttpRequest
                .newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .timeout(java.time.Duration.ofSeconds(15))
                .build()
              val resp = sqlTokenHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
              if resp.statusCode() == 200 then Right(resp.body())
              else Left(s"discovery HTTP ${resp.statusCode()}")
            catch case e: Exception => Left(e.getMessage)
        )
        Some(
          new ai.starlake.quack.edge.auth.SqlTokenOidcService(
            authCfg,
            sqlTokenPublicBaseUrl,
            mgrCfg.auth.management.sessionJwtSecret,
            sqlTokenDiscovery
          )
        )
      else None
    val authHandlers = new AuthHandlers(
      authService = authService,
      tokens = sessionTokens,
      identitySource = identitySource,
      grantsForIdentity = grantsForIdentity,
      authModeResolver = authModeResolver,
      cookieSecureOverride = cookieSecureOverride,
      cookiePath = mgrCfg.auth.management.sessionCookiePath,
      // Let operators log in with either the tenant id or its display name.
      resolveTenant = (raw: String) => sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id),
      oidc = oidcSso,
      sqlToken = sqlTokenSvc
    )
    val stmtHistory     = new ai.starlake.quack.edge.StatementHistoryStore()
    val historyHandlers = new StatementHistoryHandlers(stmtHistory, sup)
    val sessions        = new SessionRegistry
    val arrowAllocator  = new org.apache.arrow.memory.RootAllocator()
    val client          = new QuackHttpClient(
      arrowAllocator,
      nativeClient = mgrCfg.nativeClient,
      nodeDisableSsl = mgrCfg.nodeDisableSsl
    )
    val adapter = new QuackHttpAdapter(client, tracker)
    // SQL ACL validator. The RBAC-backed PostgresAclValidator reads from
    // the cached EffectiveSet pinned on ConnectionContext at handshake
    // time. acl.enabled=false falls back to allow-all for local-dev
    // workflows.
    val aclValidator: StatementValidator =
      if !aclCfg.enabled then
        logger.warn("SQL ACL disabled (set quack-flightsql.acl.enabled=true to enforce).")
        StatementValidator.allowAll
      else
        val defaultDb     = mgrCfg.defaultMetastore.dbName
        val defaultSchema =
          if mgrCfg.defaultMetastore.schemaName.nonEmpty then mgrCfg.defaultMetastore.schemaName
          else "main"
        logger.info(
          s"SQL ACL enabled (RBAC effective-set, defaultDb=$defaultDb, defaultSchema=$defaultSchema)"
        )
        new PostgresAclValidator(
          defaultDatabase = defaultDb,
          defaultSchema = defaultSchema,
          dialect = aclCfg.dialect,
          // Scope wildcard catalog grants to the session's tenant. Maps
          // qodstate_tenant.id -> the set of tenant_db.name's the tenant
          // owns; the validator's `catalogMatch` consults this to decide
          // whether `*.*.*` admits a referenced catalog. Empty set = no
          // catalog matches via wildcard (fail-closed). Explicit named
          // grants bypass this and still cross tenants on purpose.
          tenantCatalogs = tenantId =>
            sup
              .getTenantById(tenantId)
              .map(t => sup.listTenantDbsByTenant(t.id).map(_.name).toSet)
              .getOrElse(Set.empty)
        )

    // Background health probe so transient failures don't permanently mark
    // nodes unhealthy. Pings each running node with a cheap `SELECT 1` and
    // updates the tracker.
    //
    // Per-node schema init. The first time we probe a node successfully we
    // also run `CREATE SCHEMA IF NOT EXISTS <db>.<schema>` so the pool's
    // default schema is guaranteed to exist before
    // `FlightSqlRouter.wrapWithDefaultSchema` ever prepends `USE <db>.<schema>`
    // to a client query. After that first success the node-id is recorded in
    // `schemaInited` and every subsequent probe reverts to plain `SELECT 1`.
    // Self-healing if the first probe fails - not recorded -> next tick
    // retries the CREATE. Manager restart resets the map; the next first
    // probe per node re-runs the (idempotent) CREATE - at most one wasted
    // round-trip per node per restart.
    val schemaInited = new java.util.concurrent.ConcurrentHashMap[String, Unit]()
    val healthProbe  = new HealthProbe(
      tracker,
      n => {
        val initSql =
          if schemaInited.containsKey(n.nodeId) then None
          else
            sup.get(n.poolKey).flatMap { st =>
              st.metastore.get("dbName").filter(_.nonEmpty).map { db =>
                val schema = st.metastore
                  .get("schemaName")
                  .filter(_.nonEmpty)
                  .getOrElse("main")
                s"CREATE SCHEMA IF NOT EXISTS $db.$schema"
              }
            }
        val probeSql = initSql.map(s => s"$s; SELECT 1").getOrElse("SELECT 1")
        adapter.probe(n, probeSql).map { ok =>
          if ok && initSql.isDefined then schemaInited.put(n.nodeId, ())
          ok
        }
      },
      scala.concurrent.duration.DurationInt(mgrCfg.healthCheckIntervalSec).seconds
    )

    def runWithMetrics(
        metricsReg: MetricsRegistry,
        metricsEndpoint: MetricsEndpoint,
        stmtInstruments: StatementInstruments
    ): IO[Unit] =
      // Build the routing classifier from operator config. Defaults live in
      // application.conf under `quack-on-demand.statementClassifier.*`;
      // matching `QOD_CLASSIFIER_*` env vars override. Comma-separated.
      val classifierRoot =
        com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.statementClassifier")
      val classifierCfg = StatementClassifierConfig(
        select = StatementClassifierConfig.parseCsv(classifierRoot.getString("select")),
        dml = StatementClassifierConfig.parseCsv(classifierRoot.getString("dml")),
        ddl = StatementClassifierConfig.parseCsv(classifierRoot.getString("ddl")),
        begin = StatementClassifierConfig.parseCsv(classifierRoot.getString("begin")),
        commit = StatementClassifierConfig.parseCsv(classifierRoot.getString("commit")),
        rollback = StatementClassifierConfig.parseCsv(classifierRoot.getString("rollback"))
      )
      val classifier = new StatementClassifier(classifierCfg)

      val clsConfig = com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.cls")
      val clsEnabled: Boolean = clsConfig.getBoolean("enabled")
      if !clsEnabled then
        logger.info(
          "column-level security is DISABLED (quack-on-demand.cls.enabled=false). " +
            "Every statement bypasses the rewriter."
        )
      val unresolvedTableMode: ai.starlake.quack.edge.cls.UnresolvedMode =
        clsConfig.getString("unresolvedTable").toLowerCase match
          case "deny" => ai.starlake.quack.edge.cls.UnresolvedMode.Deny
          case "pass" => ai.starlake.quack.edge.cls.UnresolvedMode.Pass
          case other  =>
            logger.warn(
              s"unknown quack-on-demand.cls.unresolvedTable='$other', defaulting to pass"
            )
            ai.starlake.quack.edge.cls.UnresolvedMode.Pass

      // Resolve a DuckDB-side catalog name to its DuckLakeCatalogReader by looking up the
      // tenant-db owning that catalog and reusing the cache populated by `catalogHandlers`.
      // Returns Nil for unknown catalogs; the rewriter's unresolvedMode then decides whether
      // to deny or pass through.
      val columnCatalog: ai.starlake.quack.edge.cls.ColumnCatalog =
        new ai.starlake.quack.edge.cls.DuckLakeColumnCatalog(
          fetch = (cat, sch, tab) =>
            IO.blocking {
              sup.findTenantDbByCatalogName(cat) match
                case None     => Nil
                case Some(td) =>
                  sup.getTenantById(td.tenantId) match
                    case None    => Nil
                    case Some(t) =>
                      val reader = catalogReaderCache.computeIfAbsent(
                        (t.id, td.name),
                        { case (tn, dn) =>
                          DuckLakeCatalogReader(sup.effectiveMetastoreFor(tn, dn))
                        }
                      )
                      reader.columnNames(sch, tab)
            },
          instruments = Some(stmtInstruments)
        )
      val columnPolicyRewriter = new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        catalog = columnCatalog,
        unresolvedMode = unresolvedTableMode,
        enabled = clsEnabled
      )

      val rlsEnabled: Boolean =
        com.typesafe.config.ConfigFactory.load().getBoolean("quack-on-demand.rls.enabled")
      if !rlsEnabled then
        logger.info(
          "row-level security is DISABLED (quack-on-demand.rls.enabled=false). " +
            "Every statement bypasses the row-policy rewriter."
        )
      val rowPolicyRewriter = new ai.starlake.quack.edge.rls.RowPolicyRewriter(enabled = rlsEnabled)

      val fsRouter = new FlightSqlRouter(
        sup,
        sessions,
        tracker,
        adapter,
        aclValidator,
        stmtHistory,
        stmtInstruments,
        classifier,
        columnPolicyRewriter,
        rowPolicyRewriter
      )

      // FlightEdgeServer construction allocates Arrow's RootAllocator eagerly,
      // so defer it to IO. The explicit try/catch downgrades JVM `Error`s (e.g.
      // LinkageError when arrow-memory-* and Netty diverge) into a RuntimeException
      // - IO.attempt routes that, but treats raw `Error`s as fatal.
      val edgeIO: IO[FlightEdgeServer] = IO.delay {
        try
          val srv = new FlightEdgeServer(
            EdgeConfig(
              edgeCfg.host,
              edgeCfg.port,
              edgeCfg.tlsEnabled,
              edgeCfg.tlsCertChain,
              edgeCfg.tlsPrivateKey,
              edgeCfg.sessionTtlSec
            ),
            fsRouter,
            authService,
            (tenant, pool) =>
              sup.findPoolKeyByTenantAndPoolName(tenant, pool) match
                case None      => Left(s"pool '$pool' not found in tenant '$tenant'")
                case Some(key) =>
                  // Tenant kill switch wins over pool kill switch -- a disabled
                  // tenant should report itself, not its pool, to avoid leaking
                  // pool existence under a disabled tenant.
                  sup.getTenant(key.tenant) match
                    case Some(t) if t.disabled =>
                      Left(s"tenant '${key.tenant}' is disabled")
                    case _ =>
                      sup.get(key) match
                        case Some(s) if s.disabled =>
                          Left(s"pool '${key.pool}' in tenant '${key.tenant}' is disabled")
                        case _ =>
                          Right(key.tenantDb),
            // Accept either form of the FlightSQL `tenant` connection param.
            // Surrogate ids (`t-<8 hex>`) and display names are disjoint shapes,
            // so the `Names.looksLikeTenantId` check picks the right index.
            // FlightEdgeServer uses the result to normalize the wire value to
            // a display name (for lookupPool / authorize) AND to recover the id
            // for the Basic auth chain (which queries qodstate_user.tenant).
            raw =>
              if Names.looksLikeTenantId(raw) then sup.getTenantById(raw)
              else sup.getTenant(raw),
            // Handshake authorize: user-scope + pool-access + EffectiveSet.
            // Failures bubble up as PERMISSION_DENIED on the FlightSQL
            // handshake. JWT role + groups claims flow in from
            // FlightEdgeServer.handshake and get union-merged with the
            // user's local membership edges inside the supervisor.
            (tenant, pool, username, jwtRoles, jwtGroups) =>
              sup.authorizeHandshake(tenant, pool, username, jwtRoles, jwtGroups)
          )
          srv.start()
          srv
        catch
          case t: Throwable =>
            throw new RuntimeException(s"FlightSQL edge init failed: ${t.getMessage}", t)
      }

      // RBAC handlers wire through the supervisor + user store so persistence
      // and the in-memory RbacResolver cache stay in lockstep. The user
      // handler is built first because role / group / pool-permission
      // handlers share its DTO mappers.
      val userStoreForRbac     = UserStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
      val userHandlers         = new UserHandlers(sup, userStoreForRbac)
      val roleHandlers         = new RoleHandlers(sup, userHandlers)
      val groupHandlers        = new GroupHandlers(sup, userHandlers)
      val membershipHandlers   = new MembershipHandlers(sup, userHandlers)
      val poolPermHandlers     = new PoolPermissionHandlers(sup, userHandlers)
      val columnPolicyHandlers = new ai.starlake.quack.ondemand.api.RoleColumnPolicyHandlers(sup)
      val rowPolicyHandlers    = new ai.starlake.quack.ondemand.api.RoleRowPolicyHandlers(sup)

      // Config page registry. The roots list pairs each typed config
      // class with its HOCON prefix; the reflector pulls every
      // @ConfigField-annotated scalar (including nested case-class
      // fields). The live `Config` is the same one pureconfig drove
      // `ConfigSource.default` from, so values render with env-var
      // substitutions already applied.
      val liveConfig    = com.typesafe.config.ConfigFactory.load()
      val configEntries = ConfigRegistry.collect(
        ConfigRegistry.rootsFor(
          managerCls = classOf[ManagerConfig],
          flightCls = classOf[FlightConfig],
          authCls = classOf[AuthenticationConfig],
          aclCls = classOf[AclConfig],
          validationCls = classOf[ai.starlake.quack.edge.config.ValidationConfig],
          metricsCls = classOf[MetricsConfig]
        )
      )
      val serverConfigHandlers = new ConfigHandlers(liveConfig, configEntries)

      val federatedSourceHandlers: Option[ai.starlake.quack.ondemand.api.FederatedSourceHandlers] =
        val dm               = mgrCfg.defaultMetastore
        val jdbcUrlForFed    = s"jdbc:postgresql://${dm.pgHost}:${dm.pgPort}/${dm.dbName}"
        val fedHandlersStore = new FederatedSourceStore(jdbcUrlForFed, dm.pgUser, dm.pgPassword)
        val resolver: (String, String) => Option[String] = (tenantName, tenantDbName) =>
          sup.listTenantDbsByTenant(tenantName).find(_.name == tenantDbName).map(_.id)
        Some(
          new ai.starlake.quack.ondemand.api.FederatedSourceHandlers(fedHandlersStore, resolver)
        )

      // manifestFedStore is hoisted above the supervisor build (see top of
      // this Resource.eval block); reuse the same instance here so the
      // manifest sees the same federation table as TenantDbHandlers /
      // FederatedSourceHandlers.

      val manifestHandlers = new ai.starlake.quack.ondemand.api.ManifestHandlers(
        store = store,
        supervisor = sup,
        managerVersion = "dev",
        hostname =
          scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("unknown"),
        federatedStore = manifestFedStore
      )

      val mgr = new ManagerServer(
        mgrCfg,
        edgeCfg,
        pools,
        nodes,
        tenants,
        tenantDbs,
        health,
        authHandlers,
        sessionTokens,
        authService.hasProviders,
        historyHandlers,
        catalogHandlers,
        metricsEndpoint,
        userHandlers,
        roleHandlers,
        groupHandlers,
        membershipHandlers,
        poolPermHandlers,
        serverConfigHandlers,
        manifestHandlers,
        federatedSourceHandlers,
        columnPolicyHandlers,
        rowPolicyHandlers
      )
      // DuckLake pre-init is per-tenant-db; PoolSupervisor.createTenantDb
      // calls DuckLakeInitializer.initBlocking once the tenant-db's own
      // Postgres database has been provisioned. The control-plane
      // database holds qodstate_* tables only and must never carry
      // ducklake_* tables.
      // Order matters: the bootstrap hook may insert tenants/pools/users
      // into the store. We must run it BEFORE restore() so the supervisor's
      // in-memory cache reflects the imported state; reconcile() then sees
      // those pools and can spawn nodes. Inverting the order leaves the
      // REST/UI reading from an empty cache after a fresh boot.
      // Decide initial leadership synchronously before the boot sequence.
      // `coordinator.forall(_.isLeader)` is true when HA is off (None), so the
      // single-manager path is preserved exactly. The follower branch skips
      // bootstrap/init/discover/reconcile (the leader owns those) but still
      // restores its in-memory cache and seeds the revocation denylist.
      IO.delay(coordinator.foreach(_.tickNow())) *>
        (if coordinator.forall(_.isLeader) then
           DemoBootstrapHook.run(
             env = sys.env.get,
             readFile = path =>
               if path.startsWith("classpath:") then
                 val resource = path.stripPrefix("classpath:")
                 scala.util.Try {
                   val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
                     .getOrElse(
                       throw new java.io.FileNotFoundException(
                         s"classpath resource not found: $resource"
                       )
                     )
                   scala.util.Using.resource(stream)(s =>
                     scala.io.Source.fromInputStream(s, "UTF-8").getLines().mkString("\n")
                   )
                 }
               else
                 scala.util.Using(
                   scala.io.Source.fromFile(path)(using scala.io.Codec.UTF8)
                 )(_.getLines().mkString("\n"))
             ,
             store = store,
             fedStore = manifestFedStore
           ) *>
             IO.delay(sup.restore()) *>
             sup.ensureDuckLakeInitialized() *>
             // Latent-bug fix: repopulate the K8s per-pod token cache from the
             // qod-token-* Secrets before reconcile() adopts pods. No-op in
             // local mode (returns empty). Runs on the leader (and always in
             // single-manager mode, which takes this branch).
             backend
               .discoverExisting()
               .flatMap(found =>
                 IO.delay(logger.info(s"discovered ${found.size} pre-existing node(s)"))
               ) *>
             sup.reconcile() *>
             IO.delay(if haOn then sessionTokens.seedRevoked(store.listRevokedJti()))
         else
           IO.delay(
             logger.info("ha: booting as follower; leader owns bootstrap/init/reconcile")
           ) *>
             IO.delay(sup.restore()) *>
             IO.delay(sessionTokens.seedRevoked(store.listRevokedJti()))) *>
        mgr.serve.use { _ =>
          logger.info(
            s"manager REST on ${mgrCfg.host}:${mgrCfg.port}, " +
              s"edge FlightSQL on ${edgeCfg.host}:${edgeCfg.port}"
          )
          edgeIO.attempt.flatMap {
            case Right(edge) =>
              logger.info("edge FlightSQL started")
              // Belt-and-braces JVM shutdown hook: when the JVM is told
              // to exit (SIGTERM in containers; user Ctrl-C at the
              // terminal) we want every spawned child Quack node killed
              // before we let the process die, even if cats-effect's own
              // cancellation finalizer below has not had time to run.
              // `cleanup()` is idempotent so running it from both places
              // is safe.
              val shutdownHook = new Thread(
                { () =>
                  try edge.stop()
                  catch case _: Throwable => ()
                  try backend.cleanup().unsafeRunSync()
                  catch case _: Throwable => ()
                    // Release the leader lock + LISTEN connection. Terminal +
                    // idempotent; safe to run before the pools are drained.
                  try coordinator.foreach(_.close())
                  catch case _: Throwable => ()
                    // Drain the JDBC connection pools. Both close()s are
                    // idempotent + no-op if already closed.
                  try store.close()
                  catch case _: Throwable => ()
                  try authUserStore.foreach(_.close())
                  catch case _: Throwable => ()
                    // Close every cached catalog reader's Hikari pool. The
                    // map shouldn't see new entries past this point because
                    // serve() has already returned, but iterate defensively.
                  val it = catalogReaderCache.values.iterator()
                  while it.hasNext do
                    val r = it.next()
                    try r.close()
                    catch case _: Throwable => ()
                  catalogReaderCache.clear()
                },
                "qod-shutdown-hook"
              )
              Runtime.getRuntime.addShutdownHook(shutdownHook)

              // Graceful drain on cancellation: stop accepting new
              // FlightSQL sessions, poll the load tracker until no node
              // reports in-flight work (or `drainTimeoutSec` elapses),
              // then SIGTERM child Quack nodes via `backend.cleanup()`.
              def waitForDrain: IO[Unit] =
                val deadlineNs =
                  System.nanoTime() +
                    scala.concurrent.duration.SECONDS.toNanos(mgrCfg.drainTimeoutSec.toLong)
                def tick: IO[Unit] = IO
                  .delay {
                    tracker.snapshotAll.values.map(_.inFlight).sum
                  }
                  .flatMap { inflight =>
                    if inflight <= 0 then IO.unit
                    else if System.nanoTime() >= deadlineNs then
                      IO.delay(
                        logger.warn(
                          s"graceful shutdown: $inflight statement(s) still in-flight " +
                            s"after ${mgrCfg.drainTimeoutSec}s; proceeding"
                        )
                      )
                    else IO.sleep(scala.concurrent.duration.DurationInt(200).millis) *> tick
                  }
                tick

              val gracefulShutdown: IO[Unit] =
                IO.delay(logger.info("graceful shutdown: stopping FlightSQL edge")) *>
                  IO.delay(edge.stop()) *>
                  IO.delay(
                    logger.info(
                      s"graceful shutdown: awaiting in-flight statements (up to ${mgrCfg.drainTimeoutSec}s)"
                    )
                  ) *>
                  waitForDrain *>
                  IO.delay(logger.info("graceful shutdown: stopping child Quack nodes")) *>
                  backend.cleanup() *>
                  IO.delay(logger.info("graceful shutdown: complete"))

              // Periodic reconcile: respawn nodes that die while the manager is
              // up (boot only ran reconcile once). Disabled when the interval is
              // 0, in which case the fiber is a no-op we still cancel uniformly.
              // Under HA only the leader reconciles; the gate is `true` when HA
              // is off (coordinator None), so single-manager mode is unchanged.
              val reconcileGate: () => Boolean = () => coordinator.forall(_.isLeader)
              val reconcileFiber               =
                if mgrCfg.reconcileIntervalSec > 0 then
                  logger.info(s"periodic reconcile every ${mgrCfg.reconcileIntervalSec}s")
                  sup
                    .reconcileLoop(
                      scala.concurrent.duration.DurationInt(mgrCfg.reconcileIntervalSec).seconds,
                      reconcileGate
                    )
                    .start
                else
                  logger.info("periodic reconcile disabled (reconcileIntervalSec=0)")
                  IO.unit.start

              // Leader elector + LISTEN dispatch loop. No-op fiber when HA off.
              val coordinatorFiber = coordinator match
                case Some(c) => c.loop.start
                case None    => IO.unit.start

              // Follower/leader convergence loop: periodically re-restore the
              // supervisor cache and reseed the denylist (a safety net beyond
              // the NOTIFY handlers); the leader also purges expired jtis.
              val haRefreshFiber = coordinator match
                case Some(c) =>
                  val period =
                    scala.concurrent.duration.DurationInt(mgrCfg.ha.topologyRefreshSec).seconds
                  (IO
                    .blocking {
                      sup.restore()
                      sessionTokens.seedRevoked(store.listRevokedJti())
                      if c.isLeader then store.purgeExpiredRevokedJti(java.time.Instant.now())
                    }
                    .handleErrorWith(t =>
                      IO.delay(logger.warn(s"ha refresh: pass failed, continuing: ${t.getMessage}"))
                    ) *> IO.sleep(period)).foreverM.void.start
                case None => IO.unit.start

              healthProbe.start(() => sup.list().flatMap(_.nodes)).flatMap { fiber =>
                reconcileFiber.flatMap { rcFiber =>
                  coordinatorFiber.flatMap { coFiber =>
                    haRefreshFiber.flatMap { hrFiber =>
                      IO.never[Unit]
                        .guarantee(
                          fiber.cancel *> rcFiber.cancel *> coFiber.cancel *> hrFiber.cancel *> gracefulShutdown
                        )
                    }
                  }
                }
              }
            case Left(t) =>
              logger.error(s"edge FlightSQL failed to start: ${t.getMessage}", t)
              IO.never[Unit]
          }
        }

    val program: IO[ExitCode] =
      MetricsRegistry
        .resource(metricsCfg)
        .use { metricsReg =>
          val bindings =
            new MetricsBindings(metricsReg.composite, tracker, sessions, () => sup.list())
          val metricsEndpoint = new MetricsEndpoint(metricsReg.prometheus, () => bindings.refresh())
          val stmtInstruments = new StatementInstruments(metricsReg.composite)
          IO.delay(bindings.refresh()) *> runWithMetrics(
            metricsReg,
            metricsEndpoint,
            stmtInstruments
          )
        }
        .as(ExitCode.Success)

    program
