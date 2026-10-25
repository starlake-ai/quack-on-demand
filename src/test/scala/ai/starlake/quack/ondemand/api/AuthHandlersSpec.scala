package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{
  AuthFailure,
  AuthScope,
  AuthenticatedProfile,
  AuthenticationService,
  DatabaseAuthenticator
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
import ai.starlake.quack.ondemand.auth.{
  GrantsLookup,
  LoginRealm,
  ManagementAuthMode,
  ManagementAuthModeResolver,
  ManagementIdentitySource,
  SessionScope
}
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.UserGrant
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode
import sttp.model.headers.CookieValueWithMeta

/** Unit tests for [[AuthHandlers]]. */
class AuthHandlersSpec extends AnyFlatSpec with Matchers:

  // The authMethod of a profile the database authenticator produced: the only profile that is its
  // own grant (the qodstate_user row whose password it verified). Any other authMethod only says
  // who the user is.
  private val DbAuthMethod = DatabaseAuthenticator.AuthMethod

  // Minimal config with all providers disabled so the parent constructor
  // builds empty chains (no I/O). The fake subclass overrides the
  // public `authenticateBasic` so the chains are never consulted.
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

  /** `result` is by-name so a test can flip the fake between two calls (the forced-password-change
    * chain: refused first, accepted after the rotation). Every existing call site passes a plain
    * value and is unaffected.
    */
  private def makeHandlers(
      result: => Either[AuthFailure, AuthenticatedProfile],
      capturedScope: scala.collection.mutable.Buffer[(AuthScope, String, String)],
      tokens: SessionTokenStore = new SessionTokenStore,
      cookieSecureOverride: Option[Boolean] = None,
      resolveTenant: String => Option[String] = _ => None,
      // Every tenant is a db tenant unless a test passes its own registry.
      loadTenant: String => Option[Tenant] = id => Some(Tenant(id = id, authProvider = "db")),
      audit: AuditRecorder = AuditRecorder.noop,
      // The rows a profile that is not the database authenticator's is looked up in. No rows
      // unless a test supplies a directory: a database-authenticator profile never reads it.
      grantsForIdentity: GrantsLookup = (_, _, _) => Nil,
      events: ManagerEventSink = ManagerEventSink.noop
  ): AuthHandlers =
    val fakeSvc = new AuthenticationService(emptyConfig, "x"):
      override val hasProviders: Boolean = true
      override def authenticateBasic(
          scope: AuthScope,
          username: String,
          password: String
      ): Either[AuthFailure, AuthenticatedProfile] =
        capturedScope += ((scope, username, password))
        result

    new AuthHandlers(
      authService = fakeSvc,
      tokens = tokens,
      identitySource = ManagementIdentitySource.Db,
      grantsForIdentity = grantsForIdentity,
      cookieSecureOverride = cookieSecureOverride,
      authModeResolver = new ManagementAuthModeResolver(loadTenant, ManagementAuthMode.Db),
      resolveTenant = resolveTenant,
      audit = audit,
      events = events
    )

  "login" should "forward tenant scope to the auth service and surface manageableTenants" in {
    val profile = AuthenticatedProfile(
      username = "alice",
      role = "admin",
      groups = Set.empty,
      claims = Map.empty,
      authMethod = DbAuthMethod,
      tenant = Some("t-abc123")
    )
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("alice", "secret", tenant = Some("t-abc123"))

    val result = h.login(req).unsafeRunSync()

    result shouldBe a[Right[?, ?]]
    // login now returns (Set-Cookie, LoginResponse); the cookie part is
    // exercised in the cookie-specific spec, here we just unpack the body.
    val (_, resp) = result.toOption.get
    // The session scope is now the source of truth; the response carries
    // tenant=None and surfaces the tenant via manageableTenants.
    resp.tenant shouldBe None
    resp.superuser shouldBe false
    resp.manageableTenants shouldBe List("t-abc123")
    resp.username shouldBe "alice"
    calls should have size 1
    calls.head._1 shouldBe AuthScope.Tenant("t-abc123")
  }

  it should "pass System scope when tenant is absent and mint a superuser session" in {
    val profile = AuthenticatedProfile(
      username = "root",
      role = "admin",
      groups = Set.empty,
      claims = Map.empty,
      authMethod = DbAuthMethod,
      tenant = None
    )
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("root", "pass", tenant = None)

    val (_, resp) = h.login(req).unsafeRunSync().toOption.get

    calls.head._1 shouldBe AuthScope.System
    resp.superuser shouldBe true
    resp.manageableTenants shouldBe empty
  }

  // The typed AuthFailure must reach the wire as its own error code: the UI
  // keys the forced-rotation screen off `password_change_required`, and a
  // generic invalid_credentials would strand a user holding a valid temp
  // password. Both login scopes are pinned.
  it should "return 401 password_change_required for a flagged user (system scope)" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Left(AuthFailure.PasswordChangeRequired), calls)

    val out = h.login(LoginRequest("flagged", "temp", tenant = None)).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.Unauthorized
        err.error shouldBe "password_change_required"
        err.message shouldBe "password change required; use POST /api/auth/change-password"
      case Right(_) => fail("expected 401")
    calls.head._1 shouldBe AuthScope.System
  }

  it should "return 401 password_change_required for a flagged user (tenant scope)" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Left(AuthFailure.PasswordChangeRequired), calls)

    val out =
      h.login(LoginRequest("flagged", "temp", tenant = Some("t-abc123"))).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.Unauthorized
        err.error shouldBe "password_change_required"
      case Right(_) => fail("expected 401")
    calls.head._1 shouldBe AuthScope.Tenant("t-abc123")
  }

  it should "keep invalid_credentials for a plain bad password" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Left(AuthFailure.InvalidCredentials("Invalid password")), calls)

    val out = h.login(LoginRequest("alice", "nope", tenant = None)).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.Unauthorized
        err.error shouldBe "invalid_credentials"
        err.message shouldBe "Invalid password"
      case Right(_) => fail("expected 401")
  }

  it should "resolve a tenant DISPLAY NAME to its surrogate id before forwarding the scope" in {
    val profile = AuthenticatedProfile(
      username = "alice",
      role = "admin",
      groups = Set.empty,
      claims = Map.empty,
      authMethod = DbAuthMethod,
      tenant = Some("t-abc123")
    )
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    // Operator typed the human-readable name "acme"; the resolver maps it to id.
    val h = makeHandlers(
      Right(profile),
      calls,
      resolveTenant = {
        case "acme"     => Some("t-abc123")
        case "t-abc123" => Some("t-abc123")
        case _          => None
      }
    )

    h.login(LoginRequest("alice", "secret", tenant = Some("acme"))).unsafeRunSync()

    calls.head._1 shouldBe AuthScope.Tenant("t-abc123")
  }

  it should "accept the tenant id directly (resolver is identity on a known id)" in {
    val profile =
      AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, Some("t-abc123"))
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h = makeHandlers(Right(profile), calls, resolveTenant = Map("t-abc123" -> "t-abc123").get)

    h.login(LoginRequest("alice", "secret", tenant = Some("t-abc123"))).unsafeRunSync()

    calls.head._1 shouldBe AuthScope.Tenant("t-abc123")
  }

  it should "fall back to the raw tenant string when the resolver does not recognize it" in {
    val profile =
      AuthenticatedProfile("bob", "admin", Set.empty, Map.empty, DbAuthMethod, Some("t-x"))
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(profile), calls, resolveTenant = _ => None)

    h.login(LoginRequest("bob", "secret", tenant = Some("unknown-tenant"))).unsafeRunSync()

    // Unresolved -> pass through verbatim so the authenticator returns a clean
    // "user not found" rather than the handler 500-ing.
    calls.head._1 shouldBe AuthScope.Tenant("unknown-tenant")
  }

  it should "coerce blank tenant string to System scope before forwarding" in {
    val profile = AuthenticatedProfile(
      username = "root",
      role = "admin",
      groups = Set.empty,
      claims = Map.empty,
      authMethod = DbAuthMethod,
      tenant = None
    )
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("root", "pass", tenant = Some("   "))

    h.login(req).unsafeRunSync()

    calls.head._1 shouldBe AuthScope.System
  }

  // ----- tenant-scoped regular users (profile-only sessions) -----
  //
  // A `role=user` principal logging into a tenant it holds a grant on now gets a
  // session instead of a flat 403: non-admin, no manageable tenants, so the SPA
  // can render the profile-only view. Everything else about the gate is
  // unchanged -- the system login and a foreign tenant still answer
  // admin_required with the same message bytes.

  private val carolProfile =
    AuthenticatedProfile("carol", "user", Set.empty, Map.empty, DbAuthMethod, Some("t1"))

  it should "mint a non-admin session for a tenant-scoped regular user" in {
    val tokens = new SessionTokenStore
    val calls  = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h      = makeHandlers(Right(carolProfile), calls, tokens)

    val out = h.login(LoginRequest("carol", "pw", tenant = Some("t1"))).unsafeRunSync()

    out match
      case Right((_, resp)) =>
        resp.admin shouldBe false
        resp.superuser shouldBe false
        resp.manageableTenants shouldBe empty
        // The minted token is NOT admin but IS a known session.
        tokens.isAdmin(resp.token) shouldBe false
        tokens.scopeOf(resp.token).isDefined shouldBe true
      case Left(e) => fail(s"expected login to succeed, got $e")
  }

  it should "still refuse a regular user on the system-scope login" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(carolProfile), calls)

    val out = h.login(LoginRequest("carol", "pw", tenant = None)).unsafeRunSync()

    val (status, err) = out.swap.toOption.get
    status shouldBe StatusCode.Forbidden
    err.error shouldBe "admin_required"
    err.message shouldBe "user 'carol' has no admin grant; manager UI is admin-only"
  }

  it should "refuse a regular user of tenant A logging into tenant B" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(carolProfile), calls)

    val out = h.login(LoginRequest("carol", "pw", tenant = Some("t2"))).unsafeRunSync()

    out.swap.toOption.get._1 shouldBe StatusCode.Forbidden
    out.swap.toOption.get._2.error shouldBe "admin_required"
  }

  it should "keep admin logins unchanged with admin=true" in {
    val profile = AuthenticatedProfile("root", "admin", Set.empty, Map.empty, DbAuthMethod, None)
    val calls   = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h       = makeHandlers(Right(profile), calls)

    val (_, resp) =
      h.login(LoginRequest("root", "pass", tenant = None)).unsafeRunSync().toOption.get

    resp.admin shouldBe true
    resp.superuser shouldBe true
  }

  it should "complete the forced-change flow into a non-admin session" in {
    // Seeded flagged: the authenticator refuses a must_change_password row even
    // with the correct temp password. Rotating the password clears the flag --
    // modeled here by flipping the fake, since the real store round trip is
    // pinned by ChangePasswordHandlerSpec. The retry lands in a session, where
    // it used to dead-end on admin_required.
    var flagged = true
    val tokens  = new SessionTokenStore
    val calls   = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h       = makeHandlers(
      if flagged then Left(AuthFailure.PasswordChangeRequired) else Right(carolProfile),
      calls,
      tokens
    )

    val first = h.login(LoginRequest("carol", "temp", tenant = Some("t1"))).unsafeRunSync()
    val (firstStatus, firstErr) = first.swap.toOption.get
    firstStatus shouldBe StatusCode.Unauthorized
    firstErr.error shouldBe "password_change_required"

    flagged = false
    val second = h.login(LoginRequest("carol", "rotated", tenant = Some("t1"))).unsafeRunSync()

    second match
      case Right((_, resp)) =>
        resp.admin shouldBe false
        tokens.isAdmin(resp.token) shouldBe false
      case Left(e) => fail(s"expected the rotated login to succeed, got $e")
  }

  // ----- tenants that sign in through their identity provider -----
  //
  // A tenant whose sign-in mode is OIDC takes no password: the login answers sso_required before
  // the authentication service is asked, so no password is checked and no lockout counter moves.
  // An unknown or misconfigured tenant still authenticates first and keeps its answers.

  private val ssoTenant = Tenant(
    id = "t-sso",
    displayName = "Acme",
    authProvider = "keycloak",
    authConfig = Map(
      "issuerUrl"       -> "https://idp.example/t-sso",
      "clientId"        -> "qod-console",
      "clientSecretRef" -> "env:T_SSO_CLIENT_SECRET"
    )
  )

  private val ssoTenants: String => Option[Tenant] = Map(ssoTenant.id -> ssoTenant).get

  // What the fake authentication service answers if it is asked: a valid admin of the tenant, so
  // a session would follow if the password were checked.
  private val ssoAdmin =
    AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, Some("t-sso"))

  it should "refuse a password login into an OIDC tenant with 400 sso_required before any password check" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(ssoAdmin), calls, loadTenant = ssoTenants)

    val out = h.login(LoginRequest("alice", "pw", tenant = Some("t-sso"))).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err shouldBe ErrorResponse(
          "sso_required",
          "tenant 't-sso' signs in through its identity provider: open /ui/?tenant=t-sso, " +
            "or use a personal access token"
        )
      case Right(_) => fail("expected 400 sso_required, got a session")
    calls shouldBe empty
  }

  it should "name the tenant as typed in the sso_required message but link its resolved id, checking the mode on that id" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(
      Right(ssoAdmin),
      calls,
      resolveTenant = { case "Acme" => Some("t-sso"); case _ => None },
      loadTenant = ssoTenants
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = Some("  Acme "))).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err.error shouldBe "sso_required"
        err.message shouldBe (
          "tenant 'Acme' signs in through its identity provider: open /ui/?tenant=t-sso, " +
            "or use a personal access token"
        )
      case Right(_) => fail("expected 400 sso_required, got a session")
    calls shouldBe empty
  }

  // Tenant ids are lowercase slugs, and the login resolves a typed case variant to the id. The
  // tenant below is `ssoTenant` under such an id.
  private val acmeSso = ssoTenant.copy(id = "acme")

  it should "link the exact tenant id in sso_required when the typed value is a case variant of it" in {
    // The link must open a login the tenant-scoped endpoints resolve: it carries the id the login
    // resolved ('acme'), not the typed 'ACME'.
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(
      Right(ssoAdmin),
      calls,
      resolveTenant = { case "ACME" => Some("acme"); case _ => None },
      loadTenant = Map(acmeSso.id -> acmeSso).get
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = Some("ACME"))).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err.error shouldBe "sso_required"
        err.message shouldBe (
          "tenant 'ACME' signs in through its identity provider: open /ui/?tenant=acme, " +
            "or use a personal access token"
        )
      case Right(_) => fail("expected 400 sso_required, got a session")
    calls shouldBe empty
    // The id in the link is one the public mode endpoint answers oidc for.
    h.authMode(Some("acme")).unsafeRunSync() shouldBe Right(AuthModeResponse("oidc", ""))
  }

  it should "URL-encode the resolved tenant id in the sso_required link" in {
    // Ids are slugs in practice; the link is built so that no id can break out of the query.
    val odd   = ssoTenant.copy(id = "t sso&x")
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(ssoAdmin), calls, loadTenant = Map(odd.id -> odd).get)

    val out = h.login(LoginRequest("alice", "pw", tenant = Some("t sso&x"))).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err.error shouldBe "sso_required"
        err.message shouldBe (
          "tenant 't sso&x' signs in through its identity provider: open /ui/?tenant=t+sso%26x, " +
            "or use a personal access token"
        )
      case Right(_) => fail("expected 400 sso_required, got a session")
    calls shouldBe empty
  }

  it should "audit the sso_required refusal as a denied login with reason sso_required, under the resolved tenant id" in {
    // A padded, case-variant typed value still records the tenant id, not the typed text.
    val telemetry = new RecordingTelemetryStore
    val calls     = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h         = makeHandlers(
      Right(ssoAdmin),
      calls,
      resolveTenant = { case "ACME" => Some("acme"); case _ => None },
      loadTenant = Map(acmeSso.id -> acmeSso).get,
      audit = new AuditRecorder(telemetry, _ => None)
    )

    h.login(LoginRequest("alice", "pw", tenant = Some("  ACME "))).unsafeRunSync()

    telemetry.events should have size 1
    val e = telemetry.events.head
    e.family shouldBe "auth"
    e.action shouldBe "auth.login.failure"
    e.outcome shouldBe "denied"
    e.actor shouldBe "alice"
    e.tenant shouldBe Some("acme")
    e.detail shouldBe Map("username" -> "alice", "reason" -> "sso_required")
  }

  it should "keep authenticating an unknown tenant first (401 invalid_credentials)" in {
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(
      Left(AuthFailure.InvalidCredentials("invalid credentials")),
      calls,
      loadTenant = _ => None
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = Some("t-ghost"))).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.Unauthorized
        err.error shouldBe "invalid_credentials"
      case Right(_) => fail("expected 401 invalid_credentials")
    calls.map(_._1).toList shouldBe List(AuthScope.Tenant("t-ghost"))
  }

  it should "keep authenticating a misconfigured OIDC tenant first (400 tenant_oidc_misconfigured)" in {
    val misconfigured =
      Tenant(id = "t-bad", authProvider = "keycloak", authConfig = Map("clientId" -> "c"))
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(
      Right(
        AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, Some("t-bad"))
      ),
      calls,
      loadTenant = Map(misconfigured.id -> misconfigured).get
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = Some("t-bad"))).unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err.error shouldBe "tenant_oidc_misconfigured"
      case Right(_) => fail("expected 400 tenant_oidc_misconfigured")
    calls should have size 1
  }

  it should "keep the password login of a db tenant, whatever OIDC keys its config carries" in {
    // The provider decides the mode, not leftover keys in the config.
    val dbTenant = Tenant(
      id = "t-db",
      authProvider = "db",
      authConfig = Map(
        "issuerUrl"       -> "https://idp.example/t-db",
        "clientId"        -> "c",
        "clientSecretRef" -> "env:T_DB_CLIENT_SECRET"
      )
    )
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(
      Right(
        AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, Some("t-db"))
      ),
      calls,
      loadTenant = Map(dbTenant.id -> dbTenant).get
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = Some("t-db"))).unsafeRunSync()

    out match
      case Right((_, resp)) => resp.manageableTenants shouldBe List("t-db")
      case Left(e)          => fail(s"expected the db tenant login to succeed, got $e")
    calls should have size 1
  }

  // ----- a password login answered by an identity provider -----
  //
  // With the manager-wide Keycloak or Azure provider enabled, the basic chain also holds its
  // password-grant authenticators. Their profile carries no tenant and the IdP's role claim, and it
  // only proves who the user is: the rights come from the qodstate_user rows of the realm the login
  // names (a tenant login: that tenant's rows; no tenant: every row of the identity, as the bare
  // SSO login reads them), exactly as for an SSO login. The IdP's role claim grants nothing. Only a
  // profile the database authenticator produced is its own grant.

  private val AcmeId = "t-acme"

  /** The profile of a manager-wide password-grant backend: no tenant, the IdP's role claim (admin
    * unless a test says otherwise), and the claims `ResourceOwnerPasswordAuthenticator` carries
    * when the token has them: the email, the `preferred_username` and `email_verified`.
    */
  private def passwordGrantProfile(
      username: String = "alice",
      authMethod: String = "keycloak-ropc",
      role: String = "admin",
      email: Option[String] = None,
      preferredUsername: Option[String] = None,
      emailVerified: Option[String] = None
  ): AuthenticatedProfile =
    AuthenticatedProfile(
      username = username,
      role = role,
      groups = Set(role),
      claims = Map("sub" -> username, "role" -> role, "auth_method" -> authMethod) ++
        email.map("email" -> _) ++ preferredUsername.map("preferred_username" -> _) ++
        emailVerified.map("email_verified" -> _),
      authMethod = authMethod,
      tenant = None
    )

  /** A user directory keyed by (realm, identity) that records every lookup it is asked (realm,
    * identity, email claim). A key it does not hold answers no rows, like an identity with no row
    * in that realm. Like `UserStore.grantsForIdentity` it falls back to the email it is handed only
    * when the identity matches no row, so a handler that hands one over can be seen binding it.
    */
  private final class RecordingDirectory(rows: Map[(LoginRealm, String), List[UserGrant]]):
    val asked: scala.collection.mutable.ListBuffer[(LoginRealm, String, Option[String])] =
      scala.collection.mutable.ListBuffer.empty
    val lookup: GrantsLookup = (realm, identity, email) =>
      asked += ((realm, identity, email))
      rows.getOrElse(
        (realm, identity),
        email.filter(_ != identity).flatMap(e => rows.get((realm, e))).getOrElse(Nil)
      )

  private type LoginOutcome =
    Either[(StatusCode, ErrorResponse), (CookieValueWithMeta, LoginResponse)]

  private def sessionOf(out: LoginOutcome): LoginResponse =
    out match
      case Right((_, resp))    => resp
      case Left((status, err)) => fail(s"expected a session, got $status ${err.error}")

  // A refusal is named by its status and error code only: a response can hold a token.
  private def refusalOf(out: LoginOutcome): (StatusCode, String) =
    out match
      case Left((status, err)) => (status, err.error)
      case Right((_, resp))    =>
        fail(
          s"expected a refusal, got a session (superuser=${resp.superuser}, " +
            s"manages ${resp.manageableTenants})"
        )

  private def noCalls() = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]

  "login (password grant)" should "refuse a tenant login with 403 not_provisioned when the identity has no row in the tenant, whatever the IdP role claim says" in
    Seq("keycloak-ropc", "azure-ropc").foreach { method =>
      withClue(s"[$method] ") {
        val directory = new RecordingDirectory(Map.empty)
        val h         = makeHandlers(
          Right(passwordGrantProfile(authMethod = method)),
          noCalls(),
          grantsForIdentity = directory.lookup
        )

        val out = h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync()

        refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
      }
    }

  it should "never bind a superuser row on a tenant login: a superuser of the same name is still not_provisioned" in {
    // The directory answers the superuser row to the tenant's realm as well, as a store that
    // ignored the realm would: only the handler's own realm filter keeps it out.
    val superuserRow = List(UserGrant(None, "admin"))
    val directory    = new RecordingDirectory(
      Map(
        (LoginRealm.System, "alice")         -> superuserRow,
        (LoginRealm.Tenant(AcmeId), "alice") -> superuserRow
      )
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync()

    refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
  }

  it should "not take an admin row of another tenant for a grant on the tenant it logs into" in {
    val directory = new RecordingDirectory(
      Map((LoginRealm.Tenant(AcmeId), "alice") -> List(UserGrant(Some("t-globex"), "admin")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync()

    refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
  }

  it should "mint a session managing exactly the tenant for an admin row of that tenant, and name the tenant on the session profile" in {
    val tokens    = new SessionTokenStore
    val directory = new RecordingDirectory(
      Map((LoginRealm.Tenant(AcmeId), "alice") -> List(UserGrant(Some(AcmeId), "admin")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      tokens,
      grantsForIdentity = directory.lookup
    )

    val resp =
      sessionOf(h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync())

    resp.superuser shouldBe false
    resp.admin shouldBe true
    resp.manageableTenants shouldBe List(AcmeId)
    val session = tokens.get(resp.token).getOrElse(fail("the minted token does not resolve"))
    session.scope shouldBe SessionScope(superuser = false, manageableTenants = Set(AcmeId))
    // A personal access token minted from this session resolves its owning row by
    // (profile.tenant, username): the profile must name the realm the login was verified in.
    session.profile.tenant shouldBe Some(AcmeId)
  }

  it should "keep the tenant's own row alone when the directory answers the rows of every realm" in {
    val directory = new RecordingDirectory(
      Map(
        (LoginRealm.Tenant(AcmeId), "alice") -> List(
          UserGrant(None, "admin"),
          UserGrant(Some(AcmeId), "admin"),
          UserGrant(Some("t-globex"), "admin")
        )
      )
    )
    val tokens = new SessionTokenStore
    val h      = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      tokens,
      grantsForIdentity = directory.lookup
    )

    val resp =
      sessionOf(h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync())

    tokens.scopeOf(resp.token) shouldBe
      Some(SessionScope(superuser = false, manageableTenants = Set(AcmeId)))
  }

  it should "mint a profile-only session for a regular user's row of the tenant, and demote the IdP admin claim" in {
    val tokens    = new SessionTokenStore
    val directory = new RecordingDirectory(
      Map((LoginRealm.Tenant(AcmeId), "alice") -> List(UserGrant(Some(AcmeId), "user")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      tokens,
      grantsForIdentity = directory.lookup
    )

    val resp =
      sessionOf(h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync())

    resp.admin shouldBe false
    resp.superuser shouldBe false
    resp.manageableTenants shouldBe empty
    tokens.isAdmin(resp.token) shouldBe false
    val session = tokens.get(resp.token).getOrElse(fail("the minted token does not resolve"))
    // The IdP said admin; the row says user.
    session.profile.role shouldBe "user"
    session.profile.tenant shouldBe Some(AcmeId)
  }

  it should "refuse a login without tenant with 403 not_provisioned when the identity has no row at all, whatever the IdP role claim says" in {
    val directory = new RecordingDirectory(Map.empty)
    val h         = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = None)).unsafeRunSync()

    refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
  }

  it should "mint a superuser session on a login without tenant only for a superuser row" in {
    val directory = new RecordingDirectory(
      Map((LoginRealm.System, "alice") -> List(UserGrant(None, "admin")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile(role = "user")), // the IdP claim says user: the row decides
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val resp = sessionOf(h.login(LoginRequest("alice", "pw", tenant = None)).unsafeRunSync())

    resp.superuser shouldBe true
    resp.manageableTenants shouldBe empty
  }

  it should "build a multi-tenant admin session, not a superuser one, from tenant admin rows on a login without tenant" in {
    val directory = new RecordingDirectory(
      Map(
        (LoginRealm.System, "alice") -> List(
          UserGrant(Some("t-a"), "admin"),
          UserGrant(Some("t-b"), "admin")
        )
      )
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val resp = sessionOf(h.login(LoginRequest("alice", "pw", tenant = None)).unsafeRunSync())

    resp.superuser shouldBe false
    resp.admin shouldBe true
    resp.manageableTenants shouldBe List("t-a", "t-b")
  }

  it should "refuse a login without tenant with 403 admin_required when the identity holds regular user rows only" in {
    val directory = new RecordingDirectory(
      Map((LoginRealm.System, "alice") -> List(UserGrant(Some(AcmeId), "user")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val out = h.login(LoginRequest("alice", "pw", tenant = None)).unsafeRunSync()

    refusalOf(out) shouldBe ((StatusCode.Forbidden, "admin_required"))
  }

  it should "keep a database-authenticator profile as its own grant and never read the directory" in {
    val directory = new RecordingDirectory(Map.empty)
    def login(profile: AuthenticatedProfile, tenant: Option[String]): LoginResponse =
      sessionOf(
        makeHandlers(Right(profile), noCalls(), grantsForIdentity = directory.lookup)
          .login(LoginRequest(profile.username, "pw", tenant))
          .unsafeRunSync()
      )

    val tenantAdmin = login(
      AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, Some(AcmeId)),
      Some(AcmeId)
    )
    val regularUser = login(
      AuthenticatedProfile("carol", "user", Set.empty, Map.empty, DbAuthMethod, Some(AcmeId)),
      Some(AcmeId)
    )
    val superuser = login(
      AuthenticatedProfile("root", "admin", Set.empty, Map.empty, DbAuthMethod, None),
      None
    )

    tenantAdmin.manageableTenants shouldBe List(AcmeId)
    tenantAdmin.superuser shouldBe false
    regularUser.admin shouldBe false
    superuser.superuser shouldBe true
    directory.asked shouldBe empty
  }

  it should "look the identity up by its user name alone, in the realm the login names: the tenant's for a tenant login, the system realm without one" in {
    // The profile carries an email claim; a password login hands none to the lookup.
    val directory = new RecordingDirectory(Map.empty)
    val h         = makeHandlers(
      Right(passwordGrantProfile(email = Some("alice@acme.example"))),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync()
    h.login(LoginRequest("alice", "pw", tenant = None)).unsafeRunSync()

    directory.asked.toList shouldBe List(
      (LoginRealm.Tenant(AcmeId), "alice", None),
      (LoginRealm.System, "alice", None)
    )
  }

  it should "not bind the superuser row that the email claim of the IdP account names on a login without tenant" in {
    // The account's email is whatever its holder typed: it names the seeded superuser, the user
    // name 'mallory' matches no row.
    val directory = new RecordingDirectory(
      Map((LoginRealm.System, "admin@localhost.local") -> List(UserGrant(None, "admin")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile(username = "mallory", email = Some("admin@localhost.local"))),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val out = h.login(LoginRequest("mallory", "pw", tenant = None)).unsafeRunSync()

    refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
    directory.asked.toList shouldBe List((LoginRealm.System, "mallory", None))
  }

  it should "not bind the tenant row that the email claim of the IdP account names on a tenant login" in {
    val directory = new RecordingDirectory(
      Map(
        (LoginRealm.Tenant(AcmeId), "boss@acme.example") -> List(UserGrant(Some(AcmeId), "admin"))
      )
    )
    val h = makeHandlers(
      Right(passwordGrantProfile(username = "mallory", email = Some("boss@acme.example"))),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val out = h.login(LoginRequest("mallory", "pw", tenant = Some(AcmeId))).unsafeRunSync()

    refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
    directory.asked.toList shouldBe List((LoginRealm.Tenant(AcmeId), "mallory", None))
  }

  it should "refuse a database-authenticator profile without tenant on a tenant login: a superuser grant never opens one" in {
    // A database tenant login always carries the tenant row it verified. A profile that does not
    // (it is not what the authenticator produces) fails closed instead of opening the tenant as a
    // superuser.
    val h = makeHandlers(
      Right(AuthenticatedProfile("root", "admin", Set.empty, Map.empty, DbAuthMethod, None)),
      noCalls()
    )

    val out = h.login(LoginRequest("root", "pw", tenant = Some(AcmeId))).unsafeRunSync()

    refusalOf(out) shouldBe ((StatusCode.Forbidden, "admin_required"))
  }

  it should "look the identity up in the realm of the tenant id when the login named the tenant another way" in {
    val directory = new RecordingDirectory(Map.empty)
    val h         = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      resolveTenant = { case "Acme Corp" => Some(AcmeId); case _ => None },
      grantsForIdentity = directory.lookup
    )

    h.login(LoginRequest("alice", "pw", tenant = Some("Acme Corp"))).unsafeRunSync()

    directory.asked.toList.map(_._1) shouldBe List(LoginRealm.Tenant(AcmeId))
  }

  it should "name the tenant on the SessionOpened event and the audit row of a tenant login" in {
    val telemetry              = new RecordingTelemetryStore
    val opened                 = scala.collection.mutable.ListBuffer.empty[ManagerEvent]
    val sink: ManagerEventSink = e => { opened += e; () }
    val directory              = new RecordingDirectory(
      Map((LoginRealm.Tenant(AcmeId), "alice") -> List(UserGrant(Some(AcmeId), "admin")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      audit = new AuditRecorder(telemetry, _ => None),
      grantsForIdentity = directory.lookup,
      events = sink
    )

    h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync()

    opened.toList should contain(ManagerEvent.SessionOpened(AcmeId, "alice", "rest"))
    telemetry.events should have size 1
    val e = telemetry.events.head
    e.action shouldBe "auth.login"
    e.outcome shouldBe "ok"
    e.tenant shouldBe Some(AcmeId)
    e.detail("authMethod") shouldBe "keycloak-ropc"
  }

  it should "audit a not_provisioned refusal of a tenant login under that tenant" in {
    val telemetry = new RecordingTelemetryStore
    val h         = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      audit = new AuditRecorder(telemetry, _ => None),
      grantsForIdentity = new RecordingDirectory(Map.empty).lookup
    )

    h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync()

    telemetry.events should have size 1
    val e = telemetry.events.head
    e.action shouldBe "auth.login.failure"
    e.outcome shouldBe "denied"
    e.tenant shouldBe Some(AcmeId)
    e.detail shouldBe Map("reason" -> "not_provisioned")
  }

  it should "leave the session profile tenant empty on a login without tenant" in {
    val tokens    = new SessionTokenStore
    val directory = new RecordingDirectory(
      Map((LoginRealm.System, "alice") -> List(UserGrant(None, "admin")))
    )
    val h = makeHandlers(
      Right(passwordGrantProfile()),
      noCalls(),
      tokens,
      grantsForIdentity = directory.lookup
    )

    val resp = sessionOf(h.login(LoginRequest("alice", "pw", tenant = None)).unsafeRunSync())

    tokens.get(resp.token).map(_.profile.tenant) shouldBe Some(None)
  }

  // ----- a user name that is the email claim -----
  //
  // Both IdP authenticators name the user preferred_username, else the email claim, else the
  // subject. A token without preferred_username is therefore named by its email, which its account
  // holder may have typed: that name binds a row only when the IdP marks the address verified
  // (email_verified true). The seeded superuser admin@localhost.local is the row to protect.

  private val SeededSuperuser = "admin@localhost.local"

  private def superuserRow = Map(
    (LoginRealm.System, SeededSuperuser) -> List(UserGrant(None, "admin"))
  )

  it should "refuse a login without tenant whose user name is the email claim when the IdP does not mark it verified: the superuser row of that name stays unbound" in
    Seq(None, Some("false")).foreach { verified =>
      withClue(s"[email_verified=$verified] ") {
        val directory = new RecordingDirectory(superuserRow)
        val h         = makeHandlers(
          Right(
            passwordGrantProfile(
              username = SeededSuperuser,
              email = Some(SeededSuperuser),
              emailVerified = verified
            )
          ),
          noCalls(),
          grantsForIdentity = directory.lookup
        )

        val out = h.login(LoginRequest(SeededSuperuser, "pw", tenant = None)).unsafeRunSync()

        refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
        directory.asked shouldBe empty
      }
    }

  it should "bind the superuser row of a user name that is the email claim once the IdP marks the address verified (email_verified true)" in {
    val directory = new RecordingDirectory(superuserRow)
    val h         = makeHandlers(
      Right(
        passwordGrantProfile(
          username = SeededSuperuser,
          email = Some(SeededSuperuser),
          emailVerified = Some("true")
        )
      ),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val resp =
      sessionOf(h.login(LoginRequest(SeededSuperuser, "pw", tenant = None)).unsafeRunSync())

    resp.superuser shouldBe true
    directory.asked.toList shouldBe List((LoginRealm.System, SeededSuperuser, None))
  }

  it should "refuse a tenant login whose user name is an unverified email claim, and bind the tenant's row once the address is verified" in {
    val boss      = "boss@acme.example"
    val directory = new RecordingDirectory(
      Map((LoginRealm.Tenant(AcmeId), boss) -> List(UserGrant(Some(AcmeId), "admin")))
    )
    def login(verified: Option[String]): LoginOutcome =
      makeHandlers(
        Right(passwordGrantProfile(username = boss, email = Some(boss), emailVerified = verified)),
        noCalls(),
        grantsForIdentity = directory.lookup
      ).login(LoginRequest(boss, "pw", tenant = Some(AcmeId))).unsafeRunSync()

    refusalOf(login(None)) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
    refusalOf(login(Some("false"))) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
    directory.asked shouldBe empty
    sessionOf(login(Some("true"))).manageableTenants shouldBe List(AcmeId)
  }

  it should "count only the exact string true as verified: True, 1 and yes are not" in
    Seq("True", "TRUE", "1", "yes", "").foreach { verified =>
      withClue(s"[email_verified=$verified] ") {
        val h = makeHandlers(
          Right(
            passwordGrantProfile(
              username = SeededSuperuser,
              email = Some(SeededSuperuser),
              emailVerified = Some(verified)
            )
          ),
          noCalls(),
          grantsForIdentity = new RecordingDirectory(superuserRow).lookup
        )

        val out = h.login(LoginRequest(SeededSuperuser, "pw", tenant = None)).unsafeRunSync()

        refusalOf(out) shouldBe ((StatusCode.Forbidden, "not_provisioned"))
      }
    }

  it should "leave a user name the IdP supplied as preferred_username alone, whatever its email claim says" in {
    // preferred_username is the IdP account's name, assigned by an administrator: only a name that
    // fell back to the email claim is a value its holder may have typed.
    val boss      = "boss@acme.example"
    val directory = new RecordingDirectory(
      Map((LoginRealm.Tenant(AcmeId), boss) -> List(UserGrant(Some(AcmeId), "admin")))
    )
    val h = makeHandlers(
      Right(
        passwordGrantProfile(
          username = boss,
          email = Some(boss),
          preferredUsername = Some(boss),
          emailVerified = Some("false")
        )
      ),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val resp = sessionOf(h.login(LoginRequest(boss, "pw", tenant = Some(AcmeId))).unsafeRunSync())

    resp.manageableTenants shouldBe List(AcmeId)
  }

  it should "leave a user name that is not the email claim alone: an email claim of another address does not matter" in {
    val directory = new RecordingDirectory(
      Map((LoginRealm.Tenant(AcmeId), "alice") -> List(UserGrant(Some(AcmeId), "admin")))
    )
    val h = makeHandlers(
      Right(
        passwordGrantProfile(email = Some("alice@acme.example"), emailVerified = Some("false"))
      ),
      noCalls(),
      grantsForIdentity = directory.lookup
    )

    val resp =
      sessionOf(h.login(LoginRequest("alice", "pw", tenant = Some(AcmeId))).unsafeRunSync())

    resp.manageableTenants shouldBe List(AcmeId)
  }

  "whoami" should "surface the tenant and scope from the stored session" in {
    val profile = AuthenticatedProfile(
      username = "alice",
      role = "admin",
      groups = Set.empty,
      claims = Map.empty,
      authMethod = DbAuthMethod,
      tenant = Some("t-abc123")
    )
    val store = new SessionTokenStore
    val token = store.mintWithScope(
      profile,
      SessionScope(superuser = false, manageableTenants = Set("t-abc123"))
    )
    val calls      = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val hWithStore = makeHandlers(Right(profile), calls, store)

    // whoami now accepts (apiKey, cookie); test exercises the header path.
    val result = hWithStore.whoami(Some(token), None).unsafeRunSync()

    result shouldBe a[Right[?, ?]]
    val resp = result.toOption.get
    resp.tenant shouldBe Some("t-abc123")
    resp.username shouldBe "alice"
    resp.superuser shouldBe false
    resp.manageableTenants shouldBe List("t-abc123")
  }

  it should "return a no_session error when neither header nor cookie carries a token" in {
    val store    = new SessionTokenStore
    val calls    = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val handlers = makeHandlers(
      Right(AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, None)),
      calls,
      store
    )
    val result = handlers.whoami(None, None).unsafeRunSync()
    result shouldBe a[Left[?, ?]]
    val (status, err) = result.swap.toOption.get
    status shouldBe StatusCode.Unauthorized
    err.error shouldBe "no_session"
  }

  it should "return an invalid error when the token is malformed" in {
    val store    = new SessionTokenStore
    val calls    = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val handlers = makeHandlers(
      Right(AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, None)),
      calls,
      store
    )
    val result        = handlers.whoami(Some("definitely-not-a-jwt"), None).unsafeRunSync()
    val (status, err) = result.swap.toOption.get
    status shouldBe StatusCode.Unauthorized
    err.error shouldBe "invalid"
  }

  it should "return an invalid error when the token's signature does not verify" in {
    // Token minted by a DIFFERENT secret -- the most common real-world cause of confusion,
    // which used to surface as "expired" and mislead operators.
    val foreign = new SessionTokenStore(secret = "stranger-secret-padding-padding-padding-padding=")
    val token   = foreign.mintWithScope(
      AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, None),
      SessionScope(superuser = true, manageableTenants = Set.empty)
    )
    val store    = new SessionTokenStore
    val calls    = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val handlers = makeHandlers(
      Right(AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, None)),
      calls,
      store
    )
    val result = handlers.whoami(Some(token), None).unsafeRunSync()
    result.swap.toOption.get._2.error shouldBe "invalid"
  }

  it should "return a revoked error when the jti is on the denylist" in {
    val store   = new SessionTokenStore
    val profile = AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, None)
    val token   =
      store.mintWithScope(profile, SessionScope(superuser = true, manageableTenants = Set.empty))
    store.revoke(token)
    val calls    = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val handlers = makeHandlers(Right(profile), calls, store)
    val result   = handlers.whoami(Some(token), None).unsafeRunSync()
    result.swap.toOption.get._2.error shouldBe "revoked"
  }

  // ----- cookie Secure auto-derive from request scheme -----
  //
  // Default behaviour (no operator override): pick `secure=true` only when the request actually
  // arrived over HTTPS, as evidenced by the `X-Forwarded-Proto` header injected by a TLS-terminating
  // ingress. Plaintext HTTP requests (no header, or `http`) get `secure=false` so the browser
  // doesn't silently drop the Set-Cookie. This makes `run-jar.sh` on HTTP and helm behind a TLS
  // ingress both Just Work without an env var.

  private val testProfile =
    AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, DbAuthMethod, None)
  private val testReq = LoginRequest("alice", "secret", tenant = None)

  "login cookie Secure flag" should "derive secure=true from X-Forwarded-Proto: https when no override is set" in {
    val calls       = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h           = makeHandlers(Right(testProfile), calls, cookieSecureOverride = None)
    val (cookie, _) = h.login(testReq, forwardedProto = Some("https")).unsafeRunSync().toOption.get
    cookie.secure shouldBe true
  }

  it should "derive secure=false from X-Forwarded-Proto: http when no override is set" in {
    val calls       = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h           = makeHandlers(Right(testProfile), calls, cookieSecureOverride = None)
    val (cookie, _) = h.login(testReq, forwardedProto = Some("http")).unsafeRunSync().toOption.get
    cookie.secure shouldBe false
  }

  it should "default secure=false when no override AND no X-Forwarded-Proto (run-jar.sh on http://localhost)" in {
    val calls       = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h           = makeHandlers(Right(testProfile), calls, cookieSecureOverride = None)
    val (cookie, _) = h.login(testReq, forwardedProto = None).unsafeRunSync().toOption.get
    cookie.secure shouldBe false
  }

  it should "honor Some(true) override regardless of the request scheme" in {
    val calls       = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h           = makeHandlers(Right(testProfile), calls, cookieSecureOverride = Some(true))
    val (cookie, _) = h.login(testReq, forwardedProto = Some("http")).unsafeRunSync().toOption.get
    cookie.secure shouldBe true
  }

  it should "honor Some(false) override even when the request was https" in {
    val calls       = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h           = makeHandlers(Right(testProfile), calls, cookieSecureOverride = Some(false))
    val (cookie, _) = h.login(testReq, forwardedProto = Some("https")).unsafeRunSync().toOption.get
    cookie.secure shouldBe false
  }

  it should "match scheme case-insensitively (HTTPS / Https / https all imply secure)" in {
    val calls       = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h           = makeHandlers(Right(testProfile), calls, cookieSecureOverride = None)
    val (cookie, _) = h.login(testReq, forwardedProto = Some("HTTPS")).unsafeRunSync().toOption.get
    cookie.secure shouldBe true
  }

  // ----- OIDC management identity source -----

  private def oidcHandlers(
      directory: Map[String, List[UserGrant]],
      profile: AuthenticatedProfile = AuthenticatedProfile(
        username = "alice@corp",
        role = "user", // claim role MUST be ignored
        groups = Set.empty,
        // The IdP names the account alice@corp (preferred_username), as Keycloak's tokens do.
        claims = Map("preferred_username" -> "alice@corp", "email" -> "alice@corp"),
        authMethod = "keycloak-ropc",
        tenant = None
      ),
      // Every pre-existing caller logs in with tenant=None (System scope), which
      // never consults this -- modeFor(None) short-circuits to systemMode. A
      // tenant-scoped login needs a real lookup to resolve to Oidc mode instead
      // of 400 tenant_unknown.
      loadTenant: String => Option[Tenant] = _ => None,
      // Exposed so a caller can inspect the minted token (isAdmin / scopeOf);
      // every other caller only looks at the LoginResponse.
      tokens: SessionTokenStore = new SessionTokenStore
  ): AuthHandlers =
    val fakeSvc = new AuthenticationService(emptyConfig, "x"):
      override val hasProviders: Boolean = true
      override def authenticateBasic(
          scope: AuthScope,
          username: String,
          password: String
      ): Either[AuthFailure, AuthenticatedProfile] = Right(profile)

    val fakeDirectory: GrantsLookup = (_, identity, email) =>
      directory.getOrElse(
        identity,
        email.flatMap(directory.get).getOrElse(Nil)
      )

    new AuthHandlers(
      authService = fakeSvc,
      tokens = tokens,
      identitySource = ManagementIdentitySource.Oidc,
      grantsForIdentity = fakeDirectory,
      authModeResolver = new ManagementAuthModeResolver(loadTenant, ManagementAuthMode.Oidc)
    )

  "login (oidc)" should "treat tenant=NULL admin row as superuser, ignoring JWT role" in {
    val h   = oidcHandlers(directory = Map("alice@corp" -> List(UserGrant(None, "admin"))))
    val out = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    val (_, r) = out.toOption.get
    r.superuser shouldBe true
    r.manageableTenants shouldBe empty
    r.tenant shouldBe None
  }

  it should "build a multi-tenant admin session when only tenant rows match" in {
    val h = oidcHandlers(
      directory = Map(
        "alice@corp" -> List(
          UserGrant(Some("t-a"), "admin"),
          UserGrant(Some("t-b"), "admin")
        )
      )
    )
    val (_, r) = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync().toOption.get
    r.superuser shouldBe false
    r.manageableTenants.toSet shouldBe Set("t-a", "t-b")
  }

  it should "not fall back to the email claim on a password login: only the authenticated user name is matched" in {
    // The same shape as the SSO email fallback (AuthHandlersOidcSpec), but a password-grant account's
    // email is not vouched for: the row the email names is not bound, the user name matches none.
    val profile = AuthenticatedProfile(
      username = "alice@corp",
      role = "user",
      groups = Set.empty,
      claims = Map("email" -> "alice@corporate.example"),
      authMethod = "keycloak-ropc",
      tenant = None
    )
    val h = oidcHandlers(
      directory = Map("alice@corporate.example" -> List(UserGrant(None, "admin"))),
      profile = profile
    )
    val out = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync()
    out.swap.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Forbidden, "not_provisioned"))
  }

  it should "403 not_provisioned when the directory yields no grants" in {
    val h   = oidcHandlers(directory = Map.empty)
    val out = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    val (code, err) = out.swap.toOption.get
    code shouldBe StatusCode.Forbidden
    err.error shouldBe "not_provisioned"
  }

  it should "403 admin_required when no admin grant exists" in {
    val h = oidcHandlers(
      directory = Map("alice@corp" -> List(UserGrant(Some("t-a"), "user")))
    )
    val out         = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync()
    val (code, err) = out.swap.toOption.get
    code shouldBe StatusCode.Forbidden
    err.error shouldBe "admin_required"
  }

  it should "discard the JWT role claim entirely (admin claim does NOT mint superuser)" in {
    val profile = AuthenticatedProfile(
      username = "imposter",
      role = "admin",
      groups = Set("admin"),
      claims = Map("preferred_username" -> "imposter", "email" -> "imposter"),
      authMethod = "keycloak-ropc",
      tenant = None
    )
    val h           = oidcHandlers(directory = Map.empty, profile = profile)
    val out         = h.login(LoginRequest("imposter", "p")).unsafeRunSync()
    val (code, err) = out.swap.toOption.get
    code shouldBe StatusCode.Forbidden
    err.error shouldBe "not_provisioned"
  }

  it should "refuse a tenant password login under identitySource=oidc when that tenant signs in through SSO (400 sso_required)" in {
    // The tenant's own authProvider decides before any grant is read: an admin row of the tenant
    // does not reopen the password path. The admin-only rule of the tenant SSO login itself is
    // pinned in AuthHandlersOidcSpec.
    val oidcTenant = Tenant(
      id = "t-a",
      authProvider = "keycloak",
      authConfig =
        Map("issuerUrl" -> "https://idp.example/t-a", "clientId" -> "c", "clientSecretRef" -> "s")
    )
    val h = oidcHandlers(
      directory = Map("alice@corp" -> List(UserGrant(Some("t-a"), "admin"))),
      loadTenant = { case "t-a" => Some(oidcTenant); case _ => None }
    )
    val out = h.login(LoginRequest("alice@corp", "p", tenant = Some("t-a"))).unsafeRunSync()
    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err.error shouldBe "sso_required"
      case Right(_) => fail("expected 400 sso_required, got a session")
  }

  it should "mint role=admin for an admin principal whose IdP role claim says user" in {
    // The IdP emits no recognized role, so RoleExtractor defaults profile.role
    // to "user". Authority comes from qodstate (a tenant-admin grant on t-a), so
    // the minted JWT must carry role=admin -- otherwise isAdmin(token) is false
    // and a genuine admin is demoted into the profile-only UI shell.
    val profile = AuthenticatedProfile(
      username = "alice@corp",
      role = "user", // IdP claim, no authority
      groups = Set.empty,
      claims = Map("preferred_username" -> "alice@corp", "email" -> "alice@corp"),
      authMethod = "keycloak-ropc",
      tenant = None
    )
    val tokens = new SessionTokenStore
    val h      = oidcHandlers(
      directory = Map("alice@corp" -> List(UserGrant(Some("t-a"), "admin"))),
      profile = profile,
      tokens = tokens
    )

    val (_, r) = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync().toOption.get

    r.admin shouldBe true
    r.manageableTenants shouldBe List("t-a")
    tokens.isAdmin(r.token) shouldBe true
  }

  it should "read the directory for a database-authenticator profile too: identitySource=oidc never takes the password row as the grant" in {
    // The database authenticator verified the system row 'root' (kind admin). Under
    // identitySource=oidc the grant is still the directory's, as before: here two tenant rows.
    val profile = AuthenticatedProfile("root", "admin", Set.empty, Map.empty, DbAuthMethod, None)
    val h       = oidcHandlers(
      directory =
        Map("root" -> List(UserGrant(Some("t-a"), "admin"), UserGrant(Some("t-b"), "admin"))),
      profile = profile
    )

    val (_, r) = h.login(LoginRequest("root", "p")).unsafeRunSync().toOption.get

    r.superuser shouldBe false
    r.manageableTenants shouldBe List("t-a", "t-b")
  }

  it should "keep the email-shaped name of a database-authenticator profile: the seeded admin@localhost.local still signs in under identitySource=oidc" in {
    // The name was verified by QoD's own password check, not read from a token's email claim, so the
    // verified-email rule of the IdP logins does not touch it (its claims carry no email).
    val profile = AuthenticatedProfile(
      username = "admin@localhost.local",
      role = "admin",
      groups = Set("admin"),
      claims = Map(
        "sub"         -> "admin@localhost.local",
        "role"        -> "admin",
        "auth_method" -> DbAuthMethod
      ),
      authMethod = DbAuthMethod,
      tenant = None
    )
    val h = oidcHandlers(
      directory = Map("admin@localhost.local" -> List(UserGrant(None, "admin"))),
      profile = profile
    )

    val (_, r) = h.login(LoginRequest("admin@localhost.local", "p")).unsafeRunSync().toOption.get

    r.superuser shouldBe true
  }
