package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantSpec extends AnyFlatSpec with Matchers:

  "Tenant.toString" should "redact authClientSecret so no log line can print it" in:
    val withSecret = Tenant("acme", authClientSecret = Some("kc-s3cr3t-x"))
    withSecret.toString should not include "kc-s3cr3t-x"
    withSecret.toString should include(s"authClientSecret=Some(${FederatedSecret.RedactedMarker})")
    Tenant("acme").toString should include("authClientSecret=None")

  it should "keep printing the acl through TenantAcl's opaToken redaction" in:
    Tenant("acme", acl = TenantAcl(opaToken = Some("opa-s3cr3t-x"))).toString should
      not include "opa-s3cr3t-x"

  it should "not change case-class equality, hashCode, or copy" in:
    val a = Tenant("acme", authClientSecret = Some("kc-s3cr3t-x"))
    val b = Tenant("acme", authClientSecret = Some("kc-s3cr3t-x"))
    a shouldBe b
    a.hashCode shouldBe b.hashCode
    a should not be Tenant("acme")
    a.copy(disabled = true).authClientSecret shouldBe Some("kc-s3cr3t-x")
