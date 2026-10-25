package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ManagementOidcConfig
import ai.starlake.quack.edge.auth.{
  AuthFailure,
  AuthScope,
  AuthenticatedProfile,
  AuthenticationService,
  DatabaseAuthenticator,
  OidcBearerAuthenticator,
  OidcDiscovery,
  OidcEndpointResolver,
  OidcEndpoints,
  OidcScope,
  OidcSsoService,
  OidcStateCodec,
  SqlTokenOidcService
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
import ai.starlake.quack.ondemand.auth.{
  GrantsLookup,
  LoginRealm,
  ManagementAuthMode,
  ManagementAuthModeResolver,
  ManagementIdentitySource,
  SessionScope
}
import ai.starlake.quack.ondemand.state.UserGrant
import ai.starlake.quack.secrets.SecretRefResolver
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
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

  /** The identity the stub IdP vouches for: the user name, the provider name the profile carries
    * (`authMethod`: the issuer host in production, which a tenant admin picks through issuerUrl)
    * and the other flattened token claims. By default the IdP marks the email verified, as Keycloak
    * and Google do.
    */
  private final case class StubIdentity(
      username: String = "alice",
      authMethod: String = "oidc",
      claims: Map[String, String] = Map("email" -> "alice@acme.io", "email_verified" -> "true")
  )

  // Stub validator: echoes `nonce` in the returned profile claims so that
  // OidcSsoService.completeAuth's nonce check succeeds.
  private def stubValidator(
      nonce: String,
      identity: StubIdentity
  ): OidcEndpoints => OidcBearerAuthenticator =
    _ =>
      new OidcBearerAuthenticator("oidc", "https://idp.example.com/jwks", "", "", "role"):
        override def authenticate(token: String) =
          Right(
            AuthenticatedProfile(
              username = identity.username,
              role = "admin",
              groups = Set("admin"),
              claims = identity.claims + ("nonce" -> nonce),
              authMethod = identity.authMethod
            )
          )

  private def buildSvc(seed: String, identity: StubIdentity = StubIdentity()): OidcSsoService =
    val nonce = codec.genNonce(seed)
    new OidcSsoService(
      resolver = resolver,
      mgmt = mgmt,
      codec = codec,
      roleClaim = "role",
      publicBaseUrlOf = () => "https://qod.example.com",
      httpExchange = (_, _) => Right("""{"id_token":"x"}"""),
      buildValidator = stubValidator(nonce, identity),
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
    )
  )

  // Dummy auth service: never invoked by the OIDC path (identity comes from the IdP).
  private val dummyAuthService = new AuthenticationService(emptyConfig, "x"):
    override val hasProviders: Boolean = false
    override def authenticateBasic(
        scope: AuthScope,
        username: String,
        password: String
    ): Either[AuthFailure, AuthenticatedProfile] =
      Left(AuthFailure.InvalidCredentials("not available in OIDC mode"))

  private def makeHandlers(
      grants: List[UserGrant],
      svc: Option[OidcSsoService] = None,
      tokens: SessionTokenStore = new SessionTokenStore
  ): AuthHandlers =
    makeHandlersWith((_, _, _) => grants, svc, tokens)

  private def makeHandlersWith(
      directory: GrantsLookup,
      svc: Option[OidcSsoService],
      tokens: SessionTokenStore = new SessionTokenStore,
      events: ManagerEventSink = ManagerEventSink.noop
  ): AuthHandlers =
    new AuthHandlers(
      authService = dummyAuthService,
      tokens = tokens,
      identitySource = ManagementIdentitySource.Oidc,
      grantsForIdentity = directory,
      authModeResolver = new ManagementAuthModeResolver(_ => None, ManagementAuthMode.Db),
      oidc = svc,
      events = events
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
    (handlers, svc, started.stateParam, started.stateCookieValue)

  /** A grants directory that answers `grants` to every lookup and records what it was asked (realm,
    * identity, email claim), so a test sees both what the handler asked and what it kept.
    */
  private final class RecordingDirectory(grants: List[UserGrant]):
    val asked: scala.collection.mutable.ListBuffer[(LoginRealm, String, Option[String])] =
      scala.collection.mutable.ListBuffer.empty
    val lookup: GrantsLookup = (realm, identity, email) =>
      asked += ((realm, identity, email))
      grants

  /** A directory holding `rows` by user name, with the store's email fallback
    * (`UserStore.grantsForIdentity`): the identity is matched first, the email claim only when the
    * identity matches no row. It records every lookup (realm, identity, email claim handed over).
    */
  private final class NamedDirectory(rows: Map[String, List[UserGrant]]):
    val asked: scala.collection.mutable.ListBuffer[(LoginRealm, String, Option[String])] =
      scala.collection.mutable.ListBuffer.empty
    val lookup: GrantsLookup = (realm, identity, email) =>
      asked += ((realm, identity, email))
      rows.getOrElse(
        identity,
        email.filter(_ != identity).flatMap(rows.get).getOrElse(Nil)
      )

  /** One SSO round trip for `scope` against `directory`: the callback's status, location and
    * session cookie value, plus the token store that minted the session. `identity` is what the
    * stub IdP vouches for.
    */
  private def callbackWith(
      scope: OidcScope,
      returnTo: String,
      seed: String,
      directory: GrantsLookup,
      events: ManagerEventSink = ManagerEventSink.noop,
      identity: StubIdentity = StubIdentity()
  ): (StatusCode, String, String, SessionTokenStore) =
    val svc                            = buildSvc(seed, identity)
    val started                        = svc.startAuth(scope, returnTo, seed).toOption.get
    val tokens                         = new SessionTokenStore
    val handlers                       = makeHandlersWith(directory, Some(svc), tokens, events)
    val (status, location, session, _) =
      handlers
        .oidcCallback(
          Some(s"code-$seed"),
          Some(started.stateParam),
          Some(started.stateCookieValue),
          None
        )
        .unsafeRunSync()
    (status, location, session.value, tokens)

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
  // 4b. identitySource=db manager: an OIDC tenant still signs in through its IdP
  // ================================================================

  it should "302 an OIDC tenant to its own issuer on a manager whose identitySource is db" in {
    // identitySource governs only the bare /ui/ login; the tenant's own authProvider makes its
    // login SSO, served by the same service whatever the manager-wide source is.
    val acmeOidc = Tenant(
      id = "acme",
      displayName = "Acme Corp",
      authProvider = "keycloak",
      authConfig = Map(
        "issuerUrl"       -> "https://idp.example.com",
        "clientId"        -> "acme-app",
        "clientSecretRef" -> "env:ACME"
      )
    )
    val handlers = new AuthHandlers(
      authService = dummyAuthService,
      tokens = new SessionTokenStore,
      identitySource = ManagementIdentitySource.Db,
      grantsForIdentity = (_, _, _) => Nil,
      authModeResolver =
        new ManagementAuthModeResolver(Map(acmeOidc.id -> acmeOidc).get, ManagementAuthMode.Db),
      oidc = Some(buildSvc("s4b"))
    )
    handlers.authMode(None).unsafeRunSync().map(_.mode) shouldBe Right("db")
    handlers.authMode(Some("acme")).unsafeRunSync().map(_.mode) shouldBe Right("oidc")
    handlers.oidcStart(Some("acme"), None).unsafeRunSync() match
      case Right((status, location, _)) =>
        status shouldBe StatusCode.Found
        location should startWith("https://idp.example.com/authorize?")
        location should include("client_id=acme-app")
      case Left(e) => fail(s"expected a redirect to the tenant issuer, got $e")
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
  // 9. oidcCallback tenant scope, identity has rows only in another tenant -> not_provisioned
  // ================================================================

  it should "302 to /ui/?error=not_provisioned when tenant scope but identity has rows only in another tenant" in {
    val seed = "s9"
    // The identity's only row is in a DIFFERENT tenant: the acme realm holds no grant for it.
    val wrongGrants                        = List(UserGrant(Some("globex"), "admin"))
    val (handlers, _, state, stateCookieV) =
      roundTrip(OidcScope.Tenant("acme"), "/ui/?tenant=acme", seed, wrongGrants)
    val (status, location, session, _) =
      handlers
        .oidcCallback(Some("code-9"), Some(state), Some(stateCookieV), None)
        .unsafeRunSync()
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?error=not_provisioned"
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

  // ================================================================
  // 12. oidcLogout with a live session token -> revokes the jti
  // ================================================================

  it should "revoke the session jti when a session cookie is presented" in {
    val svc      = buildSvc("s12")
    val tokens   = new SessionTokenStore
    val handlers = makeHandlers(List(UserGrant(None, "admin")), Some(svc), tokens)
    val profile  = AuthenticatedProfile(
      username = "alice",
      role = "admin",
      groups = Set("admin"),
      claims = Map("email" -> "alice@acme.io"),
      authMethod = "oidc"
    )
    val token  = tokens.mintWithScope(profile, SessionScope(superuser = true, Set.empty))
    val before = tokens.revokedJtiCount
    val (status, _, cookie) =
      handlers.oidcLogout(Some(token), None).unsafeRunSync()
    status shouldBe StatusCode.Found
    cookie.value shouldBe ""
    cookie.maxAge shouldBe Some(0L)
    tokens.revokedJtiCount shouldBe (before + 1)
  }

  // ================================================================
  // 13. oidcCallback reads grants in the realm the login was verified in
  // ================================================================

  "oidcCallback realm" should "look up grants in the tenant's realm on a tenant-scope login" in {
    val directory = new RecordingDirectory(List(UserGrant(Some("acme"), "admin")))
    callbackWith(OidcScope.Tenant("acme"), "/ui/?tenant=acme", "s13", directory.lookup)
    directory.asked.toList shouldBe List(
      (LoginRealm.Tenant("acme"), "alice", Some("alice@acme.io"))
    )
  }

  it should "look up grants in the system realm on a system-scope login" in {
    val directory = new RecordingDirectory(List(UserGrant(None, "admin")))
    callbackWith(OidcScope.System, "/ui/", "s14", directory.lookup)
    directory.asked.toList shouldBe List((LoginRealm.System, "alice", Some("alice@acme.io")))
  }

  it should "bind only the tenant's own row on a tenant-scope login: never superuser, never another tenant" in {
    // The directory answers rows of every realm; the session keeps the tenant's row alone.
    val directory = new RecordingDirectory(
      List(
        UserGrant(None, "admin"),
        UserGrant(Some("acme"), "admin"),
        UserGrant(Some("globex"), "admin")
      )
    )
    val (status, location, session, tokens) =
      callbackWith(OidcScope.Tenant("acme"), "/ui/?tenant=acme", "s15", directory.lookup)
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?tenant=acme"
    tokens.get(session).map(_.scope) shouldBe
      Some(SessionScope(superuser = false, manageableTenants = Set("acme")))
  }

  it should "answer not_provisioned on a tenant-scope login whose only matching row is a superuser row" in {
    val directory                      = new RecordingDirectory(List(UserGrant(None, "admin")))
    val (status, location, session, _) =
      callbackWith(OidcScope.Tenant("acme"), "/ui/?tenant=acme", "s16", directory.lookup)
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?error=not_provisioned"
    session shouldBe ""
  }

  it should "name the tenant on the session profile and the SessionOpened event of a tenant-scope login" in {
    // PAT minting resolves the owning row by (profile.tenant, username): the profile must carry
    // the realm the login was verified in.
    val events                  = scala.collection.mutable.ListBuffer.empty[ManagerEvent]
    val sink: ManagerEventSink  = e => { events += e; () }
    val (_, _, session, tokens) = callbackWith(
      OidcScope.Tenant("acme"),
      "/ui/?tenant=acme",
      "s17",
      new RecordingDirectory(List(UserGrant(Some("acme"), "admin"))).lookup,
      sink
    )
    tokens.get(session).map(_.profile.tenant) shouldBe Some(Some("acme"))
    events.toList should contain(ManagerEvent.SessionOpened("acme", "alice", "oidc"))
  }

  it should "leave the session profile tenant empty on a system-scope login" in {
    val (_, _, session, tokens) = callbackWith(
      OidcScope.System,
      "/ui/",
      "s18",
      new RecordingDirectory(List(UserGrant(None, "admin"))).lookup
    )
    tokens.get(session).map(_.profile.tenant) shouldBe Some(None)
  }

  it should "keep the tenant-scope login admin-only: a regular user row mints no profile-only session" in {
    // Profile-only sessions are a DB-login feature. The IdP's role claim (admin, from the stub
    // validator) carries no qodstate authority, so a `kind=user` row on the tenant ends in
    // admin_required.
    val (status, location, session, _) = callbackWith(
      OidcScope.Tenant("acme"),
      "/ui/?tenant=acme",
      "s19",
      new RecordingDirectory(List(UserGrant(Some("acme"), "user"))).lookup
    )
    status shouldBe StatusCode.Found
    location shouldBe "/ui/?error=admin_required"
    session shouldBe ""
  }

  it should "name the tenant on the session profile of a tenant-scope login whatever provider name the profile carries" in {
    // An SSO profile's authMethod is the issuer host, which a tenant admin picks through issuerUrl:
    // a host called "database" must not make the profile look like the database authenticator's.
    // Left without the tenant, a personal access token minted from the session would be owned by
    // the superuser row of the same name.
    val (_, _, session, tokens) = callbackWith(
      OidcScope.Tenant("acme"),
      "/ui/?tenant=acme",
      "s20",
      new RecordingDirectory(List(UserGrant(Some("acme"), "admin"))).lookup,
      identity = StubIdentity(authMethod = DatabaseAuthenticator.AuthMethod)
    )
    tokens.get(session).map(_.profile.tenant) shouldBe Some(Some("acme"))
    tokens.get(session).map(_.scope) shouldBe
      Some(SessionScope(superuser = false, manageableTenants = Set("acme")))
  }

  // ================================================================
  // 14. oidcCallback email fallback: only an email the IdP marks verified
  // ================================================================

  // The IdP vouches for the user name 'mallory'. Its email claim names a row 'admin@localhost.local'
  // (the superuser's name), or an admin row of acme: a self-edited address, unless the IdP says it
  // verified it. Mallory matches no row by name.
  private val superuserByEmail = Map("admin@localhost.local" -> List(UserGrant(None, "admin")))
  private val acmeAdminByEmail =
    Map("boss@acme.example" -> List(UserGrant(Some("acme"), "admin")))

  private def mallory(email: String, verified: Option[String]): StubIdentity =
    StubIdentity(
      username = "mallory",
      claims = Map("email" -> email) ++ verified.map("email_verified" -> _)
    )

  "oidcCallback email fallback" should "not bind the superuser row an unverified email names (email_verified false)" in {
    val directory                 = new NamedDirectory(superuserByEmail)
    val (_, location, session, _) = callbackWith(
      OidcScope.System,
      "/ui/",
      "s21",
      directory.lookup,
      identity = mallory("admin@localhost.local", Some("false"))
    )
    location shouldBe "/ui/?error=not_provisioned"
    session shouldBe ""
    directory.asked.toList shouldBe List((LoginRealm.System, "mallory", None))
  }

  it should "not bind the superuser row an email names when the IdP sends no email_verified claim" in {
    val directory                 = new NamedDirectory(superuserByEmail)
    val (_, location, session, _) = callbackWith(
      OidcScope.System,
      "/ui/",
      "s22",
      directory.lookup,
      identity = mallory("admin@localhost.local", None)
    )
    location shouldBe "/ui/?error=not_provisioned"
    session shouldBe ""
    directory.asked.toList shouldBe List((LoginRealm.System, "mallory", None))
  }

  it should "bind the row a verified email names when the user name matches no row (email_verified true)" in {
    val directory                      = new NamedDirectory(superuserByEmail)
    val (_, location, session, tokens) = callbackWith(
      OidcScope.System,
      "/ui/",
      "s23",
      directory.lookup,
      identity = mallory("admin@localhost.local", Some("true"))
    )
    location shouldBe "/ui/"
    tokens.get(session).map(_.scope) shouldBe Some(SessionScope.Superuser)
    directory.asked.toList shouldBe
      List((LoginRealm.System, "mallory", Some("admin@localhost.local")))
  }

  it should "not bind the tenant row an unverified email names on a tenant-scope login" in {
    val directory                 = new NamedDirectory(acmeAdminByEmail)
    val (_, location, session, _) = callbackWith(
      OidcScope.Tenant("acme"),
      "/ui/?tenant=acme",
      "s24",
      directory.lookup,
      identity = mallory("boss@acme.example", Some("false"))
    )
    location shouldBe "/ui/?error=not_provisioned"
    session shouldBe ""
    directory.asked.toList shouldBe List((LoginRealm.Tenant("acme"), "mallory", None))
  }

  it should "bind the tenant row a verified email names on a tenant-scope login" in {
    val directory                      = new NamedDirectory(acmeAdminByEmail)
    val (_, location, session, tokens) = callbackWith(
      OidcScope.Tenant("acme"),
      "/ui/?tenant=acme",
      "s25",
      directory.lookup,
      identity = mallory("boss@acme.example", Some("true"))
    )
    location shouldBe "/ui/?tenant=acme"
    tokens.get(session).map(_.scope) shouldBe
      Some(SessionScope(superuser = false, manageableTenants = Set("acme")))
  }

  // ---- SQL-token flow (/api/auth/sql-token) ----

  private val sqlTokenConfig =
    emptyConfig.copy(keycloak =
      emptyConfig.keycloak.copy(
        enabled = true,
        baseUrl = "https://idp.example.com/auth",
        realm = "qod",
        clientId = "qod-flightsql",
        clientSecret = "s3cret"
      )
    )

  private def sqlTokenHandlers(svc: Option[SqlTokenOidcService]): AuthHandlers =
    new AuthHandlers(
      authService = dummyAuthService,
      tokens = new SessionTokenStore,
      identitySource = ManagementIdentitySource.Oidc,
      grantsForIdentity = (_, _, _) => Nil,
      authModeResolver = new ManagementAuthModeResolver(_ => None, ManagementAuthMode.Db),
      sqlToken = svc
    )

  private val sqlTokenDiscovery = new OidcDiscovery(httpGet =
    _ =>
      Right(
        """|{"issuer":"https://idp.example.com/auth/realms/qod",
           |"authorization_endpoint":"https://idp.example.com/auth/realms/qod/protocol/openid-connect/auth",
           |"token_endpoint":"https://idp.example.com/auth/realms/qod/protocol/openid-connect/token",
           |"jwks_uri":"https://idp.example.com/auth/realms/qod/protocol/openid-connect/certs"}""".stripMargin
      )
  )

  private def sqlTokenSvc(
      exchange: (String, String) => Either[String, String]
  ): SqlTokenOidcService =
    new SqlTokenOidcService(
      sqlTokenConfig,
      () => "https://gw.example",
      "state-secret-aaaaaaaaaaaaaaaaaaaaaaaa",
      sqlTokenDiscovery,
      httpExchange = exchange
    )

  "sqlTokenStart" should "302 to the IdP and set the state cookie when a provider is enabled" in:
    val (status, location, cookie) =
      sqlTokenHandlers(Some(sqlTokenSvc((_, _) => Right("{}")))).sqlTokenStart(None).unsafeRunSync()
    status shouldBe StatusCode.Found
    location should startWith(
      "https://idp.example.com/auth/realms/qod/protocol/openid-connect/auth?"
    )
    cookie.value should not be empty

  it should "302 to an error redirect when no provider is configured" in:
    val (status, location, _) = sqlTokenHandlers(None).sqlTokenStart(None).unsafeRunSync()
    status shouldBe StatusCode.Found
    location should include("error=oauth_not_configured")

  "sqlTokenCallback" should "render the token on a successful exchange" in:
    val svc        = sqlTokenSvc((_, _) => Right("""{"id_token":"the-token"}"""))
    val (_, state) = svc.startUrl().toOption.get
    val (html, _)  =
      sqlTokenHandlers(Some(svc))
        .sqlTokenCallback(Some("code"), Some(state), None, Some(state), None)
        .unsafeRunSync()
    html should include("the-token")
    html should include("token=the-token")

  it should "render an error page when the IdP returns an error param" in:
    val (html, _) = sqlTokenHandlers(None)
      .sqlTokenCallback(None, None, Some("access_denied"), None, None)
      .unsafeRunSync()
    html should include("Login failed")
    html should include("access_denied")
