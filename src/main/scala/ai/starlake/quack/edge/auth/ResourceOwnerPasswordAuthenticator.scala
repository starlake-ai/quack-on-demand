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
  ): Either[AuthFailure, AuthenticatedProfile] =
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
        Left(
          AuthFailure.InvalidCredentials(
            s"$name: authentication failed (HTTP ${response.statusCode()})"
          )
        )
      else
        // Parse the access_token from the JSON response
        val responseBody = response.body()
        JsonField.first(responseBody, "access_token") match
          case None =>
            Left(AuthFailure.InvalidCredentials(s"$name: no access_token in response"))
          case Some(accessToken) =>
            // Decode the access token to extract claims
            val signedJWT = SignedJWT.parse(accessToken)
            val claims    = signedJWT.getJWTClaimsSet

            val preferredUsername = Option(claims.getStringClaim("preferred_username"))
            // The token names the user, never the text typed into the form: an IdP that accepts a
            // login by an unverified address would otherwise let that address name a row, past
            // the email_verified rule below. A token naming no one is refused.
            preferredUsername
              .orElse(Option(claims.getStringClaim("email")))
              .orElse(Option(claims.getSubject)) match
              case None =>
                Left(
                  AuthFailure.InvalidCredentials(
                    s"$name: token has no preferred_username, email or sub"
                  )
                )
              case Some(extractedUsername) =>
                val role   = RoleExtractor.extract(claims, roleClaim)
                val groups = RoleExtractor.extractGroups(claims, "groups")
                val email  = Option(claims.getStringClaim("email")).filter(_.nonEmpty)
                // The management login (AuthHandlers.mintSessionFor) reads these two to tell a
                // user name that fell back to the email claim, an address its holder may have
                // typed, from the IdP's own account name: the name came from the email when the
                // token has no preferred_username, and counts only when the IdP marks the address
                // verified. A JSON boolean reads "true" / "false", as OidcBearerAuthenticator
                // flattens it.
                val emailVerified = Option(claims.getClaim("email_verified")).map(_.toString)

                Right(
                  AuthenticatedProfile(
                    username = extractedUsername,
                    role = role,
                    groups = groups + role,
                    claims = Map(
                      "sub"         -> extractedUsername,
                      "role"        -> role,
                      "auth_method" -> name
                    ) ++ email.map("email" -> _) ++
                      preferredUsername.map("preferred_username" -> _) ++
                      emailVerified.map("email_verified" -> _),
                    authMethod = name
                  )
                )
    catch
      case e: Exception =>
        logger.debug(s"$name: ROPC authentication failed: ${e.getMessage}", e)
        Left(AuthFailure.InvalidCredentials(s"$name: ${e.getMessage}"))

  private def buildFormBody(username: String, password: String): String =
    val enc = (s: String) => URLEncoder.encode(s, StandardCharsets.UTF_8)
    s"grant_type=password&client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}" +
      s"&username=${enc(username)}&password=${enc(password)}"
