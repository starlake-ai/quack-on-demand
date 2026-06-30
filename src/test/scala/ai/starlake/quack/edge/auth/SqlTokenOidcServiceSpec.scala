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
