package ai.starlake.quack.ondemand.security

import ai.starlake.quack.{FlightConfig, ManagerConfig, QuackNativeConfig}
import ai.starlake.quack.edge.config.AuthenticationConfig
import com.typesafe.config.{Config, ConfigFactory}
import scala.jdk.CollectionConverters.*

/** The configuration the audit judges: the live one with the values the boot resolved laid over it,
  * for every path a check reads that a boot path rewrites. `qod serve`'s embedded control plane
  * (`EmbeddedControlPlane.applyCoordinates` / `applyAuthCoordinates`) replaces both Postgres
  * passwords; the demo overlay (`DemoConfig.overlay`) sets the runtime, turns FlightSQL TLS on with
  * a certificate under the demo home and binds the native front door to loopback. The checks keep
  * reading paths.
  *
  * Left out on purpose: `auth.management.sessionJwtSecret`, which `BootPreflight.withSessionSecret`
  * fills in from the control plane when none is configured, while `secret.session-jwt-stored` asks
  * whether one is configured.
  */
object AuditConfig:

  def resolved(
      live: Config,
      manager: ManagerConfig,
      flight: FlightConfig,
      quack: QuackNativeConfig,
      auth: AuthenticationConfig
  ): Config =
    val booted: Map[String, AnyRef] = Map(
      "quack-on-demand.runtimeType"                 -> manager.runtimeType,
      "quack-on-demand.defaultMetastore.pgPassword" -> manager.defaultMetastore.pgPassword,
      "quack-on-demand.embeddedPostgres.enabled" -> Boolean.box(manager.embeddedPostgres.enabled),
      "quack-flightsql.auth.database.password"   -> auth.database.password,
      "quack-flightsql.tlsEnabled"               -> Boolean.box(flight.tlsEnabled),
      "quack-flightsql.tlsCertChain"             -> flight.tlsCertChain,
      "quack-native.enabled"                     -> Boolean.box(quack.enabled),
      "quack-native.host"                        -> quack.host,
      "quack-native.tlsEnabled"                  -> Boolean.box(quack.tlsEnabled)
    )
    ConfigFactory.parseMap(booted.asJava, "values the manager resolved at boot").withFallback(live)
