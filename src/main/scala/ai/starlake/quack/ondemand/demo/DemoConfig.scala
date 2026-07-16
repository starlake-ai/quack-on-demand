package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.{FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.config.AclConfig

/** The effective demo configs produced by [[DemoConfig.overlay]]. */
final case class DemoConfigs(manager: ManagerConfig, flight: FlightConfig, acl: AclConfig)

/** Produces the demo posture by copying the base configs with demo overrides.
  *
  * Guardrail 1: this overlay is the ONLY place the insecure demo settings (TLS off, REST open) are
  * produced, and it is called ONLY from [[DemoRunner.runDemo]]. `normalManagerRun` never invokes
  * it, so a normal boot cannot acquire the demo posture. Case-class `copy` returns new instances;
  * the base configs are never mutated.
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
      defaultMetastore = metastore
    )
    val flight = baseFlight.copy(tlsEnabled = false, port = 31338)
    val acl    = baseAcl.copy(enabled = true)
    DemoConfigs(manager, flight, acl)
