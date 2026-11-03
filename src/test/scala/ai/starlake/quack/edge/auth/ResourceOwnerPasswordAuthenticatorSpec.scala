package ai.starlake.quack.edge.auth

import ai.starlake.quack.security.JwtTestSigner
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What the password-grant authenticator puts in the profile of the token its IdP answers with. The
  * management login reads these claims to decide whether the profile's user name may name a
  * `qodstate_user` row (`AuthHandlers`: a name that fell back to the email claim counts only when
  * `email_verified` is true), so a drift here silently changes who can sign in.
  */
class ResourceOwnerPasswordAuthenticatorSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll:

  private val idp = new WireMockServer(options().dynamicPort())

  override def beforeAll(): Unit = idp.start()
  override def afterAll(): Unit  = idp.stop()

  /** The grant's outcome for the access token with `payload`, answered to a password-grant request
    * for the typed name `typed-name`.
    */
  private def outcomeFor(payload: Map[String, Any]): Either[AuthFailure, AuthenticatedProfile] =
    val token = JwtTestSigner.mint(payload, issuer = "https://idp.example.test")
    idp.resetAll()
    idp.stubFor(
      WireMock
        .post(WireMock.urlPathEqualTo("/token"))
        .willReturn(
          WireMock.okJson(s"""{"access_token":"$token","token_type":"Bearer","expires_in":300}""")
        )
    )
    val authenticator = new ResourceOwnerPasswordAuthenticator(
      "keycloak",
      s"http://localhost:${idp.port()}/token",
      "qod-console",
      "qod-console-secret",
      "role"
    )
    authenticator.authenticate(AuthScope.System, "typed-name", "pw")

  /** The profile for the access token with `payload`, answered to a password-grant request. */
  private def profileFor(payload: Map[String, Any]): AuthenticatedProfile =
    outcomeFor(payload) match
      case Right(profile) => profile
      case Left(failure)  => fail(s"the grant should have answered: ${failure.message}")

  "ResourceOwnerPasswordAuthenticator" should "carry the token's preferred_username into the profile's claims" in {
    val profile = profileFor(
      Map("preferred_username" -> "alice", "email" -> "alice@acme.example", "sub" -> "sub-1")
    )

    profile.username shouldBe "alice"
    profile.claims.get("preferred_username") shouldBe Some("alice")
  }

  it should "carry email_verified as the string true or false, whether the token holds a JSON boolean or a string" in {
    val cases = List[(Any, String)](true -> "true", false -> "false", "true" -> "true")
    cases.foreach { case (claim, expected) =>
      withClue(s"[email_verified=$claim] ") {
        val profile = profileFor(
          Map(
            "preferred_username" -> "alice",
            "email"              -> "alice@acme.example",
            "email_verified"     -> claim
          )
        )

        profile.claims.get("email_verified") shouldBe Some(expected)
      }
    }
  }

  it should "carry neither claim when the token has neither, so an absent email_verified never reads as verified" in {
    val profile = profileFor(Map("email" -> "alice@acme.example"))

    profile.claims.keySet should not contain "preferred_username"
    profile.claims.keySet should not contain "email_verified"
  }

  it should "name the user by the email claim when the token has no preferred_username, and say so in the claims" in {
    // The naming chain is preferred_username, else email, else subject. The claims it leaves are
    // what lets the management login see that this name is a typed address (no preferred_username,
    // an email equal to the user name).
    val profile = profileFor(Map("email" -> "alice@acme.example", "sub" -> "sub-1"))

    profile.username shouldBe "alice@acme.example"
    profile.claims.get("email") shouldBe Some("alice@acme.example")
    profile.claims.keySet should not contain "preferred_username"
  }

  it should "keep the claims it carried before: sub as the user name, the role and the authentication method" in {
    val profile = profileFor(
      Map("preferred_username" -> "alice", "email" -> "alice@acme.example", "role" -> "admin")
    )

    profile.claims.get("sub") shouldBe Some("alice")
    profile.claims.get("role") shouldBe Some("admin")
    profile.claims.get("auth_method") shouldBe Some("keycloak-ropc")
    profile.authMethod shouldBe "keycloak-ropc"
  }

  it should "refuse a token that names no user (no preferred_username, email or sub), never naming the user by the typed text" in {
    // An IdP that accepts a login by an unverified address would otherwise let the typed address
    // name a row, past the email_verified rule.
    outcomeFor(Map("role" -> "admin")) match
      case Left(failure)  => failure shouldBe a[AuthFailure.InvalidCredentials]
      case Right(profile) =>
        fail(s"a token naming no user must be refused, got the name ${profile.username}")
  }

  it should "refuse a token whose email_verified nests 30000 levels deep, and let its thread go on" in {
    // The claim is printed with toString, which recurses once per level: the StackOverflowError is
    // fatal to cats-effect. The case runs on a plain thread with a small stack, never a cats-effect
    // one, so a regression fails the case and not the test JVM.
    val token = NestedClaims.rs256(
      JwtTestSigner.keyPair,
      NestedClaims.claims(
        "https://idp.example.test",
        "qod-console",
        "email_verified",
        NestedClaims.Depth
      )
    )
    idp.resetAll()
    idp.stubFor(
      WireMock
        .post(WireMock.urlPathEqualTo("/token"))
        .willReturn(WireMock.okJson(s"""{"access_token":"$token","token_type":"Bearer"}"""))
    )
    val authenticator = new ResourceOwnerPasswordAuthenticator(
      "keycloak",
      s"http://localhost:${idp.port()}/token",
      "qod-console",
      "qod-console-secret",
      "role"
    )

    PlainThread.run()(
      authenticator.authenticate(AuthScope.System, "typed-name", "pw")
    ) shouldBe Right(
      Left(AuthFailure.InvalidCredentials("keycloak-ropc: token is nested too deeply"))
    )
  }

  // The JDK's HTTP/2 reader queues inbound frames with no bound, and the JDK offers `Upgrade: h2c`
  // on every http:// request. The token endpoint is the operator's URL, not a tenant's, but the
  // client costs nothing to keep to HTTP/1.1 and there is no reason to offer what it cannot bound.
  it should "speak HTTP/1.1 only: its request offers no upgrade to HTTP/2" in {
    val server = new MalformedServer(
      "HTTP/1.1 400 Bad Request\r\nContent-Length: 2\r\n\r\n{}"
        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
    )
    try
      val authenticator = new ResourceOwnerPasswordAuthenticator(
        "keycloak",
        s"${server.base}/token",
        "qod-console",
        "qod-console-secret",
        "role"
      )

      authenticator.authenticate(AuthScope.System, "typed-name", "pw").isLeft shouldBe true

      val head = server.heads.headOption
        .getOrElse(fail("the token endpoint was never asked"))
        .toLowerCase(java.util.Locale.ROOT)
      head should startWith("post /token http/1.1")
      head should not include "upgrade"
      head should not include "http2-settings"
    finally server.close()
  }
