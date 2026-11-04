package ai.starlake.quack.boot

import ai.starlake.quack.{Main, ManagerConfig}
import ai.starlake.quack.edge.BoundedHttp
import ai.starlake.quack.edge.auth.{
  AtOnce,
  EchoingServer,
  LocalIdp,
  MalformedServer,
  NestedClaims,
  OidcDiscovery,
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
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import ai.starlake.quack.security.JwtTestSigner
import ch.qos.logback.classic.Level
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import pureconfig.ConfigSource

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Pins what [[ManagementAuthWiring.build]] wires per `identitySource`. The source governs only the
  * bare `/ui/` login: the SSO service that serves tenant logins (a tenant's own `authProvider`
  * decides its mode) is wired under either source.
  */
class ManagementAuthWiringSpec extends AnyFlatSpec with Matchers with Eventually:
  import Main.given

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

  // The wiring is an object, whose logger is named after the object's class.
  private val WiringLogger = ManagementAuthWiring.getClass.getName

  // What a server said: it must be in no answer, and in the log only cut.
  private val Marker = "marker-status-line-7f3a"

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
      // The page of the SQL-token flow shows this text, so it says nothing of what the issuer
      // answered, the size limit included.
      startUrl("big") shouldBe
        Some(Left(s"OIDC discovery failed for ${idp.base}/realms/big: discovery unavailable"))
    }

  it should "answer an issuer nobody listens on with a Left that has a text" in {
    // OidcDiscovery holds a failure's text in a table that takes no null: every GET it is given
    // answers a text, the JDK's exceptions that have none of their own included.
    val closed = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    val port   = closed.getLocalPort
    closed.close()

    ManagementAuthWiring.discoveryGet(
      s"http://127.0.0.1:$port/.well-known/openid-configuration"
    ) shouldBe
      Left("discovery unavailable")
  }

  // The key set and the token endpoint of an issuer, and its discovery document, are answers of a
  // server the manager does not control. The text of a failure reaches the SQL-token page and any
  // bearer client, so it quotes no server: one fixed text, status codes included, and the log
  // gets the cause, cut.
  it should "answer every failure with one fixed text, and log the cause cut" in
    withIdp { idp =>
      idp.serve("/status/503/.well-known/openid-configuration", 503, "busy")
      val (statusAnswer, statusLog) = LogCapture.logged(WiringLogger)(
        ManagementAuthWiring.discoveryGet(
          s"${idp.base}/status/503/.well-known/openid-configuration"
        )
      )
      statusAnswer shouldBe Left("discovery unavailable")
      statusLog.map(_.getFormattedMessage).exists(_.contains("503")) shouldBe true

      val line   = s"NOT-HTTP $Marker ${"z" * 400}"
      val server =
        new MalformedServer(line.concat("\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1))
      try
        val (quoteAnswer, quoteLog) = LogCapture.logged(WiringLogger)(
          ManagementAuthWiring.discoveryGet(s"${server.base}/.well-known/openid-configuration")
        )
        quoteAnswer shouldBe Left("discovery unavailable")
        val warned = quoteLog.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)
        withClue(s"logged $warned: ") {
          warned.exists(_.contains(s"NOT-HTTP $Marker")) shouldBe true
          warned.foreach(_.length should be < 300)
        }
      finally server.close()
    }

  // The read of a discovery document is one of the exchanges with identity providers that run at
  // most 128 at once. With none free it asks nobody and answers Busy, which OidcDiscovery tells
  // from a failure of the issuer: a failure is remembered for 10 s, and a busy refusal says
  // nothing of the issuer, so remembered it would turn one tenant's flood into 10 s of failed
  // discovery for another tenant's healthy issuer.
  it should "answer Busy, asking nobody, when no identity provider exchange is free" in
    withIdp { idp =>
      idp.serve("/.well-known/openid-configuration", 200, LocalIdp.discoveryDoc(idp.base))

      // AtOnce: a read that waited for a permit would park here for good, with nobody to give one.
      val (answer, events) = LogCapture.logged(WiringLogger)(
        AtOnce(
          ManagementAuthWiring.discoveryGet(
            s"${idp.base}/.well-known/openid-configuration",
            new BoundedHttp.Exchanges(0)
          )
        )
      )

      answer shouldBe Left(BoundedHttp.Busy)
      idp.requests.size shouldBe 0
      // Below WARN: a refusal is the cheapest thing an anonymous caller can ask for, so a WARN for
      // each would be a log flood.
      events.filter(_.getLevel.isGreaterOrEqual(Level.WARN)) shouldBe empty
    }

  it should "leave a busy discovery unremembered, so that the next call asks the issuer" in
    withIdp { idp =>
      val issuer = s"${idp.base}/healthy"
      idp.serve("/healthy/.well-known/openid-configuration", 200, LocalIdp.discoveryDoc(issuer))
      // First no exchange is free, then one is: the same discovery, asked again.
      @volatile var exchanges = new BoundedHttp.Exchanges(0)
      val discovery = new OidcDiscovery(url => ManagementAuthWiring.discoveryGet(url, exchanges))

      AtOnce(discovery.resolve(issuer)) shouldBe Left(BoundedHttp.Busy)
      idp.requests.size shouldBe 0

      exchanges = new BoundedHttp.Exchanges(1)
      discovery.resolve(issuer).map(_.issuer) shouldBe Right(issuer)
      idp.requests.size shouldBe 1
    }

  it should "quote no server in the SQL-token flow's answer when the issuer answers a status line the JDK refuses" in {
    val line   = s"NOT-HTTP $Marker ${"z" * 400}"
    val server = new MalformedServer(line.concat("\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1))
    try
      val keycloak = KeycloakAuthConfig(
        enabled = true,
        baseUrl = server.base,
        realm = "r",
        clientId = "qod-flightsql",
        clientSecret = "s3cret"
      )
      val out = ManagementAuthWiring
        .build(load("db"), noProviders.copy(keycloak = keycloak), noTenants)
        .sqlToken
        .map(_.startUrl())

      out shouldBe Some(
        Left(s"OIDC discovery failed for ${server.base}/realms/r: discovery unavailable")
      )
    finally server.close()
  }

  // ----- the flows still work on real answers, each exchange in a client of its own -----

  "ManagementAuthWiring SSO" should "still sign a user in on real answers, each exchange in a client of its own" in
    withIdp { idp =>
      val issuer = s"${idp.base}/sys"
      idp.serve("/sys/.well-known/openid-configuration", 200, LocalIdp.discoveryDoc(issuer))
      idp.serve("/sys/jwks", 200, JwtTestSigner.jwksJson().noSpaces)
      val sso = ManagementAuthWiring
        .build(load("oidc", issuerUrl = issuer, clientId = "sys-app"), noProviders, noTenants)
        .oidcSso
        .getOrElse(fail("no SSO service"))
      val started = sso
        .startAuth(OidcScope.System, "/ui/", "wiring-spec")
        .getOrElse(fail("the start failed"))
      // The token answer carries the nonce the start put in the authorization URL.
      val nonce = java.net.URLDecoder.decode(
        """[?&]nonce=([^&]+)""".r
          .findFirstMatchIn(started.redirectLocation)
          .map(_.group(1))
          .getOrElse(fail("no nonce in the authorization URL")),
        StandardCharsets.UTF_8
      )
      val idToken = JwtTestSigner.mint(
        Map("sub" -> "alice", "nonce" -> nonce),
        issuer = issuer,
        audience = Some("sys-app")
      )
      idp.serve("/sys/token", 200, s"""{"id_token":"$idToken"}""")

      val result = sso.completeAuth(
        "code",
        started.stateParam,
        started.stateCookieValue,
        System.currentTimeMillis()
      )

      result.map(_.profile.username) shouldBe Right("alice")
      idp.requests.asScala.map(_._1).toList.sorted shouldBe
        List("/sys/.well-known/openid-configuration", "/sys/jwks", "/sys/token")
    }

  "ManagementAuthWiring SQL-token flow" should "still hand a client the token, on real discovery and token answers" in
    withIdp { idp =>
      val issuer = s"${idp.base}/realms/real"
      idp.serve("/realms/real/.well-known/openid-configuration", 200, LocalIdp.discoveryDoc(issuer))
      idp.serve("/realms/real/token", 200, """{"id_token":"the-jwt"}""")
      val keycloak = KeycloakAuthConfig(
        enabled = true,
        baseUrl = idp.base,
        realm = "real",
        clientId = "qod-flightsql",
        clientSecret = "s3cret"
      )
      val sqlToken = ManagementAuthWiring
        .build(load("db"), noProviders.copy(keycloak = keycloak), noTenants)
        .sqlToken
        .getOrElse(fail("no SQL-token service"))
      val (_, state) = sqlToken.startUrl().getOrElse(fail("the start failed"))

      sqlToken.completeAuth("code", state, state) shouldBe Right("the-jwt")
    }

  // The management SSO's code-to-token exchange. The form holds the client secret.
  private val clientSecretForm =
    "grant_type=authorization_code&code=abc&client_id=app&client_secret=hunter2-secret"

  "ManagementAuthWiring token exchange" should "return the body of a 200 answer" in
    withIdp { idp =>
      idp.serve("/token", 200, """{"id_token":"the-jwt"}""")
      ManagementAuthWiring.tokenExchange(s"${idp.base}/token", clientSecretForm) shouldBe
        Right("""{"id_token":"the-jwt"}""")
      idp.requests.peek() shouldBe (("/token", clientSecretForm))
    }

  it should "refuse an answer over 256 KiB" in
    withIdp { idp =>
      idp.serve("/token", 200, s"""{"id_token":"the-jwt","pad":"${"x" * OverCap}"}""")
      // A healthy 200 answer is refused only by the cap, and the Left says nothing of why.
      ManagementAuthWiring.tokenExchange(s"${idp.base}/token", clientSecretForm) shouldBe
        Left(BoundedHttp.TokenEndpointFailed)
    }

  it should "name a refused status by its code alone, never the answer or the form" in
    withIdp { idp =>
      idp.serveWith("/token")(received =>
        (400, s"""{"error":"invalid_client","echo":"$received"}""")
      )
      ManagementAuthWiring.tokenExchange(s"${idp.base}/token", clientSecretForm) shouldBe
        Left("token endpoint HTTP 400")
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
        val out = ManagementAuthWiring.tokenExchange(s"${server.base}/token", clientSecretForm)
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
