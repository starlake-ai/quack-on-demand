package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.BoundedHttp
import ai.starlake.quack.edge.config.AuthenticationConfig
import com.typesafe.scalalogging.LazyLogging
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.control.NonFatal

object SqlTokenOidcService extends LazyLogging:

  /** Default code->token exchange: POST grant_type=authorization_code to the token endpoint and
    * return the raw JSON body, or Left(reason). The answer is read under
    * [[BoundedHttp.MaxIdpAnswerBytes]] and [[BoundedHttp.IdpTimeoutMs]] over the whole exchange.
    * The callback page shows the reason, and the form holds the client secret, so no failure text
    * carries any of the exchange: a refused status is named by its code alone, and any other
    * failure by one fixed text ([[BoundedHttp.tokenExchange]]), never by the provider's answer or
    * the JDK's exception, which may quote what the provider was sent.
    */
  def defaultExchange(tokenEndpoint: String, form: String): Either[String, String] =
    BoundedHttp.tokenExchange(
      tokenEndpoint,
      form,
      code => s"Token exchange failed (HTTP $code)"
    )

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

  /** The code-to-token exchange, which the callback is anonymous to: an exchange that throws is a
    * failed exchange with the fixed text of any other, and not an exception out of the callback.
    * The form holds the client secret, so the log gets the class of what was thrown and nothing of
    * its message.
    */
  private def exchanged(tokenEndpoint: String, form: String): Either[String, String] =
    try httpExchange(tokenEndpoint, form)
    catch
      case NonFatal(e) =>
        logger.warn(s"SQL token exchange failed: ${e.getClass.getSimpleName}")
        Left(BoundedHttp.TokenEndpointFailed)

  private def sign(value: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(stateSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    val raw = mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
    raw.map("%02x".format(_)).mkString

  private val b64  = java.util.Base64.getUrlEncoder.withoutPadding()
  private val b64d = java.util.Base64.getUrlDecoder

  /** state = "<nonce>.<issuedMillis>.<handoff>.<hmac>" so the callback can verify integrity and
    * freshness. `<handoff>` is `-`, or the base64url of a JDBC driver's [[DriverHandoff]] when the
    * login was started for one.
    */
  private def mintState(handoff: Option[DriverHandoff]): String =
    val nonce = java.util.UUID.randomUUID().toString
    val h = handoff.fold("-")(d => b64.encodeToString(d.encoded.getBytes(StandardCharsets.UTF_8)))
    val body = s"$nonce.${nowMillis()}.$h"
    s"$body.${sign(body)}"

  /** The handoff segment of a state whose signature and freshness hold, else None. */
  private def verifiedHandoffSegment(state: String): Option[String] =
    state.split('.') match
      case Array(nonce, ts, h, sig) =>
        val fresh = ts.toLongOption.exists(t => nowMillis() - t <= 600000L) // 10 min
        val sigOk = java.security.MessageDigest.isEqual(
          sign(s"$nonce.$ts.$h").getBytes(StandardCharsets.UTF_8),
          sig.getBytes(StandardCharsets.UTF_8)
        )
        Option.when(sigOk && fresh)(h)
      case _ => None

  private def verifyState(state: String): Boolean = verifiedHandoffSegment(state).isDefined

  /** The JDBC driver a state signed here was started for, when the state is still valid. */
  def handoffOf(state: String): Option[DriverHandoff] =
    verifiedHandoffSegment(state)
      .filter(_ != "-")
      .flatMap(h => scala.util.Try(new String(b64d.decode(h), StandardCharsets.UTF_8)).toOption)
      .flatMap(DriverHandoff.decode)

  def startUrl(handoff: Option[DriverHandoff] = None): Either[String, (String, String)] =
    endpoints.map { case (authorize, _, clientId, _) =>
      val state = mintState(handoff)
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
        exchanged(tokenEndpoint, form).flatMap { body =>
          // Prefer id_token: the edge presents the pasted token as a Bearer, and
          // OidcBearerAuthenticator requires aud == clientId, which the id_token
          // carries by OIDC spec. The access_token's aud is the provider's resource
          // (Keycloak "account", Azure Graph) or opaque (Google), so it would be
          // rejected. The access_token is only a fallback for non-standard providers.
          JsonField
            .first(body, "id_token")
            .orElse(JsonField.first(body, "access_token"))
            .toRight("no id_token or access_token in token response")
        }
      }
