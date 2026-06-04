package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantSelectorSpec extends AnyFlatSpec with Matchers:

  // Decoded JWT body: { "sub": "alice", "tenant": "acme" }
  private val validToken = "header.eyJzdWIiOiJhbGljZSIsInRlbmFudCI6ImFjbWUifQ.sig"
  // Decoded JWT body: { "sub": "bob" }  (no tenant claim)
  private val noTenantToken = "header.eyJzdWIiOiJib2IifQ.sig"

  // Stub catalog: (tenant, pool) -> tenantDb, mimicking the
  // PoolSupervisor.findPoolKeyByTenantAndPoolName(...).map(_.tenantDb)
  // wiring that Main.scala plugs in. `sales` lives under `acme_default`;
  // any other (tenant, pool) is unknown.
  private val lookup: (String, String) => Either[String, String] =
    case ("acme",  "sales") => Right("acme_default")
    case ("other", "sales") => Right("other_default")
    case (t, p)             => Left(s"pool '$p' not found in tenant '$t'")

  "TenantSelector (JWT)" should "accept tenant claim + pool header" in:
    val out = TenantSelector.resolve(
      bearer  = Some(validToken),
      headers = Map("pool" -> "sales"),
      username = Some("alice"),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe Right(Resolved(PoolKey("acme", "acme_default", "sales"), "alice"))

  it should "let a `tenant` header override the JWT claim" in:
    val out = TenantSelector.resolve(
      bearer  = Some(validToken),
      headers = Map("tenant" -> "other", "pool" -> "sales"),
      username = Some("alice"),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe Right(Resolved(PoolKey("other", "other_default", "sales"), "alice"))

  it should "accept tenant header even when JWT lacks the claim" in:
    val out = TenantSelector.resolve(
      bearer  = Some(noTenantToken),
      headers = Map("tenant" -> "acme", "pool" -> "sales"),
      username = None, tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe Right(Resolved(PoolKey("acme", "acme_default", "sales"), "bob"))

  it should "reject when pool header is missing" in:
    val out = TenantSelector.resolve(
      bearer  = Some(validToken),
      headers = Map.empty,
      username = None, tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]

  it should "reject when JWT lacks tenant claim and no tenant header" in:
    val out = TenantSelector.resolve(
      bearer  = Some(noTenantToken),
      headers = Map("pool" -> "sales"),
      username = None, tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]

  it should "reject when the lookup can't find the (tenant, pool) pair" in:
    val out = TenantSelector.resolve(
      bearer  = Some(validToken),
      headers = Map("pool" -> "ghost"),
      username = Some("alice"),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]

  "TenantSelector (Basic)" should
    "accept bare username + tenant/pool headers" in:
    val out = TenantSelector.resolve(
      bearer = None,
      headers = Map("tenant" -> "acme", "pool" -> "sales"),
      username = Some("alice"),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe Right(Resolved(PoolKey("acme", "acme_default", "sales"), "alice"))

  it should "reject when the tenant header is missing" in:
    val out = TenantSelector.resolve(
      bearer = None,
      headers = Map("pool" -> "sales"),
      username = Some("alice"),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]

  it should "reject when the pool header is missing" in:
    val out = TenantSelector.resolve(
      bearer = None,
      headers = Map("tenant" -> "acme"),
      username = Some("alice"),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]

  it should "reject when neither JWT nor username present" in:
    val out = TenantSelector.resolve(
      bearer = None, headers = Map.empty, username = None,
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]

  it should "reject when username is the empty string" in:
    val out = TenantSelector.resolve(
      bearer = None,
      headers = Map("tenant" -> "acme", "pool" -> "sales"),
      username = Some(""),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]

  it should "reject when the lookup can't find the (tenant, pool) pair" in:
    val out = TenantSelector.resolve(
      bearer = None,
      headers = Map("tenant" -> "acme", "pool" -> "ghost"),
      username = Some("alice"),
      tenantClaim = "tenant",
      lookupPool = lookup
    )
    out shouldBe a [Left[_, _]]
