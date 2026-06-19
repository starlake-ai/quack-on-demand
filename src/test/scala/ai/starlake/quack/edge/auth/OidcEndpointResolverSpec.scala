package ai.starlake.quack.edge.auth

import ai.starlake.quack.ManagementOidcConfig
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.secrets.SecretRefResolver
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OidcEndpointResolverSpec extends AnyFlatSpec with Matchers:

  private val discoveryJson =
    """{"issuer":"https://idp.example.com",
       |"authorization_endpoint":"https://idp.example.com/authorize",
       |"token_endpoint":"https://idp.example.com/token",
       |"jwks_uri":"https://idp.example.com/jwks",
       |"end_session_endpoint":"https://idp.example.com/logout"}""".stripMargin

  private val discovery = new OidcDiscovery(httpGet = {
    case "https://idp.example.com/.well-known/openid-configuration" => Right(discoveryJson)
    case other => Left(s"unexpected url $other")
  })

  private val secrets: SecretRefResolver = new SecretRefResolver:
    def resolve(ref: String) = Right(s"resolved-${ref.stripPrefix("env:")}")

  // Tenant constructor: name, metastore, id, displayName, disabled, authProvider, authConfig
  private def tenant(authConfig: Map[String, String]): Tenant =
    Tenant(
      name = "test-tenant",
      metastore = Map.empty,
      id = "test-id",
      displayName = "Test Tenant",
      disabled = false,
      authProvider = "oidc",
      authConfig = authConfig
    )

  private val resolver = new OidcEndpointResolver(
    loadTenant = {
      case "acme" =>
        Some(
          tenant(
            Map(
              "issuerUrl"       -> "https://idp.example.com",
              "clientId"        -> "acme-app",
              "clientSecretRef" -> "env:ACME"
            )
          )
        )
      case _ => None
    },
    secrets = secrets,
    discovery = discovery
  )

  private val mgmt = ManagementOidcConfig(
    issuerUrl = "https://idp.example.com",
    clientId = "sys-app",
    clientSecret = "sys-secret",
    scopes = "openid email profile"
  )

  it should "resolve System endpoints via discovery" in {
    val ep = resolver.endpointsFor(OidcScope.System, mgmt).toOption.get
    ep.authorizeUrl shouldBe "https://idp.example.com/authorize"
    ep.tokenUrl shouldBe "https://idp.example.com/token"
    ep.jwksUrl shouldBe "https://idp.example.com/jwks"
    ep.endSessionUrl shouldBe "https://idp.example.com/logout"
    ep.issuer shouldBe "https://idp.example.com"
    ep.clientId shouldBe "sys-app"
    ep.clientSecret shouldBe "sys-secret"
  }

  it should "resolve Tenant endpoints via discovery + resolved secret" in {
    val ep = resolver.endpointsFor(OidcScope.Tenant("acme"), mgmt).toOption.get
    ep.clientId shouldBe "acme-app"
    ep.clientSecret shouldBe "resolved-ACME"
    ep.jwksUrl shouldBe "https://idp.example.com/jwks"
  }

  it should "return ScopeNotConfigured when the system issuer is unset" in {
    resolver.endpointsFor(OidcScope.System, ManagementOidcConfig()) shouldBe
      Left(OidcSsoError.ScopeNotConfigured)
  }

  it should "return ScopeNotConfigured for an unknown tenant" in {
    resolver.endpointsFor(OidcScope.Tenant("nope"), mgmt) shouldBe
      Left(OidcSsoError.ScopeNotConfigured)
  }

  it should "return DiscoveryFailed when the well-known fetch fails" in {
    val badResolver = new OidcEndpointResolver(
      loadTenant = _ => None,
      secrets = secrets,
      discovery = new OidcDiscovery(httpGet = _ => Left("boom"))
    )
    badResolver.endpointsFor(OidcScope.System, mgmt) shouldBe Left(OidcSsoError.DiscoveryFailed)
  }

  it should "parse a discovery document with no end_session_endpoint as empty" in {
    val noLogout = new OidcDiscovery(httpGet =
      _ => Right("""{"issuer":"https://i","authorization_endpoint":"https://i/a",
              |"token_endpoint":"https://i/t","jwks_uri":"https://i/j"}""".stripMargin)
    )
    val r = new OidcEndpointResolver(_ => None, secrets, noLogout)
    r.endpointsFor(OidcScope.System, ManagementOidcConfig(issuerUrl = "https://i", clientId = "c"))
      .toOption
      .get
      .endSessionUrl shouldBe ""
  }
