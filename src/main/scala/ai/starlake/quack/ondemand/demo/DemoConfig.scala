package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.{FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.config.AclConfig

/** The effective demo configs produced by [[DemoConfig.overlay]]. */
/** `adminPasswordLabel` is what the banner shows as the seeded admins' password: the literal demo
  * default when the overlay supplied it, else a placeholder, so an exported password is never
  * echoed to stdout or captured in logs.
  */
final case class DemoConfigs(
    manager: ManagerConfig,
    flight: FlightConfig,
    acl: AclConfig,
    adminPasswordLabel: String
)

/** Produces the demo posture by copying the base configs with demo overrides.
  *
  * Guardrail 1: this overlay is the ONLY place the demo settings (the advertised demo admin
  * password, the throwaway metastore) are produced, and it is called ONLY from
  * [[DemoRunner.runDemo]]. `normalManagerRun` never invokes it, so a normal boot cannot acquire the
  * demo posture. Case-class `copy` returns new instances; the base configs are never mutated.
  *
  * The FlightSQL edge keeps TLS ON: the edge auto-generates a self-signed cert at boot
  * (`FlightEdgeServer.ensureCertFiles`), pointed under the ephemeral demo home so `qod demo`
  * neither litters the invoking directory nor reuses a stale cert across runs. Clients still skip
  * verification (self-signed), so the banner snippets carry the skip-verify knob.
  */
object DemoConfig:

  val DefaultAdminPassword     = "admin"
  val ChosenAdminPasswordLabel = "<QOD_ADMIN_PASSWORD>"

  def overlay(
      baseManager: ManagerConfig,
      baseFlight: FlightConfig,
      baseAcl: AclConfig,
      pg: PgCoords,
      home: DemoHome
  ): DemoConfigs =
    val passwordDefaulted = baseManager.admin.password.isEmpty
    val metastore         = baseManager.defaultMetastore.copy(
      pgHost = pg.host,
      pgPort = pg.port.toString,
      pgUser = pg.user,
      pgPassword = pg.password,
      dbName = "qod",
      dataPath = home.dataPath.toString
    )
    val manager = baseManager.copy(
      runtimeType = "local",
      // The demo defaults the admin password to `admin`; an exported QOD_ADMIN_PASSWORD still wins.
      admin =
        if passwordDefaulted then baseManager.admin.copy(password = DefaultAdminPassword)
        else baseManager.admin,
      defaultMetastore = metastore
    )
    val flight = baseFlight.copy(
      tlsEnabled = true,
      port = 31338,
      tlsCertChain = home.root.resolve("certs/server-cert.pem").toString,
      tlsPrivateKey = home.root.resolve("certs/server-key.pem").toString
    )
    val acl = baseAcl.copy(enabled = true)
    DemoConfigs(
      manager,
      flight,
      acl,
      adminPasswordLabel =
        if passwordDefaulted then DefaultAdminPassword else ChosenAdminPasswordLabel
    )
