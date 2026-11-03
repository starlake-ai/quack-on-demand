package ai.starlake.quack.boot

import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Locale
import ai.starlake.quack.{ManagementOidcConfig, ManagerConfig}
import ai.starlake.quack.edge.BoundedHttp
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

  /** What a failed read of a discovery document says, whatever went wrong. Its `Left` reaches the
    * page of the SQL-token flow, and the issuer is a URL the manager reads for anyone who asks, so
    * the answer quotes no server: not the JDK's message (which quotes a malformed status line, or a
    * `Location`), not a status code. The log gets the cause, cut.
    */
  private[boot] val DiscoveryUnavailable = "discovery unavailable"

  /** `GET url`, the way the manager reads an identity provider's discovery document: its body when
    * the answer is 200, else [[DiscoveryUnavailable]]. A tenant admin picks the issuer, so the
    * answer is read under [[BoundedHttp.MaxIdpAnswerBytes]] and [[BoundedHttp.IdpTimeoutMs]] over
    * the whole exchange (`HttpRequest.timeout` alone bounds only the headers): one anonymous `GET
    * /api/auth/oidc/start` makes this call, and a large or trickled answer, read whole, halted the
    * manager or held a blocking thread for as long as the issuer liked.
    *
    * It is one of the [[BoundedHttp.idpExchanges]]: with none free the answer is
    * [[BoundedHttp.Busy]], passed on as it is, since OidcDiscovery behind it must tell a refusal
    * for lack of a permit from a failure of the issuer (it remembers a failure for 10 s).
    */
  private[boot] def discoveryGet(
      url: String,
      exchanges: BoundedHttp.Exchanges = BoundedHttp.idpExchanges
  ): Either[String, String] =
    val answer =
      try
        val req = HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .GET()
          .timeout(Duration.ofMillis(BoundedHttp.IdpTimeoutMs))
          .build()
        BoundedHttp.send(
          req,
          BoundedHttp.MaxIdpAnswerBytes,
          BoundedHttp.IdpTimeoutMs,
          "discovery",
          exchanges,
          secret = None
        )
      catch case e: Exception => Left(BoundedHttp.describe(e))
    answer match
      case Right((200, body)) => Right(body)
      case Right((code, _))   =>
        logger.warn(s"discovery read failed: HTTP $code")
        Left(DiscoveryUnavailable)
      case Left(BoundedHttp.Busy) =>
        // The one refusal that is not a failure of the issuer: it passes on as it is, for
        // OidcDiscovery to tell it from a failure it remembers. Below WARN, as the cheapest thing
        // an anonymous caller can ask for.
        logger.debug(s"discovery read refused: ${BoundedHttp.Busy}")
        Left(BoundedHttp.Busy)
      case Left(why) =>
        logger.warn(s"discovery read failed: $why")
        Left(DiscoveryUnavailable)

  /** The code-to-token exchange: `form` POSTed to the token endpoint, its body when the answer is
    * 200, else why not, under the same limits as [[discoveryGet]]. The form holds the client
    * secret, so no failure text carries any of the exchange: a refused status is named by its code
    * alone, and any other failure by one fixed text ([[BoundedHttp.tokenExchange]]), never by the
    * provider's answer or the JDK's exception, which may quote what the provider was sent.
    */
  private[boot] def tokenExchange(url: String, form: String): Either[String, String] =
    BoundedHttp.tokenExchange(url, form, code => s"token endpoint HTTP $code")

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
      mgrCfg.auth.management.sessionCookieSecure.trim.toLowerCase(Locale.ROOT) match
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
      oidcSso = Some(buildOidcSso(mgrCfg, authCfg, identitySource, loadTenant)),
      sqlToken = buildSqlTokenService(mgrCfg, authCfg)
    )

  /** Build the admin-UI OIDC SSO service, under either `identitySource`. `identitySource` governs
    * only the bare `/ui/` login (the system mode of `ManagementAuthModeResolver`); a tenant's own
    * `authProvider` decides whether its `/ui/?tenant=` login is SSO, so tenant logins need the
    * service whatever the manager-wide source is. The System scope keeps following
    * `identitySource`: under `db` the service gets an empty `ManagementOidcConfig()`, so the bare
    * SSO start, callback and logout never use the manager-wide client, even when `QOD_MGMT_OIDC_*`
    * are set; under `oidc` it answers `oidc_not_configured` unless `QOD_MGMT_OIDC_ISSUER_URL` and
    * `QOD_MGMT_OIDC_CLIENT_ID` are set. Nothing is fetched here: discovery runs on the first login
    * of each issuer.
    *
    * Discovery and the token exchange go through [[BoundedHttp]] (HTTP/1.1, a client for each
    * exchange, a bound on the exchanges in flight); id_token validation reuses
    * OidcBearerAuthenticator against the discovered jwks_uri. redirect_uri is built from the public
    * base URL (must match the IdP client's registered redirect URI).
    */
  private def buildOidcSso(
      mgrCfg: ManagerConfig,
      authCfg: AuthenticationConfig,
      identitySource: ManagementIdentitySource,
      loadTenant: String => Option[Tenant]
  ): OidcSsoService =
    val discovery = new OidcDiscovery(httpGet = url => discoveryGet(url))
    val resolver  = new OidcEndpointResolver(
      loadTenant = loadTenant,
      secrets = SecretRefResolver.default,
      discovery = discovery
    )
    val codec = new OidcStateCodec(mgrCfg.auth.management.sessionJwtSecret, 600000L)
    // Warn only where the bare /ui/ login is SSO, so a db-mode boot stays quiet.
    val publicBaseUrlUnset = mgrCfg.auth.management.publicBaseUrl.trim.isEmpty
    if identitySource == ManagementIdentitySource.Oidc && publicBaseUrlUnset then
      logger.warn(
        "identitySource=oidc but QOD_MGMT_PUBLIC_BASE_URL is unset; OIDC redirect_uri defaults " +
          s"to http://localhost:${mgrCfg.port}, which must match the IdP client's registered " +
          "redirect URI. Set QOD_MGMT_PUBLIC_BASE_URL for any non-localhost deploy."
      )
    val publicBaseUrlOf = () =>
      val base = mgrCfg.auth.management.publicBaseUrl
      if base.trim.nonEmpty then base.trim else s"http://localhost:${mgrCfg.port}"
    val buildValidator = (ep: OidcEndpoints) =>
      new OidcBearerAuthenticator(
        ep.provider,
        ep.jwksUrl,
        ep.issuer,
        ep.clientId,
        authCfg.roleClaim
      )
    // The System scope (bare /ui/) follows identitySource: in db mode it gets no client, even when
    // QOD_MGMT_OIDC_* are set, so its start, callback and logout never use the manager-wide one.
    val systemClient =
      if identitySource == ManagementIdentitySource.Oidc then mgrCfg.auth.management.oidc
      else ManagementOidcConfig()
    new OidcSsoService(
      resolver = resolver,
      mgmt = systemClient,
      codec = codec,
      roleClaim = authCfg.roleClaim,
      publicBaseUrlOf = publicBaseUrlOf,
      httpExchange = tokenExchange,
      buildValidator = buildValidator
    )

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
      val sqlTokenDiscovery = new OidcDiscovery(httpGet = url => discoveryGet(url))
      Some(
        new SqlTokenOidcService(
          authCfg,
          publicBaseUrl,
          mgrCfg.auth.management.sessionJwtSecret,
          sqlTokenDiscovery
        )
      )
    else None
