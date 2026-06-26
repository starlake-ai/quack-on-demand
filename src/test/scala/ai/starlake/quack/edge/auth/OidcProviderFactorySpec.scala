package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.KeycloakAuthConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins how [[OidcProviderFactory.createKeycloak]] resolves the bearer validator's expected issuer.
  * The split-horizon case (browser-facing issuer != in-cluster baseUrl behind an ingress) is the
  * reason PowerBI / OIDC bearer tokens were rejected while DB/Basic auth worked.
  */
class OidcProviderFactorySpec extends AnyFlatSpec with Matchers:

  private def cfg(issuer: String = ""): KeycloakAuthConfig = KeycloakAuthConfig(
    enabled = true,
    baseUrl = "http://keycloak:8080/auth",
    realm = "qod",
    clientId = "qod-flightsql",
    clientSecret = "secret",
    issuer = issuer
  )

  it should "derive the expected issuer from baseUrl + realm when no override is set" in {
    val a = OidcProviderFactory.createKeycloak(cfg(), "role")
    a.expectedIssuer shouldBe "http://keycloak:8080/auth/realms/qod"
    a.expectedAudience shouldBe "qod-flightsql"
  }

  it should "use the issuer override when set, keeping audience from clientId" in {
    val a = OidcProviderFactory.createKeycloak(
      cfg(issuer = "https://qod.example.com/auth/realms/qod"),
      "role"
    )
    // The override is the browser-facing issuer that ingress-minted tokens carry;
    // JWKS still derives from baseUrl (not asserted here -- it is private), so the
    // manager fetches keys in-cluster while accepting the external `iss`.
    a.expectedIssuer shouldBe "https://qod.example.com/auth/realms/qod"
    a.expectedAudience shouldBe "qod-flightsql"
  }
