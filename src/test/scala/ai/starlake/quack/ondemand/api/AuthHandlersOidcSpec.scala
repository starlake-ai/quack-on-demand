package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ManagementOidcConfig
import ai.starlake.quack.edge.auth.{
  AuthScope,
  AuthenticatedProfile,
  AuthenticationService,
  OidcBearerAuthenticator,
  OidcDiscovery,
  OidcEndpointResolver,
  OidcEndpoints,
  OidcScope,
  OidcSsoService,
  OidcStateCodec
}
import ai.starlake.quack.edge.config.{
  AuthenticationConfig,
  AwsAuthConfig,
  AzureAuthConfig,
  DatabaseAuthConfig,
  GoogleAuthConfig,
  JwtAuthConfig,
  KeycloakAuthConfig,
  OAuthConfig
}
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.{GrantsLookup, ManagementIdentitySource}
import ai.starlake.quack.ondemand.state.UserGrant
import ai.starlake.quack.secrets.SecretRefResolver
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** Unit tests for the OIDC methods on [[AuthHandlers]]: `oidcStart`, `oidcCallback`, `oidcLogout`.
  */
class AuthHandlersOidcSpec extends AnyFlatSpec with Matchers:

  // ---- shared OIDC infrastructure (mirrors OidcSsoServiceSpec) ----

  private val discoveryJson =
    """|{"issuer":"https://idp.example.com",
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

  private val resolver = new OidcEndpointResolver(
    loadTenant = {
      case "acme" =>
        Some(
          Tenant(
            name = "acme",
            metastore = Map.empty,
            id = "acme",
            displayName = "Acme Corp",
            disabled = false,
            authProvider = "db",
            authConfig = Map(
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

  private val codec = new OidcStateCodec("test-secret", 600_000L)

  // Stub validator: echoes `nonce` in the returned profile claims so that
  // OidcSsoService.completeAuth's nonce check succeeds.
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

  private def buildSvc(seed: String): OidcSsoService =
    val nonce = codec.genNonce(seed)
    new OidcSsoService(
      resolver = resolver,
      mgmt = mgmt,
      codec = codec,
      roleClaim = "role",
      publicBaseUrlOf = () => "https://qod.example.com",
      httpExchange = (_, _) => Right("""{"id_token":"x"}"""),
      buildValidator = stubValidator(nonce),
      nowMillis = () => System.currentTimeMillis()
    )

  // ---- shared AuthHandlers infrastructure (mirrors AuthHandlersSpec) ----

  private val emptyConfig = AuthenticationConfig(
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
    ),
    oauth = OAuthConfig(
      enabled = false,
      port = 0,
      baseUrl = "",
      scopes = "",
      sessionTimeoutSeconds = 0,
      disableTls = true
    )
  )

  // Dummy auth service: never invoked by the OIDC path (identity comes from the IdP).
  private val dummyAuthService = new AuthenticationService(emptyConfig, "x"):
    override val hasProviders: Boolean = false
    override def authenticateBasic(
        scope: AuthScope,
        username: String,
        password: String
    ): Either[String, AuthenticatedProfile] =
      Left("not available in OIDC mode")

  private def makeHandlers(
      grants: List[UserGrant],
      svc: Option[OidcSsoService] = None
  ): AuthHandlers =
    val directory: GrantsLookup = (_, _) => grants
    new AuthHandlers(
      authService = dummyAuthService,
      tokens = new SessionTokenStore,
      identitySource = ManagementIdentitySource.Oidc,
      grantsForIdentity = directory,
      oidc = svc
    )

  // ---- helper: run a real startAuth + callback round-trip ----
  //
  // `seed` must be CONSTANT across the oidcSvc construction and the startAuth call so that
  // codec.genNonce(seed) matches the nonce the stubValidator echoes back.
  private def roundTrip(
      scope: OidcScope,
      returnTo: String,
      seed: String,
      grants: List[UserGrant]
  ): (AuthHandlers, OidcSsoService, String, String) =
    val svc      = buildSvc(seed)
    val started  = svc.startAuth(scope, returnTo, seed).toOption.get
    val handlers = makeHandlers(grants, Some(svc))
    (handlers, svc, started.stateCookieValue, started.stateCookieValue)

  // ================================================================
  // 1. oidcStart (system scope, no tenant) -> 302 + state cookie
  // ================================================================

  "oidcStart" should "302 to the system authorize URL and set qod_oidc_state with maxAge 600" in {
    val svc      = buildSvc("s1")
    val handlers = makeHandlers(List(UserGrant(None, "admin")), Some(svc))
    val result   = handlers.oidcStart(None, None).unsafeRunSync()
    result shouldBe a[Right[?, ?]]
    val (status, location, cookie) = result.toOption.get
    status shouldBe StatusCode.Found
    location should startWith("https://idp.example.com/authorize?")
    location should include("client_id=sys-app")
    cookie.value should not be empty
    cookie.maxAge shouldBe Some(600L)
    cookie.httpOnly shouldBe true
  }

  // ================================================================
  // 2. oidcStart (tenant scope) -> 302 to the tenant authorize URL
  // ================================================================

  it should "302 to the acme authorize URL when tenant=acme is specified" in {
    val svc      = buildSvc("s2")
    val handlers = makeHandlers(List(UserGrant(Some("acme"), "admin")), Some(svc))
    val result   = handlers.oidcStart(Some("acme"), None).unsafeRunSync()
    result shouldBe a[Right[?, ?]]
    val (status, location, _) = result.toOption.get
    status shouldBe StatusCode.Found
    location should include("client_id=acme-app")
  }

  // ================================================================
  // 3. oidcStart with unknown tenant -> 400 oidc_not_configured
  // ================================================================

  it should "return Left(400, oidc_not_configured) for an unknown tenant" in {
    val svc      = buildSvc("s3")
    val handlers = makeHandlers(List.empty, Some(svc))
    val result   = handlers.oidcStart(Some("nope"), None).unsafeRunSync()
    result shouldBe a[Left[?, ?]]
    val (status, err) = result.swap.toOption.get
    status shouldBe StatusCode.BadRequest
    err.error shouldBe "oidc_not_configured"
  }

  // ================================================================
  // 4. oidcStart when oidc = None -> 404 auth_mode_disabled
  // ================================================================

  it should "return Left(404, auth_mode_disabled) when no OIDC service is wired" in {
    val handlers = makeHandlers(List.empty, None)
    val result   = handlers.oidcStart(None, None).unsafeRunSync()
    result shouldBe a[Left[?, ?]]
    val (status, err) = result.swap.toOption.get
    status shouldBe StatusCode.NotFound
    err.error shouldBe "auth_mode_disabled"
  }

  // ================================================================
  // 5. oidcCallback happy path: SUPERUSER + system scope -> success
  // ================================================================

  "oidcCallback" should "302 to returnTo and set qod_session for a superuser on system scope" in {
    val seed                                 = "s5"
    val superuserGrants                      = List(UserGrant(None, "admin"))
    val (handlers, svc, state, stateCookieV) =
      roundTrip(OidcScope.System, "/ui/", seed, superuserGrants)
    val (status, location, session, cleared) =
      handlers
        .oidcCallback(Some("code-5"), Some(state), Some(stateCookieV), None)
        .unsafeRunSync()
    status shouldBe StatusCode.Found
    location shouldBe "/ui/"
    session.value should not be empty
    cleared.value shouldBe ""
    cleared.maxAge shouldBe Some(0L)
  }

  // ================================================================
  // 6. oidcCallback system scope but non-superuser -> admin_required
  // ================================================================

  it should "302 to /ui/?error=admin_required when system scope but only tenant-admin" in {
    val seed                               = "s6"
    val tenantGrants                       = List(UserGrant(Some("acme"), "admin"))
    val (handlers, _, state, stateCookieV) =
      roundTrip(OidcScope.System, "/ui/", seed, tenantGrants)
    val (status, location, session, _) =
      handlers
        .oidcCallback(Some("code-6"), Some(state), Some(stateCookieV), None)
        .unsafeRunSync()
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?error=admin_required"
    session.value shouldBe "" // no session set
  }

  // ================================================================
  // 7. oidcCallback with mismatched state cookie -> invalid_state
  // ================================================================

  it should "302 to /ui/?error=invalid_state when state cookie does not match" in {
    val seed                     = "s7"
    val svc                      = buildSvc(seed)
    val started                  = svc.startAuth(OidcScope.System, "/ui/", seed).toOption.get
    val handlers                 = makeHandlers(List(UserGrant(None, "admin")), Some(svc))
    val (status, location, _, _) =
      handlers
        .oidcCallback(
          Some("code-7"),
          Some(started.stateCookieValue),
          Some("totally-wrong-cookie"),
          None
        )
        .unsafeRunSync()
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?error=invalid_state"
  }

  // ================================================================
  // 8. oidcCallback tenant scope, identity admin of that tenant -> success
  // ================================================================

  it should "302 to returnTo for a tenant admin on a matching tenant scope" in {
    val seed                               = "s8"
    val tenantGrants                       = List(UserGrant(Some("acme"), "admin"))
    val (handlers, _, state, stateCookieV) =
      roundTrip(OidcScope.Tenant("acme"), "/ui/?tenant=acme", seed, tenantGrants)
    val (status, location, session, cleared) =
      handlers
        .oidcCallback(Some("code-8"), Some(state), Some(stateCookieV), None)
        .unsafeRunSync()
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?tenant=acme"
    session.value should not be empty
    cleared.value shouldBe ""
  }

  // ================================================================
  // 9. oidcCallback tenant scope, identity NOT admin of that tenant -> admin_required
  // ================================================================

  it should "302 to /ui/?error=admin_required when tenant scope but identity has no grant there" in {
    val seed = "s9"
    // Identity has a grant for a DIFFERENT tenant, not "acme"
    val wrongGrants                        = List(UserGrant(Some("globex"), "admin"))
    val (handlers, _, state, stateCookieV) =
      roundTrip(OidcScope.Tenant("acme"), "/ui/?tenant=acme", seed, wrongGrants)
    val (status, location, session, _) =
      handlers
        .oidcCallback(Some("code-9"), Some(state), Some(stateCookieV), None)
        .unsafeRunSync()
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?error=admin_required"
    session.value shouldBe ""
  }

  // ================================================================
  // 10. oidcCallback: empty grants -> not_provisioned
  // ================================================================

  it should "302 to /ui/?error=not_provisioned when grantsForIdentity returns empty" in {
    val seed                               = "s10"
    val (handlers, _, state, stateCookieV) =
      roundTrip(OidcScope.System, "/ui/", seed, List.empty)
    val (status, location, session, _) =
      handlers
        .oidcCallback(Some("code-10"), Some(state), Some(stateCookieV), None)
        .unsafeRunSync()
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?error=not_provisioned"
    session.value shouldBe ""
  }

  // ================================================================
  // 11. oidcLogout -> 302 to IdP end-session URL, clears qod_session
  // ================================================================

  "oidcLogout" should "302 to IdP end-session URL and clear the session cookie" in {
    val svc                        = buildSvc("s11")
    val handlers                   = makeHandlers(List(UserGrant(None, "admin")), Some(svc))
    val (status, location, cookie) =
      handlers.oidcLogout(None, None).unsafeRunSync()
    status shouldBe StatusCode.Found
    location should startWith("https://idp.example.com/logout?")
    cookie.value shouldBe ""
    cookie.maxAge shouldBe Some(0L)
  }
