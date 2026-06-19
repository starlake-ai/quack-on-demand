package ai.starlake.quack.edge.auth

import ai.starlake.quack.ManagementOidcConfig
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.secrets.SecretRefResolver
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OidcSsoServiceSpec extends AnyFlatSpec with Matchers:

  private val discoveryJson =
    """{"issuer":"https://idp.example.com",
       |"authorization_endpoint":"https://idp.example.com/authorize",
       |"token_endpoint":"https://idp.example.com/token",
       |"jwks_uri":"https://idp.example.com/jwks",
       |"end_session_endpoint":"https://idp.example.com/logout"}""".stripMargin

  private val discovery = new OidcDiscovery(httpGet = {
    case "https://idp.example.com/.well-known/openid-configuration" => Right(discoveryJson)
    case other                                                      => Left(s"unexpected $other")
  })

  private val secrets: SecretRefResolver = new SecretRefResolver:
    def resolve(ref: String) = Right(s"resolved-${ref.stripPrefix("env:")}")

  // Tenant constructor: name, metastore, id, displayName, disabled, authProvider, authConfig
  private def tenant(authConfig: Map[String, String]): Tenant =
    Tenant(
      name = "test-tenant",
      metastore = Map.empty,
      id = "test-id",
      displayName = "Test Tenant",
      disabled = false,
      authProvider = "db",
      authConfig = authConfig
    )

  private val resolver = new OidcEndpointResolver(
    loadTenant = {
      case "acme" =>
        Some(
          tenant(
            Map(
              "issuerUrl"       -> "https://idp.example.com",
              "clientId"        -> "acme-app",
              "clientSecretRef" -> "env:ACME"
            )
          )
        )
      case _ => None
    },
    secrets = secrets,
    discovery = discovery
  )

  private val mgmt = ManagementOidcConfig(
    issuerUrl = "https://idp.example.com",
    clientId = "sys-app",
    clientSecret = "sys-secret",
    scopes = "openid email profile"
  )

  private val codec = new OidcStateCodec("test-secret", 600000L)

  // Stub validator: a real OidcBearerAuthenticator subtype whose authenticate is overridden to
  // return a profile echoing `nonce`. Construction does NOT hit the network (JWKS is lazy).
  private def stubValidator(nonce: String): OidcEndpoints => OidcBearerAuthenticator =
    _ =>
      new OidcBearerAuthenticator("oidc", "https://idp.example.com/jwks", "", "", "role"):
        override def authenticate(token: String) =
          Right(
            AuthenticatedProfile(
              username = "alice",
              role = "admin",
              groups = Set("admin"),
              claims = Map("nonce" -> nonce, "email" -> "alice@acme.io"),
              authMethod = "oidc"
            )
          )

  private def svc(
      nonce: String,
      tokenJson: String,
      clock: () => Long = () => 1_000_000L
  ): OidcSsoService =
    new OidcSsoService(
      resolver = resolver,
      mgmt = mgmt,
      codec = codec,
      roleClaim = "role",
      publicBaseUrlOf = () => "https://qod.example.com",
      httpExchange = (_, _) => Right(tokenJson),
      buildValidator = stubValidator(nonce),
      nowMillis = clock
    )

  it should "build a System authorize redirect + state cookie" in {
    val req = svc("n", "{}").startAuth(OidcScope.System, "/ui/", seed = "s1").toOption.get
    req.redirectLocation should startWith("https://idp.example.com/authorize?")
    req.redirectLocation should include("response_type=code")
    req.redirectLocation should include("client_id=sys-app")
    req.redirectLocation should include(
      "redirect_uri=https%3A%2F%2Fqod.example.com%2Fapi%2Fauth%2Foidc%2Fcallback"
    )
    req.redirectLocation should include("code_challenge=")
    req.redirectLocation should include("code_challenge_method=S256")
    req.stateCookie shouldBe OidcSsoService.STATE_COOKIE
  }

  it should "complete a valid tenant callback and return the profile" in {
    val nonce   = codec.genNonce("s2")
    val service = svc(nonce = nonce, tokenJson = """{"id_token":"jwt-abc"}""")
    val started =
      service.startAuth(OidcScope.Tenant("acme"), "/ui/?tenant=acme", seed = "s2").toOption.get
    val res = service.completeAuth(
      code = "code-1",
      state = started.stateParam,
      stateCookieValue = started.stateCookieValue,
      now = 1_000_500L
    )
    res.map(_.profile.username) shouldBe Right("alice")
    res.map(_.scope) shouldBe Right(OidcScope.Tenant("acme"))
    res.map(_.returnTo) shouldBe Right("/ui/?tenant=acme")
  }

  it should "reject a callback whose state cookie does not match" in {
    val service = svc("n", """{"id_token":"jwt"}""")
    val started = service.startAuth(OidcScope.System, "/ui/", "s3").toOption.get
    service.completeAuth(
      "c",
      started.stateParam,
      "wrong::verifier",
      now = 1_000_500L
    ) shouldBe Left(OidcSsoError.InvalidState)
  }

  it should "reject a callback whose id_token nonce does not match the state" in {
    // Validator echoes the WRONG nonce, so the nonce check fails -> IdpError.
    val service = new OidcSsoService(
      resolver,
      mgmt,
      codec,
      "role",
      () => "https://qod.example.com",
      (_, _) => Right("""{"id_token":"x"}"""),
      stubValidator("the-wrong-nonce"),
      () => 1_000_000L
    )
    val started = service.startAuth(OidcScope.System, "/ui/", "s4").toOption.get
    service.completeAuth(
      "c",
      started.stateParam,
      started.stateCookieValue,
      now = 1_000_500L
    ) shouldBe Left(OidcSsoError.IdpError)
  }

  it should "build an RP-initiated end-session URL with post_logout_redirect_uri" in {
    val url = svc("n", "{}").endSessionUrl(OidcScope.System, Some("id-tok")).get
    url should startWith("https://idp.example.com/logout?")
    url should include("post_logout_redirect_uri=https%3A%2F%2Fqod.example.com%2Fui%2F")
    url should include("id_token_hint=id-tok")
  }

  it should "sanitize malicious returnTo values to /ui/ and pass a safe path unchanged" in {
    val seed    = "s5"
    val nonce   = codec.genNonce(seed)
    val service = svc(nonce = nonce, tokenJson = """{"id_token":"x"}""")

    // (input returnTo, expected returnTo after round-trip)
    val cases = List(
      "https://evil.com" -> "/ui/",
      "//evil.com"       -> "/ui/",
      "/\\evil.com"      -> "/ui/",
      "/%2F%2Fevil.com"  -> "/ui/",
      ""                 -> "/ui/",
      "/admin"           -> "/admin"
    )

    cases.foreach { case (returnTo, expected) =>
      val started = service.startAuth(OidcScope.System, returnTo, seed = seed).toOption.get
      val result  = service.completeAuth(
        code = "code-x",
        state = started.stateParam,
        stateCookieValue = started.stateCookieValue,
        now = 1_000_500L
      )
      result.map(_.returnTo) shouldBe Right(expected)
    }
  }

  it should "round-trip the PKCE verifier into the token-exchange form" in {
    val seed         = "pkce-seed"
    val nonce        = codec.genNonce(seed)
    var capturedForm = ""
    val service      = new OidcSsoService(
      resolver = resolver,
      mgmt = mgmt,
      codec = codec,
      roleClaim = "role",
      publicBaseUrlOf = () => "https://qod.example.com",
      httpExchange = (_, form) => { capturedForm = form; Right("""{"id_token":"jwt"}""") },
      buildValidator = stubValidator(nonce),
      nowMillis = () => 1_000_000L
    )
    val started = service.startAuth(OidcScope.System, "/ui/", seed = seed).toOption.get
    // The verifier must NOT travel in the IdP-facing state param.
    started.stateParam should not include "::"
    started.stateCookieValue should include("::")
    val res = service.completeAuth(
      code = "code-pkce",
      state = started.stateParam,
      stateCookieValue = started.stateCookieValue,
      now = 1_000_500L
    )
    res.map(_.profile.username) shouldBe Right("alice")
    val expectedVerifier =
      java.net.URLEncoder
        .encode(codec.genPkce(seed).verifier, java.nio.charset.StandardCharsets.UTF_8)
    capturedForm should include("code_verifier=")
    capturedForm should include(s"code_verifier=$expectedVerifier")
  }
