package ai.starlake.quack

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
import ai.starlake.quack.model.Names
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
  given ProductHint[ManagedObjectStoreConfig]  = ProductHint[ManagedObjectStoreConfig](camelMapping)
  given ProductHint[SmtpConfig]                = ProductHint[SmtpConfig](camelMapping)
  given ProductHint[ManagerConfig]             = ProductHint[ManagerConfig](camelMapping)
  given ProductHint[FlightConfig]              = ProductHint[FlightConfig](camelMapping)
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
  given ConfigReader[SmtpConfig]               = deriveReader[SmtpConfig]
  given ConfigReader[ManagerConfig]            = deriveReader[ManagerConfig]
  given ConfigReader[FlightConfig]             = deriveReader[FlightConfig]
  given ConfigReader[DatabaseAuthConfig]       = deriveReader[DatabaseAuthConfig]
  given ConfigReader[KeycloakAuthConfig]       = deriveReader[KeycloakAuthConfig]
  given ConfigReader[GoogleAuthConfig]         = deriveReader[GoogleAuthConfig]
  given ConfigReader[AzureAuthConfig]          = deriveReader[AzureAuthConfig]
  given ConfigReader[AwsAuthConfig]            = deriveReader[AwsAuthConfig]
  given ConfigReader[JwtAuthConfig]            = deriveReader[JwtAuthConfig]
  given ConfigReader[AuthenticationConfig]     = deriveReader[AuthenticationConfig]
  import MetricsConfigCodec.given

  private val DevSessionJwtSecret = "qod-dev-session-secret-rotate-in-production-x9k2v7p3m8q1"

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
          ai.starlake.quack.cli.ManifestCli.importFrom(store, System.in)
        }.map(rc => if rc == 0 then ExitCode.Success else ExitCode.Error)
      case "demo" :: rest =>
        ai.starlake.quack.ondemand.demo.DemoRunner.runDemo(rest)
      case _ =>
        normalManagerRun

  private def normalManagerRun: IO[ExitCode] =
    val source      = ConfigSource.default
    val mgrCfg      = source.at("quack-on-demand").loadOrThrow[ManagerConfig]
    val edgeCfg     = source.at("quack-flightsql").loadOrThrow[FlightConfig]
    val authCfg     = source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]
    val aclCfg      = source.at("quack-flightsql.acl").loadOrThrow[AclConfig]
    val lockdownCfg = source.at("quack-flightsql.nodeLockdown").loadOrThrow[NodeLockdownConfig]
    val metricsCfg  = source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig]
    bootManager(
      mgrCfg,
      edgeCfg,
      authCfg,
      aclCfg,
      metricsCfg,
      lockdownCfg = lockdownCfg,
      modules = ai.starlake.quack.ondemand.module.ModuleLoader.discover()
    )

  private[quack] def bootManager(
      mgrCfg: ManagerConfig,
      edgeCfg: FlightConfig,
      authCfg: AuthenticationConfig,
      aclCfg: AclConfig,
      metricsCfg: MetricsConfig,
      lockdownCfg: NodeLockdownConfig = NodeLockdownConfig(enabled = false),
      modules: List[ai.starlake.quack.spi.ManagerModule] = Nil
  ): IO[ExitCode] =
    HaPreconditions
      .validate(
        mgrCfg.ha.enabled,
        mgrCfg.runtimeType,
        mgrCfg.auth.management.sessionJwtSecret,
        DevSessionJwtSecret
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

    // Cached per-tenant-db DuckLake catalog readers (contract in CatalogReaders).
    // Construction cycle with `sup`: readers need the supervisor's metastore
    // resolution, the supervisor's hooks need evict. Broken via supRef, filled
    // right after the supervisor is built; get() only runs at request time.
    val supRef           = new java.util.concurrent.atomic.AtomicReference[PoolSupervisor]()
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
    val sup           = new PoolSupervisor(
      backend,
      tracker,
      store,
      mgrCfg.defaultMetastore.asMap,
      dbAdmin,
      federationBlobOf,
      onTenantDbDeleted = catalogReaders.evict,
      onTenantDbChanged = catalogReaders.evict,
      onPoolTeardown = key => { placementDirectory.clear(key); localityTracker.clear(key) },
      locks = poolLocks,
      publish = publisher,
      events = moduleEventBus.sink,
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

    if mgrCfg.auth.management.sessionJwtSecret == DevSessionJwtSecret then
      // ERROR (not warn) so it survives the default ERROR root log level.
      logger.error(
        "USING THE DEV DEFAULT session JWT secret. Anyone with the source can forge admin " +
          "sessions on this manager. Override QOD_SESSION_JWT_SECRET before exposing the " +
          "manager beyond localhost."
      )
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

    val auditRecorder = new AuditRecorder(telemetryStore, sessionTokens.get)

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

    val pools = new PoolHandlers(
      sup,
      tracker,
      engineStatsTracker,
      mgrCfg.k8s.podTemplateEnabled,
      autoscaleHardCap = mgrCfg.autoscale.hardCap,
      audit = auditRecorder
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

    val tenantDbs = new TenantDbHandlers(
      sup,
      manifestFedStore,
      catalog = catalogHandlers,
      audit = auditRecorder,
      managedEnabled = mgrCfg.managedObjectStore.enabled
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
          )
        )
      )
    }
    // Management-plane auth wiring; component contracts in ManagementAuthWiring.
    val mgmtAuth = ManagementAuthWiring.build(
      mgrCfg,
      authCfg,
      loadTenant = id => sup.getTenantById(id)
    )
    val grantsForIdentity: GrantsLookup =
      (identity, email) => userStore.grantsForIdentity(identity, email)
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
      changePasswordStore = Some(userStore)
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
    val historyHandlers    = new StatementHistoryHandlers(stmtHistory, sup)
    val auditHandlers      = new ai.starlake.quack.ondemand.api.AuditHandlers(telemetryStore)
    val historyApiHandlers = new ai.starlake.quack.ondemand.api.HistoryHandlers(telemetryStore)
    val usageHandlers      = new ai.starlake.quack.ondemand.api.UsageHandlers(telemetryStore)
    // Self-service surface for non-admin sessions; scopes strictly to the
    // session's own (tenant, username), so it takes no tenant/user input.
    val profileHandlers = new ai.starlake.quack.ondemand.api.ProfileHandlers(
      sessionTokens,
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
      scala.concurrent.duration.DurationInt(mgrCfg.healthCheckIntervalSec).seconds,
      // Piggyback the engine-stats scrape on each healthy tick; fail-soft.
      onHealthy = n =>
        adapter.engineStats(n).map(_.foreach(st => engineStatsTracker.update(n.nodeId, st)))
    )

    def runWithMetrics(
        metricsReg: MetricsRegistry,
        metricsEndpoint: MetricsEndpoint,
        stmtInstruments: StatementInstruments
    ): IO[Unit] =
      val classifier           = EdgeRewriters.statementClassifier()
      val columnPolicyRewriter =
        EdgeRewriters.columnPolicyRewriter(sup, catalogReaders, stmtInstruments)
      val rowPolicyRewriter = EdgeRewriters.rowPolicyRewriter()

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
          val builtins = Set("memory", "system", "temp")
          val dbName   = sup
            .effectiveMetastoreFor(key.tenant, key.tenantDb)
            .getOrElse("dbName", key.tenantDb)
          val aliases = (sup.findTenantDb(key.tenant, key.tenantDb), manifestFedStore) match
            case (Some(td), Some(fedStore)) =>
              fedStore.listSources(td.id).map(_.alias).toSet
            case _ => Set.empty[String]
          val result = builtins + dbName ++ aliases
          attachedCatalogsCache.put(key, (now, result))
          result
        }

      // Mirrors attachedCatalogsOf's metastore resolution so the ACL SQL parser
      // resolves unqualified table refs the same way the validator does.
      val refsConfigFor: ai.starlake.quack.model.PoolKey => ai.starlake.acl.model.Config = key =>
        val ms = sup.effectiveMetastoreFor(key.tenant, key.tenantDb)
        ai.starlake.acl.model.Config.forDuckDB(
          Some(ms.getOrElse("dbName", key.tenantDb)),
          Some(ms.getOrElse("schemaName", "main")),
          attachedCatalogsOf(key)
        )
      val routingInstruments =
        new ai.starlake.quack.observability.metrics.RoutingInstruments(metricsReg.composite)
      val routingRefsCache = new ai.starlake.quack.route.RoutingRefsCache()

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
        // The load-stats sink goes FIRST: fanout has no error isolation, so a module
        // sink that throws must not be able to starve the autoscale demand signal.
        // Invariant: poolLoadStats.sink is ONLY wired when the autoscale sweep runs,
        // because that sweep is its sole drainer -- feeding it with autoscale disabled
        // would grow the per-(pool, minute) bucket map without bound.
        events =
          if mgrCfg.autoscale.enabled then
            ai.starlake.quack.spi.ManagerEventSink.fanout(poolLoadStats.sink, moduleEventBus.sink)
          else moduleEventBus.sink,
        resumeHoldTimeout =
          scala.concurrent.duration.DurationLong(edgeCfg.resumeHoldTimeoutSec).seconds,
        lockdownFor = sup.effectiveLockdown,
        // DuckLake buckets are never directly addressable from tenant SQL under lockdown:
        // every tenant-db dataPath bucket plus the managed root bucket (static config).
        deniedBuckets = () =>
          sup.duckLakeBuckets() ++
            Option
              .when(mgrCfg.managedObjectStore.enabled)(
                mgrCfg.managedObjectStore.bucket.toLowerCase
              )
              .toSet,
        routingRefs = routingRefsCache,
        refsConfigFor = refsConfigFor,
        locality = localityTracker,
        routingInstruments = routingInstruments,
        placement = placementDirectory,
        cacheAwareRouting = mgrCfg.routing.cacheAware,
        loadCapFactor = mgrCfg.routing.loadCapFactor
      )

      // The try/catch downgrades JVM Errors (e.g. Arrow/Netty LinkageError) into a
      // RuntimeException: IO.attempt routes that, but treats raw Errors as fatal.
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
                          Right(key.tenantDb),
            // The FlightSQL `tenant` param may be a surrogate id or a display
            // name; the shapes are disjoint, so the check picks the right index.
            raw =>
              if Names.looksLikeTenantId(raw) then sup.getTenantById(raw)
              else sup.getTenant(raw),
            // Handshake authorize; failures bubble up as PERMISSION_DENIED.
            (tenant, pool, username, jwtRoles, jwtGroups) =>
              sup.authorizeHandshake(tenant, pool, username, jwtRoles, jwtGroups)
          )
          srv.start()
          srv
        catch
          case t: Throwable =>
            throw new RuntimeException(s"FlightSQL edge init failed: ${t.getMessage}", t)
      }

      val userHandlers       = new UserHandlers(sup, userStore, audit = auditRecorder)
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
        Some(
          new ai.starlake.quack.ondemand.api.FederatedSourceHandlers(
            fedHandlersStore,
            resolver,
            tenantIdResolver,
            audit = auditRecorder
          )
        )

      val manifestHandlers = new ai.starlake.quack.ondemand.api.ManifestHandlers(
        store = store,
        supervisor = sup,
        managerVersion = "dev",
        hostname =
          scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("unknown"),
        federatedStore = manifestFedStore,
        audit = auditRecorder
      )

      // Adapts FlightSqlRouter.execute to PreviewExecutor, mirroring the FlightSQL
      // handshake's EffectiveSet resolution: the SuperuserIdentity sentinel gets a
      // synthetic superuser EffectiveSet (NOT None, which PostgresAclValidator
      // denies fail-safe); real sessions resolve through sup.authorizeHandshake
      // (same gate + 60s cache as the handshake), a Left short-circuiting to
      // AccessDenied before fsRouter.execute.
      // recordExecution = false for read-only probes (preview, data diff, restore
      // dry-run); true for undrop's CTAS and restore's CREATE OR REPLACE so the
      // snapshot carries the author stamp.
      // Caveat: the handler-level timeout cannot cancel the underlying IO.blocking
      // node call, so a timed-out preview may leave that call to finish unobserved
      // (bounded by previewTimeoutSec, pre-existing on the shared executor path;
      // durable fix is bracketing the connection inside QuackHttpClient).
      def routedExecutor(
          recordExecution: Boolean
      ): ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.PreviewExecutor =
        (connectionId, user, poolKey, sql) =>
          val effectiveSetIO: IO[Either[ai.starlake.quack.edge.RouterFailure, Option[
            ai.starlake.quack.ondemand.rbac.EffectiveSet
          ]]] =
            if user == ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.SuperuserIdentity
            then
              val superuser = ai.starlake.quack.ondemand.state.RbacUser(
                id = "",
                tenant = None,
                username = user,
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
              IO.delay(sup.authorizeHandshake(poolKey.tenant, poolKey.pool, user)).map {
                case Left(reason) =>
                  Left(ai.starlake.quack.edge.RouterFailure.AccessDenied(reason))
                case Right(authorized) => Right(Some(authorized.effectiveSet))
              }
          effectiveSetIO.flatMap {
            case Left(denied) => IO.pure(Left(denied))
            case Right(eff)   =>
              fsRouter.execute(
                connectionId,
                user,
                poolKey,
                sql,
                effectiveSet = eff,
                recordExecution = recordExecution
              )
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
          catalogAlias = (t, td) => sup.effectiveMetastoreFor(t, td).getOrElse("dbName", td),
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
          catalogAlias = (t, td) => sup.effectiveMetastoreFor(t, td).getOrElse("dbName", td),
          audit = auditRecorder
        )
      )

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
        passwordReset = Some(passwordResetHandlers)
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
          fedStore = manifestFedStore
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
        // them strictly after moduleStart and before the server binds.
        sup.setMutationGates(modules.flatMap(_.mutationGates)) *>
        IO.delay(buildManagerServer()).flatMap { mgr =>
          mgr.serve.use { _ =>
            logger.info(
              s"manager REST on ${mgrCfg.host}:${mgrCfg.port}, " +
                s"edge FlightSQL on ${edgeCfg.host}:${edgeCfg.port}"
            )
            edgeIO.attempt.flatMap {
              case Right(edge) =>
                logger.info("edge FlightSQL started")
                // Stdout banner (default log level is ERROR), once both listeners are up.
                println(
                  Banner.startup(
                    mgrCfg.defaultMetastore.asMap,
                    mgrCfg.host,
                    mgrCfg.port,
                    edgeCfg.host,
                    edgeCfg.port,
                    edgeCfg.tlsEnabled
                  )
                )
                val shutdownCoordinator = new ai.starlake.quack.boot.ShutdownCoordinator(
                  edge = edge,
                  backend = backend,
                  coordinator = coordinator,
                  eventJournal = eventJournal,
                  telemetryStore = telemetryStore,
                  store = store,
                  userStore = userStore,
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
                                    managedPurgeFiber.flatMap { mpFiber =>
                                      moduleDispatcherFibers.flatMap { modDispFibers =>
                                        moduleSingletonFibers.flatMap { modSingFibers =>
                                          IO.never[Unit]
                                            .guarantee(
                                              fiber.cancel *> rcFiber.cancel *> coFiber.cancel *>
                                                hrFiber.cancel *> jFiber.cancel *> pFiber.cancel *>
                                                rlFiber.cancel *> msFiber.cancel *>
                                                mdFiber.cancel *> asFiber.cancel *>
                                                mpFiber.cancel *>
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
