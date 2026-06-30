package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{AuthScope, AuthenticatedProfile, AuthenticationService}
import ai.starlake.quack.edge.config.{
  AuthenticationConfig,
  AwsAuthConfig,
  AzureAuthConfig,
  DatabaseAuthConfig,
  GoogleAuthConfig,
  JwtAuthConfig,
  KeycloakAuthConfig
}
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.{
  GrantsLookup,
  ManagementAuthMode,
  ManagementAuthModeResolver,
  ManagementIdentitySource
}
import ai.starlake.quack.ondemand.state.UserGrant
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** Unit tests for `AuthHandlers.authMode`: the unauthenticated per-tenant login-mode resolver that
  * the SPA calls before login.
  */
class AuthModeHandlerSpec extends AnyFlatSpec with Matchers:

  private val emptyConfig = AuthenticationConfig(
    roleClaim = "role",
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
    )
  )

  // authMode never invokes the auth service; identity is irrelevant to mode resolution.
  private val dummyAuthService = new AuthenticationService(emptyConfig, "x"):
    override val hasProviders: Boolean = false
    override def authenticateBasic(
        scope: AuthScope,
        username: String,
        password: String
    ): Either[String, AuthenticatedProfile] =
      Left("not used")

  private val oidcCfg = Map(
    "issuerUrl"       -> "https://idp.example.com/realms/qod",
    "clientId"        -> "qod-admin",
    "clientSecretRef" -> "env:X"
  )

  private val acmeDb  = Tenant(id = "acme", displayName = "Acme", authProvider = "db")
  private val acmeSso =
    Tenant(id = "acme", displayName = "Acme", authProvider = "keycloak", authConfig = oidcCfg)
  private val badSso =
    Tenant(
      id = "bad",
      displayName = "Bad",
      authProvider = "keycloak",
      authConfig = Map("clientId" -> "x")
    )

  private val noGrants: GrantsLookup = (_, _) => List.empty[UserGrant]

  private def handlers(
      tenants: Map[String, Tenant],
      systemMode: ManagementAuthMode
  ): AuthHandlers =
    new AuthHandlers(
      authService = dummyAuthService,
      tokens = new SessionTokenStore,
      identitySource = ManagementIdentitySource.Db,
      grantsForIdentity = noGrants,
      authModeResolver = new ManagementAuthModeResolver(tenants.get, systemMode)
    )

  private def run(h: AuthHandlers, tenant: Option[String]) =
    h.authMode(tenant).unsafeRunSync()

  it should "resolve the system scope (no tenant) via systemMode = Oidc" in {
    run(handlers(Map.empty, ManagementAuthMode.Oidc), None) shouldBe
      Right(AuthModeResponse("oidc", ""))
  }

  it should "resolve a db tenant to db" in {
    run(handlers(Map("acme" -> acmeDb), ManagementAuthMode.Oidc), Some("acme")) shouldBe
      Right(AuthModeResponse("db", ""))
  }

  it should "resolve a fully-configured oidc tenant to oidc" in {
    run(handlers(Map("acme" -> acmeSso), ManagementAuthMode.Db), Some("acme")) shouldBe
      Right(AuthModeResponse("oidc", ""))
  }

  it should "reject an unknown tenant with 400 tenant_not_found" in {
    run(handlers(Map.empty, ManagementAuthMode.Db), Some("nope")) shouldBe
      Left(
        (StatusCode.BadRequest, ErrorResponse("tenant_not_found", "tenant auth mode unresolved"))
      )
  }

  it should "reject an oidc tenant missing authConfig keys with 400 tenant_oidc_misconfigured" in {
    run(handlers(Map("bad" -> badSso), ManagementAuthMode.Db), Some("bad")) shouldBe
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("tenant_oidc_misconfigured", "tenant auth mode unresolved")
        )
      )
  }
