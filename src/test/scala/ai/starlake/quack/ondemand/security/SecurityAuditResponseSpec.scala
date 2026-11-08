package ai.starlake.quack.ondemand.security

import ai.starlake.quack.ondemand.api.SecurityAuditResponse
import ai.starlake.quack.ondemand.api.SecurityAuditResponse.given
import ai.starlake.quack.ondemand.security.checks.SecurityChecks
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecurityAuditResponseSpec extends AnyFlatSpec with Matchers:

  private val Sentinel = "SENTINEL-c0ffee-7d1a"

  "the audit answer" should "never carry a configured secret" in {
    val cfg = ConfigFactory
      .parseString(
        s"""quack-on-demand.defaultMetastore.pgPassword = "$Sentinel"
           |quack-flightsql.auth.db.password = "$Sentinel"
           |quack-on-demand.auth.management.sessionJwtSecret = "$Sentinel"
           |quack-on-demand.nodeAuthz.keySecret = "$Sentinel"
           |quack-on-demand.managedObjectStore.secretAccessKey = "$Sentinel"
           |quack-on-demand.smtp.password = "$Sentinel"
           |quack-flightsql.opa.token = "$Sentinel"
           |quack-on-demand.userS3.allowedEndpoints = "http://$Sentinel:9000"
           |quack-flightsql.tlsEnabled = false
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load())
    val report = SecurityAudit(
      SecurityChecks.all,
      AuditContext(cfg, StubAuditFacts()),
      "r",
      "v"
    ).run().unsafeRunSync()
    val json = SecurityAuditResponse.of(report).asJson.noSpaces
    report.findings.map(_.id).toSet.size shouldBe SecurityChecks.all.size
    // The plain-http endpoint is a host name, not a secret: the check names it on purpose. Every other
    // sentinel must be absent.
    json.replace(s"http://$Sentinel:9000", "") should not include Sentinel
  }
