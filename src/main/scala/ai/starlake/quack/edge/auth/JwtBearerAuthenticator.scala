package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.JwtAuthConfig
import ai.starlake.sql.TooDeep
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
    // Whoever holds the signing key builds the token, and a claim nested thousands of levels deep
    // overflows the stack of the code that prints it (flattenClaims prints each claim with
    // toString, a few frames a level). A StackOverflowError is fatal to cats-effect, so one
    // escaping here halted the whole manager under IOApp. TooDeep.orElse catches it on the thread
    // that overflowed, and the token is refused (see OidcBearerAuthenticator.authenticate).
    TooDeep.orElse[Either[String, AuthenticatedProfile]](
      Left("JWT token is nested too deeply")
    )(validated(token))

  private def validated(token: String): Either[String, AuthenticatedProfile] =
    try
      val signedJWT = SignedJWT.parse(token)
      if !verifySignature(signedJWT) then Left("JWT signature verification failed")
      else
        val claims = signedJWT.getJWTClaimsSet
        val now    = new Date()
        for
          _ <- checkIssuer(claims)
          _ <- checkAudience(claims)
          _ <- checkExpiry(claims, now)
          _ <- checkNotBefore(claims, now)
          // A token naming no one is refused rather than named "unknown", which a row could hold.
          username <- extractUsername(claims).toRight(
            "JWT: the token names no user (no preferred_username, email or sub)"
          )
        yield profileOf(claims, username)
    catch
      case e: Exception =>
        Left(s"JWT validation failed: ${e.getMessage}")

  // Validate issuer if configured
  private def checkIssuer(claims: JWTClaimsSet): Either[String, Unit] =
    if config.issuer.nonEmpty then
      val iss = Option(claims.getIssuer).getOrElse("")
      if iss != config.issuer then Left(s"Unexpected JWT issuer: $iss (expected: ${config.issuer})")
      else Right(())
    else Right(())

  // Validate audience if configured
  private def checkAudience(claims: JWTClaimsSet): Either[String, Unit] =
    if config.audience.nonEmpty then
      val audiences = Option(claims.getAudience).map(_.toArray.toList).getOrElse(Nil)
      if !audiences.contains(config.audience) then
        Left(s"JWT audience mismatch: $audiences (expected: ${config.audience})")
      else Right(())
    else Right(())

  private def checkExpiry(claims: JWTClaimsSet, now: Date): Either[String, Unit] =
    val exp = Option(claims.getExpirationTime)
    if exp.exists(_.before(now)) then Left("JWT token has expired") else Right(())

  // Not before (RFC 7519 4.1.5), with the clock skew the OIDC path's nimbus processor allows,
  // so an issuer whose clock runs a little ahead still signs usable tokens.
  private def checkNotBefore(claims: JWTClaimsSet, now: Date): Either[String, Unit] =
    val nbf = Option(claims.getNotBeforeTime)
    if nbf.exists(_.getTime > now.getTime + JwtBearerAuthenticator.NotBeforeSkewMs) then
      Left("JWT token is not valid yet (nbf is in the future)")
    else Right(())

  private def profileOf(claims: JWTClaimsSet, username: String): AuthenticatedProfile =
    val role   = RoleExtractor.extract(claims, roleClaim)
    val groups = RoleExtractor.extractGroups(claims, "groups")
    AuthenticatedProfile(
      username = username,
      role = role,
      groups = groups + role,
      claims = flattenClaims(claims),
      authMethod = "jwt"
    )

  private def verifySignature(jwt: SignedJWT): Boolean =
    hmacVerifier.exists(jwt.verify) || rsaVerifier.exists(jwt.verify)

  private def extractUsername(claims: JWTClaimsSet): Option[String] =
    Option(claims.getStringClaim("preferred_username"))
      .orElse(Option(claims.getStringClaim("email")))
      .orElse(Option(claims.getSubject))

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

object JwtBearerAuthenticator:

  /** How far in the future a token's `nbf` may lie and still be accepted: nimbus
    * `DefaultJWTClaimsVerifier`'s default, which the OIDC bearer path applies.
    */
  val NotBeforeSkewMs: Long = 60_000L
