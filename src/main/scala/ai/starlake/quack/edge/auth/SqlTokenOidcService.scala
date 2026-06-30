package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.AuthenticationConfig
import com.typesafe.scalalogging.LazyLogging
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SqlTokenOidcService extends LazyLogging:
  private val sharedClient = HttpClient.newHttpClient()

  /** Default code->token exchange: POST grant_type=authorization_code to the token endpoint and
    * return the raw JSON body, or Left(reason).
    */
  def defaultExchange(tokenEndpoint: String, form: String): Either[String, String] =
    val req = HttpRequest
      .newBuilder()
      .uri(URI.create(tokenEndpoint))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(form))
      .timeout(java.time.Duration.ofSeconds(15))
      .build()
    val resp = sharedClient.send(req, HttpResponse.BodyHandlers.ofString())
    if resp.statusCode() != 200 then Left(s"Token exchange failed (HTTP ${resp.statusCode()})")
    else Right(resp.body())

class SqlTokenOidcService(
    config: AuthenticationConfig,
    publicBaseUrl: () => String,
    stateSecret: String,
    discovery: OidcDiscovery,
    httpExchange: (String, String) => Either[String, String] = SqlTokenOidcService.defaultExchange,
    nowMillis: () => Long = () => System.currentTimeMillis()
) extends LazyLogging:

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  def enabled: Boolean =
    config.keycloak.enabled || config.google.enabled || config.azure.enabled

  def redirectUri: String = s"${publicBaseUrl()}/api/auth/sql-token/callback"

  /** (issuerUrl, clientId, clientSecret) for the first enabled provider. */
  private def issuerAndClient: Either[String, (String, String, String)] =
    if config.keycloak.enabled then
      val issuer = s"${config.keycloak.baseUrl.stripSuffix("/")}/realms/${config.keycloak.realm}"
      Right((issuer, config.keycloak.clientId, config.keycloak.clientSecret))
    else if config.google.enabled then
      Right(("https://accounts.google.com", config.google.clientId, config.google.clientSecret))
    else if config.azure.enabled then
      Right(
        (
          s"https://login.microsoftonline.com/${config.azure.tenantId}/v2.0",
          config.azure.clientId,
          config.azure.clientSecret
        )
      )
    else Left("no interactive OIDC provider is enabled")

  /** (authorizeEndpoint, tokenEndpoint, clientId, clientSecret), resolved via OIDC discovery.
    *
    * Discovery is fetched server-side from the in-cluster issuer (e.g. keycloak:8080), but the
    * provider returns its BROWSER-facing `authorization_endpoint` (Keycloak's KC_HOSTNAME_URL) so
    * the 302 the user follows is reachable from their host, while the `token_endpoint` stays
    * back-channel (in-cluster) for the server-side code exchange. Hand-building the URLs from
    * `baseUrl` would 302 the browser to the unreachable in-cluster host.
    */
  private def endpoints: Either[String, (String, String, String, String)] =
    issuerAndClient.flatMap { case (issuer, clientId, clientSecret) =>
      discovery
        .resolve(issuer)
        .left
        .map(e => s"OIDC discovery failed for $issuer: $e")
        .map(doc => (doc.authorizationEndpoint, doc.tokenEndpoint, clientId, clientSecret))
    }

  private def sign(value: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(stateSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
    raw.map("%02x".format(_)).mkString

  /** state = "<nonce>.<issuedMillis>.<hmac>" so the callback can verify integrity + freshness. */
  private def mintState(): String =
    val nonce = java.util.UUID.randomUUID().toString
    val body  = s"$nonce.${nowMillis()}"
    s"$body.${sign(body)}"

  private def verifyState(state: String): Boolean =
    state.split('.') match
      case Array(nonce, ts, sig) =>
        val fresh = ts.toLongOption.exists(t => nowMillis() - t <= 600000L) // 10 min
        val sigOk = java.security.MessageDigest.isEqual(
          sign(s"$nonce.$ts").getBytes(StandardCharsets.UTF_8),
          sig.getBytes(StandardCharsets.UTF_8)
        )
        sigOk && fresh
      case _ => false

  def startUrl(): Either[String, (String, String)] =
    endpoints.map { case (authorize, _, clientId, _) =>
      val state = mintState()
      val url   = s"$authorize?response_type=code" +
        s"&client_id=${enc(clientId)}" +
        s"&redirect_uri=${enc(redirectUri)}" +
        s"&scope=${enc(config.oauthScopes)}" +
        s"&state=${enc(state)}"
      (url, state)
    }

  def completeAuth(
      code: String,
      returnedState: String,
      cookieState: String
  ): Either[String, String] =
    if returnedState != cookieState || !verifyState(returnedState) then
      Left("invalid or expired state")
    else
      endpoints.flatMap { case (_, tokenEndpoint, clientId, clientSecret) =>
        val form = s"grant_type=authorization_code&code=${enc(code)}" +
          s"&redirect_uri=${enc(redirectUri)}" +
          s"&client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}"
        httpExchange(tokenEndpoint, form).flatMap { body =>
          // Prefer id_token: the edge presents the pasted token as a Bearer, and
          // OidcBearerAuthenticator requires aud == clientId, which the id_token
          // carries by OIDC spec. The access_token's aud is the provider's resource
          // (Keycloak "account", Azure Graph) or opaque (Google), so it would be
          // rejected. The access_token is only a fallback for non-standard providers.
          extractJson(body, "id_token")
            .orElse(extractJson(body, "access_token"))
            .toRight("no id_token or access_token in token response")
        }
      }

  private def extractJson(json: String, field: String): Option[String] =
    val m = s""""$field"\\s*:\\s*"([^"]+)"""".r
    m.findFirstMatchIn(json).map(_.group(1))
