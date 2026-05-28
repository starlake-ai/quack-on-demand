package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.{AuthenticationConfig, SessionConfig}
import com.typesafe.scalalogging.LazyLogging

/** Central authentication orchestrator. Builds chains of authenticators from config
  * and tries them in order for each authentication request.
  */
class AuthenticationService(config: AuthenticationConfig, sessionConfig: SessionConfig)
    extends AutoCloseable, LazyLogging:

  private val basicProviders: List[BasicAuthProvider] = buildBasicChain()
  private val bearerProviders: List[BearerAuthProvider] = buildBearerChain()

  logger.info(
    s"Authentication service initialized. Basic providers: [${basicProviders.map(_.name).mkString(", ")}], " +
      s"Bearer providers: [${bearerProviders.map(_.name).mkString(", ")}]"
  )

  // Start OAuth HTTP server if enabled and an OIDC provider is configured
  val oauthServer: Option[OAuthHttpServer] =
    if config.oauth.enabled &&
      (config.keycloak.enabled || config.google.enabled || config.azure.enabled)
    then
      val server = new OAuthHttpServer(config.oauth, config, sessionConfig.jwtSecretKey)
      server.start()
      Some(server)
    else None

  /** The OAuth base URL for __discover__ responses, if OAuth is enabled. */
  val oauthBaseUrl: Option[String] = oauthServer.map(_.baseUrl)

  val hasProviders: Boolean = basicProviders.nonEmpty || bearerProviders.nonEmpty

  def authenticateBasic(username: String, password: String): Either[String, AuthenticatedProfile] =
    if basicProviders.isEmpty then Left("No basic auth providers configured")
    else
      val errors = List.newBuilder[String]
      basicProviders.iterator
        .map { provider =>
          provider.authenticate(username, password) match
            case right @ Right(_) =>
              logger.info(s"User '$username' authenticated via ${provider.name}")
              right
            case Left(err) =>
              logger.debug(s"Provider ${provider.name} rejected '$username': $err")
              errors += s"${provider.name}: $err"
              Left(err)
        }
        .collectFirst { case r @ Right(_) => r }
        .getOrElse(Left(s"Authentication failed: ${errors.result().mkString("; ")}"))

  def authenticateBearer(token: String): Either[String, AuthenticatedProfile] =
    if bearerProviders.isEmpty then Left("No bearer auth providers configured")
    else
      val errors = List.newBuilder[String]
      bearerProviders.iterator
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
        "keycloak", tokenEndpoint,
        config.keycloak.clientId, config.keycloak.clientSecret,
        config.roleClaim
      )
    // Google does not support ROPC (grant_type=password) — no basic auth provider for Google.
    // Google users must authenticate via Bearer token or browser-based OAuth/SSO.
    if config.azure.enabled then
      val tokenEndpoint =
        s"https://login.microsoftonline.com/${config.azure.tenantId}/oauth2/v2.0/token"
      providers += new ResourceOwnerPasswordAuthenticator(
        "azure", tokenEndpoint,
        config.azure.clientId, config.azure.clientSecret,
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