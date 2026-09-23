package ai.starlake.quack

import java.util.Locale
import ai.starlake.quack.edge._
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.{
  AclConfig,
  AuthenticationConfig,
  AwsAuthConfig,
  AzureAuthConfig,
  DatabaseAuthConfig,
  GoogleAuthConfig,
  JwtAuthConfig,
  KeycloakAuthConfig,
  NodeLockdownConfig
}
import ai.starlake.quack.boot.{
  BootFactories,
  BootPreflight,
  CatalogReaders,
  EdgeRewriters,
  ManagementAuthWiring
}
import ai.starlake.quack.edge.sql.StatementValidator
import ai.starlake.quack.mail.{LogMailSender, MailSender, SmtpMailSender}
import ai.starlake.quack.model.{Names, RunningNode, TenantDb}
import ai.starlake.quack.observability.metrics.{
  MaintenanceMetrics,
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
import ai.starlake.quack.ondemand.telemetry.{
  AuditRecorder,
  EventJournal,
  NoopTelemetryStore,
  PostgresTelemetryStore,
  TelemetryStore
}
import ai.starlake.quack.ondemand.ha.{
  HaCoordinator,
  HaPreconditions,
  PgPoolLocker,
  PgStateChangePublisher,
  PoolLocker,
  StateChangePublisher
}
import ai.starlake.quack.ondemand.auth.GrantsLookup
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.federation.{FederationBlobBuilder, SecretResolver}
import ai.starlake.quack.ondemand.state.FederatedSourceStore
import ai.starlake.quack.ondemand.runtime._
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  LiquibaseRunner,
  PatStore,
  PostgresControlPlaneStore,
  PostgresDbAdmin,
  UserStore
}
import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.unsafe.implicits.global
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.typesafe.scalalogging.LazyLogging
import pureconfig._
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

