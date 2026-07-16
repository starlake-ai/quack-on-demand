package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.Main
import ai.starlake.quack.edge.config.AclConfig
import ai.starlake.quack.observability.metrics.MetricsConfigCodec
import ai.starlake.quack.{FlightConfig, ManagerConfig}
import cats.effect.{ExitCode, IO}
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource

/** `qod demo` orchestration. Boots a fully self-contained, seeded, RBAC-secured manager on an
  * embedded ephemeral Postgres, prints the demo banner, and tears everything down on exit.
  *
  * Guardrail 1: the insecure demo posture is produced by [[DemoConfig.overlay]] here and nowhere
  * else; `normalManagerRun` never calls into this object.
  */
object DemoRunner:

  import Main.given // camelCase ConfigReaders, matching normalManagerRun
  import MetricsConfigCodec.given

  private val logger = LoggerFactory.getLogger(getClass)
  private val Sf     = 0.1
  private val DbName = "acme_tpch"
  private val Schema = "tpch1"

  def runDemo(args: List[String]): IO[ExitCode] =
    val explicitHome = args.headOption
    // `home` is created up front (a cheap, side-effect-free mkdir) and `pgRef` records the embedded
    // PG instance the moment it starts, BEFORE any later setup step (createDatabase, config load,
    // DemoSeed) that can throw. `cleanup` is attached via `.guarantee` around the WHOLE setup+boot
    // chain (not just the boot), so a mid-setup failure -- e.g. a bad config -- still stops PG and
    // deletes the home instead of leaking both under ${TMPDIR}/qod-demo.
    val home  = DemoHome.create(explicitHome)
    val pgRef = new java.util.concurrent.atomic.AtomicReference[Option[DemoPostgres]](None)
    val cleanup = IO.blocking {
      logger.info("demo: tearing down")
      try pgRef.get().foreach(_.stop())
      finally home.deleteRecursively()
    }
    IO.blocking(setup(home, pgRef))
      .flatMap(configs => bootWithBanner(home, configs))
      .guarantee(cleanup)

  /** Blocking, ordered setup: embedded PG -> databases -> config overlay -> seed manifest env ->
    * data seed. `pgRef` is populated as soon as PG starts so `runDemo`'s cleanup can stop it even if
    * a later step here fails.
    */
  private def setup(
      home: DemoHome,
      pgRef: java.util.concurrent.atomic.AtomicReference[Option[DemoPostgres]]
  ): DemoConfigs =
    val pg = DemoPostgres.start(home.pgDir)
    pgRef.set(Some(pg))
    pg.createDatabase("qod")  // control plane
    pg.createDatabase(DbName) // tenant-db DuckLake catalog

    val base    = loadBase()
    val configs = DemoConfig.overlay(base._1, base._2, base._3, pg.coords, home)

    // Point DemoBootstrapHook at the bundled minimal manifest so the manager seeds the control
    // plane on first boot. The hook's env reader is patched (Step 6b) to fall back to system
    // properties, and its readFile already supports the `classpath:` prefix, so no file extraction
    // is needed. The JVM cannot mutate the real process env, hence the sysprop channel.
    System.setProperty("QOD_BOOTSTRAP_YAML", "classpath:bootstrap-demo-minimal.yaml")
    // Liquibase must create qodstate_* before the hook imports; the manager boot does that. The
    // hook runs inside boot after migration. Data seeding, however, needs the tenant-db catalog
    // reachable independently -- run it now against the embedded PG.
    DemoSeed.run(pg.coords, DbName, Schema, home, Sf) match
      case Left(err) => sys.error(err)
      case Right(()) => ()
    configs

  private def loadBase(): (ManagerConfig, FlightConfig, AclConfig) =
    val src = ConfigSource.default
    (
      src.at("quack-on-demand").loadOrThrow[ManagerConfig],
      src.at("quack-flightsql").loadOrThrow[FlightConfig],
      src.at("quack-flightsql.acl").loadOrThrow[AclConfig]
    )

  // NOTE (Step 6b): the manifest path is delivered via System.setProperty above; the boot-time
  // DemoBootstrapHook call site (Main.scala:1280) must read it. Change its env reader from
  // `env = sys.env.get` to `env = k => sys.env.get(k).orElse(sys.props.get(k))`. Additive: the real
  // env var still wins for normal boots; only the demo path relies on the sysprop fallback.

  private def bootWithBanner(home: DemoHome, configs: DemoConfigs): IO[ExitCode] =
    val authCfg =
      ConfigSource.default
        .at("quack-flightsql.auth")
        .loadOrThrow[ai.starlake.quack.edge.config.AuthenticationConfig]
    val metricsCfg =
      ConfigSource.default
        .at("quack-on-demand.metrics")
        .loadOrThrow[ai.starlake.quack.observability.metrics.MetricsConfig]
    val banner = DemoBanner.render(
      restPort = configs.manager.port,
      flightPort = configs.flight.port,
      dataPath = home.dataPath.toString,
      rows = "~150K"
    )
    IO.blocking(println(banner)) *>
      Main.bootManager(configs.manager, configs.flight, authCfg, configs.acl, metricsCfg)
