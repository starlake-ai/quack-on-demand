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
    httpExchange: (String, String) => Either[String, String] = SqlTokenOidcService.defaultExchange,
    nowMillis: () => Long = () => System.currentTimeMillis()
) extends LazyLogging:

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  def enabled: Boolean =
    config.keycloak.enabled || config.google.enabled || config.azure.enabled

  def redirectUri: String = s"${publicBaseUrl()}/api/auth/sql-token/callback"

  /** (authorizeEndpoint, tokenEndpoint, clientId, clientSecret) for the first enabled provider. */
  private def endpoints: Either[String, (String, String, String, String)] =
    if config.keycloak.enabled then
      val base =
        s"${config.keycloak.baseUrl}/realms/${config.keycloak.realm}/protocol/openid-connect"
      Right(
        (s"$base/auth", s"$base/token", config.keycloak.clientId, config.keycloak.clientSecret)
      )
    else if config.google.enabled then
      Right(
        (
          "https://accounts.google.com/o/oauth2/v2/auth",
          "https://oauth2.googleapis.com/token",
          config.google.clientId,
          config.google.clientSecret
        )
      )
    else if config.azure.enabled then
      val b = s"https://login.microsoftonline.com/${config.azure.tenantId}/oauth2/v2.0"
      Right(
        (s"$b/authorize", s"$b/token", config.azure.clientId, config.azure.clientSecret)
      )
    else Left("no interactive OIDC provider is enabled")

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
        sign(s"$nonce.$ts") == sig && fresh
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
          extractJson(body, "access_token")
            .orElse(extractJson(body, "id_token"))
            .toRight("no access_token or id_token in token response")
        }
      }

  private def extractJson(json: String, field: String): Option[String] =
    val m = s""""$field"\\s*:\\s*"([^"]+)"""".r
    m.findFirstMatchIn(json).map(_.group(1))
