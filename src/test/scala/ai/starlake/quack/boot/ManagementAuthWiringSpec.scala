package ai.starlake.quack.boot

import ai.starlake.quack.{Main, ManagerConfig}
import ai.starlake.quack.edge.auth.{OidcScope, OidcSsoError}
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
import ai.starlake.quack.ondemand.auth.ManagementAuthMode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins what [[ManagementAuthWiring.build]] wires per `identitySource`. The source governs only the
  * bare `/ui/` login: the SSO service that serves tenant logins (a tenant's own `authProvider`
  * decides its mode) is wired under either source.
  */
class ManagementAuthWiringSpec extends AnyFlatSpec with Matchers:
  import Main.given

  // The overlay pins every field the assertions read, whatever QOD_* the invoking shell exports.
  // `issuerUrl` / `clientId` are the manager-wide client (QOD_MGMT_OIDC_ISSUER_URL / _CLIENT_ID).
  private def load(
      identitySource: String,
      issuerUrl: String = "",
      clientId: String = ""
  ): ManagerConfig =
    ConfigSource
      .string(
        s"""quack-on-demand.auth.management {
           |  identitySource = "$identitySource"
           |  sessionJwtSecret = "wiring-spec-secret-0123456789abcdefghij"
           |  publicBaseUrl = "https://qod.example.com"
           |  oidc { issuerUrl = "$issuerUrl", clientId = "$clientId", clientSecret = "" }
           |}""".stripMargin
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]

  // Every data-plane provider off: no SQL-token service, no I/O.
  private val noProviders = AuthenticationConfig(
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

  private val noTenants: String => Option[Tenant] = _ => None

  "ManagementAuthWiring.build" should "wire the SSO service under identitySource=db, for tenant logins" in {
    ManagementAuthWiring.build(load("db"), noProviders, noTenants).oidcSso shouldBe defined
  }

  it should "leave the bare /ui/ SSO unconfigured under identitySource=db when no manager-wide OIDC client is set" in {
    val sso = ManagementAuthWiring.build(load("db"), noProviders, noTenants).oidcSso
    sso.map(_.startAuth(OidcScope.System, "/ui/", "wiring-spec")) shouldBe
      Some(Left(OidcSsoError.ScopeNotConfigured))
  }

  it should "keep the bare /ui/ SSO off under identitySource=db even when the manager-wide OIDC client is set" in {
    // identitySource governs the bare login: a db-mode manager never uses QOD_MGMT_OIDC_*, so the
    // System scope answers before any discovery (no network).
    val cfg = load("db", issuerUrl = "https://idp.example.com", clientId = "sys-app")
    val sso = ManagementAuthWiring.build(cfg, noProviders, noTenants).oidcSso
    sso.map(_.startAuth(OidcScope.System, "/ui/", "wiring-spec")) shouldBe
      Some(Left(OidcSsoError.ScopeNotConfigured))
  }

  it should "keep wiring the SSO service under identitySource=oidc" in {
    ManagementAuthWiring.build(load("oidc"), noProviders, noTenants).oidcSso shouldBe defined
  }

  it should "resolve the bare /ui/ login mode from identitySource alone" in {
    val db   = ManagementAuthWiring.build(load("db"), noProviders, noTenants)
    val oidc = ManagementAuthWiring.build(load("oidc"), noProviders, noTenants)
    db.authModeResolver.modeFor(None) shouldBe Right(ManagementAuthMode.Db)
    oidc.authModeResolver.modeFor(None) shouldBe Right(ManagementAuthMode.Oidc)
  }
