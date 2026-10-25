package ai.starlake.quack.edge.auth

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.ParkedSecretRefs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit coverage for [[TenantOidcRegistry]]: dispatch by provider, `None` when a provider's public
  * fields are incomplete, no client secret involved, cache hits and invalidation. Building an
  * [[OidcBearerAuthenticator]] opens no connection (its JWKS source fetches lazily), so these tests
  * build real ones and read back the issuer and audience they validate tokens against.
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

  private def registryOf(t: Option[Tenant]): TenantOidcRegistry =
    new TenantOidcRegistry(loadTenant = _ => t, roleClaim = "role")

  private val GoogleClientId = "abc.apps.googleusercontent.com"

  // -- provider dispatch -----------------------------------------------------

  "TenantOidcRegistry" should "return None for a tenant on an unsupported provider" in:
    registryOf(Some(tenant("t-1", provider = "okta", cfg = Map("realm" -> "x"))))
      .forTenant("t-1") shouldBe None

  it should "return None when the tenant doesn't exist" in:
    registryOf(None).forTenant("t-ghost") shouldBe None

  // -- google ----------------------------------------------------------------

  it should "return None when google clientId is unset (tenant on global Google client)" in:
    registryOf(Some(tenant("t-2", cfg = Map("hd" -> "example.com")))).forTenant("t-2") shouldBe None

  it should "build a google authenticator from clientId alone, with no client secret" in:
    val built = registryOf(Some(tenant("t-g", cfg = Map("clientId" -> GoogleClientId))))
      .forTenant("t-g")
    built.map(_.providerName) shouldBe Some("google")
    built.map(_.expectedAudience) shouldBe Some(GoogleClientId)

  it should "build a google authenticator whatever clientSecretRef the tenant carries" in:
    // The registry resolves no reference, so one that names no environment variable gates nothing.
    val cfg = Map(
      "clientId"        -> GoogleClientId,
      "clientSecretRef" -> "env:QOD_TENANT_OIDC_REGISTRY_SPEC_UNSET"
    )
    val built = registryOf(Some(tenant("t-g2", cfg = cfg))).forTenant("t-g2")
    built.map(_.providerName) shouldBe Some("google")

  it should "build a google authenticator for a tenant whose reference changeset 0048 parked" in:
    val cfg   = Map("clientId" -> GoogleClientId, ParkedSecretRefs.ParkedKey -> "env:GOOGLE_CS")
    val built = registryOf(Some(tenant("t-g3", cfg = cfg))).forTenant("t-g3")
    built.map(_.providerName) shouldBe Some("google")

  // -- keycloak --------------------------------------------------------------

  it should "return None when keycloak is missing clientId" in:
    val cfg = Map("baseUrl" -> "https://keycloak.example.com", "realm" -> "tpch")
    registryOf(Some(tenant("t-kc-1", provider = "keycloak", cfg = cfg)))
      .forTenant("t-kc-1") shouldBe None

  it should "build a keycloak authenticator from baseUrl, realm and clientId" in:
    val cfg = Map(
      "baseUrl"  -> "https://keycloak.example.com",
      "realm"    -> "tpch",
      "clientId" -> "quack-flightsql"
    )
    val built = registryOf(Some(tenant("t-kc-2", provider = "keycloak", cfg = cfg)))
      .forTenant("t-kc-2")
    built.map(_.providerName) shouldBe Some("keycloak")
    built.map(_.expectedIssuer) shouldBe Some("https://keycloak.example.com/realms/tpch")
    built.map(_.expectedAudience) shouldBe Some("quack-flightsql")

  // -- azure -----------------------------------------------------------------

  it should "return None when azure is missing tenantId" in:
    registryOf(Some(tenant("t-az-1", provider = "azure", cfg = Map("clientId" -> "azure-app"))))
      .forTenant("t-az-1") shouldBe None

  it should "build an azure authenticator from tenantId and clientId" in:
    val aad   = "11111111-2222-3333-4444-555555555555"
    val cfg   = Map("tenantId" -> aad, "clientId" -> "azure-app")
    val built = registryOf(Some(tenant("t-az-2", provider = "azure", cfg = cfg)))
      .forTenant("t-az-2")
    built.map(_.providerName) shouldBe Some("azure")
    built.map(_.expectedIssuer) shouldBe Some(s"https://login.microsoftonline.com/$aad/v2.0")
    built.map(_.expectedAudience) shouldBe Some("azure-app")

  // -- aws -------------------------------------------------------------------

  it should "return None when aws is missing userPoolId/clientId" in:
    registryOf(Some(tenant("t-aws-1", provider = "aws", cfg = Map("region" -> "us-east-1"))))
      .forTenant("t-aws-1") shouldBe None

  it should "build an aws cognito authenticator from region, userPoolId and clientId" in:
    val cfg = Map(
      "region"     -> "us-east-1",
      "userPoolId" -> "us-east-1_ABCDE",
      "clientId"   -> "cognito-app-client-id"
    )
    val built = registryOf(Some(tenant("t-aws-2", provider = "aws", cfg = cfg)))
      .forTenant("t-aws-2")
    built.map(_.providerName) shouldBe Some("aws-cognito")
    built.map(_.expectedAudience) shouldBe Some("cognito-app-client-id")

  // -- cache + invalidate ----------------------------------------------------

  it should "cache the resolved authenticator across repeated lookups" in:
    var loads = 0
    val r     = new TenantOidcRegistry(
      loadTenant = _ => {
        loads += 1
        Some(tenant("t-5", cfg = Map("clientId" -> GoogleClientId)))
      },
      roleClaim = "role"
    )
    val first  = r.forTenant("t-5")
    val second = r.forTenant("t-5")
    first shouldBe defined
    second shouldBe first
    loads shouldBe 1

  it should "drop the cached entry on invalidate, forcing a reload" in:
    var loads = 0
    val r     = new TenantOidcRegistry(
      loadTenant = _ => {
        loads += 1
        Some(tenant("t-6", cfg = Map("clientId" -> GoogleClientId)))
      },
      roleClaim = "role"
    )
    r.forTenant("t-6") shouldBe defined
    r.invalidate("t-6")
    r.forTenant("t-6") shouldBe defined
    loads shouldBe 2

  it should "also cache the negative result so subsequent lookups don't re-read" in:
    var loads = 0
    val r     = new TenantOidcRegistry(
      loadTenant = _ => { loads += 1; None },
      roleClaim = "role"
    )
    r.forTenant("t-7") shouldBe None
    r.forTenant("t-7") shouldBe None
    loads shouldBe 1
