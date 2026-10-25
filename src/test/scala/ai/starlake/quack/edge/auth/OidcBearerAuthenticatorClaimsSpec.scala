package ai.starlake.quack.edge.auth

import ai.starlake.quack.security.{JwtTestSigner, MockOidcServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What the OIDC bearer authenticator puts in the profile of a verified token. The SSO login reads
  * these claims to decide whether the profile's user name may name a `qodstate_user` row
  * (`AuthHandlers`: a name that fell back to the email claim counts only when `email_verified` is
  * true). The SSO handler specs stub the authenticator out, so this spec pins the real one.
  */
class OidcBearerAuthenticatorClaimsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val idp = MockOidcServer.boot()

  override def afterAll(): Unit = idp.shutdown()

  private val authenticator = new OidcBearerAuthenticator(
    providerName = "keycloak",
    jwksUrl = s"${idp.baseUrl}/jwks",
    expectedIssuer = idp.issuer,
    expectedAudience = "qod-console",
    roleClaim = "role"
  )

  /** The profile for a signed token holding `payload`. */
  private def profileFor(payload: Map[String, Any]): AuthenticatedProfile =
    val token = JwtTestSigner.mint(payload, issuer = idp.issuer, audience = Some("qod-console"))
    authenticator.authenticate(token) match
      case Right(profile) => profile
      case Left(error)    => fail(s"the token should have been accepted: $error")

  "OidcBearerAuthenticator" should "name a token without preferred_username by its email and leave preferred_username out of the claims" in {
    val profile = profileFor(Map("email" -> "carol@acme.example", "sub" -> "sub-1"))

    profile.username shouldBe "carol@acme.example"
    profile.claims.get("email") shouldBe Some("carol@acme.example")
    profile.claims.keySet should not contain "preferred_username"
    profile.claims.keySet should not contain "email_verified"
  }

  it should "carry preferred_username and email_verified as the token holds them" in {
    val profile = profileFor(
      Map(
        "preferred_username" -> "carol@acme.example",
        "email"              -> "carol@acme.example",
        "email_verified"     -> true
      )
    )

    profile.username shouldBe "carol@acme.example"
    profile.claims.get("preferred_username") shouldBe Some("carol@acme.example")
    profile.claims.get("email_verified") shouldBe Some("true")
  }
