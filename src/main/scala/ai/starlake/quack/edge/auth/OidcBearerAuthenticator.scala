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
        // Some exceptions have no message: Nimbus' rate limit on refreshing a key set is one, and
        // the answer read "token validation failed: null". Their class says what happened.
        Left(
          s"$providerName token validation failed: " +
            Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        )

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
    val claimedRole = RoleExtractor.claimed(claims, roleClaim)
    var groups      = RoleExtractor.extractGroups(claims, "groups")

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
      role = claimedRole.getOrElse(RoleExtractor.DefaultRole),
      groups = groups ++ claimedRole,
      claims = flattenClaims(claims),
      authMethod = providerName,
      tokenRoles = claimedRole.toSet
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

object OidcBearerAuthenticator extends LazyLogging:

  /** What the retriever throws, whatever went wrong. The URL of a key set can name anything the
    * manager can reach (a tenant admin picks it), and the JDK quotes what a server sent when it
    * refuses a malformed answer: `ProtocolException: Invalid status line: "SSH-2.0-OpenSSH_9.6
    * internal-bastion-3"`. That text, or a status code, would reach any bearer client in the answer
    * of [[OidcBearerAuthenticator.authenticate]] (the data plane's handshake, the front door),
    * which is a probe of the network behind the manager. So the answer says one thing, and the log
    * gets the cause, cut.
    */
  private[auth] val JwksUnavailable = "JWKS unavailable"

  /** The shape of [[BoundedHttp.send]], so that a spec can see what the retriever asks of it. */
  private[auth] type Send =
    (HttpRequest, Int, Long, String, BoundedHttp.Exchanges, HttpClient.Redirect) => Either[
      String,
      (Int, String)
    ]

  /** Reads a JWKS through `send`, under a size limit and a deadline over the whole read, redirects
    * included. The URL can come from a discovery document, which a tenant admin picks, and anyone
    * can then make the manager fetch it: Nimbus' default retriever has no deadline over a slow
    * body, and it reads `file:` URLs. The JDK client refuses every scheme but http(s).
    *
    * Redirects are followed (`NORMAL`): to the same scheme or to https, never from https to http
    * and never to another scheme (a Location of file: is not followed), at most 5 times
    * (jdk.httpclient.redirects.retrylimit), and the deadline covers every hop. The retriever this
    * replaced (Nimbus', over `HttpURLConnection`) followed a redirect to the same protocol only, so
    * a hop from http to https is new here: a Keycloak URL on http behind a proxy that redirects to
    * https now works, and it did not before.
    */
  private[auth] def retrieverOver(send: Send): ResourceRetriever = url =>
    val answer =
      try
        send(
          HttpRequest.newBuilder(url.toURI).GET().build(),
          BoundedHttp.MaxJwksBytes,
          BoundedHttp.JwksTimeoutMs,
          "JWKS",
          BoundedHttp.idpExchanges,
          HttpClient.Redirect.NORMAL
        )
      catch case e: Exception => Left(BoundedHttp.describe(e))
    answer match
      case Right((200, body)) => new Resource(body, "application/json")
      case Right((code, _))   =>
        logger.warn(s"JWKS read failed: HTTP $code")
        throw new IOException(JwksUnavailable)
      case Left(BoundedHttp.Busy) =>
        // Below WARN: a refusal is the cheapest thing an anonymous caller can ask for.
        logger.debug(s"JWKS read refused: ${BoundedHttp.Busy}")
        throw new IOException(JwksUnavailable)
      case Left(why) =>
        logger.warn(s"JWKS read failed: $why")
        throw new IOException(JwksUnavailable)

  private[auth] val jwksRetriever: ResourceRetriever =
    retrieverOver((request, cap, timeoutMs, what, exchanges, redirects) =>
      BoundedHttp.send(request, cap, timeoutMs, what, exchanges, redirects, secret = None)
    )
