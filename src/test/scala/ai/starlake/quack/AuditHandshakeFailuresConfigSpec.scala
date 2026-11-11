package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderException

/** Pins `telemetry.auditHandshakeFailures` (issue #202): refused FlightSQL and native Quack
  * handshakes are audited only when the operator turns it on.
  */
class AuditHandshakeFailuresConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    val conf = ConfigFactory
      .parseString(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  "telemetry.auditHandshakeFailures" should "be off by default" in {
    TelemetryConfig().auditHandshakeFailures shouldBe false
    load().telemetry.auditHandshakeFailures shouldBe false
  }

  it should "turn on from QOD_AUDIT_HANDSHAKE_FAILURES" in {
    load(Map("QOD_AUDIT_HANDSHAKE_FAILURES" -> "true")).telemetry.auditHandshakeFailures shouldBe
      true
    load(Map("QOD_AUDIT_HANDSHAKE_FAILURES" -> "false")).telemetry.auditHandshakeFailures shouldBe
      false
  }

  it should "be refused at config load when it is no boolean" in {
    a[ConfigReaderException[?]] should be thrownBy
      load(Map("QOD_AUDIT_HANDSHAKE_FAILURES" -> "maybe"))
  }

  it should "register under its variable, not sensitive" in {
    ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .find(_.path == "quack-on-demand.telemetry.auditHandshakeFailures")
      .map(e => (e.envVar, e.sensitive)) shouldBe
      Some(("QOD_AUDIT_HANDSHAKE_FAILURES", false))
  }
