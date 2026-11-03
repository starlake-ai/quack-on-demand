package ai.starlake.quack.edge.auth

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import io.circe.parser.parse

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

/** A subset of an OIDC provider's well-known configuration. */
final case class OidcDiscoveryDoc(
    issuer: String,
    authorizationEndpoint: String,
    tokenEndpoint: String,
    jwksUri: String,
    endSessionEndpoint: String
)

/** Resolves and caches an OIDC issuer's discovery document. `httpGet` is injected so the unit suite
  * runs without network: it maps a URL to either the response body or an error string.
  *
  * A document is cached for good. A failure is remembered for `failureTtlNanos` per issuer, and
  * every call in that time answers the same `Left` without asking the issuer: a tenant admin picks
  * the issuer, and an anonymous `GET /api/auth/oidc/start` resolves it, so with the issuer down
  * each request would otherwise make its own outbound GET and hold a blocking thread for as long as
  * the GET takes (up to 15 s). The time runs from the failed attempt, and a refused call does not
  * extend it. A success clears the failure. `nanoTime` is injectable for tests.
  *
  * A failure is remembered once its GET has returned, not before: calls that arrive while a GET is
  * still waiting on an issuer that never answers each make their own (the REST connection budget
  * bounds how many), so the memo ends the traffic to an issuer that fails, and only shortens it for
  * one that hangs.
  */
class OidcDiscovery(
    httpGet: String => Either[String, String],
    failureTtlNanos: Long = 10L * 1000 * 1000 * 1000,
    nanoTime: () => Long = () => System.nanoTime()
):

  private val cache = new ConcurrentHashMap[String, OidcDiscoveryDoc]()

  // A wrapper, so that a Left(null) from an injected httpGet can be remembered too. The bound only
  // keeps the table small: an entry lost to it costs one more GET, never a wrong answer.
  private final case class Failure(why: String)

  private val failures: Cache[String, Failure] = Caffeine
    .newBuilder()
    .ticker(() => nanoTime())
    .executor(_.run())
    .maximumSize(1024)
    .expireAfterWrite(failureTtlNanos, TimeUnit.NANOSECONDS)
    .build[String, Failure]()

  def resolve(issuerUrl: String): Either[String, OidcDiscoveryDoc] =
    Option(cache.get(issuerUrl)) match
      case Some(doc) => Right(doc)
      case None      =>
        Option(failures.getIfPresent(issuerUrl)) match
          case Some(failure) => Left(failure.why)
          case None          => fetch(issuerUrl)

  private def fetch(issuerUrl: String): Either[String, OidcDiscoveryDoc] =
    val url = issuerUrl.stripSuffix("/") + "/.well-known/openid-configuration"
    httpGet(url).flatMap(parseDoc) match
      case Right(doc) =>
        cache.put(issuerUrl, doc)
        failures.invalidate(issuerUrl)
        Right(doc)
      case left @ Left(why) =>
        failures.put(issuerUrl, Failure(why))
        left

  private def parseDoc(body: String): Either[String, OidcDiscoveryDoc] =
    parse(body).left.map(_.getMessage).flatMap { json =>
      val c                                          = json.hcursor
      def str(field: String): Either[String, String] =
        c.get[String](field).left.map(_ => s"missing field '$field' in discovery document")
      for
        issuer    <- str("issuer")
        authorize <- str("authorization_endpoint")
        token     <- str("token_endpoint")
        jwks      <- str("jwks_uri")
      yield OidcDiscoveryDoc(
        issuer = issuer,
        authorizationEndpoint = authorize,
        tokenEndpoint = token,
        jwksUri = jwks,
        // end_session_endpoint is OPTIONAL in the OIDC spec; default to empty when absent.
        endSessionEndpoint = c.get[String]("end_session_endpoint").getOrElse("")
      )
    }
