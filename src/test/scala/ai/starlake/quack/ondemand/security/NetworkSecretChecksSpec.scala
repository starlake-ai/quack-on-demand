package ai.starlake.quack.ondemand.security

import ai.starlake.quack.edge.CertGen
import ai.starlake.quack.ondemand.security.checks.{NetworkChecks, SecretChecks}
import cats.effect.unsafe.implicits.global
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class NetworkSecretChecksSpec extends AnyFlatSpec with Matchers:

  // The checks read real paths: every path a check reads must exist in the shipped configuration.
  private def cfg(hocon: String): Config =
    ConfigFactory.parseString(hocon).withFallback(ConfigFactory.load())

  private def status(
      checks: List[SecurityCheck],
      id: String,
      c: Config,
      facts: AuditFacts = StubAuditFacts()
  ) =
    checks.find(_.id == id).get.run(AuditContext(c, facts)).unsafeRunSync()

  "tls.flight-off" should "be critical when FlightSQL TLS is off, ok otherwise" in {
    status(
      NetworkChecks.all,
      "tls.flight-off",
      cfg("quack-flightsql.tlsEnabled = false")
    ).status shouldBe Status.Critical
    status(
      NetworkChecks.all,
      "tls.flight-off",
      cfg("quack-flightsql.tlsEnabled = true")
    ).status shouldBe Status.Ok
  }

  "tls.flight-self-signed" should "warn on a self-signed certificate, na with TLS off, fail on a missing file" in {
    val dir  = Files.createTempDirectory("qod-audit-cert")
    val cert = dir.resolve("server-cert.pem").toString
    CertGen.ensureCertFiles(cert, dir.resolve("server-key.pem").toString)
    val on = cfg(s"""quack-flightsql.tlsEnabled = true
                    |quack-flightsql.tlsCertChain = "$cert"""".stripMargin)
    status(NetworkChecks.all, "tls.flight-self-signed", on).status shouldBe Status.Warn
    status(
      NetworkChecks.all,
      "tls.flight-self-signed",
      cfg("quack-flightsql.tlsEnabled = false")
    ).status shouldBe Status.Na
    val missing = cfg(
      s"""quack-flightsql.tlsEnabled = true
                         |quack-flightsql.tlsCertChain = "${dir.resolve("nope.pem")}"""".stripMargin
    )
    an[Exception] should be thrownBy status(NetworkChecks.all, "tls.flight-self-signed", missing)
  }

  "tls.quack-off-exposed" should "be critical only with TLS off on a non-loopback bind" in {
    def q(enabled: Boolean, tls: Boolean, host: String) =
      status(
        NetworkChecks.all,
        "tls.quack-off-exposed",
        cfg(s"""quack-native { enabled = $enabled, tlsEnabled = $tls, host = "$host" }""")
      ).status
    q(enabled = true, tls = false, host = "0.0.0.0") shouldBe Status.Critical
    q(enabled = true, tls = false, host = "127.0.0.1") shouldBe Status.Ok
    q(enabled = true, tls = true, host = "0.0.0.0") shouldBe Status.Ok
    q(enabled = false, tls = false, host = "0.0.0.0") shouldBe Status.Na
  }

  "net.cookie-insecure" should "be critical only for an explicit false" in {
    def c(v: String) = status(
      NetworkChecks.all,
      "net.cookie-insecure",
      cfg(s"""quack-on-demand.auth.management.sessionCookieSecure = "$v"""")
    ).status
    c("false") shouldBe Status.Critical
    c("auto") shouldBe Status.Ok
    c("true") shouldBe Status.Ok
  }

  "secret.pg-default-password" should "be critical when either password is the shipped default" in {
    def p(meta: String, auth: String) = status(
      SecretChecks.all,
      "secret.pg-default-password",
      cfg(s"""quack-on-demand.defaultMetastore.pgPassword = "$meta"
             |quack-flightsql.auth.database.password = "$auth"""".stripMargin)
    )
    p("azizam", "x").status shouldBe Status.Critical
    p("azizam", "x").detail should include("still holds")
    p("azizam", "azizam").detail should include("still hold ")
    p("x", "azizam").status shouldBe Status.Critical
    val ok = p("s3cret-1", "s3cret-2")
    ok.status shouldBe Status.Ok
    ok.detail should not include "s3cret"
  }

  "secret.pg-default-password and secret.pg-superuser" should "speak of the embedded control plane" in {
    val embedded = cfg("""quack-on-demand.embeddedPostgres.enabled = true
                         |quack-on-demand.defaultMetastore.pgPassword = "azizam"
                         |quack-flightsql.auth.database.password = "azizam"""".stripMargin)
    val pw = status(SecretChecks.all, "secret.pg-default-password", embedded)
    pw.status shouldBe Status.Warn
    pw.detail shouldBe "The embedded control-plane Postgres lets any local OS user connect as its superuser without a password (trust authentication on loopback)."
    pw.fix shouldBe "Run the manager against a Postgres of your own (QOD_PG_*) in production."
    val su = status(
      SecretChecks.all,
      "secret.pg-superuser",
      embedded,
      StubAuditFacts(superuserLogin = true)
    )
    su.status shouldBe Status.Na
    su.detail should include("embedded control plane")
  }

  "secret.pg-superuser" should "warn when the control-plane login is a superuser" in {
    status(
      SecretChecks.all,
      "secret.pg-superuser",
      cfg(""),
      StubAuditFacts(superuserLogin = true)
    ).status shouldBe Status.Warn
    status(
      SecretChecks.all,
      "secret.pg-superuser",
      cfg(""),
      StubAuditFacts()
    ).status shouldBe Status.Ok
  }

  "secret.session-jwt-stored and secret.node-key-stored" should "warn when unset, ok when configured" in {
    for (id, path) <- List(
        "secret.session-jwt-stored" -> "quack-on-demand.auth.management.sessionJwtSecret",
        "secret.node-key-stored"    -> "quack-on-demand.nodeAuthz.keySecret"
      )
    do
      status(SecretChecks.all, id, cfg(s"""$path = """"")).status shouldBe Status.Warn
      val ok = status(SecretChecks.all, id, cfg(s"""$path = "a-configured-secret-of-32-chars-x""""))
      ok.status shouldBe Status.Ok
      ok.detail should not include "a-configured-secret"
  }
