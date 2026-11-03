package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.{AuthenticationConfig, KeycloakAuthConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SqlTokenOidcServiceSpec extends AnyFlatSpec with Matchers:

  private def kc =
    KeycloakAuthConfig(
      enabled = true,
      baseUrl = "https://idp.example/auth",
      realm = "qod",
      clientId = "qod-flightsql",
      clientSecret = "s3cret",
      issuer = ""
    )

  private def cfg = AuthenticationConfig.disabled.copy(keycloak = kc, oauthScopes = "openid email")

  // Stub discovery: returns the issuer's endpoints without a network call. The authorize endpoint
  // is the provider's browser-facing URL (which the service must use for the 302).
  private val stubDiscovery = new OidcDiscovery(httpGet =
    _ =>
      Right(
        """|{"issuer":"https://idp.example/auth/realms/qod",
           |"authorization_endpoint":"https://idp.example/auth/realms/qod/protocol/openid-connect/auth",
           |"token_endpoint":"https://idp.example/auth/realms/qod/protocol/openid-connect/token",
           |"jwks_uri":"https://idp.example/auth/realms/qod/protocol/openid-connect/certs"}""".stripMargin
      )
  )

  private def svc(exchange: (String, String) => Either[String, String]) =
    new SqlTokenOidcService(
      cfg,
      () => "https://gw.example",
      "test-secret-32-chars-long-xxxxxx",
      stubDiscovery,
      httpExchange = exchange,
      nowMillis = () => 1000L
    )

  "startUrl" should "build an authorize URL with client_id, redirect_uri, scope and a state" in:
    val Right((url: String, state: String)) = svc((_, _) => Right("{}")).startUrl(): @unchecked
    url should startWith("https://idp.example/auth/realms/qod/protocol/openid-connect/auth?")
    url should include("client_id=qod-flightsql")
    url should include("redirect_uri=https%3A%2F%2Fgw.example%2Fapi%2Fauth%2Fsql-token%2Fcallback")
    url should include("scope=openid+email")
    url should include(s"state=$state")

  "completeAuth" should "reject a state that does not match the cookie" in:
    val s = svc((_, _) => Right("""{"access_token":"abc"}"""))
    s.completeAuth("code", "tampered", "cookie-state") shouldBe a[Left[?, ?]]

  it should "prefer the id_token over the access_token (edge validates aud == clientId)" in:
    val s = svc((_, _) => Right("""{"access_token":"opaque","id_token":"the-jwt"}"""))
    val Right((_, state: String)) = s.startUrl(): @unchecked
    s.completeAuth("code", state, state) shouldBe Right("the-jwt")

  it should "fall back to the access_token when no id_token is present" in:
    val s                         = svc((_, _) => Right("""{"access_token":"only-this"}"""))
    val Right((_, state: String)) = s.startUrl(): @unchecked
    s.completeAuth("code", state, state) shouldBe Right("only-this")

  it should "surface an exchange failure" in:
    val s                         = svc((_, _) => Left("HTTP 401"))
    val Right((_, state: String)) = s.startUrl(): @unchecked
    s.completeAuth("code", state, state) shouldBe Left("HTTP 401")

  // The default code-to-token exchange reads the provider's answer under the same size limit as
  // discovery, and a transport failure is a Left like any other (it used to be an exception that
  // no caller caught).
  private def withIdp[A](f: LocalIdp => A): A =
    val idp = new LocalIdp
    try f(idp)
    finally idp.close()

  "defaultExchange" should "return the body of a 200 token answer" in:
    withIdp { idp =>
      idp.serve("/token", 200, """{"id_token":"the-jwt"}""")
      SqlTokenOidcService.defaultExchange(s"${idp.base}/token", "grant_type=x") shouldBe
        Right("""{"id_token":"the-jwt"}""")
    }

  it should "refuse a token answer over 256 KiB with a Left" in:
    withIdp { idp =>
      idp.serve("/token", 200, s"""{"id_token":"the-jwt","pad":"${"x" * (300 * 1024)}"}""")
      SqlTokenOidcService.defaultExchange(s"${idp.base}/token", "grant_type=x") match
        case Left(why) => why should include("response exceeds 256 KiB")
        case Right(_)  => fail("expected a Left for an answer over the cap")
    }

  it should "answer a non-200 with its status alone, never the answer or the form" in:
    withIdp { idp =>
      val form = "grant_type=authorization_code&client_secret=hunter2-secret"
      idp.serveWith("/token")(received => (500, s"""{"error":"boom","echo":"$received"}"""))
      SqlTokenOidcService.defaultExchange(s"${idp.base}/token", form) shouldBe
        Left("Token exchange failed (HTTP 500)")
      idp.requests.peek() shouldBe (("/token", form))
    }

  it should "answer a refused connection with a Left, not an exception" in:
    val closed = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    val port   = closed.getLocalPort
    closed.close()
    val out = SqlTokenOidcService.defaultExchange(s"http://127.0.0.1:$port/token", "grant_type=x")
    out.isLeft shouldBe true
    out.left.toOption.exists(_.contains("grant_type")) shouldBe false
