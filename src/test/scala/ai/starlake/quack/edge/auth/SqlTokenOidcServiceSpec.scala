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

  private def svc(exchange: (String, String) => Either[String, String]) =
    new SqlTokenOidcService(
      cfg,
      () => "https://gw.example",
      "test-secret-32-chars-long-xxxxxx",
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

  it should "return the access_token after a successful exchange" in:
    val s = svc((_, _) => Right("""{"access_token":"the-token","id_token":"id"}"""))
    val Right((_, state: String)) = s.startUrl(): @unchecked
    s.completeAuth("code", state, state) shouldBe Right("the-token")

  it should "surface an exchange failure" in:
    val s                         = svc((_, _) => Left("HTTP 401"))
    val Right((_, state: String)) = s.startUrl(): @unchecked
    s.completeAuth("code", state, state) shouldBe Left("HTTP 401")
