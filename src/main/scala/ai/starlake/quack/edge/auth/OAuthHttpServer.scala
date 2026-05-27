package ai.starlake.quack.edge.auth

import ai.starlake.gizmo.proxy.config.{AuthenticationConfig, OAuthConfig}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import com.typesafe.scalalogging.LazyLogging

import java.net.{InetSocketAddress, URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Lightweight HTTP server that handles the OAuth authorization code flow
  * for browser-based ADBC/CLI clients. Mimics the GizmoSQL C++ OAuth server.
  *
  * Endpoints:
  *   GET /oauth/initiate         — start a session, return UUID + auth URL
  *   GET /oauth/start?session=X  — browser redirect to IdP
  *   GET /oauth/callback         — IdP redirects here with authorization code
  *   GET /oauth/token/{uuid}     — poll for completed token
  */
class OAuthHttpServer(
    oauthConfig: OAuthConfig,
    authConfig: AuthenticationConfig,
    secretKey: String
) extends AutoCloseable,
      LazyLogging:

  private case class PendingSession(
      uuid: String,
      hash: String,
      createdAt: Long,
      var idToken: Option[String] = None,
      var error: Option[String] = None
  )

  private val sessions = new ConcurrentHashMap[String, PendingSession]()
  private val sessionsByHash = new ConcurrentHashMap[String, PendingSession]()
  private val httpClient = HttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  // Resolve IdP endpoints from the first enabled OIDC provider
  private val (authorizationEndpoint, tokenEndpoint, idpClientId, idpClientSecret) =
    resolveIdpEndpoints()

  private val redirectUri: String =
    val base = if oauthConfig.baseUrl.nonEmpty then oauthConfig.baseUrl
    else s"http://localhost:${oauthConfig.port}"
    s"$base/oauth/callback"

  val baseUrl: String =
    if oauthConfig.baseUrl.nonEmpty then oauthConfig.baseUrl
    else s"http://localhost:${oauthConfig.port}"

  private var server: HttpServer = scala.compiletime.uninitialized

  def start(): Unit =
    server = HttpServer.create(new InetSocketAddress(oauthConfig.port), 0)
    server.setExecutor(Executors.newFixedThreadPool(4))
    server.createContext("/oauth/initiate", initiateHandler)
    server.createContext("/oauth/start", startHandler)
    server.createContext("/oauth/callback", callbackHandler)
    server.createContext("/oauth/token/", tokenHandler)
    server.start()
    logger.info(s"OAuth HTTP server started on port ${oauthConfig.port} (base URL: $baseUrl)")

  override def close(): Unit =
    if server != null then
      server.stop(0)
      logger.info("OAuth HTTP server stopped")

  // --- Handlers ---

  private val initiateHandler: HttpHandler = (exchange: HttpExchange) =>
    try
      val uuid = UUID.randomUUID().toString
      val hash = hmacSha256(secretKey, uuid)
      val session = PendingSession(uuid, hash, System.currentTimeMillis())
      sessions.put(uuid, session)
      sessionsByHash.put(hash, session)
      cleanupExpiredSessions()

      val authUrl = buildAuthorizationUrl(hash)
      val json = s"""{"session_uuid":"$uuid","auth_url":"$authUrl"}"""
      sendJson(exchange, 200, json)
    catch
      case e: Exception =>
        logger.error("Error in /oauth/initiate", e)
        sendJson(exchange, 500, s"""{"error":"${e.getMessage}"}""")

  private val startHandler: HttpHandler = (exchange: HttpExchange) =>
    try
      val params = parseQuery(exchange.getRequestURI.getQuery)
      params.get("session") match
        case Some(hash) if sessionsByHash.containsKey(hash) =>
          val authUrl = buildAuthorizationUrl(hash)
          exchange.getResponseHeaders.set("Location", authUrl)
          exchange.sendResponseHeaders(302, -1)
          exchange.close()
        case _ =>
          sendJson(exchange, 404, """{"error":"Session not found"}""")
    catch
      case e: Exception =>
        logger.error("Error in /oauth/start", e)
        sendJson(exchange, 500, s"""{"error":"${e.getMessage}"}""")

  private val callbackHandler: HttpHandler = (exchange: HttpExchange) =>
    try
      val params = parseQuery(exchange.getRequestURI.getQuery)
      val code = params.getOrElse("code", "")
      val state = params.getOrElse("state", "")

      if code.isEmpty || state.isEmpty then
        val error = params.getOrElse("error", "missing code or state")
        sendHtml(exchange, 400, s"<h2>Authentication failed</h2><p>$error</p>")
      else
        val session = sessionsByHash.get(state)
        if session == null then
          sendHtml(exchange, 404, "<h2>Session expired or not found</h2>")
        else
          // Exchange authorization code for tokens
          exchangeCodeForToken(code) match
            case Right(idToken) =>
              session.idToken = Some(idToken)
              sendHtml(exchange, 200,
                "<h2>Authentication successful</h2><p>You can close this window and return to your application.</p>")
            case Left(error) =>
              session.error = Some(error)
              sendHtml(exchange, 400, s"<h2>Authentication failed</h2><p>$error</p>")
    catch
      case e: Exception =>
        logger.error("Error in /oauth/callback", e)
        sendHtml(exchange, 500, s"<h2>Server error</h2><p>${e.getMessage}</p>")

  private val tokenHandler: HttpHandler = (exchange: HttpExchange) =>
    try
      val path = exchange.getRequestURI.getPath
      val uuid = path.stripPrefix("/oauth/token/")
      val session = sessions.get(uuid)
      if session == null then
        sendJson(exchange, 404, """{"status":"error","error":"Session not found"}""")
      else if session.error.isDefined then
        val error = session.error.get
        sessions.remove(uuid)
        sessionsByHash.remove(session.hash)
        sendJson(exchange, 200, s"""{"status":"error","error":"$error"}""")
      else if session.idToken.isDefined then
        val token = session.idToken.get
        sessions.remove(uuid)
        sessionsByHash.remove(session.hash)
        sendJson(exchange, 200, s"""{"status":"complete","token":"$token"}""")
      else
        sendJson(exchange, 200, """{"status":"pending"}""")
    catch
      case e: Exception =>
        logger.error("Error in /oauth/token", e)
        sendJson(exchange, 500, s"""{"status":"error","error":"${e.getMessage}"}""")

  // --- Helpers ---

  private def buildAuthorizationUrl(state: String): String =
    val enc = (s: String) => URLEncoder.encode(s, StandardCharsets.UTF_8)
    s"$authorizationEndpoint?response_type=code" +
      s"&client_id=${enc(idpClientId)}" +
      s"&redirect_uri=${enc(redirectUri)}" +
      s"&scope=${enc(oauthConfig.scopes)}" +
      s"&state=${enc(state)}"

  private def exchangeCodeForToken(code: String): Either[String, String] =
    val enc = (s: String) => URLEncoder.encode(s, StandardCharsets.UTF_8)
    val body = s"grant_type=authorization_code" +
      s"&code=${enc(code)}" +
      s"&redirect_uri=${enc(redirectUri)}" +
      s"&client_id=${enc(idpClientId)}" +
      s"&client_secret=${enc(idpClientSecret)}"

    val request = HttpRequest.newBuilder()
      .uri(URI.create(tokenEndpoint))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .timeout(java.time.Duration.ofSeconds(15))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then
      logger.error(s"Token exchange failed: ${response.statusCode()} ${response.body()}")
      Left(s"Token exchange failed (HTTP ${response.statusCode()})")
    else
      // Prefer id_token, fall back to access_token
      extractJsonField(response.body(), "id_token")
        .orElse(extractJsonField(response.body(), "access_token"))
        .toRight("No id_token or access_token in response")

  private def resolveIdpEndpoints(): (String, String, String, String) =
    if authConfig.keycloak.enabled then
      val base = s"${authConfig.keycloak.baseUrl}/realms/${authConfig.keycloak.realm}/protocol/openid-connect"
      (s"$base/auth", s"$base/token", authConfig.keycloak.clientId, authConfig.keycloak.clientSecret)
    else if authConfig.google.enabled then
      ("https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
        authConfig.google.clientId, authConfig.google.clientSecret)
    else if authConfig.azure.enabled then
      val base = s"https://login.microsoftonline.com/${authConfig.azure.tenantId}/oauth2/v2.0"
      (s"$base/authorize", s"$base/token", authConfig.azure.clientId, authConfig.azure.clientSecret)
    else
      throw new IllegalStateException("OAuth enabled but no OIDC provider configured")

  private def hmacSha256(key: String, data: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString

  private def cleanupExpiredSessions(): Unit =
    val cutoff = System.currentTimeMillis() - (oauthConfig.sessionTimeoutSeconds * 1000L)
    sessions.values().removeIf { s =>
      if s.createdAt < cutoff then
        sessionsByHash.remove(s.hash)
        true
      else false
    }

  private def parseQuery(query: String): Map[String, String] =
    if query == null || query.isEmpty then Map.empty
    else
      query.split("&").flatMap { pair =>
        pair.split("=", 2) match
          case Array(k, v) => Some(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8) ->
            java.net.URLDecoder.decode(v, StandardCharsets.UTF_8))
          case _ => None
      }.toMap

  private def sendJson(exchange: HttpExchange, code: Int, json: String): Unit =
    val bytes = json.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  private def sendHtml(exchange: HttpExchange, code: Int, html: String): Unit =
    val bytes = html.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "text/html; charset=utf-8")
    exchange.sendResponseHeaders(code, bytes.length)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  private def extractJsonField(json: String, field: String): Option[String] =
    val pattern = s""""$field"\\s*:\\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))
