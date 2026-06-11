// src/test/scala/ai/starlake/quack/security/InMemoryAuthService.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.auth.{AuthenticatedProfile, AuthenticationService, AuthScope, BasicAuthProvider}
import ai.starlake.quack.edge.config.{
  AuthenticationConfig,
  AwsAuthConfig,
  AzureAuthConfig,
  DatabaseAuthConfig,
  GoogleAuthConfig,
  JwtAuthConfig,
  KeycloakAuthConfig,
  OAuthConfig
}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import at.favre.lib.crypto.bcrypt.BCrypt

/** Shared test helpers for an in-memory auth chain backed by
  * [[InMemoryControlPlaneStore]]. Extracted from [[ManagerServerHarness]] so
  * [[FlightEdgeHarness]] can reuse them without duplicating the implementation.
  */
object InMemoryAuthService:

  /** An all-disabled [[AuthenticationConfig]] that prevents any external
    * connection from being opened by the [[AuthenticationService]] base
    * constructor. Mirrors the pattern used in AuthHandlersSpec.
    */
  val emptyAuthConfig: AuthenticationConfig = AuthenticationConfig(
    roleClaim = "role",
    database = DatabaseAuthConfig(
      enabled     = false,
      jdbcUrl     = "",
      username    = "",
      password    = "",
      systemQuery = "",
      tenantQuery = ""
    ),
    keycloak = KeycloakAuthConfig(
      enabled      = false,
      baseUrl      = "",
      realm        = "",
      clientId     = "",
      clientSecret = ""
    ),
    google = GoogleAuthConfig(
      enabled               = false,
      clientId              = "",
      clientSecret          = "",
      groupsLookup          = false,
      serviceAccountKeyPath = "",
      groupsCacheTtlSeconds = 0L
    ),
    azure = AzureAuthConfig(
      enabled      = false,
      tenantId     = "",
      clientId     = "",
      clientSecret = ""
    ),
    aws = AwsAuthConfig(
      enabled    = false,
      region     = "",
      userPoolId = "",
      clientId   = ""
    ),
    jwt = JwtAuthConfig(
      secretKey     = "",
      publicKeyPath = "",
      issuer        = "",
      audience      = ""
    ),
    oauth = OAuthConfig(
      enabled               = false,
      port                  = 0,
      baseUrl               = "",
      scopes                = "",
      sessionTimeoutSeconds = 0,
      disableTls            = true
    )
  )

  /** [[BasicAuthProvider]] that resolves credentials directly from the
    * [[InMemoryControlPlaneStore]] -- no Postgres or network round-trip.
    */
  final class InMemoryBasicAuthProvider(store: InMemoryControlPlaneStore)
      extends BasicAuthProvider:

    val name = "in-memory"

    def authenticate(
        scope:    AuthScope,
        username: String,
        password: String
    ): Either[String, AuthenticatedProfile] =
      val tenant = scope.tenantId
      store.getPasswordHash(tenant, username) match
        case None =>
          Left(s"user '$username' not found")
        case Some(hash) =>
          val result = BCrypt.verifyer().verify(password.toCharArray, hash)
          if result.verified then
            store.findUser(tenant, username) match
              case None    => Left(s"user '$username' vanished after hash lookup")
              case Some(u) =>
                Right(
                  AuthenticatedProfile(
                    username   = u.username,
                    role       = u.role,
                    groups     = Set.empty,
                    claims     = Map.empty,
                    authMethod = "in-memory",
                    tenant     = u.tenant
                  )
                )
          else Left("invalid credentials")

  /** [[AuthenticationService]] subclass whose parent constructor opens no
    * external connections (all providers disabled via [[emptyAuthConfig]]) and
    * whose `authenticateBasic` is wired to the in-memory provider.
    *
    * Pass `providersEnabled = false` to flip `hasProviders` back to `false`,
    * driving the `auth_disabled` 503 branch in auth handlers end-to-end.
    */
  final class Service(
      store:            InMemoryControlPlaneStore,
      providersEnabled: Boolean
  ) extends AuthenticationService(emptyAuthConfig, "test-jwt-secret"):

    private val provider = new InMemoryBasicAuthProvider(store)

    override val hasProviders: Boolean = providersEnabled

    override def authenticateBasic(
        scope:    AuthScope,
        username: String,
        password: String
    ): Either[String, AuthenticatedProfile] =
      if !providersEnabled then Left("No basic auth providers configured")
      else provider.authenticate(scope, username, password)