object Main extends IOApp with LazyLogging:

  // Match application.conf's camelCase keys instead of pureconfig's kebab-case
  // default; also shadows the `derives ConfigReader` defaults of the edge auth types.
  private val camelMapping: ConfigFieldMapping = ConfigFieldMapping(CamelCase, CamelCase)
  given ProductHint[K8sConfig]                 = ProductHint[K8sConfig](camelMapping)
  given ProductHint[AdminConfig]               = ProductHint[AdminConfig](camelMapping)
  given ProductHint[FederationConfig]          = ProductHint[FederationConfig](camelMapping)
  given ProductHint[ManagementOidcConfig]      = ProductHint[ManagementOidcConfig](camelMapping)
  given ProductHint[ManagementAuthConfig]      = ProductHint[ManagementAuthConfig](camelMapping)
  given ProductHint[LockoutConfig]             = ProductHint[LockoutConfig](camelMapping)
  given ProductHint[ManagerAuthConfig]         = ProductHint[ManagerAuthConfig](camelMapping)
  given ProductHint[DefaultMetastoreConfig]    = ProductHint[DefaultMetastoreConfig](camelMapping)
  given ProductHint[HaConfig]                  = ProductHint[HaConfig](camelMapping)
  given ProductHint[TelemetryConfig]           = ProductHint[TelemetryConfig](camelMapping)
  given ProductHint[MaintenanceConfig]         = ProductHint[MaintenanceConfig](camelMapping)
  given ProductHint[CatalogConfig]             = ProductHint[CatalogConfig](camelMapping)
  given ProductHint[RoutingConfig]             = ProductHint[RoutingConfig](camelMapping)
  given ProductHint[AutoscaleConfig]           = ProductHint[AutoscaleConfig](camelMapping)
  given ProductHint[BranchingConfig]           = ProductHint[BranchingConfig](camelMapping)
  given ProductHint[ManagedObjectStoreConfig]  = ProductHint[ManagedObjectStoreConfig](camelMapping)
  given ProductHint[EmbeddedPostgresConfig]    = ProductHint[EmbeddedPostgresConfig](camelMapping)
  given ProductHint[SmtpConfig]                = ProductHint[SmtpConfig](camelMapping)
  given ProductHint[McpConfig]                 = ProductHint[McpConfig](camelMapping)
  given ProductHint[PatConfig]                 = ProductHint[PatConfig](camelMapping)
  given ProductHint[ManagerConfig]             = ProductHint[ManagerConfig](camelMapping)
  given ProductHint[FlightConfig]              = ProductHint[FlightConfig](camelMapping)
  given ProductHint[QuackNativeConfig]         = ProductHint[QuackNativeConfig](camelMapping)
  given ProductHint[DatabaseAuthConfig]        = ProductHint[DatabaseAuthConfig](camelMapping)
  given ProductHint[KeycloakAuthConfig]        = ProductHint[KeycloakAuthConfig](camelMapping)
  given ProductHint[GoogleAuthConfig]          = ProductHint[GoogleAuthConfig](camelMapping)
  given ProductHint[AzureAuthConfig]           = ProductHint[AzureAuthConfig](camelMapping)
  given ProductHint[AwsAuthConfig]             = ProductHint[AwsAuthConfig](camelMapping)
  given ProductHint[JwtAuthConfig]             = ProductHint[JwtAuthConfig](camelMapping)
  given ProductHint[AuthenticationConfig]      = ProductHint[AuthenticationConfig](camelMapping)

  given ConfigReader[K8sConfig]                = deriveReader[K8sConfig]
  given ConfigReader[AdminConfig]              = deriveReader[AdminConfig]
  given ConfigReader[FederationConfig]         = deriveReader[FederationConfig]
  given ConfigReader[ManagementOidcConfig]     = deriveReader[ManagementOidcConfig]
  given ConfigReader[ManagementAuthConfig]     = deriveReader[ManagementAuthConfig]
  given ConfigReader[LockoutConfig]            = deriveReader[LockoutConfig]
  given ConfigReader[ManagerAuthConfig]        = deriveReader[ManagerAuthConfig]
  given ConfigReader[DefaultMetastoreConfig]   = deriveReader[DefaultMetastoreConfig]
  given ConfigReader[HaConfig]                 = deriveReader[HaConfig]
  given ConfigReader[TelemetryConfig]          = deriveReader[TelemetryConfig]
  given ConfigReader[MaintenanceConfig]        = deriveReader[MaintenanceConfig]
  given ConfigReader[CatalogConfig]            = deriveReader[CatalogConfig]
  given ConfigReader[RoutingConfig]            = deriveReader[RoutingConfig]
  given ConfigReader[AutoscaleConfig]          = deriveReader[AutoscaleConfig]
  given ConfigReader[ManagedObjectStoreConfig] = deriveReader[ManagedObjectStoreConfig]
  given ConfigReader[EmbeddedPostgresConfig]   = deriveReader[EmbeddedPostgresConfig]
  given ConfigReader[SmtpConfig]               = deriveReader[SmtpConfig]
  given ConfigReader[McpConfig]                = deriveReader[McpConfig]
  given ConfigReader[PatConfig]                = deriveReader[PatConfig]
  given ConfigReader[ManagerConfig]            = deriveReader[ManagerConfig]
  given ConfigReader[FlightConfig]             = deriveReader[FlightConfig]
  given ConfigReader[QuackNativeConfig]        = deriveReader[QuackNativeConfig]
  given ConfigReader[DatabaseAuthConfig]       = deriveReader[DatabaseAuthConfig]
  given ConfigReader[KeycloakAuthConfig]       = deriveReader[KeycloakAuthConfig]
  given ConfigReader[GoogleAuthConfig]         = deriveReader[GoogleAuthConfig]
  given ConfigReader[AzureAuthConfig]          = deriveReader[AzureAuthConfig]
  given ConfigReader[AwsAuthConfig]            = deriveReader[AwsAuthConfig]
  given ConfigReader[JwtAuthConfig]            = deriveReader[JwtAuthConfig]
  given ConfigReader[AuthenticationConfig]     = deriveReader[AuthenticationConfig]
  import MetricsConfigCodec.given

  def run(args: List[String]): IO[ExitCode] =
    // Route JUL through slf4j: grpc-netty logs via JUL directly, and without the
    // bridge its benign stream-cancel warnings print raw to stderr, unfilterable.
    org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger()
    org.slf4j.bridge.SLF4JBridgeHandler.install()
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
          ai.starlake.quack.cli.ManifestCli.importFrom(
            store,
            System.in,
            mgrCfg.requireEncryption
          )
        }.map(rc => if rc == 0 then ExitCode.Success else ExitCode.Error)
      case "demo" :: rest =>
        ai.starlake.quack.ondemand.demo.DemoRunner.runDemo(rest)
      case _ =>
        normalManagerRun

  /** Runs `boot` with the control plane's Postgres coordinates resolved.
    *
    * With `embeddedPostgres.enabled = false` (the default) this is the identity: `boot` receives
    * both configs verbatim and no server is started, so existing deployments are untouched.
    *
    * With it enabled, a persistent embedded Postgres is started first, the control-plane database
    * is ensured, and ONLY the five Postgres coordinates are projected onto `mgrCfg`. `authCfg`'s
    * `database` sub-block (`jdbcUrl`/`username`/`password`) is ALSO re-anchored to the same live
    * server: those fields are HOCON substitutions of `defaultMetastore.*` resolved at config-load
    * time, before this server exists, so left alone they would still point at the config-file
    * coordinates while `seedAdminUsers` writes the admin row into the embedded server -- the auth
    * split-brain this wrapper exists to close. See `EmbeddedControlPlane.applyAuthCoordinates` for
    * the per-key env-override rule. The server is stopped (never deleted) on every exit path via
    * `guarantee`, including a failed boot, including a failure in the control-plane database ensure
    * step.
    *
    * This is deliberately NOT `DemoConfig.overlay`: it does not touch `apiKey`, `runtimeType`,
    * `nativeClient`, TLS, or ACL, so a persistent embedded install keeps the normal secure posture.
    * `DemoConfig.overlay` remains reachable only from `DemoRunner.runDemo`.
    */
  private[quack] def withEmbeddedControlPlane(
      mgrCfg: ManagerConfig,
      authCfg: AuthenticationConfig,
      env: String => Option[String] = sys.env.get
  )(boot: (ManagerConfig, AuthenticationConfig) => IO[ExitCode]): IO[ExitCode] =
    if !mgrCfg.embeddedPostgres.enabled then boot(mgrCfg, authCfg)
    else
      IO.blocking(ai.starlake.quack.boot.EmbeddedControlPlane.start(mgrCfg.embeddedPostgres))
        .flatMap { cp =>
          (IO.blocking(cp.ensureDatabase(mgrCfg.defaultMetastore.dbName)) *>
            boot(
              ai.starlake.quack.boot.EmbeddedControlPlane.applyCoordinates(mgrCfg, cp),
              ai.starlake.quack.boot.EmbeddedControlPlane.applyAuthCoordinates(
                authCfg,
                cp,
                mgrCfg.defaultMetastore.dbName,
                env
              )
            )).guarantee(IO.blocking(cp.stop()))
        }

  private def normalManagerRun: IO[ExitCode] =
    val source      = ConfigSource.default
    val mgrCfg      = source.at("quack-on-demand").loadOrThrow[ManagerConfig]
    val edgeCfg     = source.at("quack-flightsql").loadOrThrow[FlightConfig]
    val quackCfg    = source.at("quack-native").loadOrThrow[QuackNativeConfig]
    val authCfg     = source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]
    val aclCfg      = source.at("quack-flightsql.acl").loadOrThrow[AclConfig]
    val lockdownCfg = source.at("quack-flightsql.nodeLockdown").loadOrThrow[NodeLockdownConfig]
    val metricsCfg  = source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig]
    withEmbeddedControlPlane(mgrCfg, authCfg) { (resolved, resolvedAuth) =>
      bootManager(
        resolved,
        edgeCfg,
        resolvedAuth,
        aclCfg,
        metricsCfg,
        lockdownCfg = lockdownCfg,
        modules = ai.starlake.quack.ondemand.module.ModuleLoader.discover(),
        quackCfg = Some(quackCfg)
      )
    }

  private[quack] def bootManager(
      mgrCfg0: ManagerConfig,
      edgeCfg: FlightConfig,
      authCfg: AuthenticationConfig,
      aclCfg: AclConfig,
      metricsCfg: MetricsConfig,
      lockdownCfg: NodeLockdownConfig = NodeLockdownConfig(enabled = false),
      modules: List[ai.starlake.quack.spi.ManagerModule] = Nil,
      /** The native Quack front door block; loaded from `quack-native` when the caller does not
        * pass one (demo and serve overlays set their system properties before this runs).
        */
      quackCfg: Option[QuackNativeConfig] = None
  ): IO[ExitCode] =
    val quackCfgResolved: QuackNativeConfig =
      quackCfg.getOrElse(ConfigSource.default.at("quack-native").loadOrThrow[QuackNativeConfig])
    // Unset boot secrets (session JWT secret, static API key) are generated and printed here,
    // BEFORE anything reads them. A no-op under HA, so the gate below still sees the raw empty
    // secret and refuses: per-replica random secrets cannot verify each other's sessions.
    val mgrCfg = BootPreflight.withGeneratedBootSecrets(mgrCfg0)
    HaPreconditions
      .validate(
        mgrCfg.ha.enabled,
        mgrCfg.runtimeType,
        mgrCfg.auth.management.sessionJwtSecret,
        mgrCfg.embeddedPostgres.enabled
      )
      .left
      .foreach(msg => sys.error(msg))

    TelemetryConfig
      .validate(mgrCfg.telemetry.store, mgrCfg.telemetry.stmtHistoryRetentionDays)
      .left
      .foreach(msg => sys.error(msg))

    // Lockout enabled with no SMTP relay would strand a locked-out user with no way
    // back in. Pure check, no DB/network required, so it runs before the Postgres
    // preflight below.
    BootPreflight
      .checkLockoutSmtp(mgrCfg.auth.lockout.enabled, mgrCfg.smtp.host)
      .left
      .foreach(msg => sys.error(msg))
    logger.info(
      if mgrCfg.auth.lockout.enabled then
        s"account lockout: enabled (locks after ${mgrCfg.auth.lockout.maxFailures} consecutive failed logins)"
      else "account lockout: disabled"
    )

    // Refuse to start when the control-plane Postgres is unreachable, with a clear
    // message instead of a raw JDBC stack trace from the Liquibase apply below.
    Banner.postgresPreflight(mgrCfg.defaultMetastore.asMap) match
      case Left(message) =>
        println(message)
        sys.exit(1)
      case Right(()) => ()

    LiquibaseRunner.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap).run()

    ai.starlake.quack.ondemand.module.ModuleMigrations.run(modules, mgrCfg.defaultMetastore.asMap)

    // Probe the database-auth query shape now instead of at first login. Must run AFTER the
    // Liquibase apply above.
    if authCfg.database.enabled then BootPreflight.probeAuthDatabase(authCfg.database)

    // Lockout enforces against qodstate_user in the CONTROL-PLANE database (the defaultMetastore
    // URL UserStore uses below), NOT the auth database. If an operator points QOD_AUTH_DB_JDBC_URL
    // at a different database, lockout writes hit 0 rows and isLocked is always false -- the control
    // fails open while boot claims it is enabled. Refuse that combination, then probe the
    // control-plane db (where the columns actually live) for failed_attempts/locked_at/email.
    if mgrCfg.auth.lockout.enabled then
      val dm              = mgrCfg.defaultMetastore
      val controlPlaneUrl = s"jdbc:postgresql://${dm.pgHost}:${dm.pgPort}/${dm.dbName}"
      if authCfg.database.enabled then
        BootPreflight
          .checkLockoutDbCoherence(
            lockoutEnabled = true,
            controlPlaneUrl = controlPlaneUrl,
            authUrl = authCfg.database.jdbcUrl
          )
          .left
          .foreach(msg => sys.error(msg))
      BootPreflight.probeLockoutColumns(controlPlaneUrl, dm.pgUser, dm.pgPassword)

    // One shared Hikari pool against qodstate_user; closed in the shutdown hook.
    val userStore =
      UserStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap, mgrCfg.auth.lockout)
    BootPreflight.seedAdminUsers(userStore, mgrCfg.admin)

    // Personal access tokens live next to the user rows they reference (qodstate_pat
    // FKs qodstate_user); its own small Hikari pool, closed in the shutdown hook.
    val patStore = PatStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
    // Resolves a PAT bearer to its owner's principal for /api admission. Grants are
    // row-only BY CONTRACT (PatAuthenticator's scaladoc): a PAT is bound to one
    // qodstate_user row, and an identity-keyed lookup would fold in the grants of a
    // same-named user in another tenant.
    val patAuthenticator = new ai.starlake.quack.ondemand.auth.PatAuthenticator(
      patStore,
      userById = userStore.userById,
      grantsFor = u => List(ai.starlake.quack.ondemand.state.UserGrant(u.tenant, u.role))
    )

    val backend: QuackBackend = BootFactories.quackBackend(mgrCfg)

    val secretResolver: SecretResolver =
      BootFactories.secretResolver(mgrCfg.federation.secretStore)
    logger.info(
      s"federation: secretStore=${mgrCfg.federation.secretStore}, resolver=${secretResolver.getClass.getSimpleName}"
    )

    // Task 4 wires this into the reset-link handler; log-only until QOD_SMTP_HOST is set.
    val mailSender: MailSender = mgrCfg.smtp.host.filter(_.nonEmpty) match
      case Some(_) => new SmtpMailSender(mgrCfg.smtp)
      case None    => new LogMailSender()

    val tracker            = new NodeLoadTracker
    val engineStatsTracker = new EngineStatsTracker
    logger.info("state storage: postgres (normalized qodstate_* tables via Liquibase)")
    val store: PostgresControlPlaneStore =
      PostgresControlPlaneStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
    // HA leader election and cross-replica NOTIFY run against this database.
    val meta      = mgrCfg.defaultMetastore.asMap
    val cpJdbcUrl = s"jdbc:postgresql://${meta("pgHost")}:${meta("pgPort")}/${meta("dbName")}"
    // Built early so handlers constructed before runWithMetrics can record audit
    // events; its metrics drop-counter is wired later, drops until then are silent.
    val telemetryStore: TelemetryStore = mgrCfg.telemetry.store match
      case "none" => NoopTelemetryStore
      case _      => new PostgresTelemetryStore(cpJdbcUrl, meta("pgUser"), meta("pgPassword"))
    if telemetryStore.enabled then logger.info("telemetry: postgres (qodstate_audit)")
    else logger.info("telemetry: none (audit log disabled; nothing is recorded)")
    val haOn = mgrCfg.ha.enabled
    // Opens against the `postgres` system DB to CREATE/DROP per-tenant-db databases.
    val dbAdmin = PostgresDbAdmin.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)

    // Shared by federationBlobOf, TenantDbHandlers, FederatedSourceHandlers, ManifestHandlers.
    val manifestFedStore: Option[FederatedSourceStore] =
      val dm      = mgrCfg.defaultMetastore
      val jdbcUrl = s"jdbc:postgresql://${dm.pgHost}:${dm.pgPort}/${dm.dbName}"
      Some(new FederatedSourceStore(jdbcUrl, dm.pgUser, dm.pgPassword))

    // Best-effort: reports (never fails boot on) a federated alias the naming rule now rejects,
    // so an operator learns about it on restart rather than on their next edit attempt.
    manifestFedStore.foreach(BootPreflight.checkFederatedAliases)

    // Declared here (rather than just above catalogReaders below) so federationBlobOf can also
    // close over it: both need the supervisor's tenant-db resolution but are themselves inputs to
    // the supervisor's constructor. Empty reference, filled right after the supervisor is built;
    // get() only runs at request time, never during construction.
    val supRef = new java.util.concurrent.atomic.AtomicReference[PoolSupervisor]()

    // ONE builder instance, shared by the spawn-time blob (`federationBlobOf`, what the node
    // actually runs) and the Iceberg attach verifier's re-attach (`buildOne`, what the verifier
    // re-issues onto a live node). These were two separate constructions and they drifted: the
    // verifier's copy omitted `catalogAliasOf`, so it reserved a smaller alias set than the blob
    // deployed to the node, and re-issued an ATTACH the node's own startup script had refused.
    // Sharing the instance makes that drift unrepresentable rather than merely fixed once.
    // The tenant-db's own DuckDB catalog alias. ONE definition, two consumers: the blob builder
    // reserves it so an iceberg source can't claim the name its own tenant-db is ATTACHed under,
    // and the attach verifier takes a declared alias equal to it out of the node-listing match
    // (where it would always look attached, because the tenant-db itself is attached under that
    // name). Mirrors attachedCatalogsOf's resolution below. Null-safe before supRef is filled
    // (construction order) and never throws: a lookup failure just means one fewer reserved
    // alias, not a broken blob.
    val catalogAliasOfDbId: String => IO[Option[String]] = tdId =>
      IO.delay(
        Option(supRef.get())
          .flatMap(_.getTenantDbById(tdId))
          .map(td => TenantDb.catalogAlias(td.metastore, td.name))
      )

    val federationBlobBuilder: Option[FederationBlobBuilder] =
      manifestFedStore.map { federatedStore =>
        new FederationBlobBuilder(
          loadEnabled = tdId => IO.blocking(federatedStore.listEnabledSources(tdId)),
          loadSecrets = sid => IO.blocking(federatedStore.listSecrets(sid)),
          resolver = secretResolver,
          catalogAliasOf = catalogAliasOfDbId
        )
      }

    val federationBlobOf: String => IO[Option[String]] =
      federationBlobBuilder match
        case Some(builder) => tdId => builder.build(tdId)
        case None          => _ => IO.pure(None)

    // Cached per-tenant-db DuckLake catalog readers (contract in CatalogReaders).
    // Construction cycle with `sup`: readers need the supervisor's metastore
    // resolution, the supervisor's hooks need evict. Broken via supRef, filled
    // right after the supervisor is built; get() only runs at request time.
    val catalogReaderCfg =
      com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.catalogReader")
    val catalogReaders: CatalogReaders = new CatalogReaders(
      metastoreOf = (t, td) => supRef.get().effectiveMetastoreFor(t, td),
      idleEvictMin = catalogReaderCfg.getInt("idleEvictMin").toLong,
      sweepIntervalMin = catalogReaderCfg.getInt("sweepIntervalMin").toLong
    )

    // With HA off these stay no-ops: no advisory locks, no NOTIFY, no extra connection.
    val poolLocks =
      if haOn then new PgPoolLocker(cpJdbcUrl, meta("pgUser"), meta("pgPassword"))
      else PoolLocker.noop
    val publisher =
      if haOn then new PgStateChangePublisher(store) else StateChangePublisher.noop
    val moduleEventBus = new ai.starlake.quack.ondemand.module.ModuleEventBus(modules)
    val singletonTasks = new ai.starlake.quack.ondemand.module.SingletonTasksImpl
    // Constructed before the supervisor so its teardown hook can clear a torn-down
    // pool's entries; the FlightSQL router shares the same two instances.
    val placementDirectory =
      new ai.starlake.quack.route.PlacementDirectory(mgrCfg.routing.directoryMaxTables)
    val localityTracker = new ai.starlake.quack.route.LocalityTracker()
    // In-process demand buckets for the autoscale sweep. Fed from the router's
    // StatementExecuted events; drained (and flushed to Postgres) by AutoscaleWiring
    // on every replica, since each edge only sees the statements it served.
    val poolLoadStats = new ai.starlake.quack.route.PoolLoadStats()
    // Per-pool last-activity timestamps for the hibernation sweep. Fed from the
    // router's StatementExecuted events AND the supervisor's PoolResumed events;
    // drained (and flushed to Postgres) by HibernationWiring on every replica.
    // Invariant: its sink is ONLY wired when the hibernation sweep runs, because
    // that sweep's flush is its sole drainer.
    val poolActivity = new ai.starlake.quack.route.PoolActivity()
    val supEvents    =
      if mgrCfg.hibernation.enabled then
        ai.starlake.quack.spi.ManagerEventSink.fanout(poolActivity.sink, moduleEventBus.sink)
      else moduleEventBus.sink
    val sup = new PoolSupervisor(
      backend,
      tracker,
      store,
      mgrCfg.defaultMetastore.asMap,
      dbAdmin,
      federationBlobOf,
      federatedTenantDbIds = () => manifestFedStore.map(s => s.tenantDbIdsWithSources()),
      onTenantDbDeleted = catalogReaders.evict,
      onTenantDbChanged = catalogReaders.evict,
      onPoolTeardown = key => { placementDirectory.clear(key); localityTracker.clear(key) },
      locks = poolLocks,
      publish = publisher,
      events = supEvents,
      lockdownEnabled = lockdownCfg.enabled,
      managedStore = Option.when(mgrCfg.managedObjectStore.enabled)(mgrCfg.managedObjectStore)
    )
    supRef.set(sup)

    // Tenants with their own OIDC clientId/clientSecretRef get a per-tenant
    // authenticator; others fall back to the manager-wide auth.google block.
    val tenantOidcRegistry = new ai.starlake.quack.edge.auth.TenantOidcRegistry(
      loadTenant = id => sup.getTenantById(id),
      secrets = ai.starlake.quack.secrets.SecretRefResolver.default,
      roleClaim = authCfg.roleClaim
    )
    val authService = new AuthenticationService(
      authCfg,
      authCfg.jwt.secretKey,
      Some(tenantOidcRegistry),
      lockout = mgrCfg.auth.lockout,
      lockoutStore = Some(userStore)
    )

    def catalogReader(tenant: String, tenantDb: String): DuckLakeCatalogReader =
      catalogReaders.get(tenant, tenantDb)

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

    val sessionTokens = new SessionTokenStore(
      secret = mgrCfg.auth.management.sessionJwtSecret,
      maxLifetime = scala.concurrent.duration.DurationInt(mgrCfg.sessionIdleTtlSec).seconds,
      onRevoke = (jti, exp) =>
        // Best-effort: a Postgres blip during logout must never throw out of
        // revoke(); the local in-process denylist stays authoritative.
        try
          store.insertRevokedJti(jti, exp)
          store.notifyListeners("qod_revocation", s"$jti|${exp.getEpochSecond}")
        catch
          case t: Throwable =>
            logger.warn(
              s"onRevoke: persisting/notifying revocation of jti=$jti failed " +
                s"(${t.getClass.getSimpleName}: ${t.getMessage}); local denylist still authoritative"
            )
    )

    // A PAT bearer must attribute to its real owner, not fall through to the
    // "unresolved token" static-key branch: session lookup first, PAT second.
    // patIdOf feeds pat_id on every audit.rest(apiKey, ...) call site (dozens,
    // across the REST handlers the MCP admin tools curry the raw PAT bearer
    // into) with zero call-site changes.
    val auditRecorder = new AuditRecorder(
      telemetryStore,
      sessionLookup = t => sessionTokens.get(t).orElse(patAuthenticator.sessionOf(t)),
      patIdOf = t => patAuthenticator.resolve(t).map(_.patId)
    )

    // Starlake SSO handoff ticket store. Process-local and cheap; constructed unconditionally
    // (like sessionTokens' denylist) even though the endpoints it backs are only mounted when
    // the integration is on -- see ManagementAuthConfig.slIntegrationOn. No redeem-side rate
    // limiter: the endpoint is public/unauthenticated, so a limiter keyed on the caller-supplied
    // ticket string would itself be an unbounded, attacker-fed memory-retention vector while a
    // 128-bit single-use 60s-TTL ticket is already infeasible to brute force without one.
    val ssoTicketStore = new SsoTicketStore()

    // SPI contract: moduleStart runs m.start(ctx) for every module BEFORE the
    // ManagerServer is constructed, so routes built inside start() are honored.
    val moduleCtx = ai.starlake.quack.spi.ManagerContext(
      supervisor = sup,
      users = userStore,
      controlPlaneDs = store.jdbcDataSource,
      rawConfig = com.typesafe.config.ConfigFactory.load(),
      audit = auditRecorder,
      singleton = singletonTasks,
      scopeOf = sessionTokens.scopeOf,
      sessionOf = sessionTokens.get
    )
    val moduleStart: IO[Unit] =
      modules.traverse_(m => IO(logger.info(s"module ${m.name}: starting")) *> m.start(moduleCtx))

    // Declared here (rather than beside its verifier below) so both the node and federated-source
    // REST responses can read from it: NodeInfo.catalogAttachFailures needs it right away, and
    // IcebergAttachVerifier (further down, once adapter/manifestFedStore exist) writes into this
    // same instance.
    val attachRegistry = new ai.starlake.quack.ondemand.federation.iceberg.AttachStatusRegistry()

    val pools = new PoolHandlers(
      sup,
      tracker,
      engineStatsTracker,
      mgrCfg.k8s.podTemplateEnabled,
      autoscaleHardCap = mgrCfg.autoscale.hardCap,
      audit = auditRecorder,
      attachFailuresOf = (nodeId, startedAt) =>
        attachRegistry
          .failuresFor(nodeId, startedAt.toEpochMilli)
          .map(f =>
            ai.starlake.quack.ondemand.api.CatalogAttachFailureDto(
              alias = f.alias,
              error = f.error,
              at = f.at.toString,
              attempts = f.attempts
            )
          )
    )
    val nodes   = new NodeHandlers(sup, tracker, store, publisher, audit = auditRecorder)
    val tenants = new TenantHandlers(
      sup,
      onAuthChanged = tenantOidcRegistry.invalidate,
      audit = auditRecorder
    )
    val tagHandlers: Option[ai.starlake.quack.ondemand.api.TagHandlers] = Some(
      new ai.starlake.quack.ondemand.api.TagHandlers(
        sup,
        store,
        snapshotExists = (t, td, id) => catalogReader(t, td).snapshotExists(id),
        snapshotsExist = (t, td, ids) => catalogReader(t, td).snapshotsExist(ids),
        audit = auditRecorder
      )
    )

    def tenantDbKindOf(
        tenant: String,
        tenantDb: String
    ): Option[ai.starlake.quack.model.TenantDbKind] =
      sup.findTenantDb(tenant, tenantDb).map(_.kind)

    val catalogHandlers: Option[CatalogHandlers] =
      Some(
        new CatalogHandlers(
          catalogReader,
          sup,
          store,
          tenantDbKindOf,
          audit = auditRecorder,
          auditReads = mgrCfg.catalog.auditCatalogReads
        )
      )

    val catalogHistoryHandlers: Option[ai.starlake.quack.ondemand.api.CatalogHistoryHandlers] =
      Some(
        new ai.starlake.quack.ondemand.api.CatalogHistoryHandlers(
          catalogReader,
          sup,
          tenantDbKindOf,
          audit = auditRecorder,
          auditReads = mgrCfg.catalog.auditCatalogReads
        )
      )

    if mgrCfg.requireEncryption then
      logger.info("encryption at rest is REQUIRED for new databases (QOD_REQUIRE_ENCRYPTION=true)")

    val tenantDbs = new TenantDbHandlers(
      sup,
      manifestFedStore,
      catalog = catalogHandlers,
      audit = auditRecorder,
      managedEnabled = mgrCfg.managedObjectStore.enabled,
      requireEncryption = mgrCfg.requireEncryption
    )

    // REST surface only; the scheduler + drain-loop fibers start later with the duty fibers.
    val maintenanceHandlers: Option[ai.starlake.quack.ondemand.api.MaintenanceHandlers] = Some(
      new ai.starlake.quack.ondemand.api.MaintenanceHandlers(
        sup,
        store,
        audit = auditRecorder
      )
    )

    val stmtHistory        = new ai.starlake.quack.edge.StatementHistoryStore()
    val activeStatements   = new ActiveStatementRegistry()
    val activeStmtHandlers = new ai.starlake.quack.ondemand.api.ActiveStatementHandlers(
      activeStatements,
      stmtHistory,
      store,
      haEnabled = haOn,
      audit = auditRecorder
    )

    // Leader elector + LISTEN dispatcher, present only under HA. Topology/RBAC
    // NOTIFYs re-restore the supervisor cache and reseed the revocation denylist.
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
          },
          ai.starlake.quack.ondemand.api.KillBroadcast.Channel -> (payload =>
            activeStmtHandlers.onKillBroadcast(payload)
          ),
          ai.starlake.quack.ondemand.api.PatKillBroadcast.Channel -> (payload =>
            activeStmtHandlers.onPatKillBroadcast(payload)
          )
        )
      )
    }
    // Starlake SSO integration: SL_ENABLED alone does nothing without SL_URL to hand the
    // browser off to. Warn rather than refuse to boot -- unlike the lockout/SMTP gate, a
    // misconfigured SSO integration only disables a menu link and two endpoints, not a whole
    // auth path.
    if mgrCfg.auth.management.slEnabled && mgrCfg.auth.management.slUrl.isEmpty then
      logger.warn(
        "SL_ENABLED=true but SL_URL is empty; the Starlake menu and logout callback are disabled"
      )
    // Management-plane auth wiring; component contracts in ManagementAuthWiring.
    val mgmtAuth = ManagementAuthWiring.build(
      mgrCfg,
      authCfg,
      loadTenant = id => sup.getTenantById(id)
    )
    val grantsForIdentity: GrantsLookup =
      (identity, email) => userStore.grantsForIdentity(identity, email)
    // Best-effort logout callback to Starlake, only wired when the SSO integration is on --
    // otherwise NoopStarlakeNotifier keeps logout free of any Starlake dependency.
    val starlakeNotifier: StarlakeNotifier =
      if mgrCfg.auth.management.slIntegrationOn then
        new HttpStarlakeNotifier(mgrCfg.auth.management.slUrl)
      else NoopStarlakeNotifier
    val authHandlers = new AuthHandlers(
      authService = authService,
      tokens = sessionTokens,
      identitySource = mgmtAuth.identitySource,
      grantsForIdentity = grantsForIdentity,
      authModeResolver = mgmtAuth.authModeResolver,
      cookieSecureOverride = mgmtAuth.cookieSecureOverride,
      cookiePath = mgrCfg.auth.management.sessionCookiePath,
      // Let operators log in with either the tenant id or its display name.
      resolveTenant = (raw: String) => sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id),
      oidc = mgmtAuth.oidcSso,
      sqlToken = mgmtAuth.sqlToken,
      audit = auditRecorder,
      events = moduleEventBus.sink,
      changePasswordStore = Some(userStore),
      ssoTickets = ssoTicketStore,
      starlakeNotifier = starlakeNotifier
    )

    // Public password-recovery handler. The reset token reuses the SESSION JWT
    // secret (short-lived, fingerprint-bound, distinct claims shape -> no
    // cross-use risk); one secret to manage.
    val resetTokens = new ai.starlake.quack.ondemand.api.ResetTokenStore(
      mgrCfg.auth.management.sessionJwtSecret
    )
    if mgrCfg.publicBaseUrl.trim.isEmpty then
      logger.warn(
        "QOD_PUBLIC_BASE_URL is not set: password-reset links will be host-relative " +
          "(/ui/reset-password?...). Set it to the browser-visible manager origin before " +
          "exposing password recovery behind a proxy."
      )
    val passwordResetHandlers = new ai.starlake.quack.ondemand.api.PasswordResetHandlers(
      users = userStore,
      tokens = resetTokens,
      mail = mailSender,
      // Same tenant resolution as login: id or display name -> surrogate id.
      resolveTenant = (raw: String) => sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id),
      publicBaseUrl = mgrCfg.publicBaseUrl.trim
    )
    // Self-service personal access tokens. Identity comes from the session JWT and
    // is resolved to the owning qodstate_user row id, which is what qodstate_pat
    // keys (and FKs) on.
    val patHandlers = new ai.starlake.quack.ondemand.api.PatHandlers(
      pats = patStore,
      sessions = sessionTokens,
      // The whole row, not just its id: a disabled owner must be refused at MINT
      // time too, not only when the resulting token is used.
      userOf = (tenant, username) => store.findUser(tenant, username),
      audit = auditRecorder,
      maxDepth = mgrCfg.pat.maxDepth,
      // Revocation also kills: this replica synchronously, the others via qod_pat_kill.
      killStatements = ids => activeStmtHandlers.killByPats(ids),
      broadcastKill = ids =>
        if haOn then
          ai.starlake.quack.ondemand.api.PatKillBroadcast
            .encodeBatches(ids)
            .foreach(payload =>
              store
                .notifyListeners(ai.starlake.quack.ondemand.api.PatKillBroadcast.Channel, payload)
            )
    )
    val historyHandlers    = new StatementHistoryHandlers(stmtHistory, sup)
    val auditHandlers      = new ai.starlake.quack.ondemand.api.AuditHandlers(telemetryStore)
    val historyApiHandlers = new ai.starlake.quack.ondemand.api.HistoryHandlers(telemetryStore)
    val usageHandlers      = new ai.starlake.quack.ondemand.api.UsageHandlers(telemetryStore)
    // One composed bearer lookup (session JWT first, then PAT) shared by every
    // handler that needs the caller's identity, not just its scope.
    val bearerSessionOf
        : String => Option[ai.starlake.quack.ondemand.api.SessionTokenStore.Session] =
      t => sessionTokens.get(t).orElse(patAuthenticator.sessionOf(t))
    // Self-service surface for non-admin sessions; scopes strictly to the
    // session's own (tenant, username), so it takes no tenant/user input.
    val profileHandlers = new ai.starlake.quack.ondemand.api.ProfileHandlers(
      // Composed bearer lookup, session JWT first then PAT: a PAT owner sees the
      // same self-scoped profile a login session would.
      bearerSessionOf,
      telemetryStore,
      stmtHistory,
      id => sup.getTenantById(id)
    )
    val sessions       = new SessionRegistry
    val arrowAllocator = new org.apache.arrow.memory.RootAllocator()
    val client         = new QuackHttpClient(
      arrowAllocator,
      // Degrades to the embedded HTTP client on platforms with no bundled
      // libquackwire native (Windows on ARM64) instead of crashing at JNI load.
      nativeClient = ai.starlake.quack.edge.adapter.QuackNativeSupport
        .effectiveNativeClient(mgrCfg.nativeClient),
      nodeDisableSsl = mgrCfg.nodeDisableSsl
    )
    val adapter                          = new QuackHttpAdapter(client, tracker)
    val aclValidator: StatementValidator = BootFactories.aclValidator(aclCfg, mgrCfg, sup)
    logger.info(
      s"node lockdown: ${if lockdownCfg.enabled then "enabled" else "disabled"}"
    )

    // The first successful probe of a node also runs CREATE SCHEMA IF NOT EXISTS
    // so the pool's default schema exists before wrapWithDefaultSchema ever
    // prepends `USE <db>.<schema>`. Self-healing: a failed first probe is not
    // recorded, so the next tick retries the (idempotent) CREATE.
    val schemaInited = new java.util.concurrent.ConcurrentHashMap[String, Unit]()

    // Reads one node's attached catalogs. Decoding (batch draining, schema-only first batch,
    // close-on-every-path) lives in IcebergAttachVerifier.decodeCatalogNames, which is unit
    // tested; this is wiring only. Fail-soft: any surprise becomes a Left, never an exception.
    def nodeCatalogs(n: RunningNode): IO[Either[String, Set[String]]] =
      adapter
        .send(n, "SELECT database_name FROM duckdb_databases()", session = None, recordLoad = false)
        .map {
          case QuackResponse.Failed(err, _)     => Left(err.toString)
          case QuackResponse.Ok(rows, _, close) =>
            ai.starlake.quack.ondemand.federation.iceberg.IcebergAttachVerifier
              .decodeCatalogNames(rows, close)
        }
        .handleError(t => Left(t.getMessage))

    // Both halves come from the same `manifestFedStore`, so this is all-or-nothing in practice;
    // zipping says so to the compiler instead of re-deriving a second builder here.
    val attachVerifier = manifestFedStore.zip(federationBlobBuilder).map { (fedStore, builder) =>
      new ai.starlake.quack.ondemand.federation.iceberg.IcebergAttachVerifier(
        sourcesOf = key =>
          sup.findTenantDb(key.tenant, key.tenantDb) match
            case Some(td) => IO.blocking(fedStore.listEnabledSources(td.id))
            case None     =>
              // Distinguish "lookup failed" from "nothing declared": a missing tenant-db here
              // is a cache-population race, not proof the pool has no Iceberg source, and must
              // not latch the node. IO.raiseError routes it through verify's own Left arm.
              IO.raiseError(
                new NoSuchElementException(
                  s"no tenant-db for ${key.tenant}/${key.tenantDb}"
                )
              ),
        // Same `catalogAliasOfDbId` the blob builder reserves with, so the verifier and the
        // deployed blob cannot disagree about which name belongs to the tenant-db itself. Reached
        // only after `sourcesOf` succeeded, which already required this same tenant-db lookup, so
        // the None arm here is a cache race, not a normal state.
        ownCatalogAliasOf = key =>
          sup.findTenantDb(key.tenant, key.tenantDb) match
            case Some(td) => catalogAliasOfDbId(td.id)
            case None     => IO.pure(None),
        renderOne = src => builder.buildOne(src),
        runOnNode = (n, sql) =>
          adapter.send(n, sql, session = None, recordLoad = false).map {
            case QuackResponse.Ok(_, _, close) => close(); Right(())
            case QuackResponse.Failed(err, _)  => Left(err.toString)
          },
        listCatalogs = nodeCatalogs,
        // The SAME enumeration the health probe itself ticks over (see `healthProbe.start`
        // below), so the registry is reconciled against the supervisor's own view of the fleet
        // rather than against a second list that could disagree with it.
        liveNodes = () => sup.list().flatMap(_.nodes),
        registry = attachRegistry
      )
    }

    val healthProbe = new HealthProbe(
      tracker,
      n => {
        val initSql =
          if schemaInited.containsKey(n.nodeId) then None
          else
            sup.get(n.poolKey).flatMap { st =>
              Option(TenantDb.catalogAlias(st.metastore)).filter(_.nonEmpty).map { db =>
                val schema = st.metastore
                  .get("schemaName")
                  .filter(_.nonEmpty)
                  .getOrElse("main")
                s"CREATE SCHEMA IF NOT EXISTS $db.$schema"
              }
            }
        val probeSql = initSql.map(s => s"$s; SELECT 1").getOrElse("SELECT 1")
        // tracker still holds the PREVIOUS tick's healthy flag here: HealthProbe.start only
        // calls tracker.setHealthy(n.nodeId, ok) after this pingFn IO completes, so reading it
        // now is a transition check (was healthy, now failing) without restructuring HealthProbe
        // to expose one itself.
        val wasHealthy = tracker.snapshot(n.nodeId).healthy
        adapter.probe(n, probeSql).map { ok =>
          if ok && initSql.isDefined then schemaInited.put(n.nodeId, ())
          // An encrypted database that fails its probe is most likely a key mismatch, which looks
          // exactly like the orphaned-node-port failure. Name the likely cause once per
          // healthy-to-unhealthy transition (not on every tick of a still-unhealthy node) so the
          // operator is not sent down the wrong path and a permanently key-mismatched node
          // doesn't spam the log every healthCheckIntervalSec forever.
          if !ok && wasHealthy && sup
              .get(n.poolKey)
              .exists(_.metastore.get("encrypted").contains("true"))
          then
            logger.warn(
              s"node ${n.nodeId} is unhealthy and its database is encrypted: for kind=duckdb-file " +
                "an ENCRYPTION_KEY that does not match the file fails the boot ATTACH, which " +
                "presents identically to an occupied node port"
            )
          ok
        }
      },
      scala.concurrent.duration.DurationInt(mgrCfg.healthCheckIntervalSec).seconds,
      // Piggyback the engine-stats scrape and the Iceberg attach verifier on each healthy tick;
      // both are fail-soft (HealthProbe swallows onHealthy errors, so neither can flip the health
      // flag).
      onHealthy = n =>
        adapter.engineStats(n).map(_.foreach(st => engineStatsTracker.update(n.nodeId, st))) *>
          attachVerifier.fold(IO.unit)(_.verify(n).handleErrorWith(_ => IO.unit))
    )

    def runWithMetrics(
        metricsReg: MetricsRegistry,
        metricsEndpoint: MetricsEndpoint,
        stmtInstruments: StatementInstruments
    ): IO[Unit] =
      val classifier = EdgeRewriters.statementClassifier()
      // ONE catalog instance (hence one cache) shared by the SELECT-path rewriter and the
      // write-path guard, so both resolve a table's columns identically AND the metastore fetch
      // is not doubled on cold misses.
      val columnCatalog        = EdgeRewriters.columnCatalog(sup, catalogReaders, stmtInstruments)
      val columnPolicyRewriter = EdgeRewriters.columnPolicyRewriter(columnCatalog)
      val rowPolicyRewriter    = EdgeRewriters.rowPolicyRewriter()
      val protectedWriteGuard  = EdgeRewriters.protectedWriteGuard(columnCatalog)

      // SQL admin dialect (GRANT/REVOKE, ROW/COLUMN POLICY, ALTER GROUP, admin SHOW forms)
      // answered at the FlightSQL edge. Off restores the pre-feature fail-closed denial of
      // these statements (the claim step in FlightSqlRouter.execute is simply never wired).
      val sqlAdminEnabled =
        com.typesafe.config.ConfigFactory.load().getBoolean("quack-on-demand.sqlAdmin.enabled")
      val adminExecutor =
        Option.when(sqlAdminEnabled)(
          new ai.starlake.quack.edge.admin.AdminStatementExecutor(
            sup,
            createUserFn = (tenantId, username, password, role) =>
              sup.createUser(
                tenant = Some(tenantId),
                username = username,
                password = password,
                role = role,
                userStore = userStore,
                failIfExists = true
              ),
            // Same per-(tenant, username) rotation path REST user/update uses
            // (PoolSupervisor.updateUserPassword, which clears failed_attempts /
            // locked_at as part of the password write) - resolved by username
            // since the dialect never carries a userId.
            alterPasswordFn = (tenantId, username, newPassword) =>
              IO.blocking(sup.findUser(Some(tenantId), username)).flatMap {
                case None =>
                  IO.pure(
                    Left(
                      ai.starlake.quack.ondemand.SupervisorError
                        .NotFound(s"user not found: $username")
                    )
                  )
                case Some(u) =>
                  sup
                    .updateUserPassword(u.id, Some(newPassword), None, userStore)
                    .map(_.map(_ => ()))
              },
            // enabled-only rewrite through the same updateUserPassword path REST user/update's
            // lock/unlock uses - no password, no mustChangePassword, so the credential and that
            // flag are untouched. The UserStore argument is required by the method's signature
            // but is only consulted when a password accompanies the call, so it is unused here.
            setUserEnabledFn = (tenantId, username, enabled) =>
              IO.blocking(sup.findUser(Some(tenantId), username)).flatMap {
                case None =>
                  IO.pure(
                    Left(
                      ai.starlake.quack.ondemand.SupervisorError
                        .NotFound(s"user not found: $username")
                    )
                  )
                case Some(u) =>
                  sup
                    .updateUserPassword(u.id, None, None, userStore, enabled = Some(enabled))
                    .map(_.map(_ => ()))
              },
            // Flag-only rewrite through the same updateUserPassword path, mirroring
            // setUserEnabledFn above: no password, so the needRewrite branch persists
            // mustChangePassword = true against the row's EXISTING hash rather than
            // requiring a fresh credential (PoolSupervisor.updateUserPassword's
            // needRewrite/guard were extended for exactly this call shape).
            requirePasswordChangeFn = (tenantId, username) =>
              IO.blocking(sup.findUser(Some(tenantId), username)).flatMap {
                case None =>
                  IO.pure(
                    Left(
                      ai.starlake.quack.ondemand.SupervisorError
                        .NotFound(s"user not found: $username")
                    )
                  )
                case Some(u) =>
                  sup
                    .updateUserPassword(
                      u.id,
                      None,
                      None,
                      userStore,
                      mustChangePassword = Some(true)
                    )
                    .map(_.map(_ => ()))
              },
            audit = auditRecorder
          )
        )
      if !sqlAdminEnabled then
        logger.info("SQL admin dialect is DISABLED (quack-on-demand.sqlAdmin.enabled=false)")

      val journalDropped: Int => Unit = n =>
        metricsReg.composite
          .counter("qod_journal_dropped_total", "table", "audit")
          .increment(n.toDouble)
      val journalStatementDropped: Int => Unit = n =>
        metricsReg.composite
          .counter("qod_journal_dropped_total", "table", "stmt_history")
          .increment(n.toDouble)
      val eventJournal =
        new EventJournal(
          telemetryStore,
          mgrCfg.telemetry.journalCapacity,
          onDrop = journalDropped,
          onStatementDrop = journalStatementDropped
        )
      auditRecorder.onDropCounter(journalDropped)

      // Per-pool attached-catalogs lookup for ACL resolution, cached 60s per PoolKey.
      // Disabled sources are included deliberately: their alias stays ATTACHed on
      // running nodes until the pool recycles, and excluding it would re-open the
      // two-part-name bypass. No invalidation hook by design: the set reflects what
      // MAY be attached on any node of the pool.
      val attachedCatalogsCache =
        new java.util.concurrent.ConcurrentHashMap[
          ai.starlake.quack.model.PoolKey,
          (Long, Set[String])
        ]()
      val attachedCatalogsOf: ai.starlake.quack.model.PoolKey => Set[String] = key =>
        val now    = System.currentTimeMillis()
        val cached = Option(attachedCatalogsCache.get(key)).collect {
          case (at, set) if now - at < 60000L => set
        }
        cached.getOrElse {
          val builtins = ai.starlake.quack.model.DuckDbCatalogs.Builtins
          val dbName   =
            TenantDb.catalogAlias(sup.effectiveMetastoreFor(key.tenant, key.tenantDb), key.tenantDb)
          val aliases = (sup.findTenantDb(key.tenant, key.tenantDb), manifestFedStore) match
            case (Some(td), Some(fedStore)) =>
              fedStore.listSources(td.id).map(_.alias).toSet
            case _ => Set.empty[String]
          val result = builtins + dbName ++ aliases
          attachedCatalogsCache.put(key, (now, result))
          result
        }

      // Per-pool read-only catalog lookup, cached 60s like attachedCatalogsOf. Disabled sources
      // are included for the same reason: a disabled source's alias stays ATTACHed on running
      // nodes until the pool recycles, so its read-only flag must keep applying until then.
      val readOnlyCatalogsCache =
        new java.util.concurrent.ConcurrentHashMap[
          ai.starlake.quack.model.PoolKey,
          (Long, Set[String])
        ]()
      val readOnlyCatalogsOf: ai.starlake.quack.model.PoolKey => Set[String] = key =>
        val now    = System.currentTimeMillis()
        val cached = Option(readOnlyCatalogsCache.get(key)).collect {
          case (at, set) if now - at < 60000L => set
        }
        cached.getOrElse {
          val result = (sup.findTenantDb(key.tenant, key.tenantDb), manifestFedStore) match
            case (Some(td), Some(fedStore)) =>
              // Through `FederatedAlias.readOnlySet`, not an inline map: this set is matched by
              // `CatalogWriteScreen` against refs the ACL parser lowercased with `Locale.ROOT`,
              // by exact equality, so a default-locale fold here fails OPEN on a `tr`/`az` JVM.
              ai.starlake.quack.model.FederatedAlias.readOnlySet(fedStore.listSources(td.id))
            case _ => Set.empty[String]
          readOnlyCatalogsCache.put(key, (now, result))
          result
        }

      // Mirrors attachedCatalogsOf's metastore resolution so the ACL SQL parser
      // resolves unqualified table refs the same way the validator does.
      val refsConfigFor: ai.starlake.quack.model.PoolKey => ai.starlake.acl.model.Config = key =>
        val ms = sup.effectiveMetastoreFor(key.tenant, key.tenantDb)
        ai.starlake.acl.model.Config.forDuckDB(
          Some(TenantDb.catalogAlias(ms, key.tenantDb)),
          Some(ms.getOrElse("schemaName", "main")),
          attachedCatalogsOf(key)
        )
      val routingInstruments =
        new ai.starlake.quack.observability.metrics.RoutingInstruments(metricsReg.composite)
      val routingRefsCache = new ai.starlake.quack.route.RoutingRefsCache()

      // Shared by the FlightSQL router and the native Quack front door (SessionOpened events).
      val routerEvents: ai.starlake.quack.spi.ManagerEventSink = {
        val sweepsFed = List(
          Option.when(mgrCfg.autoscale.enabled)(poolLoadStats.sink),
          Option.when(mgrCfg.hibernation.enabled)(poolActivity.sink)
        ).flatten
        if sweepsFed.isEmpty then moduleEventBus.sink
        else ai.starlake.quack.spi.ManagerEventSink.fanout((sweepsFed :+ moduleEventBus.sink)*)
      }

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
        rowPolicyRewriter,
        activeStatements,
        eventJournal,
        stampWrites = mgrCfg.stampWrites,
        attachedCatalogsOf = attachedCatalogsOf,
        readOnlyCatalogsOf = readOnlyCatalogsOf,
        // The in-process sinks go FIRST: fanout has no error isolation, so a module
        // sink that throws must not be able to starve the autoscale demand signal or
        // the hibernation activity signal. Invariant: each of poolLoadStats.sink and
        // poolActivity.sink is ONLY wired when its sweep runs, because that sweep is
        // its sole drainer -- feeding either with its sweep disabled would grow the
        // in-memory map without bound.
        events = routerEvents,
        resumeHoldTimeout =
          scala.concurrent.duration.DurationLong(edgeCfg.resumeHoldTimeoutSec).seconds,
        lockdownFor = sup.effectiveLockdown,
        // DuckLake buckets are never directly addressable from tenant SQL under lockdown:
        // every tenant-db dataPath bucket plus the managed root bucket (static config).
        deniedBuckets = () =>
          sup.duckLakeBuckets() ++
            Option
              .when(mgrCfg.managedObjectStore.enabled)(
                mgrCfg.managedObjectStore.bucket.toLowerCase(Locale.ROOT)
              )
              .toSet,
        routingRefs = routingRefsCache,
        refsConfigFor = refsConfigFor,
        locality = localityTracker,
        routingInstruments = routingInstruments,
        placement = placementDirectory,
        cacheAwareRouting = mgrCfg.routing.cacheAware,
        loadCapFactor = mgrCfg.routing.loadCapFactor,
        // Same AclConfig value BootFactories hands the validator's implicit admit: the
        // admit is only safe while the filter that narrows those rows is mounted, so the
        // two must never be able to disagree. With ACL off nothing is admitted implicitly
        // and there is no principal to filter for, hence the conjunction.
        metadataFilterRewriter = new ai.starlake.quack.edge.meta.MetadataFilterRewriter(
          enabled = aclCfg.enabled && aclCfg.filteredMetadata
        ),
        protectedWriteGuard = protectedWriteGuard,
        adminExecutor = adminExecutor
      )

      // The try/catch downgrades JVM Errors (e.g. Arrow/Netty LinkageError) into a
      // RuntimeException: IO.attempt routes that, but treats raw Errors as fatal.
      // Handshake collaborators shared by the FlightSQL edge and the native Quack front door.
      val lookupPoolForEdge: (String, String) => Either[String, String] = (tenant, pool) =>
        sup.findPoolKeyByTenantAndPoolName(tenant, pool) match
          case None      => Left(s"pool '$pool' not found in tenant '$tenant'")
          case Some(key) =>
            // Tenant kill switch wins: a disabled tenant reports itself, not
            // its pool, to avoid leaking pool existence.
            sup.getTenant(key.tenant) match
              case Some(t) if t.disabled =>
                Left(s"tenant '${key.tenant}' is disabled")
              case _ =>
                sup.get(key) match
                  case Some(s) if s.disabled =>
                    Left(s"pool '${key.pool}' in tenant '${key.tenant}' is disabled")
                  case _ =>
                    Right(key.tenantDb)
      // The FlightSQL `tenant` param may be a surrogate id or a display
      // name; the shapes are disjoint, so the check picks the right index.
      val resolveTenantForEdge: String => Option[ai.starlake.quack.model.Tenant] = raw =>
        if Names.looksLikeTenantId(raw) then sup.getTenantById(raw)
        else sup.getTenant(raw)
      // Handshake authorize; failures bubble up as PERMISSION_DENIED.
      val authorizeForEdge: (String, String, String, Set[String], Set[String], Boolean) => Either[
        String,
        ai.starlake.quack.ondemand.rbac.AuthorizedHandshake
      ] =
        (tenant, pool, username, jwtRoles, jwtGroups, superuserAdmissible) =>
          sup.authorizeHandshake(
            tenant,
            pool,
            username,
            jwtRoles,
            jwtGroups,
            superuserAdmissible
          )

      // Branch targeting seam for the edge (Epic 1). The branch service is built later in this
      // block (it needs the preview executor); the edge only consults the holder at handshake
      // time, long after boot has bound it.
      var branchLookup
          : (String, String, String) => Either[String, ai.starlake.quack.model.PoolKey] =
        (_, _, b) => Left(s"branch '$b' not found (branching not yet wired)")
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
            lookupPoolForEdge,
            resolveTenantForEdge,
            authorizeForEdge,
            // Branch targeting (Epic 1): the `branch` connection header.
            lookupBranch = (tenant, parentDb, branch) => branchLookup(tenant, parentDb, branch)
          )
          srv.start()
          srv
        catch
          case t: Throwable =>
            throw new RuntimeException(s"FlightSQL edge init failed: ${t.getMessage}", t)
      }

      // Native Quack protocol front door: same handshake collaborators and router as the Flight
      // edge, its own listener. Built here, started right after the edge below.
      val quackDoor: Option[ai.starlake.quack.edge.quack.QuackFrontDoorServer] =
        Option.when(quackCfgResolved.enabled) {
          val handshake = new ai.starlake.quack.edge.EdgeHandshake(
            authService,
            lookupPoolForEdge,
            resolveTenantForEdge,
            authorizeForEdge
          )
          val quackSessions = new ai.starlake.quack.edge.quack.QuackSessionRegistry(
            sessionTtlSec = edgeCfg.sessionTtlSec,
            maxHeartbeatSec = quackCfgResolved.maxHeartbeatTimeoutSec
          )
          val transport = new ai.starlake.quack.edge.adapter.QuackProtocol.JdkHttpTransport(
            java.net.http.HttpClient.newHttpClient()
          )
          val door = new ai.starlake.quack.edge.quack.QuackFrontDoor(
            fsRouter,
            handshake,
            quackSessions,
            transport,
            routerEvents,
            Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
          )
          new ai.starlake.quack.edge.quack.QuackFrontDoorServer(
            quackCfgResolved,
            door.handle,
            door.sweep,
            door.closeAll()
          )
        }
      // A front door bind failure aborts boot exactly like an edge init failure.
      val dataPlaneIO: IO[FlightEdgeServer] =
        edgeIO.flatTap(_ =>
          quackDoor.fold(IO.unit)(d =>
            d.start().adaptError { case t =>
              new RuntimeException(s"Quack front door init failed: ${t.getMessage}", t)
            }
          )
        )

      val userHandlers =
        new UserHandlers(sup, userStore, audit = auditRecorder, sessionOf = bearerSessionOf)
      val roleHandlers       = new RoleHandlers(sup, userHandlers, audit = auditRecorder)
      val groupHandlers      = new GroupHandlers(sup, userHandlers, audit = auditRecorder)
      val membershipHandlers = new MembershipHandlers(sup, userHandlers, audit = auditRecorder)
      val poolPermHandlers   = new PoolPermissionHandlers(sup, userHandlers, audit = auditRecorder)
      val columnPolicyHandlers =
        new ai.starlake.quack.ondemand.api.RoleColumnPolicyHandlers(sup, audit = auditRecorder)
      val rowPolicyHandlers =
        new ai.starlake.quack.ondemand.api.RoleRowPolicyHandlers(sup, audit = auditRecorder)

      // Config page registry; values render with env-var substitutions applied.
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
      val serverConfigHandlers =
        new ConfigHandlers(liveConfig, configEntries, telemetryStore.enabled)

      val federatedSourceHandlers: Option[ai.starlake.quack.ondemand.api.FederatedSourceHandlers] =
        val dm               = mgrCfg.defaultMetastore
        val jdbcUrlForFed    = s"jdbc:postgresql://${dm.pgHost}:${dm.pgPort}/${dm.dbName}"
        val fedHandlersStore = new FederatedSourceStore(jdbcUrlForFed, dm.pgUser, dm.pgPassword)
        val resolver: (String, String) => Option[String] = (tenantName, tenantDbName) =>
          sup.listTenantDbsByTenant(tenantName).find(_.name == tenantDbName).map(_.id)
        val tenantIdResolver: String => Option[String] = tenantName =>
          sup.getTenant(tenantName).map(_.id)
        // Mirrors attachedCatalogsOf's dbName resolution: the tenant-db's own DuckDB catalog
        // alias, reserved so a new iceberg_rest source cannot be aliased onto it.
        val catalogAliasOf: String => Option[String] = tenantDbId =>
          sup.getTenantDbById(tenantDbId).map { td =>
            TenantDb.catalogAlias(sup.effectiveMetastoreFor(td.tenantId, td.name), td.name)
          }
        // Aggregated across the tenant-db's pool(s): a tenant-db id resolves to zero or more live
        // PoolStates, whose nodes' incarnations are looked up in the same AttachStatusRegistry the
        // node endpoint reads (attachFailuresOf above). This is the one guarded lookup on the
        // attach-reporting path, because unlike the handler-side calls it does real work
        // (supervisor map reads plus a scan) -- and it LOGS rather than swallowing, so a lookup
        // that starts throwing cannot masquerade as a healthy catalog in silence. Both supervisor
        // calls are plain in-memory map reads, so the per-source re-derivation costs a filter over
        // the pool map; hoisting it would mean reshaping the handler's parameter, which is not
        // worth it at this cost.
        val attachStatusOf: (String, String) => Option[String] = (tenantDbId, alias) =>
          scala.util.Try {
            sup.getTenantDbById(tenantDbId) match
              case None     => None
              case Some(td) =>
                val nodeIds = sup
                  .list()
                  .filter(st => st.key.tenant == td.tenantId && st.key.tenantDb == td.name)
                  .flatMap(_.nodes.map(_.nodeId))
                  .toSet
                attachRegistry.aliasSummary(alias, nodeIds)
          } match
            case scala.util.Success(v) => v
            case scala.util.Failure(t) =>
              logger.warn(
                s"attach status lookup failed for tenant-db $tenantDbId alias '$alias': " +
                  t.getMessage
              )
              None
        Some(
          new ai.starlake.quack.ondemand.api.FederatedSourceHandlers(
            fedHandlersStore,
            resolver,
            tenantIdResolver,
            audit = auditRecorder,
            scopeOf = t => sessionTokens.scopeOf(t).orElse(patAuthenticator.scopeOf(t)),
            catalogAliasOf = catalogAliasOf,
            attachStatusOf = attachStatusOf
          )
        )

      val manifestHandlers = new ai.starlake.quack.ondemand.api.ManifestHandlers(
        store = store,
        supervisor = sup,
        managerVersion = "dev",
        hostname =
          scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("unknown"),
        federatedStore = manifestFedStore,
        audit = auditRecorder,
        requireEncryption = mgrCfg.requireEncryption
      )

      // Adapts FlightSqlRouter.execute to PreviewExecutor, mirroring the FlightSQL
      // handshake's EffectiveSet resolution: the SuperuserIdentity sentinel gets a
      // synthetic superuser EffectiveSet (NOT None, which PostgresAclValidator
      // denies fail-safe) and is never attenuated -- the sentinel is definitionally
      // TokenRestriction.Unrestricted; real sessions resolve through
      // sup.authorizeHandshake (same gate + 60s cache as the handshake), a Left
      // short-circuiting to AccessDenied before fsRouter.execute.
      // recordExecution = false for read-only probes (preview, data diff, restore
      // dry-run); true for undrop's CTAS and restore's CREATE OR REPLACE so the
      // snapshot carries the author stamp.
      // Attenuation runs AFTER the EffectiveSet is resolved -- for real sessions,
      // after PoolSupervisor's 60s-TTL cache read -- and BEFORE the router runs.
      // PoolSupervisor caches that closure per user keyed on
      // (userId, jwtRoles.hashCode, jwtGroups.hashCode), not per token, so two
      // differently-scoped tokens belonging to the same user share one cached
      // closure; each call narrows its own copy on the way out, and the narrowed
      // copy is never the thing fed back into that cache.
      // Caveat: the handler-level timeout cannot cancel the underlying IO.blocking
      // node call, so a timed-out preview may leave that call to finish unobserved
      // (bounded by previewTimeoutSec, pre-existing on the shared executor path;
      // durable fix is bracketing the connection inside QuackHttpClient). The
      // per-caller `stmtTimeoutMs` below is the same kind of bound: it is a
      // BOUNDED WAIT, not a cancellation of the statement in flight.
      def routedExecutor(
          recordExecution: Boolean
      ): ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.PreviewExecutor =
        (caller, poolKey, sql) =>
          val isSuperuser =
            caller.identity == ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.SuperuserIdentity
          val effectiveSetIO: IO[Either[ai.starlake.quack.edge.RouterFailure, Option[
            ai.starlake.quack.ondemand.rbac.EffectiveSet
          ]]] =
            // `pools` axis, enforced here on the RESOLVED pool key rather than only at the MCP
            // call sites that happen to take a `pool` argument: this is the single choke point
            // every current and future PreviewExecutor caller routes through (run_sql,
            // describe_table's sample fetch, preview, data diff, restore dry-run, undrop), so a
            // token scoped to `pools` cannot reach an out-of-scope pool merely by omitting the
            // argument and letting the caller pick one for it. `allowsPool` is `pools.forall(_
            // .contains(pool))`, so `TokenRestriction.Unrestricted` (every superuser call site,
            // and any token that never set the axis) always passes here.
            // A branch pool (Epic 1) is never named on the axis: it inherits the axis from
            // the pools of its parent tenant-db (any allowed parent pool admits the branch).
            val poolAllowed =
              if caller.restriction.allowsPool(poolKey.pool) then true
              else if ai.starlake.quack.ondemand.branch.BranchNames.isBranchPool(poolKey.pool) then
                sup
                  .findTenantDb(poolKey.tenant, poolKey.tenantDb)
                  .flatMap(_.branchOf)
                  .exists { parentId =>
                    sup
                      .list()
                      .map(_.key)
                      .filter(k =>
                        k.tenant == poolKey.tenant && sup
                          .findTenantDb(k.tenant, k.tenantDb)
                          .exists(_.id == parentId)
                      )
                      .exists(k => caller.restriction.allowsPool(k.pool))
                  }
              else false
            // branchOnly (Epic 1): writes are admitted only on a branch pool. Classified here on
            // the raw statement, the same classifier the router applies downstream.
            val writeOnMain =
              caller.restriction.branchOnly &&
                !ai.starlake.quack.ondemand.branch.BranchNames.isBranchPool(poolKey.pool) && {
                  val k = classifier.classify(sql)
                  k == ai.starlake.quack.model.StatementKind.Dml ||
                  k == ai.starlake.quack.model.StatementKind.Ddl
                }
            if !poolAllowed then
              IO.pure(
                Left(
                  ai.starlake.quack.edge.RouterFailure
                    .AccessDenied(s"pool '${poolKey.pool}' is not permitted for this token")
                )
              )
            else if writeOnMain then
              IO.pure(
                Left(
                  ai.starlake.quack.edge.RouterFailure.AccessDenied(
                    "access denied: write_requires_branch: this token may only write on a branch " +
                      "(create_branch, then pass branch=<name>)"
                  )
                )
              )
            else if isSuperuser then
              val superuser = ai.starlake.quack.ondemand.state.RbacUser(
                id = "",
                tenant = None,
                username = caller.identity,
                role = "admin"
              )
              IO.pure(
                Right(
                  Some(
                    ai.starlake.quack.ondemand.rbac
                      .EffectiveSet(superuser, Nil, Nil, Nil, Nil)
                  )
                )
              )
            else
              IO.delay(sup.authorizeHandshake(poolKey.tenant, poolKey.pool, caller.identity)).map {
                case Left(reason) =>
                  Left(ai.starlake.quack.edge.RouterFailure.AccessDenied(reason))
                case Right(authorized) => Right(Some(authorized.effectiveSet))
              }
          effectiveSetIO.flatMap {
            case Left(denied) => IO.pure(Left(denied))
            case Right(eff)   =>
              // Skip attenuation for the superuser sentinel rather than relying on
              // attenuatedBy's Unrestricted no-op path: this keeps "the synthetic
              // superuser set is never narrowed" an explicit invariant here, not
              // an incidental consequence of every superuser caller currently
              // being built with ExecCaller.unrestricted.
              val narrowed =
                if isSuperuser then eff
                else
                  eff.map(e =>
                    ai.starlake.quack.ondemand.rbac.Attenuation.attenuatedBy(e, caller.restriction)
                  )
              val run = fsRouter.execute(
                caller.connectionId,
                caller.identity,
                poolKey,
                sql,
                effectiveSet = narrowed,
                recordExecution = recordExecution,
                patId = caller.patId,
                // This closure is the single choke point every PreviewExecutor caller shares
                // (preview, data diff, restore, undrop, AND the MCP run_sql / describe_table
                // tools per the comment above). PAT attenuation narrows `narrowed` but the
                // SQL admin dialect's own authorization does not know about that ceiling, so
                // a claimed admin statement reaching this path must stay on the pre-dialect
                // routed path (fail-closed denial or normal ACL, unchanged) rather than the
                // dialect's superuser/tenant-admin check.
                adminDispatch = false
              )
              // BOUNDED WAIT, not a cancellation: past this many milliseconds the
              // caller of this executor gets a failure back, but the underlying
              // IO.blocking node call keeps running unobserved and may still
              // complete -- the same caveat as previewTimeoutSec above, and the
              // same best-effort gap FlightSqlRouter already has between register
              // and attachCancel. Durable cancellation (bracketing the connection
              // inside QuackHttpClient) is deliberately deferred to a later
              // sub-project; do not describe this branch as killing or aborting
              // the statement.
              caller.restriction.stmtTimeoutMs match
                case Some(ms) if ms > 0 =>
                  run.timeoutTo(
                    scala.concurrent.duration.FiniteDuration(
                      ms.toLong,
                      java.util.concurrent.TimeUnit.MILLISECONDS
                    ),
                    IO.pure(
                      Left(
                        ai.starlake.quack.edge.RouterFailure
                          .Unavailable(s"statement exceeded this token's ${ms}ms limit")
                      )
                    )
                  )
                case _ => run
          }

      val previewExecutor: ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.PreviewExecutor =
        routedExecutor(recordExecution = false)

      val previewHandlers: Option[ai.starlake.quack.ondemand.api.CatalogPreviewHandlers] = Some(
        new ai.starlake.quack.ondemand.api.CatalogPreviewHandlers(
          sup,
          store,
          sessionTokens.get,
          previewExecutor,
          catalogReader,
          mgrCfg.catalog,
          catalogAlias = (t, td) => TenantDb.catalogAlias(sup.effectiveMetastoreFor(t, td), td),
          audit = auditRecorder
        )
      )

      val undropHandlers: Option[ai.starlake.quack.ondemand.api.CatalogUndropHandlers] = Some(
        new ai.starlake.quack.ondemand.api.CatalogUndropHandlers(
          sup,
          routedExecutor(recordExecution = true),
          catalogReader,
          mgrCfg.catalog,
          sessionTokens.get,
          audit = auditRecorder
        )
      )

      val restoreHandlers: Option[ai.starlake.quack.ondemand.api.CatalogRestoreHandlers] = Some(
        new ai.starlake.quack.ondemand.api.CatalogRestoreHandlers(
          sup,
          store,
          routedExecutor(recordExecution = false),
          routedExecutor(recordExecution = true),
          catalogReader,
          mgrCfg.catalog,
          sessionTokens.get,
          catalogAlias = (t, td) => TenantDb.catalogAlias(sup.effectiveMetastoreFor(t, td), td),
          audit = auditRecorder
        )
      )

      // Branches (Epic 1): the lifecycle service and its REST handlers. The actor resolver
      // accepts a session JWT (UI / CLI login), a PAT (the MCP data tools curry the bearer as
      // apiKey) or nothing (the static key, already admitted by the perimeter guard).
      val branchActorOf: Option[String] => ai.starlake.quack.ondemand.branch.BranchActor = token =>
        token.flatMap(sessionTokens.get) match
          case Some(s) =>
            ai.starlake.quack.ondemand.branch.BranchActor(
              s.profile.username,
              isAdmin = s.scope.superuser || s.scope.manageableTenants.nonEmpty
            )
          case None =>
            token.flatMap(patAuthenticator.resolve) match
              case Some(p) =>
                ai.starlake.quack.ondemand.branch.BranchActor(p.user.username, isAdmin = p.isAdmin)
              case None =>
                ai.starlake.quack.ondemand.branch.BranchActor(
                  ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.SuperuserIdentity,
                  isAdmin = true
                )
      lazy val branchService: ai.starlake.quack.ondemand.branch.BranchService =
        new ai.starlake.quack.ondemand.branch.BranchService(
          cfg = mgrCfg.branching,
          sup = sup,
          store = store,
          resolveReader = catalogReader,
          cloneCatalog = (meta, parentDb, branchDb, path) =>
            ai.starlake.quack.ondemand.branch.BranchCloner(meta).clone(parentDb, branchDb, path),
          mergeExecutor =
            ai.starlake.quack.boot.BranchWiring.mergeExecutor(mgrCfg.branching, backend, adapter),
          counter = ai.starlake.quack.boot.BranchWiring.changeCounter(
            previewExecutor,
            b => branchService.poolKeyOf(b),
            scala.concurrent.duration.DurationInt(mgrCfg.catalog.previewTimeoutSec).seconds
          ),
          purgeFiles = ai.starlake.quack.boot.BranchWiring.purgeFiles,
          audit = auditRecorder,
          events = moduleEventBus.sink
        )
      branchLookup = (tenant, parentDb, branch) =>
        branchService
          .resolveTarget(tenant.toLowerCase(Locale.ROOT), parentDb, branch)
          .left
          .map(_.message)
          .map(_._2)
      val branchHandlers: Option[ai.starlake.quack.ondemand.api.BranchHandlers] =
        previewHandlers.map { ph =>
          new ai.starlake.quack.ondemand.api.BranchHandlers(
            sup,
            branchService,
            ph,
            sessionTokens.get,
            branchActorOf,
            audit = auditRecorder
          )
        }

      // MCP endpoint (POST /mcp): the agent-facing tool surface over the SAME handler
      // instances REST uses. Auth is PAT or the static key only; the run_sql path goes
      // through routedExecutor(recordExecution = true) so DuckLake snapshots carry the
      // acting user's author stamp exactly like FlightSQL writes.
      val mcpRoutes: Option[org.http4s.HttpRoutes[IO]] =
        if !mgrCfg.mcp.enabled then None
        else
          val mcpScopeOf: String => Option[ai.starlake.quack.ondemand.auth.SessionScope] =
            t => sessionTokens.scopeOf(t).orElse(patAuthenticator.scopeOf(t))
          for
            cat      <- catalogHandlers
            hist     <- catalogHistoryHandlers
            tagH     <- tagHandlers
            maint    <- maintenanceHandlers
            resto    <- restoreHandlers
            undropH2 <- undropHandlers
          yield
            val dataTools = new ai.starlake.quack.mcp.McpDataTools(
              mgrCfg.mcp,
              routedExecutor(recordExecution = true),
              sup,
              cat,
              hist,
              tagH,
              tenantDbs,
              profileHandlers,
              mcpScopeOf,
              branchTarget = (tenant, database, branch) =>
                branchService
                  .resolveTarget(tenant, database, branch)
                  .left
                  .map(_.message)
                  .map { case (b, key) => (b.tenantDbName, key) }
            )
            val branchTools =
              branchHandlers.map(bh => new ai.starlake.quack.mcp.McpBranchTools(bh, mcpScopeOf))
            val adminTools = new ai.starlake.quack.mcp.McpAdminTools(
              pools,
              nodes,
              activeStmtHandlers,
              maint,
              tagH,
              auditHandlers,
              tenantDbs,
              mcpScopeOf
            )
            val identityTools = new ai.starlake.quack.mcp.McpIdentityTools(
              tenants,
              userHandlers,
              groupHandlers,
              roleHandlers,
              membershipHandlers,
              mcpScopeOf
            )
            val accessTools = new ai.starlake.quack.mcp.McpAccessTools(
              roleHandlers,
              columnPolicyHandlers,
              rowPolicyHandlers,
              poolPermHandlers,
              mcpScopeOf
            )
            val platformTools = new ai.starlake.quack.mcp.McpPlatformTools(
              resto,
              undropH2,
              federatedSourceHandlers,
              manifestHandlers,
              patHandlers,
              serverConfigHandlers,
              historyApiHandlers,
              usageHandlers,
              mcpScopeOf
            )
            new ai.starlake.quack.mcp.McpRoutes(
              mgrCfg.mcp,
              mgrCfg.apiKey.filter(_.nonEmpty),
              patAuthenticator.resolve,
              dataTools.tools ++ branchTools.toList.flatMap(_.tools) ++ adminTools.tools ++
                identityTools.tools ++ accessTools.tools ++ platformTools.tools,
              serverVersion = "dev"
            ).routes
      if mgrCfg.mcp.enabled && mcpRoutes.isEmpty then
        logger.warn("mcp: enabled but a required handler is unwired; POST /mcp not mounted")
      else if mgrCfg.mcp.enabled then
        logger.info("mcp: serving POST /mcp (bearer auth: PAT or static API key)")
      else logger.info("mcp: disabled (QOD_MCP_ENABLED=false); POST /mcp not mounted")

      // Reads the module surfaces (endpoints / publicPathPrefixes / staticMounts),
      // so it MUST run after moduleStart; called from the IO chain below.
      def buildManagerServer(): ManagerServer = new ManagerServer(
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
        tagHandlers,
        maintenanceHandlers,
        previewHandlers,
        catalogHistoryHandlers,
        undropHandlers,
        restoreHandlers,
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
        rowPolicyHandlers,
        activeStmtHandlers,
        audit = auditRecorder,
        auditLimiter = new ai.starlake.quack.ondemand.telemetry.AuditRateLimiter(),
        auditHandlers = auditHandlers,
        history = historyApiHandlers,
        usage = usageHandlers,
        profile = profileHandlers,
        moduleEndpoints = modules.flatMap(_.endpoints),
        modulePublicPrefixes = modules.flatMap(_.publicPathPrefixes).toSet,
        moduleStaticMounts = modules.flatMap(_.staticMounts),
        passwordReset = Some(passwordResetHandlers),
        pat = Some(patHandlers),
        branches = branchHandlers,
        patAuth = Some(patAuthenticator),
        scim = Some(
          new ai.starlake.quack.ondemand.api.ScimHandlers(sup, userStore, auditRecorder)
        ),
        canonicalTenantIdOf = t =>
          ai.starlake.quack.ondemand.api.HandlerResolvers.resolveTenantId(sup, t),
        mcpRoutes = mcpRoutes,
        quackCfg = Some(quackCfgResolved)
      )
      // One managed-object-store client for both the boot probe below and the purge
      // worker further down. Constructed unconditionally: the SDK client it wraps is
      // lazy, so nothing is touched while managed storage is off.
      val managedStoreClient =
        new ai.starlake.quack.ondemand.storage.S3ManagedStoreClient(mgrCfg.managedObjectStore)

      // Leader-only boot duties. Ordering: the bootstrap hook runs BEFORE restore()
      // so the supervisor cache reflects imported state and reconcile() can spawn
      // those pools; inverting leaves the REST/UI on an empty cache after boot.
      // Extracted so they run either at boot or later on promotion (haRefreshFiber
      // tick); leaderDutiesDone guards against running twice.
      val leaderDutiesDone       = new java.util.concurrent.atomic.AtomicBoolean(false)
      def leaderDuties: IO[Unit] =
        DemoBootstrapHook.run(
          env = k => sys.env.get(k).orElse(sys.props.get(k)),
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
          fedStore = manifestFedStore,
          requireEncryption = mgrCfg.requireEncryption
        ) *>
          IO.delay(sup.restore()) *>
          sup.ensureDuckLakeInitialized() *>
          // Repopulate the K8s per-pod token cache from the qod-token-* Secrets
          // before reconcile() adopts pods; no-op in local mode.
          backend
            .discoverExisting()
            .flatMap(found =>
              IO.delay(logger.info(s"discovered ${found.size} pre-existing node(s)"))
            ) *>
          sup.reconcile() *>
          IO.delay(sessionTokens.seedRevoked(store.listRevokedJti()))

      IO.delay(coordinator.foreach(_.tickNow())) *>
        (if coordinator.forall(_.isLeader) then leaderDuties *> IO.delay(leaderDutiesDone.set(true))
         else
           IO.delay(
             logger.info("ha: booting as follower; leader owns bootstrap/init/reconcile")
           ) *>
             IO.delay(sup.restore()) *>
             IO.delay(sessionTokens.seedRevoked(store.listRevokedJti()))) *>
        // One-shot purge at boot: single-manager mode never runs the HA leader's
        // periodic purge of expired denylist rows.
        IO.delay(store.purgeExpiredRevokedJti(java.time.Instant.now())) *>
        // Managed object store reachability probe. Advisory only: an unreachable or
        // mis-credentialed bucket must never stop the manager from booting. It does
        // not gate managed tenant-db creates either - those still succeed at the
        // control plane; the failure only surfaces later, when a node tries to
        // ATTACH against the missing bucket.
        (if !mgrCfg.managedObjectStore.enabled then IO.unit
         else
           IO.blocking(managedStoreClient.ensureBucket()).attempt.map {
             case Right(Right(())) =>
               logger.info(
                 s"managed object store ready: bucket '${mgrCfg.managedObjectStore.bucket}'"
               )
             case Right(Left(err)) =>
               logger.warn(
                 s"managed object store unreachable: managed creates still succeed " +
                   s"at the control plane, but their nodes will fail to ATTACH until " +
                   s"the store recovers: $err"
               )
             case Left(t) =>
               logger.warn(
                 s"managed object store unreachable: managed creates still succeed " +
                   s"at the control plane, but their nodes will fail to ATTACH until " +
                   s"the store recovers: " +
                   Option(t.getMessage).getOrElse(t.toString)
               )
           }) *>
        moduleStart *>
        // Modules may build their MutationGates only inside start(), so this reads
        // them strictly after moduleStart and before the server binds. The IO.defer
        // is load-bearing: without it the modules.flatMap(_.mutationGates) argument
        // evaluates when this IO chain is CONSTRUCTED (before anything runs), captures
        // the pre-start empty list, and every gate silently never registers - live
        // enforcement off while module suites (which wire gates directly) stay green.
        IO.defer(sup.setMutationGates(modules.flatMap(_.mutationGates))) *>
        IO.delay(buildManagerServer()).flatMap { mgr =>
          mgr.serve.use { _ =>
            logger.info(
              s"manager REST on ${mgrCfg.host}:${mgrCfg.port}, " +
                s"edge FlightSQL on ${edgeCfg.host}:${edgeCfg.port}"
            )
            dataPlaneIO.attempt.flatMap {
              case Right(edge) =>
                logger.info("edge FlightSQL started")
                quackDoor.foreach(_ => logger.info("Quack front door started"))
                // Stdout banner (default log level is ERROR), once both listeners are up.
                println(
                  Banner.startup(
                    mgrCfg.defaultMetastore.asMap,
                    mgrCfg.host,
                    mgrCfg.port,
                    edgeCfg.host,
                    edgeCfg.port,
                    edgeCfg.tlsEnabled,
                    quack = quackDoor.map(_ =>
                      (quackCfgResolved.host, quackCfgResolved.port, quackCfgResolved.tlsEnabled)
                    ),
                    aclEnabled = aclCfg.enabled
                  )
                )
                val shutdownCoordinator = new ai.starlake.quack.boot.ShutdownCoordinator(
                  edge = edge,
                  backend = backend,
                  quackFrontDoor = quackDoor,
                  coordinator = coordinator,
                  eventJournal = eventJournal,
                  telemetryStore = telemetryStore,
                  store = store,
                  userStore = userStore,
                  patStore = patStore,
                  catalogReaders = catalogReaders,
                  tracker = tracker,
                  modules = modules,
                  moduleEventBus = moduleEventBus,
                  drainTimeoutSec = mgrCfg.drainTimeoutSec
                )
                shutdownCoordinator.installJvmHook()
                val gracefulShutdown: IO[Unit] = shutdownCoordinator.gracefulShutdown

                // Respawn nodes that die while the manager is up; under HA only the
                // leader reconciles (the gate is true when HA is off).
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

                val maintenanceWiring = new ai.starlake.quack.boot.MaintenanceWiring(
                  store = store,
                  sup = sup,
                  backend = backend,
                  adapter = adapter,
                  poolLocks = poolLocks,
                  catalogReader = catalogReader,
                  maintenance = mgrCfg.maintenance,
                  isLeader = () => coordinator.forall(_.isLeader),
                  audit = auditRecorder,
                  metrics = new MaintenanceMetrics.Micrometer(metricsReg.composite)
                )
                val maintenanceSchedulerFiber = maintenanceWiring.schedulerFiber
                val maintenanceDrainFiber     = maintenanceWiring.drainFiber

                val autoscaleWiring = new ai.starlake.quack.boot.AutoscaleWiring(
                  cfg = mgrCfg.autoscale,
                  views = () =>
                    ai.starlake.quack.boot.AutoscaleWiringSupport
                      .views(sup, store, mgrCfg.autoscale),
                  flushLocal = () =>
                    poolLoadStats.drainClosed().foreach { case ((key, bucketMs), s) =>
                      sup
                        .poolId(key)
                        .foreach(id =>
                          store.addPoolLoad(
                            id,
                            java.time.Instant.ofEpochMilli(bucketMs),
                            s.statements,
                            s.totalDurationMs
                          )
                        )
                    },
                  purge =
                    () => store.purgePoolLoad(java.time.Instant.now().minusSeconds(3600)): Unit,
                  // Both directions are the same supervisor call: scale() takes the
                  // target size and the new distribution the decision core computed.
                  // force = false keeps the quota gate and the pool lock in play, so a
                  // rejected scale surfaces as a Left the wiring counts as a failure.
                  scale = { a =>
                    val (k, t, d) = a match
                      case ai.starlake.quack.ondemand.autoscale.AutoscaleAction
                            .ScaleOut(k, t, d) =>
                        (k, t, d)
                      case ai.starlake.quack.ondemand.autoscale.AutoscaleAction.ScaleIn(k, t, d) =>
                        (k, t, d)
                    sup.scale(k, t, d, force = false, reason = "autoscale").attempt.map {
                      case Right(_) => Right(())
                      case Left(e)  => Left(Option(e.getMessage).getOrElse(e.toString))
                    }
                  },
                  isLeader = () => coordinator.forall(_.isLeader),
                  audit = auditRecorder
                )
                val autoscaleFiber = autoscaleWiring.fiber

                val hibernationWiring = new ai.starlake.quack.boot.HibernationWiring(
                  cfg = mgrCfg.hibernation,
                  views = () =>
                    ai.starlake.quack.boot.HibernationWiringSupport
                      .views(sup, mgrCfg.hibernation),
                  activityByKey =
                    () => ai.starlake.quack.boot.HibernationWiringSupport.activityByKey(sup, store),
                  flushLocal = () =>
                    poolActivity.drain().foreach { case (key, atMs) =>
                      sup
                        .poolId(key)
                        .foreach(id =>
                          store.upsertPoolActivity(id, java.time.Instant.ofEpochMilli(atMs))
                        )
                    },
                  purge = () =>
                    store.purgePoolActivity(
                      java.time.Instant.now().minusSeconds(30L * 24 * 3600)
                    ): Unit,
                  suspend = key =>
                    sup.suspendPool(key, "idle").attempt.map {
                      case Right(Right(_)) => Right(())
                      case Right(Left(e))  => Left(e.toString)
                      case Left(t)         => Left(Option(t.getMessage).getOrElse(t.toString))
                    },
                  isLeader = () => coordinator.forall(_.isLeader),
                  audit = auditRecorder
                )
                val hibernationFiber = hibernationWiring.fiber

                // Managed-object-store purge worker: deletes the objects under a
                // tombstoned tenant-db prefix once its retention window has passed.
                val managedStoreWiring = new ai.starlake.quack.boot.ManagedStoreWiring(
                  cfg = mgrCfg.managedObjectStore,
                  client = managedStoreClient,
                  due = store.dueManagedPrefixes,
                  markPurged = store.markManagedPrefixPurged,
                  isLeader = () => coordinator.forall(_.isLeader)
                )
                val managedPurgeFiber = managedStoreWiring.fiber

                // Branch TTL expiry sweep (Epic 1), leader-only, inert when branching is off.
                val branchExpiryFiber = ai.starlake.quack.boot.BranchWiring.expiryFiber(
                  mgrCfg.branching,
                  branchService,
                  () => coordinator.forall(_.isLeader)
                )

                // Leader elector + LISTEN dispatch loop. No-op fiber when HA off.
                val coordinatorFiber = coordinator match
                  case Some(c) => c.loop.start
                  case None    => IO.unit.start

                // Periodic re-restore + denylist reseed, a safety net beyond the
                // NOTIFY handlers; the leader also purges expired jtis.
                val haRefreshFiber = coordinator match
                  case Some(c) =>
                    val period =
                      scala.concurrent.duration.DurationInt(mgrCfg.ha.topologyRefreshSec).seconds
                    // On promotion, run the leader duties exactly once. A duty
                    // failure neither sets the flag (retried next tick) nor aborts
                    // the rest of this tick's refresh.
                    val promoteDuties: IO[Unit] =
                      if c.isLeader && !leaderDutiesDone.get then
                        (leaderDuties *> IO.delay(leaderDutiesDone.set(true)))
                          .handleErrorWith(t =>
                            IO.delay(
                              logger.warn(
                                s"ha promotion: leader duties failed, will retry next tick: ${t.getMessage}"
                              )
                            )
                          )
                      else IO.unit
                    (promoteDuties *> IO
                      .blocking {
                        sup.restore()
                        sessionTokens.seedRevoked(store.listRevokedJti())
                        if c.isLeader then store.purgeExpiredRevokedJti(java.time.Instant.now())
                      }
                      .handleErrorWith(t =>
                        IO.delay(
                          logger.warn(s"ha refresh: pass failed, continuing: ${t.getMessage}")
                        )
                      ) *> IO.sleep(period)).foreverM.void.start
                  case None => IO.unit.start

                val journalFiber =
                  if telemetryStore.enabled then eventJournal.start else IO.unit.start
                val auditPurgeFiber = ai.starlake.quack.boot.TelemetryFibers.auditPurge(
                  telemetryStore,
                  mgrCfg.telemetry,
                  isLeader = () => coordinator.forall(_.isLeader)
                )
                val rollupFiber = ai.starlake.quack.boot.TelemetryFibers.rollup(
                  telemetryStore,
                  mgrCfg.telemetry,
                  isLeader = () => coordinator.forall(_.isLeader)
                )

                // `dispatchers` builds fresh loop closures per call, so it is
                // evaluated exactly once here.
                val moduleDispatcherFibers =
                  moduleEventBus.dispatchers.traverse(_.start)
                val moduleSingletonFibers =
                  singletonTasks.loops(() => coordinator.forall(_.isLeader)).traverse(_.start)

                healthProbe.start(() => sup.list().flatMap(_.nodes)).flatMap { fiber =>
                  reconcileFiber.flatMap { rcFiber =>
                    coordinatorFiber.flatMap { coFiber =>
                      haRefreshFiber.flatMap { hrFiber =>
                        journalFiber.flatMap { jFiber =>
                          auditPurgeFiber.flatMap { pFiber =>
                            rollupFiber.flatMap { rlFiber =>
                              maintenanceSchedulerFiber.flatMap { msFiber =>
                                maintenanceDrainFiber.flatMap { mdFiber =>
                                  autoscaleFiber.flatMap { asFiber =>
                                    hibernationFiber.flatMap { hbFiber =>
                                      managedPurgeFiber.flatMap { mpFiber =>
                                        branchExpiryFiber.flatMap { beFiber =>
                                          moduleDispatcherFibers.flatMap { modDispFibers =>
                                            moduleSingletonFibers.flatMap { modSingFibers =>
                                              IO.never[Unit]
                                                .guarantee(
                                                  fiber.cancel *> rcFiber.cancel *> coFiber.cancel *>
                                                    hrFiber.cancel *> jFiber.cancel *>
                                                    pFiber.cancel *>
                                                    rlFiber.cancel *> msFiber.cancel *>
                                                    mdFiber.cancel *> asFiber.cancel *>
                                                    hbFiber.cancel *> mpFiber.cancel *>
                                                    beFiber.cancel *>
                                                    modDispFibers.traverse_(_.cancel) *>
                                                    modSingFibers.traverse_(_.cancel) *>
                                                    gracefulShutdown
                                                )
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              case Left(t) =>
                logger.error(s"edge FlightSQL failed to start: ${t.getMessage}", t)
                IO.never[Unit]
            }
          }
        }

    val program: IO[ExitCode] =
      MetricsRegistry
        .resource(metricsCfg)
        .use { metricsReg =>
          val bindings =
            new MetricsBindings(
              metricsReg.composite,
              tracker,
              sessions,
              () => sup.list(),
              engineStatsTracker
            )
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
