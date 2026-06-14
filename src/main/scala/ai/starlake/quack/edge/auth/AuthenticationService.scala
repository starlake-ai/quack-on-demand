package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.AuthenticationConfig
import com.typesafe.scalalogging.LazyLogging

/** Central authentication orchestrator. Builds chains of authenticators from config and tries them
  * in order for each authentication request.
  *
  * `tenantOidcRegistry` (optional) lets an `AuthScope.Tenant(tenantId)` bearer handshake validate
  * the token against THAT tenant's per-tenant OIDC client (Google / Keycloak / Azure / AWS) instead
  * of the manager-wide one. When absent (or when the registry has no per-tenant override for the
  * tenant), the chain falls back to the global config -- which is what the system-scope path uses
  * unconditionally.
  */
class AuthenticationService(
    config: AuthenticationConfig,
    jwtSecretKey: String,
    tenantOidcRegistry: Option[TenantOidcRegistry] = None
) extends AutoCloseable,
      LazyLogging:

  private val basicProviders: List[BasicAuthProvider]   = buildBasicChain()
  private val bearerProviders: List[BearerAuthProvider] = buildBearerChain()

  logger.info(
    s"Authentication service initialized. Basic providers: [${basicProviders.map(_.name).mkString(", ")}], " +
      s"Bearer providers: [${bearerProviders.map(_.name).mkString(", ")}]" +
      tenantOidcRegistry.fold("")(_ => " (per-tenant OIDC registry enabled)")
  )

  // Start OAuth HTTP server if enabled and an OIDC provider is configured
  val oauthServer: Option[OAuthHttpServer] =
    if config.oauth.enabled &&
      (config.keycloak.enabled || config.google.enabled || config.azure.enabled)
    then
      val server = new OAuthHttpServer(config.oauth, config, jwtSecretKey)
      server.start()
      Some(server)
    else None

  /** The OAuth base URL for __discover__ responses, if OAuth is enabled. */
  val oauthBaseUrl: Option[String] = oauthServer.map(_.baseUrl)

  val hasProviders: Boolean = basicProviders.nonEmpty || bearerProviders.nonEmpty

  /** Authenticate `(scope, username, password)` against the basic chain.
    *
    *   - [[AuthScope.System]] -- manager UI login with empty tenant, or FlightSQL handshake with
    *     `?superuser=true`. Validated against the global realm; the matching `qodstate_user` row
    *     must have `tenant IS NULL`.
    *   - [[AuthScope.Tenant]] -- regular tenant login. Validated against the tenant's realm; the
    *     matching `qodstate_user` row must have `tenant = ?`.
    */
  def authenticateBasic(
      scope: AuthScope,
      username: String,
      password: String
  ): Either[String, AuthenticatedProfile] =
    if basicProviders.isEmpty then Left("No basic auth providers configured")
    else
      val scopeLabel = scope match
        case AuthScope.System    => "system"
        case AuthScope.Tenant(t) => t
      val errors = List.newBuilder[String]
      basicProviders.iterator
        .map { provider =>
          provider.authenticate(scope, username, password) match
            case right @ Right(_) =>
              logger.info(s"User '$username' ($scopeLabel) authenticated via ${provider.name}")
              right
            case Left(err) =>
              logger.debug(s"Provider ${provider.name} rejected '$username' ($scopeLabel): $err")
              errors += s"${provider.name}: $err"
              Left(err)
        }
        .collectFirst { case r @ Right(_) => r }
        .getOrElse(Left(s"Authentication failed: ${errors.result().mkString("; ")}"))

  /** System-scope bearer validation against the global chain. Used for management-plane logins (no
    * tenant context) and FlightSQL handshakes with `?superuser=true`.
    */
  def authenticateBearer(token: String): Either[String, AuthenticatedProfile] =
    authenticateBearer(AuthScope.System, token)

  /** Scope-aware bearer validation. For `AuthScope.Tenant(t)` the registry can substitute a
    * per-tenant OIDC authenticator into the chain in place of the matching global one (Google /
    * Keycloak / Azure / AWS).
    */
  def authenticateBearer(
      scope: AuthScope,
      token: String
  ): Either[String, AuthenticatedProfile] =
    val chain = bearerChainFor(scope)
    if chain.isEmpty then Left("No bearer auth providers configured")
    else
      val errors = List.newBuilder[String]
      chain.iterator
        .map { provider =>
          provider.authenticate(token) match
            case right @ Right(_) =>
              logger.info(s"Bearer token authenticated via ${provider.name}")
              right
            case Left(err) =>
              logger.debug(s"Bearer provider ${provider.name} rejected token: $err")
              errors += s"${provider.name}: $err"
              Left(err)
        }
        .collectFirst { case r @ Right(_) => r }
        .getOrElse(Left(s"Bearer authentication failed: ${errors.result().mkString("; ")}"))

  /** Resolve the bearer chain for a given scope. For `AuthScope.Tenant(t)` we ask the registry for
    * a per-tenant OIDC authenticator built from the tenant's `authProvider` + `authConfig`. If the
    * global chain already has a slot for that provider it gets swapped; if not (operator runs fully
    * per-tenant OIDC) the per-tenant authenticator is prepended so it actually gets tried.
    */
  private def bearerChainFor(scope: AuthScope): List[BearerAuthProvider] =
    scope match
      case AuthScope.System           => bearerProviders
      case AuthScope.Tenant(tenantId) =>
        tenantOidcRegistry.flatMap(_.forTenant(tenantId)) match
          case None            => bearerProviders
          case Some(perTenant) =>
            val target  = perTenant.providerName
            val swapped = bearerProviders.map {
              case o: OidcBearerAuthenticator if o.providerName == target => perTenant
              case other                                                  => other
            }
            val hadMatch = bearerProviders.exists {
              case o: OidcBearerAuthenticator => o.providerName == target
              case _                          => false
            }
            if hadMatch then swapped else perTenant :: swapped

  override def close(): Unit =
    oauthServer.foreach { s =>
      try s.close()
      catch case e: Exception => logger.warn(s"Error closing OAuth server: ${e.getMessage}")
    }
    basicProviders.foreach { p =>
      try p.close()
      catch case e: Exception => logger.warn(s"Error closing ${p.name}: ${e.getMessage}")
    }
    bearerProviders.foreach { p =>
      try p.close()
      catch case e: Exception => logger.warn(s"Error closing ${p.name}: ${e.getMessage}")
    }

  private def buildBasicChain(): List[BasicAuthProvider] =
    val providers = List.newBuilder[BasicAuthProvider]
    if config.database.enabled then
      logger.info("Initializing database authentication provider")
      providers += new DatabaseAuthenticator(config.database, config.roleClaim)
    // ROPC providers for OIDC backends that support password grant
    if config.keycloak.enabled then
      val tokenEndpoint =
        s"${config.keycloak.baseUrl}/realms/${config.keycloak.realm}/protocol/openid-connect/token"
      providers += new ResourceOwnerPasswordAuthenticator(
        "keycloak",
        tokenEndpoint,
        config.keycloak.clientId,
        config.keycloak.clientSecret,
        config.roleClaim
      )
    // Google does not support ROPC (grant_type=password) - no basic auth provider for Google.
    // Google users must authenticate via Bearer token or browser-based OAuth/SSO.
    if config.azure.enabled then
      val tokenEndpoint =
        s"https://login.microsoftonline.com/${config.azure.tenantId}/oauth2/v2.0/token"
      providers += new ResourceOwnerPasswordAuthenticator(
        "azure",
        tokenEndpoint,
        config.azure.clientId,
        config.azure.clientSecret,
        config.roleClaim
      )
    providers.result()

  private def buildBearerChain(): List[BearerAuthProvider] =
    val providers = List.newBuilder[BearerAuthProvider]
    // External JWT (custom issuers with shared secret or public key)
    val jwtConfig = config.jwt
    if jwtConfig.secretKey.nonEmpty || jwtConfig.publicKeyPath.nonEmpty then
      logger.info("Initializing external JWT authentication provider")
      providers += new JwtBearerAuthenticator(jwtConfig, config.roleClaim)
    // OIDC providers (JWKS-based token validation)
    if config.keycloak.enabled then
      logger.info("Initializing Keycloak bearer authentication provider")
      providers += OidcProviderFactory.createKeycloak(config.keycloak, config.roleClaim)
    if config.google.enabled then
      logger.info("Initializing Google bearer authentication provider")
      providers += OidcProviderFactory.createGoogle(config.google, config.roleClaim)
    if config.azure.enabled then
      logger.info("Initializing Azure AD bearer authentication provider")
      providers += OidcProviderFactory.createAzure(config.azure, config.roleClaim)
    if config.aws.enabled then
      logger.info("Initializing AWS Cognito bearer authentication provider")
      providers += OidcProviderFactory.createAws(config.aws, config.roleClaim)
    providers.result()
