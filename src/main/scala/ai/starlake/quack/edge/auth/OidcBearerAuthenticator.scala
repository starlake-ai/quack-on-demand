package ai.starlake.quack.edge.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.typesafe.scalalogging.LazyLogging

import java.net.URL
import java.util.Date

/** Validates Bearer tokens from OIDC providers (Keycloak, Google, Azure AD, AWS Cognito) by
  * verifying signatures against the provider's JWKS endpoint.
  *
  * Uses Nimbus JOSE+JWT's DefaultJWTProcessor with RemoteJWKSet which automatically caches and
  * refreshes JWK sets for key rotation support.
  */
class OidcBearerAuthenticator(
    val providerName: String,
    jwksUrl: String,
    val expectedIssuer: String,
    val expectedAudience: String,
    roleClaim: String,
    groupsLookup: Option[String => Set[String]] = None
) extends BearerAuthProvider,
      LazyLogging:

  val name: String = providerName

  private val jwkSource: JWKSource[SecurityContext] =
    JWKSourceBuilder.create[SecurityContext](new URL(jwksUrl)).build()

  private val jwtProcessor: DefaultJWTProcessor[SecurityContext] =
    val processor = new DefaultJWTProcessor[SecurityContext]()
    // Support RS256 (most common), RS384, RS512, ES256
    val keySelector = new JWSVerificationKeySelector(
      java.util.Set.of(
        JWSAlgorithm.RS256,
        JWSAlgorithm.RS384,
        JWSAlgorithm.RS512,
        JWSAlgorithm.ES256,
        JWSAlgorithm.ES384,
        JWSAlgorithm.ES512
      ),
      jwkSource
    )
    processor.setJWSKeySelector(keySelector)
    processor

  override def authenticate(token: String): Either[String, AuthenticatedProfile] =
    try
      val claims: JWTClaimsSet = jwtProcessor.process(token, null)

      // Validate issuer
      val iss = Option(claims.getIssuer).getOrElse("")
      if expectedIssuer.nonEmpty && iss != expectedIssuer then
        return Left(s"$providerName: unexpected issuer '$iss' (expected: '$expectedIssuer')")

      // Validate audience
      if expectedAudience.nonEmpty then
        val audiences = Option(claims.getAudience).map(_.toArray.toList).getOrElse(Nil)
        if !audiences.contains(expectedAudience) then
          return Left(s"$providerName: audience mismatch: $audiences")

      // Check expiry
      val exp = Option(claims.getExpirationTime)
      if exp.exists(_.before(new Date())) then return Left(s"$providerName: token has expired")

      val username = extractUsername(claims)
      val role     = RoleExtractor.extract(claims, roleClaim)
      var groups   = RoleExtractor.extractGroups(claims, "groups")

      // Enrich groups from external lookup (e.g., Google Directory API)
      groupsLookup.foreach { lookup =>
        val email          = Option(claims.getStringClaim("email")).getOrElse(username)
        val externalGroups = lookup(email)
        if externalGroups.nonEmpty then
          logger.debug(s"Enriched groups for $email from $providerName directory: $externalGroups")
          groups = groups ++ externalGroups
      }

      Right(
        AuthenticatedProfile(
          username = username,
          role = role,
          groups = groups + role,
          claims = flattenClaims(claims),
          authMethod = providerName
        )
      )
    catch
      case e: Exception =>
        Left(s"$providerName token validation failed: ${e.getMessage}")

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
