package ai.starlake.quack.edge.auth

import io.circe.parser.parse

import java.util.concurrent.ConcurrentHashMap

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
  */
class OidcDiscovery(httpGet: String => Either[String, String]):

  private val cache = new ConcurrentHashMap[String, OidcDiscoveryDoc]()

  def resolve(issuerUrl: String): Either[String, OidcDiscoveryDoc] =
    Option(cache.get(issuerUrl)) match
      case Some(doc) => Right(doc)
      case None      =>
        val url = issuerUrl.stripSuffix("/") + "/.well-known/openid-configuration"
        httpGet(url).flatMap(parseDoc).map { doc =>
          cache.put(issuerUrl, doc)
          doc
        }

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
