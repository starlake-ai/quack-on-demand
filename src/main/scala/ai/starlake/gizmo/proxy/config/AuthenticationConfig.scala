package ai.starlake.gizmo.proxy.config

import pureconfig.*

case class DatabaseAuthConfig(
    enabled: Boolean,
    jdbcUrl: String,
    username: String,
    password: String,
    query: String
) derives ConfigReader

case class KeycloakAuthConfig(
    enabled: Boolean,
    baseUrl: String,
    realm: String,
    clientId: String,
    clientSecret: String
) derives ConfigReader

case class GoogleAuthConfig(
    enabled: Boolean,
    clientId: String,
    clientSecret: String,
    groupsLookup: Boolean,
    serviceAccountKeyPath: String,
    groupsCacheTtlSeconds: Long
) derives ConfigReader

case class AzureAuthConfig(
    enabled: Boolean,
    tenantId: String,
    clientId: String,
    clientSecret: String
) derives ConfigReader

case class AwsAuthConfig(
    enabled: Boolean,
    region: String,
    userPoolId: String,
    clientId: String
) derives ConfigReader

case class JwtAuthConfig(
    secretKey: String,
    publicKeyPath: String,
    issuer: String,
    audience: String
) derives ConfigReader

case class OAuthConfig(
    enabled: Boolean,
    port: Int,
    baseUrl: String,
    scopes: String,
    sessionTimeoutSeconds: Int,
    disableTls: Boolean
) derives ConfigReader

case class AuthenticationConfig(
    roleClaim: String,
    database: DatabaseAuthConfig,
    keycloak: KeycloakAuthConfig,
    google: GoogleAuthConfig,
    azure: AzureAuthConfig,
    aws: AwsAuthConfig,
    jwt: JwtAuthConfig,
    oauth: OAuthConfig
) derives ConfigReader