package ai.starlake.quack.edge.auth

import com.nimbusds.jwt.SignedJWT
import com.typesafe.scalalogging.LazyLogging

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Authenticates username/password via OAuth2 Resource Owner Password Credentials (ROPC) grant.
  * Posts grant_type=password to the IdP token endpoint and parses the returned JWT.
  */
class ResourceOwnerPasswordAuthenticator(
    providerName: String,
    tokenEndpoint: String,
    clientId: String,
    clientSecret: String,
    roleClaim: String
) extends BasicAuthProvider,
      LazyLogging:

  val name: String = s"$providerName-ropc"

  private val httpClient: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  override def authenticate(
      scope: AuthScope,
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile] =
    // OIDC ROPC providers don't see `scope` -- the OIDC server is
    // authoritative for the password check; map-to-tenant happens via
    // the JWT claims on the way back. (The TenantOidcRegistry picks the
    // right ROPC provider instance per scope before this is called.)
    val _ = scope
    try
      val body    = buildFormBody(username, password)
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(tokenEndpoint))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(java.time.Duration.ofSeconds(15))
        .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if response.statusCode() != 200 then
        logger.debug(s"$name: token endpoint returned ${response.statusCode()}: ${response.body()}")
        Left(s"$name: authentication failed (HTTP ${response.statusCode()})")
      else
        // Parse the access_token from the JSON response
        val responseBody = response.body()
        extractJsonField(responseBody, "access_token") match
          case None =>
            Left(s"$name: no access_token in response")
          case Some(accessToken) =>
            // Decode the access token to extract claims
            val signedJWT = SignedJWT.parse(accessToken)
            val claims    = signedJWT.getJWTClaimsSet

            val extractedUsername = Option(claims.getStringClaim("preferred_username"))
              .orElse(Option(claims.getStringClaim("email")))
              .orElse(Option(claims.getSubject))
              .getOrElse(username)

            val role   = RoleExtractor.extract(claims, roleClaim)
            val groups = RoleExtractor.extractGroups(claims, "groups")
            val email  = Option(claims.getStringClaim("email")).filter(_.nonEmpty)

            Right(
              AuthenticatedProfile(
                username = extractedUsername,
                role = role,
                groups = groups + role,
                claims = Map(
                  "sub"         -> extractedUsername,
                  "role"        -> role,
                  "auth_method" -> name
                ) ++ email.map("email" -> _),
                authMethod = name
              )
            )
    catch
      case e: Exception =>
        logger.debug(s"$name: ROPC authentication failed: ${e.getMessage}", e)
        Left(s"$name: ${e.getMessage}")

  private def buildFormBody(username: String, password: String): String =
    val enc = (s: String) => URLEncoder.encode(s, StandardCharsets.UTF_8)
    s"grant_type=password&client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}" +
      s"&username=${enc(username)}&password=${enc(password)}"

  /** Simple JSON field extraction without adding a JSON parser dependency. Handles:
    * {"access_token":"value",...}
    */
  private def extractJsonField(json: String, field: String): Option[String] =
    val pattern = s""""$field"\\s*:\\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))
