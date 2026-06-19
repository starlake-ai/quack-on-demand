package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.KeycloakAuthConfig
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.secrets.SecretRefResolver
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OidcSsoServiceSpec extends AnyFlatSpec with Matchers:

  private val globalKc = KeycloakAuthConfig(
    enabled = true,
    baseUrl = "http://kc:8080/auth",
    realm = "qod",
    clientId = "qod-admin",
    clientSecret = "s3cret"
  )

  // SecretRefResolver that echoes "env:X" -> "resolved-X" for tenant secrets.
  private val secrets = new SecretRefResolver:
    def resolve(ref: String) = Right(s"resolved-${ref.stripPrefix("env:")}")

  private def tenantWith(authProvider: String, cfg: Map[String, String]): Tenant =
    Tenant(
      name = authProvider,
      authProvider = authProvider,
      authConfig = cfg
    )

  private val registry = new TenantOidcRegistry(
    loadTenant = {
      case "acme" =>
        Some(
          tenantWith(
            "keycloak",
            Map(
              "baseUrl"         -> "http://kc:8080/auth",
              "realm"           -> "acme",
              "clientId"        -> "acme-admin",
              "clientSecretRef" -> "env:ACME"
            )
          )
        )
      case "globex" =>
        Some(tenantWith("google", Map("clientId" -> "g", "clientSecretRef" -> "env:G")))
      case _ => None
    },
    secrets = secrets,
    roleClaim = "role"
  )

  it should "resolve manager-wide Keycloak endpoints for System scope" in {
    val ep = registry.endpointsFor(OidcScope.System, globalKc).toOption.get
    ep.authorizeUrl shouldBe "http://kc:8080/auth/realms/qod/protocol/openid-connect/auth"
    ep.tokenUrl shouldBe "http://kc:8080/auth/realms/qod/protocol/openid-connect/token"
    ep.endSessionUrl shouldBe "http://kc:8080/auth/realms/qod/protocol/openid-connect/logout"
    ep.issuer shouldBe "http://kc:8080/auth/realms/qod"
    ep.clientId shouldBe "qod-admin"
  }

  it should "resolve per-tenant Keycloak endpoints for Tenant scope" in {
    val ep = registry.endpointsFor(OidcScope.Tenant("acme"), globalKc).toOption.get
    ep.issuer shouldBe "http://kc:8080/auth/realms/acme"
    ep.clientId shouldBe "acme-admin"
    ep.clientSecret shouldBe "resolved-ACME"
  }

  it should "reject an unknown tenant" in {
    registry.endpointsFor(OidcScope.Tenant("nope"), globalKc) shouldBe
      Left(OidcSsoError.TenantNotConfigured)
  }

  it should "reject a non-Keycloak provider as unsupported for UI SSO (MVP)" in {
    registry.endpointsFor(OidcScope.Tenant("globex"), globalKc) shouldBe
      Left(OidcSsoError.ProviderUnsupported)
  }
