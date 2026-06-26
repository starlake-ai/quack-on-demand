package ai.starlake.quack.edge.auth

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.secrets.SecretRefResolver
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit coverage for [[TenantOidcRegistry]]. Avoids constructing the OidcBearerAuthenticator
  * itself (it would open a JWKS connection to the IdP at construction); instead we assert on
  * registry shape: dispatch by provider, fallback to None when config is incomplete, cache hits
  * and invalidation.
  */
class TenantOidcRegistrySpec extends AnyFlatSpec with Matchers:

  private def tenant(
      id: String,
      provider: String = "google",
      cfg: Map[String, String] = Map.empty
  ): Tenant =
    Tenant(
      id = id,
      displayName = id,
      authProvider = provider,
      authConfig = cfg
    )

  private val staticEnv: SecretRefResolver =
    SecretRefResolver.fromEnv {
      case "GOOGLE_CS_TPCH"   => Some("tpch-secret-plaintext")
      case "KEYCLOAK_CS_TPCH" => Some("kc-secret-plaintext")
      case "AZURE_CS_TPCH"    => Some("az-secret-plaintext")
      case "GOOGLE_CS_BROKEN" => None
      case _                  => None
    }

  // -- provider dispatch -----------------------------------------------------

  "TenantOidcRegistry" should "return None for a tenant on an unsupported provider" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-1", provider = "okta", cfg = Map("realm" -> "x"))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-1") shouldBe None

  it should "return None when the tenant doesn't exist" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => None,
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-ghost") shouldBe None

  // -- google ----------------------------------------------------------------

  it should "return None when google clientId is unset (tenant on global Google client)" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-2", cfg = Map("hd" -> "example.com"))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-2") shouldBe None

  it should "return None when google clientId is set but clientSecretRef is absent" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-3", cfg = Map("clientId" -> "abc.apps.googleusercontent.com"))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-3") shouldBe None

  it should "return None when google clientSecretRef fails to resolve" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(
        tenant("t-4", cfg = Map(
          "clientId"        -> "abc.apps.googleusercontent.com",
          "clientSecretRef" -> "env:GOOGLE_CS_BROKEN"
        ))
      ),
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-4") shouldBe None

  it should "build a google authenticator when clientId + clientSecretRef resolve" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-g", cfg = Map(
        "clientId"        -> "abc.apps.googleusercontent.com",
        "clientSecretRef" -> "env:GOOGLE_CS_TPCH"
      ))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    val built = r.forTenant("t-g")
    built shouldBe defined
    built.map(_.providerName) shouldBe Some("google")

  // -- keycloak --------------------------------------------------------------

  it should "return None when keycloak is missing baseUrl/realm/clientId/clientSecretRef" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-kc-1", provider = "keycloak", cfg = Map(
        "baseUrl" -> "https://keycloak.example.com",
        "realm"   -> "tpch"
        // missing clientId + clientSecretRef
      ))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-kc-1") shouldBe None

  it should "build a keycloak authenticator when all fields are set" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-kc-2", provider = "keycloak", cfg = Map(
        "baseUrl"         -> "https://keycloak.example.com",
        "realm"           -> "tpch",
        "clientId"        -> "quack-flightsql",
        "clientSecretRef" -> "env:KEYCLOAK_CS_TPCH"
      ))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    val built = r.forTenant("t-kc-2")
    built shouldBe defined
    built.map(_.providerName) shouldBe Some("keycloak")

  // -- azure -----------------------------------------------------------------

  it should "return None when azure is missing tenantId/clientId/clientSecretRef" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-az-1", provider = "azure", cfg = Map(
        "clientId" -> "azure-app-client-id"
        // missing tenantId + clientSecretRef
      ))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-az-1") shouldBe None

  it should "build an azure authenticator when all fields are set" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-az-2", provider = "azure", cfg = Map(
        "tenantId"        -> "11111111-2222-3333-4444-555555555555",
        "clientId"        -> "azure-app-client-id",
        "clientSecretRef" -> "env:AZURE_CS_TPCH"
      ))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    val built = r.forTenant("t-az-2")
    built shouldBe defined
    built.map(_.providerName) shouldBe Some("azure")

  // -- aws (no secret) -------------------------------------------------------

  it should "return None when aws is missing region/userPoolId/clientId" in:
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-aws-1", provider = "aws", cfg = Map(
        "region" -> "us-east-1"
        // missing userPoolId + clientId
      ))),
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-aws-1") shouldBe None

  it should "build an aws cognito authenticator without consulting the secret resolver" in:
    // Use a resolver that throws on any call -- proves we don't touch it for AWS.
    val explodingSecrets: SecretRefResolver =
      (_: String) => sys.error("should not be called for AWS Cognito")
    val r = new TenantOidcRegistry(
      loadTenant = _ => Some(tenant("t-aws-2", provider = "aws", cfg = Map(
        "region"     -> "us-east-1",
        "userPoolId" -> "us-east-1_ABCDE",
        "clientId"   -> "cognito-app-client-id"
      ))),
      secrets = explodingSecrets,
      roleClaim = "role"
    )
    val built = r.forTenant("t-aws-2")
    built shouldBe defined
    built.map(_.providerName) shouldBe Some("aws-cognito")

  // -- cache + invalidate ----------------------------------------------------

  it should "cache the resolved authenticator across repeated lookups" in:
    var loads = 0
    val r = new TenantOidcRegistry(
      loadTenant = _ => {
        loads += 1
        Some(tenant("t-5", cfg = Map(
          "clientId"        -> "abc.apps.googleusercontent.com",
          "clientSecretRef" -> "env:GOOGLE_CS_TPCH"
        )))
      },
      secrets = staticEnv,
      roleClaim = "role"
    )
    val first  = r.forTenant("t-5")
    val second = r.forTenant("t-5")
    first         shouldBe defined
    second        shouldBe defined
    second        shouldBe first
    loads         shouldBe 1

  it should "drop the cached entry on invalidate, forcing a reload" in:
    var loads = 0
    val r = new TenantOidcRegistry(
      loadTenant = _ => {
        loads += 1
        Some(tenant("t-6", cfg = Map(
          "clientId"        -> "abc.apps.googleusercontent.com",
          "clientSecretRef" -> "env:GOOGLE_CS_TPCH"
        )))
      },
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-6") shouldBe defined
    r.invalidate("t-6")
    r.forTenant("t-6") shouldBe defined
    loads shouldBe 2

  it should "also cache the negative result so subsequent lookups don't re-read" in:
    var loads = 0
    val r = new TenantOidcRegistry(
      loadTenant = _ => { loads += 1; None },
      secrets = staticEnv,
      roleClaim = "role"
    )
    r.forTenant("t-7") shouldBe None
    r.forTenant("t-7") shouldBe None
    loads shouldBe 1