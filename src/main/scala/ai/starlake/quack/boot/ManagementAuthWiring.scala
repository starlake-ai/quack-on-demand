package ai.starlake.quack.boot

import ai.starlake.quack.ManagerConfig
import ai.starlake.quack.edge.auth.{
  OidcBearerAuthenticator,
  OidcDiscovery,
  OidcEndpointResolver,
  OidcEndpoints,
  OidcSsoService,
  OidcStateCodec,
  SqlTokenOidcService
}
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.{
  ManagementAuthMode,
  ManagementAuthModeResolver,
  ManagementIdentitySource
}
import ai.starlake.quack.secrets.SecretRefResolver
import com.typesafe.scalalogging.LazyLogging

/** Admin-UI / management-plane auth components, extracted from Main.bootManager: identity source
  * selection, per-tenant auth-mode resolution, session-cookie Secure policy, the admin-UI OIDC SSO
  * service, and the data-plane SQL-token flow.
  */
final case class ManagementAuthComponents(
    identitySource: ManagementIdentitySource,
    authModeResolver: ManagementAuthModeResolver,
    cookieSecureOverride: Option[Boolean],
    oidcSso: Option[OidcSsoService],
    sqlToken: Option[SqlTokenOidcService]
)

object ManagementAuthWiring extends LazyLogging:

  def build(
      mgrCfg: ManagerConfig,
      authCfg: AuthenticationConfig,
      loadTenant: String => Option[Tenant]
  ): ManagementAuthComponents =
    val identitySource = ManagementIdentitySource.fromConfig(mgrCfg.auth.management.identitySource)
    // System scope (bare /ui/) login mode mirrors identitySource; per-tenant logins resolve their
    // mode from the tenant's authProvider via the resolver below.
    val systemAuthMode = identitySource match
      case ManagementIdentitySource.Oidc => ManagementAuthMode.Oidc
      case ManagementIdentitySource.Db   => ManagementAuthMode.Db
    val authModeResolver = new ManagementAuthModeResolver(
      loadTenant = loadTenant,
      systemMode = systemAuthMode
    )
    // 'auto' -> None (handler derives Secure from X-Forwarded-Proto per request); 'true' / 'false'
    // -> explicit override. Unknown values fall back to auto with a warning so a typo in the env
    // var doesn't accidentally weaken the cookie.
    val cookieSecureOverride: Option[Boolean] =
      mgrCfg.auth.management.sessionCookieSecure.trim.toLowerCase match
        case "auto"  => None
        case "true"  => Some(true)
        case "false" => Some(false)
        case other   =>
          logger.warn(
            s"QOD_SESSION_COOKIE_SECURE='$other' not recognized; expected auto|true|false. " +
              "Treating as 'auto' (derive from X-Forwarded-Proto)."
          )
          None
    ManagementAuthComponents(
      identitySource = identitySource,
      authModeResolver = authModeResolver,
      cookieSecureOverride = cookieSecureOverride,
      oidcSso = buildOidcSso(mgrCfg, authCfg, identitySource, loadTenant),
      sqlToken = buildSqlTokenService(mgrCfg, authCfg)
    )

  /** Build the admin-UI OIDC SSO service only in oidc mode. Discovery + token exchange use a shared
    * java.net.http client; id_token validation reuses OidcBearerAuthenticator against the
    * discovered jwks_uri. redirect_uri is built from the public base URL (must match the IdP
    * client's registered redirect URI).
    */
  private def buildOidcSso(
      mgrCfg: ManagerConfig,
      authCfg: AuthenticationConfig,
      identitySource: ManagementIdentitySource,
      loadTenant: String => Option[Tenant]
  ): Option[OidcSsoService] =
    if identitySource == ManagementIdentitySource.Oidc then
      val httpClient = java.net.http.HttpClient
        .newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val discovery = new OidcDiscovery(httpGet =
        url =>
          try
            val req = java.net.http.HttpRequest
              .newBuilder()
              .uri(java.net.URI.create(url))
              .GET()
              .timeout(java.time.Duration.ofSeconds(15))
              .build()
            val resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if resp.statusCode() == 200 then Right(resp.body())
            else Left(s"discovery HTTP ${resp.statusCode()}")
          catch case e: Exception => Left(e.getMessage)
      )
      val resolver = new OidcEndpointResolver(
        loadTenant = loadTenant,
        secrets = SecretRefResolver.default,
        discovery = discovery
      )
      val codec = new OidcStateCodec(mgrCfg.auth.management.sessionJwtSecret, 600000L)
      if mgrCfg.auth.management.publicBaseUrl.trim.isEmpty then
        logger.warn(
          "identitySource=oidc but QOD_MGMT_PUBLIC_BASE_URL is unset; OIDC redirect_uri defaults " +
            s"to http://localhost:${mgrCfg.port}, which must match the IdP client's registered " +
            "redirect URI. Set QOD_MGMT_PUBLIC_BASE_URL for any non-localhost deploy."
        )
      val publicBaseUrlOf = () =>
        val base = mgrCfg.auth.management.publicBaseUrl
        if base.trim.nonEmpty then base.trim else s"http://localhost:${mgrCfg.port}"
      val httpExchange = (url: String, form: String) =>
        try
          val req = java.net.http.HttpRequest
            .newBuilder()
            .uri(java.net.URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form))
            .timeout(java.time.Duration.ofSeconds(15))
            .build()
          val resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
          if resp.statusCode() == 200 then Right(resp.body())
          else Left(s"token endpoint HTTP ${resp.statusCode()}")
        catch case e: Exception => Left(e.getMessage)
      val buildValidator = (ep: OidcEndpoints) =>
        new OidcBearerAuthenticator(
          ep.provider,
          ep.jwksUrl,
          ep.issuer,
          ep.clientId,
          authCfg.roleClaim
        )
      Some(
        new OidcSsoService(
          resolver = resolver,
          mgmt = mgrCfg.auth.management.oidc,
          codec = codec,
          roleClaim = authCfg.roleClaim,
          publicBaseUrlOf = publicBaseUrlOf,
          httpExchange = httpExchange,
          buildValidator = buildValidator
        )
      )
    else None

  /** Data-plane SQL-token flow (/api/auth/sql-token): an auth-code login against the EDGE OIDC
    * provider (not the management one) that hands a JDBC client a bearer to paste into DBeaver's
    * `token` property. None when no edge OIDC provider is enabled; handlers gate on `.enabled`.
    * Discovery is fetched server-side from the in-cluster issuer, but yields the provider's
    * browser-facing authorization_endpoint (so the 302 the user follows is reachable) and the
    * back-channel token_endpoint (for the server-side code exchange).
    */
  private def buildSqlTokenService(
      mgrCfg: ManagerConfig,
      authCfg: AuthenticationConfig
  ): Option[SqlTokenOidcService] =
    val publicBaseUrl = () =>
      val base = mgrCfg.auth.management.publicBaseUrl
      if base.trim.nonEmpty then base.trim else s"http://localhost:${mgrCfg.port}"
    if authCfg.keycloak.enabled || authCfg.google.enabled || authCfg.azure.enabled then
      val sqlTokenHttp = java.net.http.HttpClient
        .newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val sqlTokenDiscovery = new OidcDiscovery(httpGet =
        url =>
          try
            val req = java.net.http.HttpRequest
              .newBuilder()
              .uri(java.net.URI.create(url))
              .GET()
              .timeout(java.time.Duration.ofSeconds(15))
              .build()
            val resp = sqlTokenHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if resp.statusCode() == 200 then Right(resp.body())
            else Left(s"discovery HTTP ${resp.statusCode()}")
          catch case e: Exception => Left(e.getMessage)
      )
      Some(
        new SqlTokenOidcService(
          authCfg,
          publicBaseUrl,
          mgrCfg.auth.management.sessionJwtSecret,
          sqlTokenDiscovery
        )
      )
    else None
