package ai.starlake.quack.edge.config

import ai.starlake.quack.config.ConfigField
import pureconfig.*

import scala.annotation.meta.field

case class DatabaseAuthConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_DB_ENABLED",
      description = "Enable database (bcrypt) authentication for the FlightSQL edge."
    )
    enabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_AUTH_DB_JDBC_URL",
      description = "JDBC URL for the auth-lookup database."
    )
    jdbcUrl: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_DB_USER",
      description = "Username for the auth-lookup JDBC connection."
    )
    username: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_DB_PASSWORD",
      description = "Password for the auth-lookup JDBC connection.",
      sensitive = true
    )
    password: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_DB_SYSTEM_QUERY",
      description =
        "SQL template for AuthScope.System (empty tenant / superuser=true). Returns (password_hash, role) and accepts one ? placeholder for username; matches WHERE tenant IS NULL."
    )
    systemQuery: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_DB_TENANT_QUERY",
      description =
        "SQL template for AuthScope.Tenant. Returns (password_hash, role) and accepts two ? placeholders in order: tenant, username."
    )
    tenantQuery: String
) derives ConfigReader

case class KeycloakAuthConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_KEYCLOAK_ENABLED",
      description = "Enable the Keycloak OIDC bearer provider."
    )
    enabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_AUTH_KEYCLOAK_BASE_URL",
      description = "Keycloak base URL (e.g. https://keycloak.example.com)."
    )
    baseUrl: String,
    @field @ConfigField(envVar = "QOD_AUTH_KEYCLOAK_REALM", description = "Keycloak realm name.")
    realm: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_KEYCLOAK_CLIENT_ID",
      description = "Keycloak client ID for ROPC."
    )
    clientId: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_KEYCLOAK_CLIENT_SECRET",
      description = "Keycloak client secret.",
      sensitive = true
    )
    clientSecret: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_KEYCLOAK_ISSUER",
      description =
        "Override for the expected token issuer (the `iss` claim the bearer validator requires). " +
          "Leave empty to derive it from baseUrl + realm. Set it when Keycloak's browser-facing " +
          "issuer (KC_HOSTNAME_URL) differs from the in-cluster baseUrl used to fetch JWKS: tokens " +
          "minted via an ingress carry iss=https://host/auth/realms/<realm> while the manager " +
          "fetches JWKS in-cluster. JWKS is always derived from baseUrl, not this value."
    )
    issuer: String = ""
) derives ConfigReader

case class GoogleAuthConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_GOOGLE_ENABLED",
      description = "Enable the Google OIDC bearer provider."
    )
    enabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_AUTH_GOOGLE_CLIENT_ID",
      description = "Google OAuth client ID."
    )
    clientId: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_GOOGLE_CLIENT_SECRET",
      description = "Google OAuth client secret.",
      sensitive = true
    )
    clientSecret: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_GOOGLE_GROUPS_LOOKUP",
      description = "Resolve Google Workspace groups membership server-side."
    )
    groupsLookup: Boolean,
    @field @ConfigField(
      envVar = "QOD_AUTH_GOOGLE_SVC_ACCT_KEY_PATH",
      description = "Path to a Google service-account JSON used for groups lookup."
    )
    serviceAccountKeyPath: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_GOOGLE_GROUPS_CACHE_TTL_SEC",
      description = "Cache TTL for Google groups lookups in seconds."
    )
    groupsCacheTtlSeconds: Long
) derives ConfigReader

case class AzureAuthConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_AZURE_ENABLED",
      description = "Enable the Azure AD bearer provider."
    )
    enabled: Boolean,
    @field @ConfigField(envVar = "QOD_AUTH_AZURE_TENANT_ID", description = "Azure AD tenant ID.")
    tenantId: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_AZURE_CLIENT_ID",
      description = "Azure AD application (client) ID."
    )
    clientId: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_AZURE_CLIENT_SECRET",
      description = "Azure AD application client secret.",
      sensitive = true
    )
    clientSecret: String
) derives ConfigReader

