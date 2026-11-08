package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.Main
import ai.starlake.quack.observability.metrics.MetricsConfigCodec
import ai.starlake.quack.{FlightConfig, ManagerConfig, QuackNativeConfig}
import ai.starlake.quack.ondemand.storage.{ManagedStoreClient, S3ManagedStoreClient}
import cats.effect.{ExitCode, IO}
import com.typesafe.scalalogging.LazyLogging
import pureconfig.ConfigSource

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** `qod demo` orchestration. Boots a fully self-contained, seeded, RBAC-secured manager on an
  * embedded ephemeral Postgres, prints the demo banner, and tears everything down on exit.
  *
  * Guardrail 1: the insecure demo posture is produced by [[DemoConfig.overlay]] here and nowhere
  * else; `normalManagerRun` never calls into this object.
  */
object DemoRunner extends LazyLogging:

  import Main.given // camelCase ConfigReaders, matching normalManagerRun
  import MetricsConfigCodec.given

  private val Sf     = 0.1
  private val DbName = "acme_tpch"
  private val Schema = "tpch1"

  // Final-review Fix 2: every JVM-global system property `setup` mutates, listed once so
  // snapshot/restore stay obviously symmetric (add here, not ad hoc at each call site, if a future
  // change makes `setup` set another one). `Test/fork := true` runs each spec class in its own JVM,
  // but within ONE suite class multiple tests (or, worse, a later spec sharing that fork) would
  // otherwise inherit whatever `runDemo` last left set -- e.g. `QOD_BOOTSTRAP_YAML` pointing at the
  // demo's minimal manifest, or cls/rls forced on -- an order-dependent flake.
  private val MutatedSysProps = List(
    "quack-on-demand.cls.enabled",
    "quack-on-demand.rls.enabled",
    "QOD_BOOTSTRAP_YAML"
  )

  /** Capture the current value (`Some`) or absence (`None`) of every property [[MutatedSysProps]]
    * lists, so [[restoreSysProps]] can put the JVM back exactly how it found it.
    */
  private def snapshotSysProps(): Map[String, Option[String]] =
    MutatedSysProps.map(k => k -> Option(System.getProperty(k))).toMap

  /** Undo `setup`'s system-property mutations: re-set whatever was previously present, clear
    * whatever was previously absent, then invalidate Typesafe Config's cache so a later boot in the
    * same JVM (e.g. the next spec in this fork) re-reads the restored values instead of a stale
    * snapshot.
    */
  private def restoreSysProps(snapshot: Map[String, Option[String]]): Unit =
    snapshot.foreach {
      case (k, Some(v)) => System.setProperty(k, v)
      case (k, None)    => System.clearProperty(k)
    }
    com.typesafe.config.ConfigFactory.invalidateCaches()

  def runDemo(args: List[String]): IO[ExitCode] =
    // Before anything is created: a malformed path stops here with one line, not a stack trace.
    DemoS3.fromEnv(sys.env.get) match
      case Left(err) => IO.blocking(System.err.println(s"demo: $err")).as(ExitCode.Error)
      case Right(s3) => runDemo(args, s3)

  private def runDemo(args: List[String], s3: Option[DemoS3]): IO[ExitCode] =
    val explicitHome = args.headOption
    // `home` is created up front (a cheap, side-effect-free mkdir) and `pgRef` records the embedded
    // PG instance the moment it starts, BEFORE any later setup step (createDatabase, config load,
    // DemoSeed) that can throw. `cleanup` is attached via `.guarantee` around the WHOLE setup+boot
    // chain (not just the boot), so a mid-setup failure -- e.g. a bad config -- still stops PG and
    // deletes the home instead of leaking both under ${TMPDIR}/qod-demo.
    val home          = DemoHome.create(explicitHome)
    val propsSnapshot = snapshotSysProps() // captured BEFORE `setup` sets any of MutatedSysProps
    val pgRef         = new AtomicReference[Option[DemoPostgres]](None)
    // Set once the store is known to work, so a bad key does not also report a failed delete.
    val prepared = new AtomicReference[Option[(DemoS3, ManagedStoreClient)]](None)
    val cleanup  = IO.blocking {
      logger.info("demo: tearing down")
      try prepared.get().foreach((s, c) => removeRun(s, c))
      finally
        try pgRef.get().foreach(_.stop())
        finally
          try home.deleteRecursively()
          finally restoreSysProps(propsSnapshot)
    }
    IO.blocking(setup(home, pgRef, s3, prepared))
      .flatMap(configs => bootWithBanner(home, configs, s3))
      .guarantee(cleanup)

  /** Own-bucket mode's end: the only thing left of a run is its folder of objects. A local store's
    * objects go with the store, which the CLI deletes. Never fails the exit.
    */
  private def removeRun(s3: DemoS3, client: ManagedStoreClient): Unit =
    if !s3.local then
      val outcome =
        try s3.deleteRun(client)
        catch case NonFatal(e) => Left(String.valueOf(e.getMessage))
      outcome match
        case Right(n)  => println(s"demo: deleted $n objects under ${s3.runFolder}")
        case Left(err) =>
          System.err.println(
            s"demo: could not delete ${s3.runFolder}: ${s3.redact(err)}. Remove it by hand."
          )

  /** Blocking, ordered setup: embedded PG -> databases -> config overlay -> seed manifest env ->
    * data seed. `pgRef` is populated as soon as PG starts so `runDemo`'s cleanup can stop it even
    * if a later step here fails.
    */
  private def setup(
      home: DemoHome,
      pgRef: AtomicReference[Option[DemoPostgres]],
      s3: Option[DemoS3],
      prepared: AtomicReference[Option[(DemoS3, ManagedStoreClient)]]
  ): DemoConfigs =
    // RLS/CLS are the whole point of the demo banner's promise, but both are read straight off
    // `com.typesafe.config.ConfigFactory.load()` deep inside `Main.bootManager` (`cls.enabled` /
    // `rls.enabled` under `quack-on-demand`) rather than through the `ManagerConfig` case class
    // `DemoConfig.overlay` copies -- so that overlay can't reach them. Unlike `QOD_BOOTSTRAP_YAML`
    // (a plain `sys.env`-reading Scala function DemoBootstrapHook owns), these are genuine HOCON
    // keys resolved via Typesafe Config, which DOES treat JVM system properties as the
    // highest-priority override layer -- so setting the config keys themselves (not the `QOD_*`
    // env-var aliases) as system properties works. Done first, before `loadBase()`'s pureconfig
    // call ever touches `ConfigFactory`, with an explicit `invalidateCaches()` as a safety net
    // against Typesafe Config's internal system-properties snapshot caching.
    System.setProperty("quack-on-demand.cls.enabled", "true")
    System.setProperty("quack-on-demand.rls.enabled", "true")
    com.typesafe.config.ConfigFactory.invalidateCaches()
    s3.foreach { s =>
      val client = new S3ManagedStoreClient(s.storeConfig)
      s.prepare(client, key => client.putObject(key, Array.emptyByteArray)) match
        case Left(err) => sys.error(s.redact(err))
        case Right(()) => prepared.set(Some((s, client)))
    }
    val pg = DemoPostgres.start(home.pgDir)
    pgRef.set(Some(pg))
    pg.createDatabase("qod")  // control plane
    pg.createDatabase(DbName) // tenant-db DuckLake catalog

    val base    = loadBase()
    val configs = DemoConfig.overlay(base._1, base._2, base._3, pg.coords, home, s3)

    // Point DemoBootstrapHook at the bundled minimal manifest so the manager seeds the control
    // plane on first boot. The hook's env reader is patched (Step 6b) to fall back to system
    // properties, and its readFile already supports the `classpath:` prefix, so no file extraction
    // is needed. The JVM cannot mutate the real process env, hence the sysprop channel.
    System.setProperty("QOD_BOOTSTRAP_YAML", "classpath:bootstrap-demo-minimal.yaml")
    // Liquibase must create qodstate_* before the hook imports; the manager boot does that. The
    // hook runs inside boot after migration. Data seeding, however, needs the tenant-db catalog
    // reachable independently -- run it now against the embedded PG.
    DemoSeed.run(
      pg.coords,
      DbName,
      Schema,
      s3.fold(home.dataPath.toString)(_.defaultDataPath),
      Sf,
      s3.fold[List[String]](Nil)(_.seedStorageSql),
      s3.fold[String => String](identity)(s => s.redact)
    ) match
      case Left(err) => sys.error(err)
      case Right(()) => ()
    configs

  private def loadBase(): (ManagerConfig, FlightConfig, QuackNativeConfig) =
    val src = ConfigSource.default
    (
      src.at("quack-on-demand").loadOrThrow[ManagerConfig],
      src.at("quack-flightsql").loadOrThrow[FlightConfig],
      src.at("quack-native").loadOrThrow[QuackNativeConfig]
    )

  // NOTE (Step 6b): the manifest path is delivered via System.setProperty above; the boot-time
  // DemoBootstrapHook call site (Main.scala:1280) must read it. Change its env reader from
  // `env = sys.env.get` to `env = k => sys.env.get(k).orElse(sys.props.get(k))`. Additive: the real
  // env var still wins for normal boots; only the demo path relies on the sysprop fallback.

  private def bootWithBanner(
      home: DemoHome,
      configs: DemoConfigs,
      s3: Option[DemoS3]
  ): IO[ExitCode] =
    val baseAuthCfg =
      ConfigSource.default
        .at("quack-flightsql.auth")
        .loadOrThrow[ai.starlake.quack.edge.config.AuthenticationConfig]
    // `auth.database.{jdbcUrl,username,password}` is templated in application.conf off
    // `quack-on-demand.defaultMetastore.*` (HOCON substitution at parse time), so reloading it
    // fresh from `ConfigSource.default` here re-resolves against the REAL default metastore coords
    // -- not `configs.manager.defaultMetastore`, which only exists as an in-memory overlay produced
    // by `DemoConfig.overlay` and was never written back to a config source. Left unpatched, the
    // demo's DB-auth backend probes the wrong Postgres (the operator's real dev instance, if any, or
    // a connection-refused) instead of the embedded one -- a silent Guardrail-1-adjacent gap, not
    // the deliberate insecure-posture overlay itself. Point it at the SAME coords + `qod` database
    // DemoConfig.overlay already put in `configs.manager.defaultMetastore`.
    val ms      = configs.manager.defaultMetastore
    val authCfg = baseAuthCfg.copy(
      database = baseAuthCfg.database.copy(
        jdbcUrl = s"jdbc:postgresql://${ms.pgHost}:${ms.pgPort}/${ms.dbName}",
        username = ms.pgUser,
        password = ms.pgPassword
      )
    )
    val opaCfg = ai.starlake.quack.edge.config.OpaConfig
      .validate(
        ConfigSource.default
          .at("quack-flightsql.opa")
          .loadOrThrow[ai.starlake.quack.edge.config.OpaConfig]
      )
      .fold(err => throw new IllegalArgumentException(err), identity)
    val metricsCfg =
      ConfigSource.default
        .at("quack-on-demand.metrics")
        .loadOrThrow[ai.starlake.quack.observability.metrics.MetricsConfig]
    val banner = DemoBanner.render(
      restPort = configs.manager.port,
      flightPort = configs.flight.port,
      dataPath = s3.fold(home.dataPath.toString)(_.runFolder),
      rows = "~150K",
      adminNames = configs.manager.admin.usernameList,
      adminPasswordLabel = configs.adminPasswordLabel,
      uiHost = if s3.exists(!_.local) then "127.0.0.1" else "localhost",
      storageNotes = s3.fold[List[String]](Nil)(_.bannerNotes)
    )
    // Print the banner once the manager REST port actually accepts connections, so the
    // connect recipes land at the END of the boot output instead of scrolling away under
    // it. Runs on a background fiber (bootManager blocks until shutdown); if the port
    // never comes up within the budget, print anyway - the recipes must never be lost.
    val printWhenReady = IO.blocking {
      DemoBanner.awaitPort("127.0.0.1", configs.manager.port, timeoutMs = 180000L)
      println(banner)
    }
    printWhenReady.start *>
      Main.bootManager(
        configs.manager,
        configs.flight,
        authCfg,
        metricsCfg,
        opaCfg,
        // The control plane is DemoPostgres: trust authentication on loopback, as qod serve's.
        demoControlPlane = true,
        quackCfg = Some(configs.quack)
      )
