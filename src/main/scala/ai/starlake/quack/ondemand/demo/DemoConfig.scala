package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.{FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.config.AclConfig

/** The effective demo configs produced by [[DemoConfig.overlay]]. */
final case class DemoConfigs(manager: ManagerConfig, flight: FlightConfig, acl: AclConfig)

/** Produces the demo posture by copying the base configs with demo overrides.
  *
  * Guardrail 1: this overlay is the ONLY place the insecure demo settings (REST open, demo
  * credentials) are produced, and it is called ONLY from [[DemoRunner.runDemo]]. `normalManagerRun`
  * never invokes it, so a normal boot cannot acquire the demo posture. Case-class `copy` returns
  * new instances; the base configs are never mutated.
  *
  * The FlightSQL edge keeps TLS ON: the edge auto-generates a self-signed cert at boot
  * (`FlightEdgeServer.ensureCertFiles`), pointed under the ephemeral demo home so `qod demo`
  * neither litters the invoking directory nor reuses a stale cert across runs. Clients still skip
  * verification (self-signed), so the banner snippets carry the skip-verify knob.
  */
object DemoConfig:

  def overlay(
      baseManager: ManagerConfig,
      baseFlight: FlightConfig,
      baseAcl: AclConfig,
      pg: PgCoords,
      home: DemoHome
  ): DemoConfigs =
    val metastore = baseManager.defaultMetastore.copy(
      pgHost = pg.host,
      pgPort = pg.port.toString,
      pgUser = pg.user,
      pgPassword = pg.password,
      dbName = "qod",
      dataPath = home.dataPath.toString
    )
    val manager = baseManager.copy(
      runtimeType = "local",
      apiKey = None,
      defaultMetastore = metastore,
      // The demo must run unmodified on any host: the JNI native client
      // (`quackwire`) is an ABI-pinned, opt-in-classifier native lib (see
      // CLAUDE.md "JVM forking" / "native installers") that can fail to load
      // on a glibc it wasn't built against (observed: `UnsatisfiedLinkError:
      // GLIBC_2.32 not found`). The plain HTTP client (`QuackHttpClient`'s
      // non-native path) is the documented fallback for exactly this case, so
      // the demo pins it rather than inheriting whatever `QOD_NATIVE_CLIENT`
      // happens to be set to in the operator's shell.
      nativeClient = false
    )
    val flight = baseFlight.copy(
      tlsEnabled = true,
      port = 31338,
      tlsCertChain = home.root.resolve("certs/server-cert.pem").toString,
      tlsPrivateKey = home.root.resolve("certs/server-key.pem").toString
    )
    val acl = baseAcl.copy(enabled = true)
    DemoConfigs(manager, flight, acl)
