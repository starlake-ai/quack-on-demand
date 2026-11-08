package ai.starlake.quack.ondemand.security

import ai.starlake.quack.ondemand.security.checks.SecurityChecks
import cats.effect.unsafe.implicits.global
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IdentityDataFleetChecksSpec extends AnyFlatSpec with Matchers:

  // The checks read real paths: every path a check reads must exist in the shipped configuration.
  private def cfg(hocon: String): Config =
    ConfigFactory.parseString(hocon).withFallback(ConfigFactory.load())
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
      "id.disabled-account-pats",
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

  it should "warn for the system login under identitySource=oidc, with no OIDC tenant" in {
    val sys =
      cfg("""quack-on-demand.auth.management { identitySource = "oidc", publicBaseUrl = "" }""")
    val o = run("id.sso-redirect-default", sys, StubAuditFacts())
    o.status shouldBe Status.Warn
    o.detail should include("QOD_MGMT_IDENTITY_SOURCE=oidc")
    o.detail should not include "Tenants"
    val both = run("id.sso-redirect-default", sys, StubAuditFacts(oidc = List("acme")))
    both.status shouldBe Status.Warn
    both.detail should include("QOD_MGMT_IDENTITY_SOURCE=oidc")
    both.detail should include("acme")
    run(
      "id.sso-redirect-default",
      cfg(
        """quack-on-demand.auth.management { identitySource = "OIDC", publicBaseUrl = "https://q" }"""
      ),
      StubAuditFacts()
    ).status shouldBe Status.Ok
    run(
      "id.sso-redirect-default",
      cfg("""quack-on-demand.auth.management { identitySource = "db", publicBaseUrl = "" }"""),
      StubAuditFacts()
    ).status shouldBe Status.Na
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

  "id.disabled-account-pats" should "warn on dormant tokens" in {
    run(
      "id.disabled-account-pats",
      f = StubAuditFacts(dormantPats = 2)
    ).status shouldBe Status.Warn
    run("id.disabled-account-pats").status shouldBe Status.Ok
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
  }

  it should "warn under requireEncryption about the databases created before it was set" in {
    val on = cfg("quack-on-demand.requireEncryption = true")
    val o  =
      run("data.unencrypted", on, StubAuditFacts(unencrypted = List("acme" -> 2, "globex" -> 1)))
    o.status shouldBe Status.Warn
    o.detail should startWith(
      "3 databases created before QOD_REQUIRE_ENCRYPTION was set are unencrypted"
    )
    o.detail should include("2 in acme")
    o.fix shouldBe "Encryption is chosen at create time only: recreate them encrypted."
    run(
      "data.unencrypted",
      on,
      StubAuditFacts(unencrypted = List("acme" -> 1))
    ).detail should startWith(
      "1 database created before QOD_REQUIRE_ENCRYPTION was set is unencrypted"
    )
    run("data.unencrypted", on, StubAuditFacts()).status shouldBe Status.Ok
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

  "fleet.auto-approve-open" should "judge entries as the manager does" in {
    def fleet(approve: String) =
      cfg(s"""quack-on-demand { runtimeType = "fleet", fleet.autoApprove = "$approve" }""")
    val v6 = run("fleet.auto-approve-open", fleet("::0/0"))
    v6.status shouldBe Status.Critical
    v6.detail should include("::0/0")
    val v4 = run("fleet.auto-approve-open", fleet("10.0.0.0/0"))
    v4.status shouldBe Status.Critical
    v4.detail should include("10.0.0.0/0")
    run("fleet.auto-approve-open", fleet("10.0.0.0/8")).status shouldBe Status.Ok
    an[IllegalArgumentException] should be thrownBy run(
      "fleet.auto-approve-open",
      fleet("nonsense")
    )
  }

  "fleet.unbound-approvals" should "name the servers" in {
    val c = cfg("""quack-on-demand.runtimeType = "fleet"""")
    run(
      "fleet.unbound-approvals",
      c,
      StubAuditFacts(unboundServers = List("srv-1"))
    ).detail should include("srv-1")
  }

  "node.local-files" should "be ok when tenant folders are authorized, and word the count" in {
    val withRoot =
      cfg("""quack-on-demand { nodeFiles.allowLocal = true, tenantFiles.root = "/srv/t" }""")
    val o = run(
      "node.local-files",
      withRoot,
      StubAuditFacts(failWith = Some(new RuntimeException("not read")))
    )
    o.status shouldBe Status.Ok
    o.detail shouldBe "Tenant folders are authorized under QOD_TENANT_FILES_ROOT, so nodes need local files."
    val on = cfg("""quack-on-demand { nodeFiles.allowLocal = true, tenantFiles.root = "" }""")
    run("node.local-files", on, StubAuditFacts(local = List("a/b"))).detail should startWith(
      "1 database keeps"
    )
    run("node.local-files", on, StubAuditFacts(local = List("a/b", "a/c"))).detail should startWith(
      "2 databases keep"
    )
  }

  "node.local-files" should "be ok when a memory database may read local files" in {
    val on = cfg("""quack-on-demand { nodeFiles.allowLocal = true, tenantFiles.root = "" }""")
    val o  = run("node.local-files", on, StubAuditFacts(memory = List("acme/acme_files")))
    o.status shouldBe Status.Ok
    o.detail should include("memory databases")
    o.detail should include("acme/acme_files")
    o.detail should include("local files")
  }

  it should "word the warning so that it also reads right with no database at all" in {
    val on = cfg("""quack-on-demand { nodeFiles.allowLocal = true, tenantFiles.root = "" }""")
    val o  = run("node.local-files", on, StubAuditFacts())
    o.status shouldBe Status.Warn
    o.detail shouldBe "No database keeps its data on a local path or is a memory database, yet nodes may open local files."
  }

  "node.community-extensions" should "name each setting that is on" in {
    val one = run(
      "node.community-extensions",
      cfg("quack-on-demand.nodeExtensions { allowCommunity = true, autoinstallKnown = false }")
    )
    one.status shouldBe Status.Warn
    one.detail should include("QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS")
    one.detail should not include "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS"
    val both = run(
      "node.community-extensions",
      cfg("quack-on-demand.nodeExtensions { allowCommunity = true, autoinstallKnown = true }")
    )
    both.detail should include("QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS")
    both.detail should include("QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS")
  }

  "id.lockout-off" should "only mention SMTP when none is configured" in {
    val noSmtp = run(
      "id.lockout-off",
      cfg("""quack-on-demand { auth.lockout.enabled = false, smtp.host = "" }""")
    )
    noSmtp.fix should include("QOD_SMTP_HOST")
    val smtp = run(
      "id.lockout-off",
      cfg("""quack-on-demand { auth.lockout.enabled = false, smtp.host = "mail" }""")
    )
    smtp.fix shouldBe "Set QOD_AUTH_LOCKOUT_ENABLED=true."
  }
