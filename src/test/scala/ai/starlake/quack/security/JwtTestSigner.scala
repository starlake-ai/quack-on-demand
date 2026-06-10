// src/test/scala/ai/starlake/quack/security/JwtTestSigner.scala
package ai.starlake.quack.security

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import io.circe.Json

import java.time.{Duration, Instant}
import java.util.{Date, UUID}

/** Mints test RSA keys and signed JWTs for the security e2e test suite.
  *
  * The key pair is generated once per JVM via a lazy val, so all JWTs minted
  * during a test run share the same public key. This is intentional: the
  * MockOidcServer caches a single JWKS document per boot, and OidcBearerAuthenticator
  * caches the JWKS for the suite lifetime. Re-generating per-test would cause
  * signature verification failures against a stale JWKS.
  */
object JwtTestSigner:

  /** A test RSA-2048 key pair, generated once per JVM. Stable across the test
    * suite lifetime so cached JWKS responses stay valid for the suite.
    */
  lazy val keyPair: RSAKey =
    new RSAKeyGenerator(2048)
      .keyID(UUID.randomUUID().toString)
      .generate()

  /** Mint a signed RS256 JWT.
    *
    * @param payload  extra claims to include in the token (in addition to the
    *                 standard iat / nbf / exp / iss / aud added here).
    * @param issuer   the `iss` claim.
    * @param audience optional `aud` claim (single string).
    * @param expIn    token lifetime from now (default 5 minutes).
    * @return compact serialized JWT string.
    */
  def mint(
      payload:  Map[String, Any],
      issuer:   String,
      audience: Option[String]  = None,
      expIn:    Duration        = Duration.ofMinutes(5)
  ): String =
    val now = Instant.now()
    val exp = now.plus(expIn)

    val claimsBuilder = new JWTClaimsSet.Builder()
      .issuer(issuer)
      .issueTime(Date.from(now))
      .notBeforeTime(Date.from(now))
      .expirationTime(Date.from(exp))

    audience.foreach(aud => claimsBuilder.audience(aud))

    payload.foreach {
      case (k, v: String)  => claimsBuilder.claim(k, v)
      case (k, v: Int)     => claimsBuilder.claim(k, v.toLong)
      case (k, v: Long)    => claimsBuilder.claim(k, v)
      case (k, v: Boolean) => claimsBuilder.claim(k, v)
      case (k, v: Double)  => claimsBuilder.claim(k, v)
      case (k, v)          => claimsBuilder.claim(k, v.toString)
    }

    val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID(keyPair.getKeyID)
      .build()

    val jwt = new SignedJWT(header, claimsBuilder.build())
    jwt.sign(new RSASSASigner(keyPair.toRSAPrivateKey))
    jwt.serialize()

  /** Build a JWKS JSON object containing the test public key, in the shape
    * that OidcBearerAuthenticator's JWKS resolver (Nimbus RemoteJWKSet) expects.
    *
    * The returned Json encodes the RSA public key parameters (n, e) plus the
    * kid so the processor can select the right key when verifying a token.
    */
  def jwksJson(): Json =
    // toPublicJWK strips the private key material; toJSONString gives the
    // per-key JWK JSON (RFC 7517).
    val publicKeyJson = keyPair.toPublicJWK.toJSONString
    val keyObj        = io.circe.parser.parse(publicKeyJson).getOrElse(Json.obj())
    Json.obj("keys" -> Json.arr(keyObj))