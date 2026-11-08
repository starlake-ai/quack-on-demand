package ai.starlake.quack.ondemand.security

import ai.starlake.quack.{FlightConfig, ManagerConfig, QuackNativeConfig}
import ai.starlake.quack.edge.config.AuthenticationConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class AuditConfigSpec extends AnyFlatSpec with Matchers:
  import ai.starlake.quack.Main.given

  private val live    = ConfigFactory.load()
  private val manager = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
  private val flight  = ConfigSource.default.at("quack-flightsql").loadOrThrow[FlightConfig]
  private val quack   = ConfigSource.default.at("quack-native").loadOrThrow[QuackNativeConfig]
  private val auth    =
    ConfigSource.default.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig]

  "AuditConfig.resolved" should "read what the boot resolved, not what the files say" in {
    // What `qod serve` (the embedded control plane) and the demo overlay rewrite at boot.
    val booted = AuditConfig.resolved(
      live,
      manager.copy(
        runtimeType = "local",
        defaultMetastore = manager.defaultMetastore.copy(pgPassword = "booted-pg"),
        embeddedPostgres = manager.embeddedPostgres.copy(enabled = true)
      ),
      flight.copy(tlsEnabled = true, tlsCertChain = "/demo/home/certs/server-cert.pem"),
      quack.copy(enabled = true, host = "127.0.0.1", tlsEnabled = true),
      auth.copy(database = auth.database.copy(password = "booted-auth"))
    )
    booted.getString("quack-on-demand.runtimeType") shouldBe "local"
    booted.getString("quack-on-demand.defaultMetastore.pgPassword") shouldBe "booted-pg"
    booted.getBoolean("quack-on-demand.embeddedPostgres.enabled") shouldBe true
    booted.getString("quack-flightsql.auth.database.password") shouldBe "booted-auth"
    booted.getBoolean("quack-flightsql.tlsEnabled") shouldBe true
    booted.getString("quack-flightsql.tlsCertChain") shouldBe "/demo/home/certs/server-cert.pem"
    booted.getBoolean("quack-native.enabled") shouldBe true
    booted.getString("quack-native.host") shouldBe "127.0.0.1"
    booted.getBoolean("quack-native.tlsEnabled") shouldBe true
  }

  it should "keep the configured session secret, which the boot fills in from the control plane" in {
    // BootPreflight.withSessionSecret puts the stored secret into the manager config; the check
    // asks whether one is configured, so the live value must win.
    val booted = AuditConfig.resolved(
      live,
      manager.copy(auth =
        manager.auth.copy(management =
          manager.auth.management.copy(sessionJwtSecret = "stored-in-the-control-plane")
        )
      ),
      flight,
      quack,
      auth
    )
    booted.getString("quack-on-demand.auth.management.sessionJwtSecret") shouldBe
      live.getString("quack-on-demand.auth.management.sessionJwtSecret")
  }

  it should "fall back to the live configuration for every other path" in {
    val booted = AuditConfig.resolved(live, manager, flight, quack, auth)
    booted.getBoolean("quack-on-demand.nodeFiles.allowLocal") shouldBe
      live.getBoolean("quack-on-demand.nodeFiles.allowLocal")
    booted.getString("quack-on-demand.fleet.autoApprove") shouldBe
      live.getString("quack-on-demand.fleet.autoApprove")
  }
