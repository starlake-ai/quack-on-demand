package ai.starlake.quack

import ai.starlake.quack.edge._
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.{
  AclConfig, AuthenticationConfig, AwsAuthConfig, AzureAuthConfig,
  DatabaseAuthConfig, GoogleAuthConfig, JwtAuthConfig, KeycloakAuthConfig,
  OAuthConfig, SessionConfig
}
import ai.starlake.quack.edge.sql.{AclStatementValidator, PostgresAclValidator, StatementValidator}
import ai.starlake.quack.ondemand._
import ai.starlake.quack.ondemand.api._
import ai.starlake.quack.ondemand.runtime._
import ai.starlake.quack.ondemand.state.{AclGrantStore, PostgresStateStore, StateStore, UserStore}
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
  // at class-definition time — we shadow those defaults here).
  private val camelMapping: ConfigFieldMapping = ConfigFieldMapping(CamelCase, CamelCase)
  given ProductHint[K8sConfig]              = ProductHint[K8sConfig](camelMapping)
  given ProductHint[AdminConfig]            = ProductHint[AdminConfig](camelMapping)
  given ProductHint[RoleDistributionConfig] = ProductHint[RoleDistributionConfig](camelMapping)
  given ProductHint[BootstrapConfig]        = ProductHint[BootstrapConfig](camelMapping)
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

  def run: IO[Unit] =
    val source  = ConfigSource.default
    val mgrCfg  = source.at("quack-on-demand").loadOrThrow[ManagerConfig]
    val edgeCfg = source.at("quack-flightsql").loadOrThrow[FlightConfig]
    val authCfg = source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]
    val aclCfg  = source.at("quack-flightsql.acl").loadOrThrow[AclConfig]

    val sessionCfg = SessionConfig(
      slProjectId  = "",
      slDataPath   = "",
      pgUsername   = mgrCfg.defaultMetastore.getOrElse("pgUser", "postgres"),
      pgPassword   = mgrCfg.defaultMetastore.getOrElse("pgPassword", ""),
      pgPort       = mgrCfg.defaultMetastore.getOrElse("pgPort", "5432").toInt,
      pgHost       = mgrCfg.defaultMetastore.getOrElse("pgHost", "localhost"),
      jwtSecretKey = authCfg.jwt.secretKey,
      aclTenant    = edgeCfg.defaultTenant
    )
    val authService = new AuthenticationService(authCfg, sessionCfg)

    // Bootstrap admin users. Always runs at startup when stateStorage=postgres
    // so the DB auth backend has at least one credential. Re-hashed on every
    // boot: changing SL_QUACK_ADMIN_PASSWORD + restart rotates. All names in
    // SL_QUACK_ADMIN_USERNAME (comma-separated) get the same password + role.
    // Skipped for stateStorage=file (no Postgres connection assumed).
    if mgrCfg.stateStorage.equalsIgnoreCase("postgres") then
      val userStore = UserStore.fromDefaultMetastore(mgrCfg.defaultMetastore)
      val admins = mgrCfg.admin.usernameList
      if admins.isEmpty then
        logger.warn("quack-on-demand.admin.username is empty — no admin user seeded.")
      else
        admins.foreach { name =>
          val inserted = userStore.upsertUser(name, mgrCfg.admin.password, mgrCfg.admin.role)
          val verb = if inserted then "created" else "updated"
          logger.info(
            s"admin user $verb: $name (role=${mgrCfg.admin.role}) in slkstate_user"
          )
        }

    val backend: QuackBackend = mgrCfg.runtimeType.toLowerCase match
      case "local" =>
        new LocalQuackBackend(mgrCfg.minPort, mgrCfg.maxPort, mgrCfg.defaultMetastore)
      case "kubernetes" | "k8s" =>
        val k8s = new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()
        new KubernetesQuackBackend(
          k8s, mgrCfg.k8s.namespace, mgrCfg.k8s.image, mgrCfg.k8s.quackPort,
          mgrCfg.k8s.podLabel, mgrCfg.k8s.startupTimeoutSec, mgrCfg.defaultMetastore)
      case other => sys.error(s"unknown runtime: $other")

    val tracker  = new NodeLoadTracker
    val store: StateStore = mgrCfg.stateStorage.toLowerCase match
      case "postgres" =>
        logger.info("state storage: postgres (defaultMetastore database)")
        PostgresStateStore.fromDefaultMetastore(mgrCfg.defaultMetastore)
      case "file" | "" =>
        logger.info(s"state storage: file (${mgrCfg.statePath})")
        StateStore(Path.of(mgrCfg.statePath))
      case other =>
        sys.error(s"unknown stateStorage: $other (expected 'file' or 'postgres')")
    val sup      = new PoolSupervisor(backend, tracker, store, mgrCfg.defaultMetastore)
    val pools    = new PoolHandlers(sup, tracker)
    val nodes    = new NodeHandlers(sup, tracker, backend)
    val tenants  = new TenantHandlers(sup)
    val health   = new HealthHandler(sup)

    // ACL handlers are only available with a Postgres state backend — that's
    // the same connection the relational grant store reuses. File-mode
    // deploys skip them; the endpoints won't be mounted and return 404.
    val aclHandlers: Option[AclHandlers] =
      if mgrCfg.stateStorage.equalsIgnoreCase("postgres") then
        val grantStore = AclGrantStore.fromDefaultMetastore(mgrCfg.defaultMetastore)
        grantStore.ensureTable()
        logger.info("ACL grant store ready (slkstate_acl_grant)")
        Some(new AclHandlers(grantStore))
      else None

    val sessionTokens     = new SessionTokenStore
    val authHandlers      = new AuthHandlers(authService, sessionTokens)
    val stmtHistory       = new ai.starlake.quack.edge.StatementHistoryStore()
    val historyHandlers   = new StatementHistoryHandlers(stmtHistory)
    val mgr      = new ManagerServer(mgrCfg, edgeCfg, pools, nodes, tenants, health, aclHandlers, authHandlers, sessionTokens, authService.hasProviders, historyHandlers)

    val sessions = new SessionRegistry
    val arrowAllocator = new org.apache.arrow.memory.RootAllocator()
    val client   = new QuackHttpClient(arrowAllocator)
    val adapter  = new QuackHttpAdapter(client, tracker)
    // SQL ACL validator. Picks between two implementations:
    //   - PostgresAclValidator (preferred when stateStorage=postgres): reads
    //     grants from slkstate_acl_grant, enforces them per statement.
    //   - AclStatementValidator (file-based): reads YAML files under
    //     acl.base-path. Still useful for read-only ACL configs shipped
    //     alongside an immutable deploy.
    // Both honor acl.enabled=false by falling back to allow-all.
    val aclValidator: StatementValidator =
      if !aclCfg.enabled then
        logger.warn("SQL ACL disabled (set quack-flightsql.acl.enabled=true to enforce).")
        StatementValidator.allowAll
      else if mgrCfg.stateStorage.equalsIgnoreCase("postgres") then
        val defaultDb     = mgrCfg.defaultMetastore.getOrElse("dbName", "")
        val defaultSchema = mgrCfg.defaultMetastore.getOrElse("schemaName", "main")
        logger.info(
          s"SQL ACL enabled (Postgres slkstate_acl_grant, tenant=${sessionCfg.aclTenant}, " +
          s"defaultDb=$defaultDb, defaultSchema=$defaultSchema)"
        )
        new PostgresAclValidator(
          store           = AclGrantStore.fromDefaultMetastore(mgrCfg.defaultMetastore),
          tenantId        = sessionCfg.aclTenant,
          defaultDatabase = defaultDb,
          defaultSchema   = defaultSchema,
          dialect         = aclCfg.dialect
        )
      else
        logger.info(s"SQL ACL enabled (file-based, base-path=${aclCfg.basePath}, dialect=${aclCfg.dialect})")
        new AclStatementValidator(aclCfg, sessionCfg)

    val fsRouter = new FlightSqlRouter(sup, sessions, tracker, adapter, edgeCfg.tenantClaim, aclValidator, stmtHistory)

    // FlightEdgeServer construction allocates Arrow's RootAllocator eagerly,
    // so defer it to IO. The explicit try/catch downgrades JVM `Error`s (e.g.
    // LinkageError when arrow-memory-* and Netty diverge) into a RuntimeException
    // — IO.attempt routes that, but treats raw `Error`s as fatal.
    val edgeIO: IO[FlightEdgeServer] = IO.delay {
      try
        val srv = new FlightEdgeServer(
          EdgeConfig(edgeCfg.host, edgeCfg.port, edgeCfg.tlsEnabled,
                     edgeCfg.tlsCertChain, edgeCfg.tlsPrivateKey, edgeCfg.tenantClaim,
                     edgeCfg.defaultTenant, edgeCfg.defaultPool, edgeCfg.sessionTtlSec),
          fsRouter,
          authService)
        srv.start()
        srv
      catch case t: Throwable =>
        throw new RuntimeException(s"FlightSQL edge init failed: ${t.getMessage}", t)
    }

    // Background health probe so transient failures don't permanently mark
    // nodes unhealthy. Pings each running node with a cheap `SELECT 1` and
    // updates the tracker.
    val healthProbe = new HealthProbe(
      tracker,
      n => adapter.send(n, "SELECT 1", None).map {
        case QuackResponse.Ok(_, _, close) => close(); true
        case _                             => false
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
        val key  = ai.starlake.quack.model.PoolKey(bs.tenant, bs.pool)
        val dist = ai.starlake.quack.model.RoleDistribution(
          bs.roleDistribution.writeonly,
          bs.roleDistribution.readonly,
          bs.roleDistribution.dual
        )
        val createTenantIO =
          if sup.getTenant(bs.tenant).isDefined then
            IO.delay(logger.info(s"bootstrap: tenant '${bs.tenant}' already exists; skipping"))
          else
            sup.createTenant(ai.starlake.quack.model.Tenant(bs.tenant, Map.empty)).flatMap {
              case Right(_) => IO.delay(logger.info(s"bootstrap: created tenant '${bs.tenant}'"))
              case Left(err) => IO.delay(logger.warn(s"bootstrap: tenant create failed: $err"))
            }
        val createPoolIO =
          if sup.get(key).isDefined then
            IO.delay(logger.info(s"bootstrap: pool '${bs.tenant}/${bs.pool}' already exists; skipping"))
          else
            sup.createPool(key, dist, Map.empty, Map.empty).attempt.flatMap {
              case Right(nodes) =>
                IO.delay(logger.info(
                  s"bootstrap: created pool '${bs.tenant}/${bs.pool}' with ${nodes.size} node(s) " +
                  s"(writeonly=${dist.writeonly}, readonly=${dist.readonly}, dual=${dist.dual})"))
              case Left(t) =>
                IO.delay(logger.warn(s"bootstrap: pool create failed: ${t.getMessage}"))
            }
        createTenantIO *> createPoolIO
    }

    val program =
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
            healthProbe.start(() => sup.list().flatMap(_.nodes)).flatMap { fiber =>
              IO.never[Unit].guarantee(fiber.cancel *> IO.delay(edge.stop()))
            }
          case Left(t) =>
            logger.error(s"edge FlightSQL failed to start: ${t.getMessage}", t)
            IO.never[Unit]
        }
      }

    program