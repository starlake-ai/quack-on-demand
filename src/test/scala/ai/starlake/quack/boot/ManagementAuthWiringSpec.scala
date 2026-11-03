package ai.starlake.quack.boot

import ai.starlake.quack.{Main, ManagerConfig}
import ai.starlake.quack.edge.auth.{LocalIdp, OidcScope, OidcSsoError}
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
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

/** Pins what [[ManagementAuthWiring.build]] wires per `identitySource`. The source governs only the
  * bare `/ui/` login: the SSO service that serves tenant logins (a tenant's own `authProvider`
  * decides its mode) is wired under either source.
  */
class ManagementAuthWiringSpec extends AnyFlatSpec with Matchers with Eventually:
  import Main.given

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

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
    // System scope answers before any discovery (no network). The issuer is a loopback address
    // that refuses connections, so a regression that did reach discovery would fail the assertion
    // without making a call outside this host.
    val cfg = load("db", issuerUrl = "http://127.0.0.1:1", clientId = "sys-app")
    val sso = ManagementAuthWiring.build(cfg, noProviders, noTenants).oidcSso
    sso.map(_.startAuth(OidcScope.System, "/ui/", "wiring-spec")) shouldBe
      Some(Left(OidcSsoError.ScopeNotConfigured))
  }

  it should "use the manager-wide OIDC client for the bare /ui/ SSO under identitySource=oidc" in {
    // Port 1 on loopback refuses the connection: discovery of the manager-wide issuer fails at
    // once and nothing leaves this host. DiscoveryFailed (not ScopeNotConfigured) shows the System
    // scope reached discovery with the configured client.
    val cfg = load("oidc", issuerUrl = "http://127.0.0.1:1", clientId = "sys-app")
    val sso = ManagementAuthWiring.build(cfg, noProviders, noTenants).oidcSso
    sso.map(_.startAuth(OidcScope.System, "/ui/", "wiring-spec")) shouldBe
      Some(Left(OidcSsoError.DiscoveryFailed))
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

  // ----- an identity provider's answers are read under a size limit -----
  //
  // A tenant admin picks the issuer, and one anonymous GET /api/auth/oidc/start makes the manager
  // read its discovery document: read whole, a large one halted the manager for every tenant
  // (exit code 1 at -Xmx256m after 223 MiB). Each case below serves a document that is valid JSON
  // and over 256 KiB, so only the size limit can refuse it, and a small one beside it, so that the
  // refusal is not just a wiring that cannot reach the server.

  private def withIdp[A](f: LocalIdp => A): A =
    val idp = new LocalIdp
    try f(idp)
    finally idp.close()

  private val OverCap = 300 * 1024

  "ManagementAuthWiring discovery" should "read the manager-wide issuer's document under 256 KiB" in
    withIdp { idp =>
      idp.serve(
        "/small/.well-known/openid-configuration",
        200,
        LocalIdp.discoveryDoc(s"${idp.base}/small")
      )
      idp.serve(
        "/big/.well-known/openid-configuration",
        200,
        LocalIdp.discoveryDoc(s"${idp.base}/big", padBytes = OverCap)
      )
      def start(issuer: String) =
        ManagementAuthWiring
          .build(load("oidc", issuerUrl = issuer, clientId = "sys-app"), noProviders, noTenants)
          .oidcSso
          .map(_.startAuth(OidcScope.System, "/ui/", "wiring-spec"))

      start(s"${idp.base}/small").map(_.isRight) shouldBe Some(true)
      start(s"${idp.base}/big") shouldBe Some(Left(OidcSsoError.DiscoveryFailed))
    }

  it should "read the SQL-token provider's document under 256 KiB" in
    withIdp { idp =>
      idp.serve(
        "/realms/small/.well-known/openid-configuration",
        200,
        LocalIdp.discoveryDoc(s"${idp.base}/realms/small")
      )
      idp.serve(
        "/realms/big/.well-known/openid-configuration",
        200,
        LocalIdp.discoveryDoc(s"${idp.base}/realms/big", padBytes = OverCap)
      )
      def startUrl(realm: String) =
        val keycloak = KeycloakAuthConfig(
          enabled = true,
          baseUrl = idp.base,
          realm = realm,
          clientId = "qod-flightsql",
          clientSecret = "s3cret"
        )
        ManagementAuthWiring
          .build(load("db"), noProviders.copy(keycloak = keycloak), noTenants)
          .sqlToken
          .map(_.startUrl())

      startUrl("small").map(_.isRight) shouldBe Some(true)
      startUrl("big") match
        case Some(Left(why)) => why should include("response exceeds 256 KiB")
        case other           => fail(s"expected a failed discovery, got $other")
    }

  // The management SSO's code-to-token exchange. The form holds the client secret.
  private val clientSecretForm =
    "grant_type=authorization_code&code=abc&client_id=app&client_secret=hunter2-secret"

  "ManagementAuthWiring token exchange" should "return the body of a 200 answer" in
    withIdp { idp =>
      idp.serve("/token", 200, """{"id_token":"the-jwt"}""")
      ManagementAuthWiring.tokenExchange(java.net.http.HttpClient.newHttpClient())(
        s"${idp.base}/token",
        clientSecretForm
      ) shouldBe Right("""{"id_token":"the-jwt"}""")
      idp.requests.peek() shouldBe (("/token", clientSecretForm))
    }

  it should "refuse an answer over 256 KiB" in
    withIdp { idp =>
      idp.serve("/token", 200, s"""{"id_token":"the-jwt","pad":"${"x" * OverCap}"}""")
      ManagementAuthWiring.tokenExchange(java.net.http.HttpClient.newHttpClient())(
        s"${idp.base}/token",
        clientSecretForm
      ) shouldBe Left("token endpoint response exceeds 256 KiB")
    }

  it should "name a refused status by its code alone, never the answer or the form" in
    withIdp { idp =>
      idp.serveWith("/token")(received =>
        (400, s"""{"error":"invalid_client","echo":"$received"}""")
      )
      ManagementAuthWiring.tokenExchange(java.net.http.HttpClient.newHttpClient())(
        s"${idp.base}/token",
        clientSecretForm
      ) shouldBe Left("token endpoint HTTP 400")
    }

  it should "be what the wired SSO service reads the manager-wide token endpoint with" in
    withIdp { idp =>
      // A token answer of 64 MiB: a reader that takes it whole is never cut short, one under the
      // 256 KiB limit closes the connection on it, which the server sees as a write that failed.
      val issuer = s"${idp.base}/sys"
      idp.serve("/sys/.well-known/openid-configuration", 200, LocalIdp.discoveryDoc(issuer))
      idp.serve("/sys/token", 200, "x" * (64 * 1024 * 1024))
      val sso = ManagementAuthWiring
        .build(load("oidc", issuerUrl = issuer, clientId = "sys-app"), noProviders, noTenants)
        .oidcSso
        .getOrElse(fail("no SSO service"))
      val started = sso
        .startAuth(OidcScope.System, "/ui/", "wiring-spec")
        .getOrElse(fail("the start failed"))

      sso.completeAuth(
        "code",
        started.stateParam,
        started.stateCookieValue,
        System.currentTimeMillis()
      ) shouldBe Left(OidcSsoError.IdpError)

      // The POST reached the token endpoint, and the reader gave up on its answer.
      idp.requests.asScala.map(_._1).toList should contain("/sys/token")
      eventually(idp.aborted.get should be >= 1)
    }
