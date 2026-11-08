package ai.starlake.quack.ondemand.security

import ai.starlake.quack.{FlightConfig, ManagerConfig, QuackNativeConfig}
import ai.starlake.quack.edge.CertGen
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.ondemand.security.checks.SecurityChecks
import cats.effect.unsafe.implicits.global
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import pureconfig.ConfigSource

/** Every configuration path a check reads exists in the shipped configuration (as Main hands it to
  * the audit, through [[AuditConfig.resolved]]): a reader throws on a missing one, so a typo in a
  * check would read as an `error` finding here. The checks read some paths only behind a gate (TLS
  * on, the fleet runtime, an SMTP relay), so the registry runs with every gate off and with every
  * gate on. The certificate is generated, as a missing file is an `error` of its own.
  */
class AuditConfigPathsSpec extends AnyFlatSpec with Matchers:

  import ai.starlake.quack.Main.given

  private val shipped = ConfigFactory.load()

  /** The shipped configuration as Main hands it to the audit: the boot's values laid over it, which
    * adds the overlay's own trust-auth signal.
    */
  private val booted = AuditConfig.resolved(
    shipped,
    ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig],
    ConfigSource.default.at("quack-flightsql").loadOrThrow[FlightConfig],
    ConfigSource.default.at("quack-native").loadOrThrow[QuackNativeConfig],
    ConfigSource.default.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig],
    trustAuthControlPlane = false
  )

  private lazy val cert: String =
    val dir  = Files.createTempDirectory("qod-audit-paths")
    val cert = dir.resolve("server-cert.pem").toString
    CertGen.ensureCertFiles(cert, dir.resolve("server-key.pem").toString)
    cert

  /** The only path a check reads that the shipped configuration leaves without a value. */
  private val NoDefault = Set("quack-on-demand.smtp.host")

  private def overlay(values: (String, AnyRef)*): Config =
    values
      .foldLeft(ConfigFactory.empty()) { case (c, (k, v)) =>
        c.withValue(k, ConfigValueFactory.fromAnyRef(v))
      }
      .withFallback(booted)

  private def errors(gates: Seq[(String, AnyRef)], facts: AuditFacts): List[String] =
    gates.map(_._1).filterNot(NoDefault).foreach { p =>
      withClue(s"gate $p: ")(booted.hasPath(p) shouldBe true)
    }
    SecurityAudit(SecurityChecks.all, AuditContext(overlay(gates*), facts), "r", "v")
      .run()
      .unsafeRunSync()
      .findings
      .filter(_.status == Status.Error)
      .map(_.id)

  "every path a check reads" should "exist with the shipped defaults" in {
    errors(Seq("quack-flightsql.tlsCertChain" -> cert), StubAuditFacts()) shouldBe Nil
  }

  it should "exist with every gate on" in {
    errors(
      Seq(
        "quack-on-demand.runtimeType"                    -> "fleet",
        "quack-flightsql.tlsEnabled"                     -> java.lang.Boolean.TRUE,
        "quack-flightsql.tlsCertChain"                   -> cert,
        "quack-native.enabled"                           -> java.lang.Boolean.TRUE,
        "quack-native.tlsEnabled"                        -> java.lang.Boolean.FALSE,
        "quack-on-demand.smtp.host"                      -> "mail",
        "quack-on-demand.auth.lockout.enabled"           -> java.lang.Boolean.FALSE,
        "quack-on-demand.auth.management.identitySource" -> "oidc",
        "quack-on-demand.nodeFiles.allowLocal"           -> java.lang.Boolean.TRUE,
        "quack-on-demand.requireEncryption"              -> java.lang.Boolean.FALSE,
        AuditConfig.TrustAuthControlPlane                -> java.lang.Boolean.FALSE
      ),
      StubAuditFacts(oidc = List("acme"), unencrypted = List("acme" -> 1))
    ) shouldBe Nil
  }

  it should "exist with every gate off" in {
    errors(
      Seq(
        "quack-on-demand.runtimeType"          -> "local",
        "quack-flightsql.tlsEnabled"           -> java.lang.Boolean.FALSE,
        "quack-native.enabled"                 -> java.lang.Boolean.FALSE,
        "quack-on-demand.auth.lockout.enabled" -> java.lang.Boolean.TRUE,
        "quack-on-demand.nodeFiles.allowLocal" -> java.lang.Boolean.FALSE,
        "quack-on-demand.requireEncryption"    -> java.lang.Boolean.TRUE,
        AuditConfig.TrustAuthControlPlane      -> java.lang.Boolean.TRUE
      ),
      StubAuditFacts()
    ) shouldBe Nil
  }
