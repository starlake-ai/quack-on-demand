package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.JwtAuthConfig
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Date

/** The token's validity window: `exp` and `nbf` (RFC 7519 4.1.4 / 4.1.5). */
class JwtBearerAuthenticatorTimeSpec extends AnyFlatSpec with Matchers:

  private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef"
  private val auth   = new JwtBearerAuthenticator(
    JwtAuthConfig(secretKey = secret, publicKeyPath = "", issuer = "qod-test", audience = "qod"),
    roleClaim = "role"
  )

  private def token(customize: JWTClaimsSet.Builder => JWTClaimsSet.Builder): String =
    val claims = customize(
      new JWTClaimsSet.Builder()
        .issuer("qod-test")
        .audience("qod")
        .subject("alice")
        .claim("preferred_username", "alice")
        .expirationTime(new Date(System.currentTimeMillis() + 3600_000L))
    ).build()
    val jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims)
    jwt.sign(new MACSigner(secret))
    jwt.serialize()

  "JwtBearerAuthenticator" should "accept a token with no nbf" in {
    auth.authenticate(token(identity)).map(_.username) shouldBe Right("alice")
  }

  it should "accept a token whose nbf has passed" in {
    auth
      .authenticate(token(_.notBeforeTime(new Date(System.currentTimeMillis() - 60_000L))))
      .map(_.username) shouldBe Right("alice")
  }

  it should "refuse a token that is not valid yet" in {
    val tomorrow = new Date(System.currentTimeMillis() + 86_400_000L)
    val out      = auth.authenticate(token(_.notBeforeTime(tomorrow)))
    out.isLeft shouldBe true
    out.left.toOption.get should include("not valid yet")
  }

  it should "accept a token whose nbf is within the clock skew allowance" in {
    val soon = new Date(System.currentTimeMillis() + 10_000L)
    auth.authenticate(token(_.notBeforeTime(soon))).map(_.username) shouldBe Right("alice")
  }

  it should "refuse an expired token" in {
    val out =
      auth.authenticate(token(_.expirationTime(new Date(System.currentTimeMillis() - 1000L))))
    out.left.toOption.get should include("expired")
  }
