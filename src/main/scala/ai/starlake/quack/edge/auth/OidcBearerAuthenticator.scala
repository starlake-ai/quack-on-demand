package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.BoundedHttp
import ai.starlake.sql.TooDeep
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.util.{Resource, ResourceRetriever}
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.typesafe.scalalogging.LazyLogging

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.util.Date

/** Validates Bearer tokens from OIDC providers (Keycloak, Google, Azure AD, AWS Cognito) by
  * verifying signatures against the provider's JWKS endpoint.
  *
  * Uses Nimbus JOSE+JWT's DefaultJWTProcessor with a caching, rate-limited JWK source, which
  * refreshes the JWK set for key rotation support. The set is read by
  * [[OidcBearerAuthenticator.jwksRetriever]], under a size limit and a deadline.
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
    JWKSourceBuilder
      .create[SecurityContext](URI.create(jwksUrl).toURL(), OidcBearerAuthenticator.jwksRetriever)
      .build()

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
    // A signed token is the issuer's to build, and a tenant admin picks the issuer: a claim nested
    // thousands of levels deep passes the signature check, and the code that prints or walks it
    // (flattenClaims prints each claim with toString, a few frames a level) overflows the stack. A
    // StackOverflowError is fatal to cats-effect, so one escaping here halted the whole manager
    // under IOApp, on one anonymous callback or one bearer token. TooDeep.orElse catches it on the
    // thread that overflowed, where the stack is unwound and nothing is left half-done, and the
    // token is refused.
    TooDeep.orElse[Either[String, AuthenticatedProfile]](
      Left(s"$providerName token is nested too deeply")
    )(validated(token))

  private def validated(token: String): Either[String, AuthenticatedProfile] =
    try
      val claims: JWTClaimsSet = jwtProcessor.process(token, null)
      for
        _ <- checkIssuer(claims)
        _ <- checkAudience(claims)
        _ <- checkExpiry(claims)
        // A token naming no one is refused rather than named "unknown", which a row could hold.
        username <- extractUsername(claims).toRight(
          s"$providerName: the token names no user (no preferred_username, email or sub)"
        )
      yield profileOf(claims, username)
    catch
      case e: Exception =>
        Left(s"$providerName token validation failed: ${e.getMessage}")

  private def checkIssuer(claims: JWTClaimsSet): Either[String, Unit] =
    val iss = Option(claims.getIssuer).getOrElse("")
    if expectedIssuer.nonEmpty && iss != expectedIssuer then
      Left(s"$providerName: unexpected issuer '$iss' (expected: '$expectedIssuer')")
    else Right(())

  private def checkAudience(claims: JWTClaimsSet): Either[String, Unit] =
    if expectedAudience.nonEmpty then
      val audiences = Option(claims.getAudience).map(_.toArray.toList).getOrElse(Nil)
      if !audiences.contains(expectedAudience) then
        Left(s"$providerName: audience mismatch: $audiences")
      else Right(())
    else Right(())

  private def checkExpiry(claims: JWTClaimsSet): Either[String, Unit] =
    val exp = Option(claims.getExpirationTime)
    if exp.exists(_.before(new Date())) then Left(s"$providerName: token has expired")
    else Right(())

  private def profileOf(claims: JWTClaimsSet, username: String): AuthenticatedProfile =
    val role   = RoleExtractor.extract(claims, roleClaim)
    var groups = RoleExtractor.extractGroups(claims, "groups")

    // Enrich groups from external lookup (e.g., Google Directory API)
    groupsLookup.foreach { lookup =>
      val email          = Option(claims.getStringClaim("email")).getOrElse(username)
      val externalGroups = lookup(email)
      if externalGroups.nonEmpty then
        logger.debug(s"Enriched groups for $email from $providerName directory: $externalGroups")
        groups = groups ++ externalGroups
    }

    AuthenticatedProfile(
      username = username,
      role = role,
      groups = groups + role,
      claims = flattenClaims(claims),
      authMethod = providerName
    )

  private def extractUsername(claims: JWTClaimsSet): Option[String] =
    Option(claims.getStringClaim("preferred_username"))
      .orElse(Option(claims.getStringClaim("email")))
      .orElse(Option(claims.getSubject))

  private val closes = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Releases the JWK source (its refresh-ahead cache owns an executor). Idempotent: only the first
    * call closes it. The [[TenantOidcRegistry]] closes a per-tenant authenticator once a newer one
    * replaced it or its tenant lost its override.
    */
  override def close(): Unit =
    if closes.getAndIncrement() == 0 then
      jwkSource match
        case c: java.io.Closeable =>
          try c.close()
          catch
            case e: Exception => logger.debug(s"$providerName: JWK source close: ${e.getMessage}")
        case _ => ()

  /** True once [[close]] ran. */
  def isClosed: Boolean = closes.get() > 0

  /** Visible for tests: how many times [[close]] was called (the registry must call it once). */
  private[auth] def closeCount: Int = closes.get()

  private def flattenClaims(claims: JWTClaimsSet): Map[String, String] =
    import scala.jdk.CollectionConverters.*
    claims.getClaims.asScala.collect {
      case (k, v) if v != null => k -> v.toString
    }.toMap

object OidcBearerAuthenticator:

  // NORMAL, as the retriever it replaces followed redirects: a Keycloak base URL on http behind a
  // proxy that redirects to https keeps working. It follows a redirect to the same scheme or to
  // https, never from https to http and never to another scheme (a Location of file: is not
  // followed), at most 5 times (jdk.httpclient.redirects.retrylimit), and the deadline of
  // BoundedHttp.send covers every hop.
  private val jwksClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

  /** Reads a JWKS under a size limit and a deadline over the whole read, redirects included. The
    * URL can come from a discovery document, which a tenant admin picks, and anyone can then make
    * the manager fetch it: Nimbus' default retriever has no deadline over a slow body, and it reads
    * `file:` URLs. The JDK client refuses every scheme but http(s).
    */
  private[auth] val jwksRetriever: ResourceRetriever = url =>
    val answer =
      try
        BoundedHttp.send(
          jwksClient,
          HttpRequest.newBuilder(url.toURI).GET().build(),
          BoundedHttp.MaxJwksBytes,
          BoundedHttp.JwksTimeoutMs,
          "JWKS"
        )
      catch case e: IllegalArgumentException => Left(e.getMessage)
    answer match
      case Right((200, body)) => new Resource(body, "application/json")
      case Right((code, _))   => throw new IOException(s"JWKS HTTP $code")
      case Left(why)          => throw new IOException(why)
