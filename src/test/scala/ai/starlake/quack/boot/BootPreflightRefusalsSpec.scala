package ai.starlake.quack.boot

import ai.starlake.quack.{
  FlightConfig,
  Main,
  ManagementAuthConfig,
  ManagementOidcConfig,
  ManagerConfig
}
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.{AclConfig, AuthenticationConfig, AzureAuthConfig, OpaConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import ai.starlake.quack.observability.metrics.MetricsConfigCodec.given
import ai.starlake.quack.security.InMemoryAuthService.emptyAuthConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** The two boot refusals of the static API key removal:
  *   - a non-empty `QOD_API_KEY` (environment) or `quack-on-demand.apiKey` (loaded configuration)
  *     stops the boot with the removal message, before HA validation; an empty value passes;
  *   - a manager nobody can sign in to (no password provider and no system-scope OIDC sign-in, as
  *     `ManagementAuthWiring` serves it) stops too.
  */
class BootPreflightRefusalsSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private val noEnv: String => Option[String]             = _ => None
  private def keyEnv(v: String): String => Option[String] = Map("QOD_API_KEY" -> v).get

  "checkRemovedApiKey" should "refuse a non-empty QOD_API_KEY in the environment" in {
    BootPreflight.checkRemovedApiKey(keyEnv("k1"), ConfigFactory.empty()) shouldBe
      Left(BootPreflight.RemovedApiKeyMessage)
  }

  it should "refuse a non-empty quack-on-demand.apiKey in the loaded configuration" in {
    val raw = ConfigFactory.parseString("quack-on-demand.apiKey = \"k1\"")
    BootPreflight.checkRemovedApiKey(noEnv, raw) shouldBe Left(BootPreflight.RemovedApiKeyMessage)
  }

  it should "accept an empty or blank value, and an absent one" in {
    BootPreflight.checkRemovedApiKey(keyEnv(""), ConfigFactory.empty()) shouldBe Right(())
    BootPreflight.checkRemovedApiKey(keyEnv("   "), ConfigFactory.empty()) shouldBe Right(())
    val blank = ConfigFactory.parseString("quack-on-demand.apiKey = \" \"")
    BootPreflight.checkRemovedApiKey(noEnv, blank) shouldBe Right(())
    BootPreflight.checkRemovedApiKey(noEnv, ConfigFactory.empty()) shouldBe Right(())
  }

  it should "carry the removal message verbatim" in {
    BootPreflight.RemovedApiKeyMessage shouldBe
      "QOD_API_KEY (quack-on-demand.apiKey) was removed: the manager no longer accepts a static " +
      "API key. Use a personal access token (qod auth pat create) or a qod login session, and " +
      "unset QOD_API_KEY."
  }

  /** Boots with HA on over the local runtime, which HA validation always refuses: a boot that gets
    * past the removed-key refusal stops there, before any I/O (no Postgres probe, no Liquibase).
    */
  private def bootUntilHaRefusal(env: String => Option[String]): Unit =
    val source = ConfigSource
      .string(
        "quack-on-demand { ha.enabled = true, runtimeType = local, embeddedPostgres.enabled = false }"
      )
      .withFallback(ConfigSource.default)
    val opa = OpaConfig
      .validate(source.at("quack-flightsql.opa").loadOrThrow[OpaConfig])
      .fold(e => fail(e), identity)
    Main.bootManager(
      source.at("quack-on-demand").loadOrThrow[ManagerConfig],
      source.at("quack-flightsql").loadOrThrow[FlightConfig],
      source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig],
      source.at("quack-flightsql.acl").loadOrThrow[AclConfig],
      source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig],
      opa,
      env = env,
      rawConfig = ConfigFactory.empty()
    )
    ()

  "Main.bootManager" should "refuse a set QOD_API_KEY before HA validation" in {
    val err = intercept[RuntimeException](bootUntilHaRefusal(keyEnv("k1")))
    err.getMessage shouldBe BootPreflight.RemovedApiKeyMessage
  }

  it should "go on to HA validation with an empty QOD_API_KEY" in {
    val err = intercept[RuntimeException](bootUntilHaRefusal(keyEnv("")))
    err.getMessage should include("ha.enabled=true requires runtimeType=kubernetes or fleet")
  }

  /** The management auth block with only the fields the login gate reads set. */
  private def mgmt(
      identitySource: String = "db",
      issuerUrl: String = "",
      clientId: String = ""
  ): ManagementAuthConfig =
    ManagementAuthConfig(
      identitySource = identitySource,
      sessionJwtSecret = "",
      sessionCookieSecure = "auto",
      sessionCookiePath = "/api",
      oidc = ManagementOidcConfig(issuerUrl = issuerUrl, clientId = clientId)
    )

  private val google = "https://accounts.google.com"

  "checkLoginAvailable" should "refuse with neither a password provider nor system-scope OIDC" in {
    val out = BootPreflight.checkLoginAvailable(hasPasswordProvider = false, mgmt())
    out.isLeft shouldBe true
    out.left.toOption.get should include("QOD_AUTH_DB_ENABLED")
    out.left.toOption.get should include("QOD_MGMT_OIDC_ISSUER_URL")
    out.left.toOption.get should include("QOD_MGMT_IDENTITY_SOURCE=oidc")
  }

  it should "treat a blank issuer URL or client id as unset" in {
    BootPreflight
      .checkLoginAvailable(false, mgmt("oidc", issuerUrl = "  ", clientId = "c"))
      .isLeft shouldBe true
    BootPreflight
      .checkLoginAvailable(false, mgmt("oidc", issuerUrl = google, clientId = " "))
      .isLeft shouldBe true
  }

  it should "not count a configured issuer while the system scope signs in by password (db)" in {
    // ManagementAuthWiring hands the system scope no OIDC client under identitySource=db, so the
    // QOD_MGMT_OIDC_* values serve no sign-in there.
    BootPreflight
      .checkLoginAvailable(false, mgmt("db", issuerUrl = google, clientId = "c"))
      .isLeft shouldBe true
  }

  it should "boot with a password provider alone" in {
    BootPreflight.checkLoginAvailable(hasPasswordProvider = true, mgmt()) shouldBe Right(())
  }

  it should "boot with system-scope OIDC alone (identitySource=oidc, issuer and client id)" in {
    BootPreflight.checkLoginAvailable(
      hasPasswordProvider = false,
      mgmt(" OIDC ", issuerUrl = google, clientId = "c")
    ) shouldBe Right(())
  }

  "AuthenticationService.hasPasswordProvider" should "follow the basic chain, not the bearer one" in {
    def has(cfg: AuthenticationConfig): Boolean =
      val svc = new AuthenticationService(cfg, "")
      try svc.hasPasswordProvider
      finally svc.close()
    // Every provider off: no basic chain at all.
    has(emptyAuthConfig) shouldBe false
    // Google is bearer-only (no password grant): still no password provider.
    has(
      emptyAuthConfig.copy(google = emptyAuthConfig.google.copy(enabled = true, clientId = "c"))
    ) shouldBe
      false
    // Azure adds a resource-owner-password provider to the basic chain.
    has(
      emptyAuthConfig.copy(azure =
        AzureAuthConfig(enabled = true, tenantId = "t", clientId = "c", clientSecret = "s")
      )
    ) shouldBe true
  }
