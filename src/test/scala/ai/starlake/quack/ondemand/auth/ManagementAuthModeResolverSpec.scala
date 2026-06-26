package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.model.Tenant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ManagementAuthModeResolverSpec extends AnyFlatSpec with Matchers:

  private val oidcCfg = Map(
    "issuerUrl"       -> "https://idp/realms/qod",
    "clientId"        -> "qod-admin",
    "clientSecretRef" -> "env:X"
  )

  private def resolver(tenants: Map[String, Tenant], system: ManagementAuthMode) =
    new ManagementAuthModeResolver(tenants.get, system)

  private val acmeDb   = Tenant(id = "acme", displayName = "Acme", authProvider = "db")
  private val acmeOidc =
    Tenant(id = "acme", displayName = "Acme", authProvider = "keycloak", authConfig = oidcCfg)
  private val acmeBad =
    Tenant(
      id = "acme",
      displayName = "Acme",
      authProvider = "keycloak",
      authConfig = Map("clientId" -> "x")
    )

  it should "resolve a db tenant to Db" in {
    resolver(Map("acme" -> acmeDb), ManagementAuthMode.Oidc)
      .modeFor(Some("acme")) shouldBe Right(ManagementAuthMode.Db)
  }
  it should "resolve an oidc tenant with full authConfig to Oidc" in {
    resolver(Map("acme" -> acmeOidc), ManagementAuthMode.Db)
      .modeFor(Some("acme")) shouldBe Right(ManagementAuthMode.Oidc)
  }
  it should "reject an oidc tenant missing authConfig keys as MisconfiguredOidc" in {
    resolver(Map("acme" -> acmeBad), ManagementAuthMode.Db)
      .modeFor(Some("acme")) shouldBe Left(ModeError.MisconfiguredOidc)
  }
  it should "reject an unknown tenant as TenantNotFound" in {
    resolver(Map.empty, ManagementAuthMode.Db).modeFor(Some("nope")) shouldBe Left(
      ModeError.TenantNotFound
    )
  }
  it should "use systemMode for the system scope (no tenant)" in {
    resolver(Map.empty, ManagementAuthMode.Oidc).modeFor(None) shouldBe Right(
      ManagementAuthMode.Oidc
    )
    resolver(Map.empty, ManagementAuthMode.Db).modeFor(None) shouldBe Right(ManagementAuthMode.Db)
  }
