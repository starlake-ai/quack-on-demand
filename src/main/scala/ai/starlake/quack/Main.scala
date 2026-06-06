package ai.starlake.quack

import ai.starlake.quack.edge._
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.{
  AclConfig, AuthenticationConfig, AwsAuthConfig, AzureAuthConfig,
  DatabaseAuthConfig, GoogleAuthConfig, JwtAuthConfig, KeycloakAuthConfig,
  OAuthConfig
}
import ai.starlake.quack.edge.sql.{PostgresAclValidator, StatementValidator}
import ai.starlake.quack.observability.metrics.{
  MetricsBindings, MetricsConfig, MetricsConfigCodec, MetricsEndpoint, MetricsRegistry, StatementInstruments
}
import ai.starlake.quack.ondemand._
import ai.starlake.quack.ondemand.api._
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime._
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore, LiquibaseRunner, PostgresControlPlaneStore,
  PostgresDbAdmin, PostgresStateStore, StateStore, UserStore
}
import cats.effect.{IO, IOApp}
import com.typesafe.scalalogging.LazyLogging
import pureconfig._
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

import java.nio.file.Path

object Main extends IOApp.Simple with LazyLogging:

  // Match the camelCase keys used in application.conf rather than pureconfig's
  // default kebab-case mapping. Affects our own ManagerConfig / FlightConfig
  // AND the edge AuthenticationConfig types (which `derives ConfigReader`
  // at class-definition time - we shadow those defaults here).
  private val camelMapping: ConfigFieldMapping = ConfigFieldMapping(CamelCase, CamelCase)
  given ProductHint[K8sConfig]              = ProductHint[K8sConfig](camelMapping)
  given ProductHint[AdminConfig]            = ProductHint[AdminConfig](camelMapping)
  given ProductHint[RoleDistributionConfig] = ProductHint[RoleDistributionConfig](camelMapping)
  given ProductHint[BootstrapConfig]        = ProductHint[BootstrapConfig](camelMapping)
  given ProductHint[DefaultMetastoreConfig] = ProductHint[DefaultMetastoreConfig](camelMapping)
  given ProductHint[ManagerConfig]          = ProductHint[ManagerConfig](camelMapping)
  given ProductHint[FlightConfig]           = ProductHint[FlightConfig](camelMapping)
  given ProductHint[DatabaseAuthConfig]   = ProductHint[DatabaseAuthConfig](camelMapping)
  given ProductHint[KeycloakAuthConfig]   = ProductHint[KeycloakAuthConfig](camelMapping)
  given ProductHint[GoogleAuthConfig]     = ProductHint[GoogleAuthConfig](camelMapping)
  given ProductHint[AzureAuthConfig]      = ProductHint[AzureAuthConfig](camelMapping)
  given ProductHint[AwsAuthConfig]        = ProductHint[AwsAuthConfig](camelMapping)
  given ProductHint[JwtAuthConfig]        = ProductHint[JwtAuthConfig](camelMapping)
  given ProductHint[OAuthConfig]          = ProductHint[OAuthConfig](camelMapping)
  given ProductHint[AuthenticationConfig] = ProductHint[AuthenticationConfig](camelMapping)

  given ConfigReader[K8sConfig]              = deriveReader[K8sConfig]
  given ConfigReader[AdminConfig]            = deriveReader[AdminConfig]
  given ConfigReader[RoleDistributionConfig] = deriveReader[RoleDistributionConfig]
  given ConfigReader[BootstrapConfig]        = deriveReader[BootstrapConfig]
  given ConfigReader[DefaultMetastoreConfig] = deriveReader[DefaultMetastoreConfig]
  given ConfigReader[ManagerConfig]          = deriveReader[ManagerConfig]
  given ConfigReader[FlightConfig]           = deriveReader[FlightConfig]
  given ConfigReader[DatabaseAuthConfig]   = deriveReader[DatabaseAuthConfig]
  given ConfigReader[KeycloakAuthConfig]   = deriveReader[KeycloakAuthConfig]
  given ConfigReader[GoogleAuthConfig]     = deriveReader[GoogleAuthConfig]
  given ConfigReader[AzureAuthConfig]      = deriveReader[AzureAuthConfig]
  given ConfigReader[AwsAuthConfig]        = deriveReader[AwsAuthConfig]
  given ConfigReader[JwtAuthConfig]        = deriveReader[JwtAuthConfig]
  given ConfigReader[OAuthConfig]          = deriveReader[OAuthConfig]
  given ConfigReader[AuthenticationConfig] = deriveReader[AuthenticationConfig]
  import MetricsConfigCodec.given

  def run: IO[Unit] =
    val source  = ConfigSource.default
    val mgrCfg  = source.at("quack-on-demand").loadOrThrow[ManagerConfig]
    val edgeCfg = source.at("quack-flightsql").loadOrThrow[FlightConfig]
    val authCfg = source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]
    val aclCfg  = source.at("quack-flightsql.acl").loadOrThrow[AclConfig]
    val metricsCfg = source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig]

    val authService = new AuthenticationService(authCfg, authCfg.jwt.secretKey)

    // Bootstrap admin users. Always runs at startup when stateStorage=postgres
    // so the DB auth backend has at least one credential. Re-hashed on every
    // boot: changing QOD_ADMIN_PASSWORD + restart rotates. All names in
    // QOD_ADMIN_USERNAME (comma-separated) get the same password + role.
    // Skipped for stateStorage=file (no Postgres connection assumed).
    if mgrCfg.stateStorage.equalsIgnoreCase("postgres") then
      // Apply the Liquibase changelog first: `qodstate_user` (and the rest
      // of the control plane) must exist before we upsert the admin row.
      // Idempotent: DATABASECHANGELOG records skip already-applied changesets.
      LiquibaseRunner.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap).run()
      val userStore = UserStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
      val admins = mgrCfg.admin.usernameList
      if admins.isEmpty then
        logger.warn("quack-on-demand.admin.username is empty - no admin user seeded.")
      else
        admins.foreach { name =>
          // Superuser scope: tenant=NULL. The qodstate_user_scope_consistency
          // CHECK only forbids empty-string tenants now; NULL alone is fine.
          val out = userStore.upsertUser(
            tenant    = None,
            username  = name,
            plaintext = mgrCfg.admin.password,
            role      = mgrCfg.admin.role
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
          k8s, mgrCfg.k8s.namespace, mgrCfg.k8s.image, mgrCfg.k8s.quackPort,
          mgrCfg.k8s.podLabel, mgrCfg.k8s.startupTimeoutSec, mgrCfg.defaultMetastore.asMap)
      case other => sys.error(s"unknown runtime: $other")

    val tracker  = new NodeLoadTracker
    logger.info("state storage: postgres (normalized qodstate_* tables via Liquibase)")
    val store: ControlPlaneStore =
      PostgresControlPlaneStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
    // Per-tenant-db Postgres provisioning. The admin connection opens
    // against the `postgres` system DB and issues CREATE/DROP DATABASE
    // for each `qodstate_tenant_db` row the supervisor manages.
    val dbAdmin  = PostgresDbAdmin.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
    val sup      = new PoolSupervisor(backend, tracker, store, mgrCfg.defaultMetastore.asMap, dbAdmin)
    val pools    = new PoolHandlers(sup, tracker)
    val nodes    = new NodeHandlers(sup, tracker, backend)
    val tenants  = new TenantHandlers(sup)
    val tenantDbs = new TenantDbHandlers(sup)
    val health   = new HealthHandler(sup)

    // Catalog browser handlers. Only mounted in postgres mode: the DuckLake
    // catalog tables (ducklake_schema, ducklake_table, ...) only exist in a
    // Postgres metastore. One reader per tenant, cached so we don't reopen
    // Hikari on every request. The resolver reads the effective metastore
    // (default <- tenant overrides) the same way PoolSupervisor does for
    // spawn-node.
    val catalogHandlers: Option[CatalogHandlers] =
      if mgrCfg.stateStorage.equalsIgnoreCase("postgres") then
        val cache = new java.util.concurrent.ConcurrentHashMap[(String, String), DuckLakeCatalogReader]()
        def reader(tenant: String, tenantDb: String): DuckLakeCatalogReader =
          cache.computeIfAbsent(
            (tenant, tenantDb),
            { case (t, td) => DuckLakeCatalogReader(sup.effectiveMetastoreFor(t, td)) }
          )
        Some(new CatalogHandlers(reader))
      else None

    val sessionTokens     = new SessionTokenStore
    val authHandlers      = new AuthHandlers(authService, sessionTokens)
    val stmtHistory       = new ai.starlake.quack.edge.StatementHistoryStore()
    val historyHandlers   = new StatementHistoryHandlers(stmtHistory)
    val sessions = new SessionRegistry
    val arrowAllocator = new org.apache.arrow.memory.RootAllocator()
    val client   = new QuackHttpClient(
      arrowAllocator,
      nativeClient   = mgrCfg.nativeClient,
      nodeDisableSsl = mgrCfg.nodeDisableSsl
    )
    val adapter  = new QuackHttpAdapter(client, tracker)
    // SQL ACL validator. Phase C: the only validator is the RBAC-backed
    // PostgresAclValidator, which reads from the cached EffectiveSet
    // pinned on ConnectionContext at handshake time. acl.enabled=false
    // still falls back to allow-all for local-dev workflows.
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
          defaultSchema   = defaultSchema,
          dialect         = aclCfg.dialect
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
    val healthProbe = new HealthProbe(
      tracker,
      n => {
        val initSql =
          if schemaInited.containsKey(n.nodeId) then None
          else
            sup.get(n.poolKey).flatMap { st =>
              st.metastore.get("dbName").filter(_.nonEmpty).map { db =>
                val schema = st.metastore.get("schemaName")
                  .filter(_.nonEmpty).getOrElse("main")
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

    // Auto-create the bootstrap tenant + pool on every boot. Idempotent: a
    // restart with the same config is a no-op. Disabled via
    // quack-on-demand.bootstrap.enabled=false (e.g. when tenants are
    // managed externally and the manager must not create them itself).
    val bootstrapIO: IO[Unit] = IO.defer {
      val bs = mgrCfg.bootstrap
      if !bs.enabled then
        logger.info("bootstrap: disabled (quack-on-demand.bootstrap.enabled=false)")
        IO.unit
      else
        // Compose the tenant-db name up-front so it can serve both the
        // explicit `createTenantDb` call AND the per-tenant-db dataPath
        // derivation.
        val tenantDbName = ai.starlake.quack.model.Names
          .normalizeTenantDbName(bs.tenant, bs.tenantDb)
          .fold(err => sys.error(s"bootstrap: invalid tenant/tenantDb: $err"), identity)

        val key  = ai.starlake.quack.model.PoolKey(bs.tenant, tenantDbName, bs.pool)
        val dist = ai.starlake.quack.model.RoleDistribution(
          bs.roleDistribution.writeonly,
          bs.roleDistribution.readonly,
          bs.roleDistribution.dual
        )

        // Each tenant-db owns its own on-disk data path. Derive it by
        // replacing the last path component of the global default
        // `dataPath` with the composed tenant-db name -- e.g.
        // `/Users/.../ducklake/tpch` + `tpch_tpch1` -> `/Users/.../ducklake/tpch_tpch1`.
        val rootDataPath = mgrCfg.defaultMetastore.dataPath
        val tenantDbDataPath =
          if rootDataPath.isEmpty then ""
          else
            val p      = java.nio.file.Paths.get(rootDataPath)
            val parent = p.getParent
            if parent == null then tenantDbName else parent.resolve(tenantDbName).toString
        val tenantDbMetastore = mgrCfg.defaultMetastore.asMap
          .updated("dataPath", tenantDbDataPath)
          .updated("dbName",   tenantDbName)

        val createTenantIO: IO[Unit] =
          if sup.getTenant(bs.tenant).isDefined then
            IO.delay(logger.info(s"bootstrap: tenant '${bs.tenant}' already exists; skipping"))
          else
            // Bootstrap tenant uses the `db` auth provider -- the only
            // provider that needs zero out-of-band config to be useful.
            sup.createTenant(
              ai.starlake.quack.model.Tenant(name = bs.tenant, authProvider = "db")
            ).flatMap {
              case Right(_)  => IO.delay(logger.info(s"bootstrap: created tenant '${bs.tenant}' (auth=db)"))
              case Left(err) => IO.delay(logger.warn(s"bootstrap: tenant create failed: $err"))
            }

        val createTenantDbIO: IO[Unit] =
          if sup.listTenantDbsByTenant(bs.tenant).exists(_.name == tenantDbName) then
            IO.delay(logger.info(s"bootstrap: tenant-db '${bs.tenant}/$tenantDbName' already exists; skipping"))
          else
            sup.createTenantDb(
              tenantName  = bs.tenant,
              suffix      = bs.tenantDb,
              metastore   = tenantDbMetastore,
              dataPath    = tenantDbDataPath,
              objectStore = Map.empty
            ).flatMap {
              case Right(_)  => IO.delay(logger.info(s"bootstrap: created tenant-db '${bs.tenant}/$tenantDbName' at $tenantDbDataPath"))
              case Left(err) => IO.delay(logger.warn(s"bootstrap: tenant-db create failed: $err"))
            }

        val createPoolIO: IO[Unit] =
          if sup.get(key).isDefined then
            IO.delay(logger.info(s"bootstrap: pool '$key' already exists; skipping"))
          else
            sup.createPool(key, dist).attempt.flatMap {
              case Right(nodes) =>
                IO.delay(logger.info(
                  s"bootstrap: created pool '$key' with ${nodes.size} node(s) " +
                  s"(writeonly=${dist.writeonly}, readonly=${dist.readonly}, dual=${dist.dual})"))
              case Left(t) =>
                IO.delay(logger.warn(s"bootstrap: pool create failed: ${t.getMessage}"))
            }

        // Per-tenant auth model: the bootstrap tenant uses provider=db,
        // identity is implicit in `qodstate_user.username`. No identity
        // table to seed any more -- superuser admins (tenant=NULL)
        // bypass the per-statement ACL gate; tenant-scoped admins
        // authenticate through the qodstate_user partial unique index.
        createTenantIO *> createTenantDbIO *> createPoolIO
    }

    def runWithMetrics(
        metricsReg: MetricsRegistry,
        metricsEndpoint: MetricsEndpoint,
        stmtInstruments: StatementInstruments
    ): IO[Unit] =
      val fsRouter = new FlightSqlRouter(sup, sessions, tracker, adapter, edgeCfg.tenantClaim, aclValidator, stmtHistory, stmtInstruments)

      // FlightEdgeServer construction allocates Arrow's RootAllocator eagerly,
      // so defer it to IO. The explicit try/catch downgrades JVM `Error`s (e.g.
      // LinkageError when arrow-memory-* and Netty diverge) into a RuntimeException
      // - IO.attempt routes that, but treats raw `Error`s as fatal.
      val edgeIO: IO[FlightEdgeServer] = IO.delay {
        try
          val srv = new FlightEdgeServer(
            EdgeConfig(edgeCfg.host, edgeCfg.port, edgeCfg.tlsEnabled,
                       edgeCfg.tlsCertChain, edgeCfg.tlsPrivateKey, edgeCfg.tenantClaim,
                       edgeCfg.sessionTtlSec),
            fsRouter,
            authService,
            (tenant, pool) =>
              sup.findPoolKeyByTenantAndPoolName(tenant, pool) match
                case None => Left(s"pool '$pool' not found in tenant '$tenant'")
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
            // Phase C handshake authorize: user-scope + pool-access +
            // EffectiveSet. Failures bubble up as PERMISSION_DENIED on
            // the FlightSQL handshake. JWT role + groups claims flow in
            // from FlightEdgeServer.handshake and get union-merged with
            // the user's local membership edges inside the supervisor.
            (tenant, pool, username, jwtRoles, jwtGroups) =>
              sup.authorizeHandshake(tenant, pool, username, jwtRoles, jwtGroups))
          srv.start()
          srv
        catch case t: Throwable =>
          throw new RuntimeException(s"FlightSQL edge init failed: ${t.getMessage}", t)
      }

      // RBAC handlers wire through the supervisor + user store so persistence
      // and the in-memory RbacResolver cache stay in lockstep. The user
      // handler is built first because role / group / pool-permission
      // handlers share its DTO mappers.
      val userStoreForRbac = UserStore.fromDefaultMetastore(mgrCfg.defaultMetastore.asMap)
      val userHandlers     = new UserHandlers(sup, userStoreForRbac)
      val roleHandlers     = new RoleHandlers(sup, userHandlers)
      val groupHandlers    = new GroupHandlers(sup, userHandlers)
      val membershipHandlers = new MembershipHandlers(sup)
      val poolPermHandlers = new PoolPermissionHandlers(sup, userHandlers)

      // Config page registry. The roots list pairs each typed config
      // class with its HOCON prefix; the reflector pulls every
      // @ConfigField-annotated scalar (including nested case-class
      // fields). The live `Config` is the same one pureconfig drove
      // `ConfigSource.default` from, so values render with env-var
      // substitutions already applied.
      val liveConfig    = com.typesafe.config.ConfigFactory.load()
      val configEntries = ConfigRegistry.collect(
        ConfigRegistry.rootsFor(
          managerCls    = classOf[ManagerConfig],
          flightCls     = classOf[FlightConfig],
          authCls       = classOf[AuthenticationConfig],
          aclCls        = classOf[AclConfig],
          validationCls = classOf[ai.starlake.quack.edge.config.ValidationConfig],
          metricsCls    = classOf[MetricsConfig]
        )
      )
      val serverConfigHandlers = new ConfigHandlers(liveConfig, configEntries)

      val manifestHandlers = new ai.starlake.quack.ondemand.api.ManifestHandlers(
        store          = store,
        managerVersion = "dev",
        hostname       = scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("unknown")
      )

      val mgr = new ManagerServer(
        mgrCfg, edgeCfg, pools, nodes, tenants, tenantDbs, health,
        authHandlers, sessionTokens, authService.hasProviders,
        historyHandlers, catalogHandlers, metricsEndpoint,
        userHandlers, roleHandlers, groupHandlers, membershipHandlers, poolPermHandlers,
        serverConfigHandlers, manifestHandlers
      )
      // DuckLake pre-init is per-tenant-db; PoolSupervisor.createTenantDb
      // calls DuckLakeInitializer.initBlocking once the tenant-db's own
      // Postgres database has been provisioned. The control-plane
      // database holds qodstate_* / slkstate_* only and must never carry
      // ducklake_* tables.
      IO.delay(sup.restore()) *>
      sup.reconcile() *>
      bootstrapIO *>
      mgr.serve.use { _ =>
        logger.info(
          s"manager REST on ${mgrCfg.host}:${mgrCfg.port}, " +
          s"edge FlightSQL on ${edgeCfg.host}:${edgeCfg.port}")
        edgeIO.attempt.flatMap {
          case Right(edge) =>
            logger.info("edge FlightSQL started")
            healthProbe.
              start(() => sup.list().flatMap(_.nodes)).flatMap { fiber =>
              IO.never[Unit].guarantee(fiber.cancel *> IO.delay(edge.stop()))
            }
          case Left(t) =>
            logger.error(s"edge FlightSQL failed to start: ${t.getMessage}", t)
            IO.never[Unit]
        }
      }

    val program: IO[Unit] =
      MetricsRegistry.resource(metricsCfg).use { metricsReg =>
        val bindings = new MetricsBindings(metricsReg.composite, tracker, sessions, () => sup.list())
        val metricsEndpoint = new MetricsEndpoint(metricsReg.prometheus, () => bindings.refresh())
        val stmtInstruments = new StatementInstruments(metricsReg.composite)
        IO.delay(bindings.refresh()) *> runWithMetrics(metricsReg, metricsEndpoint, stmtInstruments)
      }

    program