case class AwsAuthConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_AWS_ENABLED",
      description = "Enable the AWS Cognito bearer provider."
    )
    enabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_AUTH_AWS_REGION",
      description = "AWS region hosting the Cognito user pool."
    )
    region: String,
    @field @ConfigField(envVar = "QOD_AUTH_AWS_USER_POOL_ID", description = "Cognito user pool ID.")
    userPoolId: String,
    @field @ConfigField(envVar = "QOD_AUTH_AWS_CLIENT_ID", description = "Cognito app client ID.")
    clientId: String
) derives ConfigReader

case class JwtAuthConfig(
    @field @ConfigField(
      envVar = "JWT_SECRET_KEY",
      description = "HMAC secret for HS256/HS512 external JWT verification.",
      sensitive = true
    )
    secretKey: String,
    @field @ConfigField(
      envVar = "JWT_PUBLIC_KEY_PATH",
      description = "Path to the RSA/ECDSA PEM public key for external JWT verification."
    )
    publicKeyPath: String,
    @field @ConfigField(
      envVar = "JWT_ISSUER",
      description = "Expected 'iss' claim value (empty = not checked)."
    )
    issuer: String,
    @field @ConfigField(
      envVar = "JWT_AUDIENCE",
      description = "Expected 'aud' claim value (empty = not checked)."
    )
    audience: String
) derives ConfigReader

case class OAuthConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_OAUTH_ENABLED",
      description = "Enable the browser-based OAuth (auth-code grant) flow."
    )
    enabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_AUTH_OAUTH_PORT",
      description = "Local port the OAuth callback server listens on."
    )
    port: Int,
    @field @ConfigField(
      envVar = "QOD_AUTH_OAUTH_BASE_URL",
      description = "Externally-reachable base URL for OAuth callbacks."
    )
    baseUrl: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_OAUTH_SCOPES",
      description = "OAuth scopes requested at authorization time."
    )
    scopes: String,
    @field @ConfigField(
      envVar = "QOD_AUTH_OAUTH_SESSION_TIMEOUT_SEC",
      description = "OAuth-session timeout in seconds."
    )
    sessionTimeoutSeconds: Int,
    @field @ConfigField(
      envVar = "QOD_AUTH_OAUTH_DISABLE_TLS",
      description = "Disable TLS on the OAuth callback server (dev only)."
    )
    disableTls: Boolean
) derives ConfigReader

case class AuthenticationConfig(
    @field @ConfigField(
      envVar = "QOD_AUTH_ROLE_CLAIM",
      description = "JWT claim that carries the user's role."
    )
    roleClaim: String,
    database: DatabaseAuthConfig,
    keycloak: KeycloakAuthConfig,
    google: GoogleAuthConfig,
    azure: AzureAuthConfig,
    aws: AwsAuthConfig,
    jwt: JwtAuthConfig,
    oauth: OAuthConfig,
    @field @ConfigField(
      envVar = "QOD_AUTH_OAUTH_SCOPES",
      description = "OAuth scopes requested at authorization time for the browser SQL-token flow."
    )
    oauthScopes: String = "openid profile email"
) derives ConfigReader

object AuthenticationConfig:
  val disabled: AuthenticationConfig = AuthenticationConfig(
    roleClaim = "",
    database = DatabaseAuthConfig(
      enabled = false,
      jdbcUrl = "",
      username = "",
      password = "",
      systemQuery = "",
      tenantQuery = ""
    ),
    keycloak = KeycloakAuthConfig(
      enabled = false,
      baseUrl = "",
      realm = "",
      clientId = "",
      clientSecret = ""
    ),
    google = GoogleAuthConfig(
      enabled = false,
      clientId = "",
      clientSecret = "",
      groupsLookup = false,
      serviceAccountKeyPath = "",
      groupsCacheTtlSeconds = 0L
    ),
    azure = AzureAuthConfig(
      enabled = false,
      tenantId = "",
      clientId = "",
      clientSecret = ""
    ),
    aws = AwsAuthConfig(
      enabled = false,
      region = "",
      userPoolId = "",
      clientId = ""
    ),
    jwt = JwtAuthConfig(
      secretKey = "",
      publicKeyPath = "",
      issuer = "",
      audience = ""
    ),
    oauth = OAuthConfig(
      enabled = false,
      port = 0,
      baseUrl = "",
      scopes = "",
      sessionTimeoutSeconds = 0,
      disableTls = true
    )
  )
