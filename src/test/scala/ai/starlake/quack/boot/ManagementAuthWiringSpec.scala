package ai.starlake.quack.boot

import ai.starlake.quack.{Main, ManagerConfig}
import ai.starlake.quack.edge.BoundedHttp
import ai.starlake.quack.edge.auth.{
  EchoingServer,
  LocalIdp,
  NestedClaims,
  OidcScope,
  OidcSsoError,
  PlainThread
}
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
import ai.starlake.quack.security.JwtTestSigner
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

  it should "answer an issuer nobody listens on with a Left that has a text" in {
    // OidcDiscovery holds a failure's text in a table that takes no null: every GET it is given
    // answers a text, the JDK's exceptions that have none of their own included.
    val closed = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    val port   = closed.getLocalPort
    closed.close()
    val get = ManagementAuthWiring.discoveryGet(java.net.http.HttpClient.newHttpClient())

    get(s"http://127.0.0.1:$port/.well-known/openid-configuration") match
      case Left(why) => Option(why).exists(_.nonEmpty) shouldBe true
      case Right(_)  => fail("nothing listens there")
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
      // A healthy 200 answer is refused only by the cap, and the Left says nothing of why.
      ManagementAuthWiring.tokenExchange(java.net.http.HttpClient.newHttpClient())(
        s"${idp.base}/token",
        clientSecretForm
      ) shouldBe Left(BoundedHttp.TokenEndpointFailed)
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

  it should "answer a form that the server quotes back in a malformed answer with a fixed text, none of it in the Left" in
    // The JDK's exception for a malformed answer quotes the line, and the form holds the client
    // secret: a status line (or a Content-Length) that echoes the request put it in the Left.
    List(EchoingServer.Shape.StatusLine, EchoingServer.Shape.ContentLength).foreach { shape =>
      val server = new EchoingServer(shape)
      try
        val out = ManagementAuthWiring.tokenExchange(java.net.http.HttpClient.newHttpClient())(
          s"${server.base}/token",
          clientSecretForm
        )
        withClue(s"[$shape] $out ") {
          out shouldBe Left(BoundedHttp.TokenEndpointFailed)
          server.requests shouldBe 1
        }
      finally server.close()
    }

  // ----- a token an issuer signed with claims nested too deep -----
  //
  // The issuer is a tenant admin's choice and anyone can make the manager read its token (the SSO
  // callback). A claim nested thousands of levels deep passed the signature check and then
  // overflowed the stack of the code that printed the claims: a StackOverflowError, fatal to
  // cats-effect, halted the manager (exit code 1 at -Xmx256m, reproduced). The case runs on a plain
  // thread with a small stack, never a cats-effect one, so a regression fails the case and not the
  // test JVM.

  "ManagementAuthWiring SSO" should "refuse an id_token whose claim nests 30000 levels deep, and let its thread go on" in
    withIdp { idp =>
      val issuer = s"${idp.base}/sys"
      idp.serve("/sys/.well-known/openid-configuration", 200, LocalIdp.discoveryDoc(issuer))
      idp.serve("/sys/jwks", 200, JwtTestSigner.jwksJson().noSpaces)
      val idToken = NestedClaims.rs256(
        JwtTestSigner.keyPair,
        NestedClaims.claims(issuer, "sys-app", "a", NestedClaims.Depth)
      )
      idp.serve("/sys/token", 200, s"""{"id_token":"$idToken"}""")
      val sso = ManagementAuthWiring
        .build(load("oidc", issuerUrl = issuer, clientId = "sys-app"), noProviders, noTenants)
        .oidcSso
        .getOrElse(fail("no SSO service"))
      val started = sso
        .startAuth(OidcScope.System, "/ui/", "wiring-spec")
        .getOrElse(fail("the start failed"))

      PlainThread.run()(
        sso.completeAuth(
          "code",
          started.stateParam,
          started.stateCookieValue,
          System.currentTimeMillis()
        )
      ) shouldBe Right(Left(OidcSsoError.IdpError))

      // It read the key set: the signature was checked before the claims were read.
      idp.requests.asScala.map(_._1).toList should contain("/sys/jwks")
    }
