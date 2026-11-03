package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.BoundedHttp
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import io.circe.parser.parse

import java.util.concurrent.TimeUnit

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
  * A document is cached for a day ([[OidcDiscovery.DocumentTtlNanos]]) and a failure for
  * `failureTtlNanos`, each per issuer and each in a table of at most [[OidcDiscovery.MaxIssuers]]:
  * the issuer is a tenant admin's choice, and every setting of a new one would otherwise leave an
  * entry for the life of the process. An entry lost to the bound or to the day costs one more GET,
  * never a wrong answer, and a day ends a document that an issuer has since changed.
  *
  * What an entry costs is bounded too. A table keeps the five parsed fields of a document, not the
  * document, and the issuer URL it is keyed by; a failure it keeps is cut to
  * [[BoundedHttp.MaxFailureChars]] characters, whatever `httpGet` answered (the production one
  * answers a short text of its own, but this class keeps what it is given, and a failure quoted
  * from a server once ran a 256 MiB heap out after 474 issuers). A document with a field over
  * [[OidcDiscovery.MaxFieldChars]] characters is refused (a `Left` naming the field, never its
  * value, remembered like any failure), and so is an issuer URL over
  * [[OidcDiscovery.MaxIssuerUrlChars]], before it is asked or kept: a tenant admin picks it, and
  * nothing but the size of a REST body would bound it. Without that, one field of a document of the
  * 256 KiB that `BoundedHttp` lets through could fill an entry, and 1024 of them would pin about
  * 256 MiB for a day. With it the document table holds about 12 MiB at most (5 fields and a key of
  * 2048 characters in 1024 entries; twice that if every character were outside Latin-1). A real
  * endpoint URL is far shorter.
  *
  * Every call that finds a failure answers the same `Left` without asking the issuer: a tenant
  * admin picks the issuer, and an anonymous `GET /api/auth/oidc/start` resolves it, so with the
  * issuer down each request would otherwise make its own outbound GET and hold a blocking thread
  * for as long as the GET takes (up to 15 s). The time runs from the failed attempt, and a refused
  * call does not extend it. A success clears the failure. `nanoTime` is injectable for tests.
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

  private val documents: Cache[String, OidcDiscoveryDoc] = Caffeine
    .newBuilder()
    .ticker(() => nanoTime())
    .executor(_.run())
    .maximumSize(OidcDiscovery.MaxIssuers)
    .expireAfterWrite(OidcDiscovery.DocumentTtlNanos, TimeUnit.NANOSECONDS)
    .build[String, OidcDiscoveryDoc]()

  private val failures: Cache[String, String] = Caffeine
    .newBuilder()
    .ticker(() => nanoTime())
    .executor(_.run())
    .maximumSize(OidcDiscovery.MaxIssuers)
    .expireAfterWrite(failureTtlNanos, TimeUnit.NANOSECONDS)
    .build[String, String]()

  def resolve(issuerUrl: String): Either[String, OidcDiscoveryDoc] =
    // The URL is the key of both tables, and a tenant admin picks it: refused before it is asked or
    // kept, with a text that does not quote it.
    if issuerUrl.length > OidcDiscovery.MaxIssuerUrlChars then
      Left(s"issuer URL is longer than ${OidcDiscovery.MaxIssuerUrlChars} characters")
    else
      Option(documents.getIfPresent(issuerUrl)) match
        case Some(doc) => Right(doc)
        case None      =>
          Option(failures.getIfPresent(issuerUrl)) match
            case Some(why) => Left(why)
            case None      => fetch(issuerUrl)

  /** Visible for tests: the documents still held, once the bound has been applied. */
  private[auth] def cachedDocuments: Long =
    documents.cleanUp()
    documents.estimatedSize()

  private def fetch(issuerUrl: String): Either[String, OidcDiscoveryDoc] =
    val url = issuerUrl.stripSuffix("/") + "/.well-known/openid-configuration"
    httpGet(url).flatMap(parseDoc) match
      case Right(doc) =>
        documents.put(issuerUrl, doc)
        failures.invalidate(issuerUrl)
        Right(doc)
      case Left(why) =>
        val kept = BoundedHttp.cut(why)
        failures.put(issuerUrl, kept)
        Left(kept)

  private def parseDoc(body: String): Either[String, OidcDiscoveryDoc] =
    parse(body).left.map(_.getMessage).flatMap { json =>
      val c = json.hcursor
      // The Left names the field, never its value: it is remembered and shown.
      def bounded(field: String, value: String): Either[String, String] =
        if value.length > OidcDiscovery.MaxFieldChars then
          Left(
            s"field '$field' in discovery document is longer than " +
              s"${OidcDiscovery.MaxFieldChars} characters"
          )
        else Right(value)
      def str(field: String): Either[String, String] =
        c.get[String](field)
          .left
          .map(_ => s"missing field '$field' in discovery document")
          .flatMap(bounded(field, _))
      for
        issuer    <- str("issuer")
        authorize <- str("authorization_endpoint")
        token     <- str("token_endpoint")
        jwks      <- str("jwks_uri")
        // end_session_endpoint is OPTIONAL in the OIDC spec; default to empty when absent.
        endSession <- c
          .get[String]("end_session_endpoint")
          .fold(_ => Right(""), bounded("end_session_endpoint", _))
      yield OidcDiscoveryDoc(
        issuer = issuer,
        authorizationEndpoint = authorize,
        tokenEndpoint = token,
        jwksUri = jwks,
        endSessionEndpoint = endSession
      )
    }

object OidcDiscovery:

  /** How many issuers a table keeps (documents and failures each). */
  private[auth] val MaxIssuers = 1024

  /** How long a document is kept: a day. */
  val DocumentTtlNanos: Long = TimeUnit.DAYS.toNanos(1)

  /** The most characters a field of a document may hold: a longer one refuses the document. */
  val MaxFieldChars: Int = 2048

  /** The most characters of an issuer URL, the key of both tables: a longer one is refused. */
  val MaxIssuerUrlChars: Int = 2048
