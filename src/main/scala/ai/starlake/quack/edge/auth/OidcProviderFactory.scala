package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.*

/** Constructs OidcBearerAuthenticator instances with the correct JWKS URL and expected
  * issuer/audience for each supported OIDC provider.
  */
object OidcProviderFactory:

  def createKeycloak(config: KeycloakAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    val jwksUrl = s"${config.baseUrl}/realms/${config.realm}/protocol/openid-connect/certs"
    // JWKS always comes from baseUrl (reachable in-cluster). The expected issuer
    // is the override when set, else derived from baseUrl -- they differ when
    // Keycloak's browser-facing issuer (KC_HOSTNAME_URL behind an ingress) is not
    // the in-cluster baseUrl. See KeycloakAuthConfig.issuer.
    val issuer =
      if config.issuer.nonEmpty then config.issuer
      else s"${config.baseUrl}/realms/${config.realm}"
    new OidcBearerAuthenticator("keycloak", jwksUrl, issuer, config.clientId, roleClaim)

  def createGoogle(config: GoogleAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    val lookup: Option[String => Set[String]] =
      if config.groupsLookup && config.serviceAccountKeyPath.nonEmpty then
        val client =
          new GoogleGroupsLookup(config.serviceAccountKeyPath, config.groupsCacheTtlSeconds)
        Some(client.getGroupsForUser)
      else None
    new OidcBearerAuthenticator(
      "google",
      "https://www.googleapis.com/oauth2/v3/certs",
      "https://accounts.google.com",
      config.clientId,
      roleClaim,
      lookup
    )

  def createAzure(config: AzureAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    val jwksUrl =
      s"https://login.microsoftonline.com/${config.tenantId}/discovery/v2.0/keys"
    val issuer =
      s"https://login.microsoftonline.com/${config.tenantId}/v2.0"
    new OidcBearerAuthenticator("azure", jwksUrl, issuer, config.clientId, roleClaim)

  def createAws(config: AwsAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    val base    = s"https://cognito-idp.${config.region}.amazonaws.com/${config.userPoolId}"
    val jwksUrl = s"$base/.well-known/jwks.json"
    new OidcBearerAuthenticator("aws-cognito", jwksUrl, base, config.clientId, roleClaim)
