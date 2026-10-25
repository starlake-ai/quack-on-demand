package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantAclSpec extends AnyFlatSpec with Matchers:

  "TenantAcl.effectiveMode" should "fall back to the manager default when unset" in:
    TenantAcl().effectiveMode("qod") shouldBe "qod"
    TenantAcl().effectiveMode("opa") shouldBe "opa"
    TenantAcl(mode = Some("opa")).effectiveMode("qod") shouldBe "opa"
    TenantAcl(mode = Some("qod")).isOpa("opa") shouldBe false

  "TenantAcl.isOpa" should "fail closed: any mode other than exactly qod routes to OPA" in:
    TenantAcl(mode = Some("OPA")).isOpa("qod") shouldBe true
    TenantAcl(mode = Some("Qod")).isOpa("qod") shouldBe true
    TenantAcl(mode = Some("bogus")).isOpa("qod") shouldBe true
    TenantAcl(mode = Some("opa")).isOpa("qod") shouldBe true
    TenantAcl().isOpa("qod") shouldBe false
    TenantAcl().isOpa("opa") shouldBe true

  "TenantAcl.effectiveUrl" should "prefer the tenant URL and ignore a blank manager URL" in:
    TenantAcl(opaUrl = Some("http://t:8181")).effectiveUrl("http://m:8181") shouldBe
      Some("http://t:8181")
    TenantAcl().effectiveUrl("http://m:8181") shouldBe Some("http://m:8181")
    TenantAcl().effectiveUrl("  ") shouldBe None

  "TenantAcl.effectivePolicyPath" should "default to qod/authz" in:
    TenantAcl().effectivePolicyPath shouldBe "qod/authz"
    TenantAcl(opaPolicyPath = Some("acme/data")).effectivePolicyPath shouldBe "acme/data"

  "TenantAcl.validPolicyPath" should "accept slash-separated identifiers only" in:
    TenantAcl.validPolicyPath("qod/authz") shouldBe true
    TenantAcl.validPolicyPath("a_1/b2") shouldBe true
    Seq("", "/qod", "qod/", "qod//authz", "qod/../x", "qod?x=1", "qod authz", "qod/a.b")
      .foreach(p => withClue(p)(TenantAcl.validPolicyPath(p) shouldBe false))

  "TenantAcl.validUrl" should "accept absolute http(s) URLs with a host only" in:
    TenantAcl.validUrl("http://opa:8181") shouldBe true
    TenantAcl.validUrl("https://opa.example.com/") shouldBe true
    Seq("opa:8181", "ftp://opa", "http://", "not a url", "")
      .foreach(u => withClue(u)(TenantAcl.validUrl(u) shouldBe false))

  it should "reject a URL carrying credentials (userinfo)" in:
    Seq("http://user:pw@opa:8181", "https://tok@opa.example.com/", "http://:pw@opa")
      .foreach(u => withClue(u)(TenantAcl.validUrl(u) shouldBe false))

  "TenantAcl.toString" should "redact opaToken so no log line can ever print it" in:
    val withToken = TenantAcl(opaToken = Some("s3cr3t-x"))
    withToken.toString should not include "s3cr3t-x"
    withToken.toString should include(s"Some(${FederatedSecret.RedactedMarker})")
    TenantAcl().toString should include("opaToken=None")
    // A tenant-level toString (e.g. an exception message built from "$tenant") must not leak the
    // token either: Tenant's own toString prints its acl through TenantAcl's override.
    Tenant("t", acl = withToken).toString should not include "s3cr3t-x"

  it should "not change case-class equality, hashCode, or copy" in:
    val a = TenantAcl(mode = Some("opa"), opaToken = Some("s3cr3t-x"))
    val b = TenantAcl(mode = Some("opa"), opaToken = Some("s3cr3t-x"))
    a shouldBe b
    a.hashCode shouldBe b.hashCode
    a.copy(sendStatementText = true).opaToken shouldBe Some("s3cr3t-x")
