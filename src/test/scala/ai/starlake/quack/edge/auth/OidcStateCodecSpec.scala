package ai.starlake.quack.edge.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OidcStateCodecSpec extends AnyFlatSpec with Matchers:

  private val codec = new OidcStateCodec(secret = "test-secret-please-change", ttlMillis = 600000L)

  private val claims = OidcStateClaims(
    scope = "tenant",
    tenant = Some("acme"),
    nonce = "n-123",
    returnTo = "/ui/",
    iatMillis = 1_000_000L
  )

  it should "round-trip signed state within TTL" in {
    val token = codec.sign(claims)
    codec.verify(token, nowMillis = 1_000_500L) shouldBe Right(claims)
  }

  it should "reject a tampered token" in {
    val token = codec.sign(claims)
    codec.verify(token.dropRight(2) + "xx", nowMillis = 1_000_500L).isLeft shouldBe true
  }

  it should "reject an expired token" in {
    val token = codec.sign(claims)
    codec.verify(token, nowMillis = 1_000_000L + 600_001L).isLeft shouldBe true
  }

  it should "derive a PKCE challenge as base64url-sha256 of the verifier" in {
    val pkce = codec.genPkce("seed-xyz")
    pkce.verifier should not be empty
    pkce.challenge should not include "=" // base64url, no padding
    pkce.challenge should not include "+"
    pkce.challenge should not include "/"
  }
