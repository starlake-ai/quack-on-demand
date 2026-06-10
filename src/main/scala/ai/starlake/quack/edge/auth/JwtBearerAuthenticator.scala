package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.JwtAuthConfig
import com.nimbusds.jose.crypto.{MACVerifier, RSASSAVerifier}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{JWSAlgorithm, JWSVerifier}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import com.typesafe.scalalogging.LazyLogging

import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.{Base64, Date}

/** Validates JWT tokens signed with HMAC (shared secret) or RSA (public key). Used for custom JWT
  * issuers, not OIDC providers (use OidcBearerAuthenticator for those).
  */
class JwtBearerAuthenticator(config: JwtAuthConfig, roleClaim: String)
    extends BearerAuthProvider,
      LazyLogging:

  val name = "jwt"

  private val hmacVerifier: Option[MACVerifier] =
    if config.secretKey.nonEmpty then Some(new MACVerifier(config.secretKey))
    else None

  private val rsaVerifier: Option[RSASSAVerifier] =
    if config.publicKeyPath.nonEmpty then
      Some(new RSASSAVerifier(loadRSAPublicKey(config.publicKeyPath)))
    else None

  override def authenticate(token: String): Either[String, AuthenticatedProfile] =
    try
      val signedJWT = SignedJWT.parse(token)
      val verified  = verifySignature(signedJWT)
      if !verified then return Left("JWT signature verification failed")

      val claims = signedJWT.getJWTClaimsSet

      // Validate issuer if configured
      if config.issuer.nonEmpty then
        val iss = Option(claims.getIssuer).getOrElse("")
        if iss != config.issuer then
          return Left(s"Unexpected JWT issuer: $iss (expected: ${config.issuer})")

      // Validate audience if configured
      if config.audience.nonEmpty then
        val audiences = Option(claims.getAudience).map(_.toArray.toList).getOrElse(Nil)
        if !audiences.contains(config.audience) then
          return Left(s"JWT audience mismatch: $audiences (expected: ${config.audience})")

      // Check expiry
      val exp = Option(claims.getExpirationTime)
      if exp.exists(_.before(new Date())) then return Left("JWT token has expired")

      val username = extractUsername(claims)
      val role     = RoleExtractor.extract(claims, roleClaim)
      val groups   = RoleExtractor.extractGroups(claims, "groups")

      Right(
        AuthenticatedProfile(
          username = username,
          role = role,
          groups = groups + role,
          claims = flattenClaims(claims),
          authMethod = "jwt"
        )
      )
    catch
      case e: Exception =>
        Left(s"JWT validation failed: ${e.getMessage}")

  private def verifySignature(jwt: SignedJWT): Boolean =
    hmacVerifier.exists(jwt.verify) || rsaVerifier.exists(jwt.verify)

  private def extractUsername(claims: JWTClaimsSet): String =
    Option(claims.getStringClaim("preferred_username"))
      .orElse(Option(claims.getStringClaim("email")))
      .orElse(Option(claims.getSubject))
      .getOrElse("unknown")

  private def flattenClaims(claims: JWTClaimsSet): Map[String, String] =
    import scala.jdk.CollectionConverters.*
    claims.getClaims.asScala.collect {
      case (k, v) if v != null => k -> v.toString
    }.toMap

  private def loadRSAPublicKey(path: String): RSAPublicKey =
    val pem      = new String(Files.readAllBytes(new File(path).toPath))
    val stripped = pem
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replaceAll("\\s", "")
    val decoded = Base64.getDecoder.decode(stripped)
    val spec    = new X509EncodedKeySpec(decoded)
    KeyFactory.getInstance("RSA").generatePublic(spec).asInstanceOf[RSAPublicKey]
