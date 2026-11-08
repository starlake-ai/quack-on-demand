package ai.starlake.quack.ondemand.security

import ai.starlake.quack.ondemand.security.checks.SecurityChecks
import cats.effect.unsafe.implicits.global
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IdentityDataFleetChecksSpec extends AnyFlatSpec with Matchers:

  private def cfg(hocon: String): Config = ConfigFactory.parseString(hocon)
  private def run(id: String, c: Config = cfg(""), f: AuditFacts = StubAuditFacts()): Outcome =
    SecurityChecks.all
      .find(_.id == id)
      .getOrElse(fail(s"no check $id"))
      .run(AuditContext(c, f))
      .unsafeRunSync()

  "the registry" should "hold every check of the spec once" in {
    SecurityChecks.all.map(_.id) shouldBe List(
      "tls.flight-off",
      "tls.flight-self-signed",
      "tls.quack-off-exposed",
      "net.cookie-insecure",
      "secret.pg-default-password",
      "secret.pg-superuser",
      "secret.session-jwt-stored",
      "secret.node-key-stored",
      "id.lockout-off",
      "id.reset-link-relative",
      "id.sso-redirect-default",
      "id.permissive-builtins",
      "id.disabled-superuser-pats",
      "node.unsigned-extensions",
      "node.community-extensions",
      "node.local-files",
      "data.s3-endpoints-plain-http",
      "data.unencrypted",
      "data.untrusted-initsql",
      "data.writable-federation-with-secrets",
      "data.branch-no-ttl",
      "fleet.auto-approve-open",
      "fleet.unbound-approvals"
    )
    SecurityChecks.all.map(_.docsPath).foreach(_ should startWith("/operating/"))
  }

  "id.lockout-off" should "warn while lockout is off" in {
    run(
      "id.lockout-off",
      cfg("quack-on-demand.auth.lockout.enabled = false")
    ).status shouldBe Status.Warn
    run(
      "id.lockout-off",
      cfg("quack-on-demand.auth.lockout.enabled = true")
    ).status shouldBe Status.Ok
  }

  "id.reset-link-relative" should "warn with SMTP on and no public base URL, na without SMTP" in {
    run(
      "id.reset-link-relative",
      cfg("""quack-on-demand { smtp.host = "mail", publicBaseUrl = "" }""")
    ).status shouldBe Status.Warn
    run(
      "id.reset-link-relative",
      cfg("""quack-on-demand { smtp.host = "mail", publicBaseUrl = "https://q" }""")
    ).status shouldBe Status.Ok
    run("id.reset-link-relative", cfg("")).status shouldBe Status.Na
  }

  "id.sso-redirect-default" should "warn with an OIDC tenant and no management base URL" in {
    val noUrl = cfg("""quack-on-demand.auth.management.publicBaseUrl = """"")
    val o     = run("id.sso-redirect-default", noUrl, StubAuditFacts(oidc = List("acme")))
    o.status shouldBe Status.Warn
    o.detail should include("acme")
    run("id.sso-redirect-default", noUrl, StubAuditFacts()).status shouldBe Status.Na
    run(
      "id.sso-redirect-default",
      cfg("""quack-on-demand.auth.management.publicBaseUrl = "https://q""""),
      StubAuditFacts(oidc = List("acme"))
    ).status shouldBe Status.Ok
  }

  "id.permissive-builtins" should "warn with counts per tenant" in {
    val o = run(
      "id.permissive-builtins",
      f = StubAuditFacts(permissive = List("acme" -> 12, "globex" -> 3))
    )
    o.status shouldBe Status.Warn
    o.detail should include("12 in acme")
    o.detail should include("3 in globex")
    run("id.permissive-builtins").status shouldBe Status.Ok
  }

  "id.disabled-superuser-pats" should "warn on dormant tokens" in {
    run(
      "id.disabled-superuser-pats",
      f = StubAuditFacts(dormantPats = 2)
    ).status shouldBe Status.Warn
    run("id.disabled-superuser-pats").status shouldBe Status.Ok
  }

  "node.unsigned-extensions and node.community-extensions" should "grade unsigned critical, the others warn" in {
    run(
      "node.unsigned-extensions",
      cfg("quack-on-demand.nodeExtensions.allowUnsigned = true")
    ).status shouldBe Status.Critical
    run(
      "node.unsigned-extensions",
      cfg("quack-on-demand.nodeExtensions.allowUnsigned = false")
    ).status shouldBe Status.Ok
    val w = run(
      "node.community-extensions",
      cfg("quack-on-demand.nodeExtensions { allowCommunity = false, autoinstallKnown = true }")
    )
    w.status shouldBe Status.Warn
    w.detail should include("QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS")
    run(
      "node.community-extensions",
      cfg("quack-on-demand.nodeExtensions { allowCommunity = false, autoinstallKnown = false }")
    ).status shouldBe Status.Ok
  }

  "node.local-files" should "warn when local files are allowed and no database is local" in {
    val on = cfg("quack-on-demand.nodeFiles.allowLocal = true")
    run("node.local-files", on, StubAuditFacts()).status shouldBe Status.Warn
    run(
      "node.local-files",
      on,
      StubAuditFacts(local = List("acme/sales"))
    ).status shouldBe Status.Ok
    run(
      "node.local-files",
      cfg("quack-on-demand.nodeFiles.allowLocal = false")
    ).status shouldBe Status.Ok
  }

  "data.s3-endpoints-plain-http" should "name the plain-http entries" in {
    val o = run(
      "data.s3-endpoints-plain-http",
      cfg("""quack-on-demand.userS3.allowedEndpoints = "minio.lan:9000,http://seaweed:8333"""")
    )
    o.status shouldBe Status.Warn
    o.detail should include("http://seaweed:8333")
    o.detail should not include "minio.lan"
    run(
      "data.s3-endpoints-plain-http",
      cfg("""quack-on-demand.userS3.allowedEndpoints = """"")
    ).status shouldBe Status.Ok
  }

  "data.unencrypted" should "warn without requireEncryption when unencrypted databases exist" in {
    val off = cfg("quack-on-demand.requireEncryption = false")
    run(
      "data.unencrypted",
      off,
      StubAuditFacts(unencrypted = List("acme" -> 2))
    ).status shouldBe Status.Warn
    run("data.unencrypted", off, StubAuditFacts()).status shouldBe Status.Ok
    run(
      "data.unencrypted",
      cfg("quack-on-demand.requireEncryption = true"),
      StubAuditFacts(unencrypted = List("acme" -> 2))
    ).status shouldBe Status.Ok
  }

  "data.untrusted-initsql, data.writable-federation-with-secrets, data.branch-no-ttl" should "warn and name what they found" in {
    val f = StubAuditFacts(
      untrusted = List("database acme/sales"),
      writableSources = List("acme/sales/pg"),
      noTtlBranches = List("acme/sales/feature")
    )
    for (id, name) <- List(
        "data.untrusted-initsql"                -> "database acme/sales",
        "data.writable-federation-with-secrets" -> "acme/sales/pg",
        "data.branch-no-ttl"                    -> "acme/sales/feature"
      )
    do
      val o = run(id, f = f)
      o.status shouldBe Status.Warn
      o.detail should include(name)
      run(id).status shouldBe Status.Ok
  }

  "fleet checks" should "be na off the fleet runtime" in {
    run(
      "fleet.auto-approve-open",
      cfg("""quack-on-demand.runtimeType = "local"""")
    ).status shouldBe Status.Na
    run(
      "fleet.unbound-approvals",
      cfg("""quack-on-demand.runtimeType = "local"""")
    ).status shouldBe Status.Na
  }

  they should "grade an open auto-approve critical and unbound approvals warn" in {
    def fleet(approve: String) =
      cfg(s"""quack-on-demand { runtimeType = "fleet", fleet.autoApprove = "$approve" }""")
    run("fleet.auto-approve-open", fleet("0.0.0.0/0,::/0")).status shouldBe Status.Critical
    run("fleet.auto-approve-open", fleet("10.0.3.0/24,::/0")).status shouldBe Status.Critical
    run("fleet.auto-approve-open", fleet("10.0.3.0/24")).status shouldBe Status.Ok
    run("fleet.auto-approve-open", fleet("")).status shouldBe Status.Ok
    run(
      "fleet.unbound-approvals",
      fleet(""),
      StubAuditFacts(unboundServers = List("srv-1"))
    ).status shouldBe Status.Warn
    run("fleet.unbound-approvals", fleet(""), StubAuditFacts()).status shouldBe Status.Ok
  }
