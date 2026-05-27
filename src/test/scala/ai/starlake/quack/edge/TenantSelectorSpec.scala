package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantSelectorSpec extends AnyFlatSpec with Matchers:

  // Decoded JWT body: { "sub": "alice", "tenant": "acme" }
  private val validToken = "header.eyJzdWIiOiJhbGljZSIsInRlbmFudCI6ImFjbWUifQ.sig"
  // Decoded JWT body: { "sub": "bob" }  (no tenant claim)
  private val noTenantToken = "header.eyJzdWIiOiJib2IifQ.sig"

  private val dT = "tenant-default"
  private val dP = "pool-default"

  "TenantSelector" should "prefer JWT claim + X-Pool header when both present" in:
    val out = TenantSelector.resolve(
      bearer  = Some(validToken),
      headers = Map("X-Pool" -> "sales"),
      username = Some("alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey("acme", "sales"), "alice"))

  it should "fall back to defaultTenant when JWT lacks the tenant claim" in:
    val out = TenantSelector.resolve(
      bearer  = Some(noTenantToken),
      headers = Map.empty,
      username = None,
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey(dT, dP), "bob"))

  it should "fall back to structured 3-part username when no JWT" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map.empty,
      username = Some("acme/sales/alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey("acme", "sales"), "alice"))

  it should "accept 2-part username 'pool/user' with default tenant" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map.empty,
      username = Some("sales/alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey(dT, "sales"), "alice"))

  it should "NOT let X-Pool override an explicit pool in 2-part username" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map("X-Pool" -> "ops"),
      username = Some("sales/alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey(dT, "sales"), "alice"))

  it should "NOT let X-Pool override an explicit pool in 3-part username" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map("X-Pool" -> "ops"),
      username = Some("acme/sales/alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey("acme", "sales"), "alice"))

  it should "accept 1-part username with default tenant and pool" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map.empty,
      username = Some("alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey(dT, dP), "alice"))

  it should "let X-Pool override the default pool for a 1-part username" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map("X-Pool" -> "ops"),
      username = Some("alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey(dT, "ops"), "alice"))

  it should "default pool to defaultPool when JWT present but no X-Pool" in:
    val out = TenantSelector.resolve(
      bearer = Some(validToken),
      headers = Map.empty,
      username = Some("alice"),
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe Right(Resolved(PoolKey("acme", dP), "alice"))

  it should "reject empty-segment usernames" in:
    val cases = List("acme/", "/sales", "acme//alice", "")
    cases.foreach { u =>
      val out = TenantSelector.resolve(
        bearer = None, headers = Map.empty,
        username = Some(u),
        tenantClaim = "tenant",
        defaultTenant = dT, defaultPool = dP
      )
      out shouldBe a [Left[_, _]]
    }

  it should "reject when neither JWT nor username present" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map.empty, username = None,
      tenantClaim = "tenant",
      defaultTenant = dT, defaultPool = dP
    )
    out shouldBe a [Left[_, _]]