package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{AuthenticatedProfile, AuthenticationService, AuthScope}
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
import ai.starlake.quack.ondemand.auth.{GrantsLookup, ManagementIdentitySource, SessionScope}
import ai.starlake.quack.ondemand.state.UserGrant
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** Unit tests for [[AuthHandlers]]. */
class AuthHandlersSpec extends AnyFlatSpec with Matchers:

  // Minimal config with all providers disabled so the parent constructor
  // builds empty chains (no I/O). The fake subclass overrides the
  // public `authenticateBasic` so the chains are never consulted.
  private val emptyConfig = AuthenticationConfig(
    roleClaim = "role",
    database = DatabaseAuthConfig(
      enabled     = false,
      jdbcUrl     = "",
      username    = "",
      password    = "",
      systemQuery = "",
      tenantQuery = ""
    ),
    keycloak = KeycloakAuthConfig(
      enabled      = false,
      baseUrl      = "",
      realm        = "",
      clientId     = "",
      clientSecret = ""
    ),
    google = GoogleAuthConfig(
      enabled               = false,
      clientId              = "",
      clientSecret          = "",
      groupsLookup          = false,
      serviceAccountKeyPath = "",
      groupsCacheTtlSeconds = 0L
    ),
    azure = AzureAuthConfig(
      enabled      = false,
      tenantId     = "",
      clientId     = "",
      clientSecret = ""
    ),
    aws = AwsAuthConfig(
      enabled    = false,
      region     = "",
      userPoolId = "",
      clientId   = ""
    ),
    jwt = JwtAuthConfig(
      secretKey     = "",
      publicKeyPath = "",
      issuer        = "",
      audience      = ""
    ),
    oauth = OAuthConfig(
      enabled               = false,
      port                  = 0,
      baseUrl               = "",
      scopes                = "",
      sessionTimeoutSeconds = 0,
      disableTls            = true
    )
  )

  private def makeHandlers(
      result: Either[String, AuthenticatedProfile],
      capturedScope: scala.collection.mutable.Buffer[(AuthScope, String, String)],
      tokens: SessionTokenStore = new SessionTokenStore
  ): AuthHandlers =
    val fakeSvc = new AuthenticationService(emptyConfig, "x"):
      override val hasProviders: Boolean = true
      override def authenticateBasic(
          scope: AuthScope,
          username: String,
          password: String
      ): Either[String, AuthenticatedProfile] =
        capturedScope += ((scope, username, password))
        result

    new AuthHandlers(
      authService       = fakeSvc,
      tokens            = tokens,
      identitySource    = ManagementIdentitySource.Db,
      grantsForIdentity = (_, _) => Nil // unused in Db mode
    )

  "login" should "forward tenant scope to the auth service and surface manageableTenants" in {
    val profile = AuthenticatedProfile(
      username   = "alice",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = Some("t-abc123")
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
    resp.tenant            shouldBe None
    resp.superuser         shouldBe false
    resp.manageableTenants shouldBe List("t-abc123")
    resp.username          shouldBe "alice"
    calls should have size 1
    calls.head._1 shouldBe AuthScope.Tenant("t-abc123")
  }

  it should "pass System scope when tenant is absent and mint a superuser session" in {
    val profile = AuthenticatedProfile(
      username   = "root",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = None
    )
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("root", "pass", tenant = None)

    val (_, resp) = h.login(req).unsafeRunSync().toOption.get

    calls.head._1     shouldBe AuthScope.System
    resp.superuser    shouldBe true
    resp.manageableTenants shouldBe empty
  }

  it should "coerce blank tenant string to System scope before forwarding" in {
    val profile = AuthenticatedProfile(
      username   = "root",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = None
    )
    val calls = scala.collection.mutable.Buffer.empty[(AuthScope, String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("root", "pass", tenant = Some("   "))

    h.login(req).unsafeRunSync()

    calls.head._1 shouldBe AuthScope.System
  }

  "whoami" should "surface the tenant and scope from the stored session" in {
    val profile = AuthenticatedProfile(
      username   = "alice",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = Some("t-abc123")
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
    resp.tenant            shouldBe Some("t-abc123")
    resp.username          shouldBe "alice"
    resp.superuser         shouldBe false
    resp.manageableTenants shouldBe List("t-abc123")
  }

  // ----- OIDC management identity source -----

  private def oidcHandlers(
      directory: Map[String, List[UserGrant]],
      profile: AuthenticatedProfile = AuthenticatedProfile(
        username   = "alice@corp",
        role       = "user", // claim role MUST be ignored
        groups     = Set.empty,
        claims     = Map("email" -> "alice@corp"),
        authMethod = "keycloak-ropc",
        tenant     = None
      )
  ): AuthHandlers =
    val fakeSvc = new AuthenticationService(emptyConfig, "x"):
      override val hasProviders: Boolean = true
      override def authenticateBasic(
          scope: AuthScope,
          username: String,
          password: String
      ): Either[String, AuthenticatedProfile] = Right(profile)

    val fakeDirectory: GrantsLookup = (identity, email) =>
      directory.getOrElse(
        identity,
        email.flatMap(directory.get).getOrElse(Nil)
      )

    new AuthHandlers(
      authService       = fakeSvc,
      tokens            = new SessionTokenStore,
      identitySource    = ManagementIdentitySource.Oidc,
      grantsForIdentity = fakeDirectory
    )

  "login (oidc)" should "treat tenant=NULL admin row as superuser, ignoring JWT role" in {
    val h   = oidcHandlers(directory = Map("alice@corp" -> List(UserGrant(None, "admin"))))
    val out = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    val (_, r) = out.toOption.get
    r.superuser         shouldBe true
    r.manageableTenants shouldBe empty
    r.tenant            shouldBe None
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
    r.superuser              shouldBe false
    r.manageableTenants.toSet shouldBe Set("t-a", "t-b")
  }

  it should "fall back to the email claim when the primary identity does not match" in {
    val profile = AuthenticatedProfile(
      username   = "alice@corp",
      role       = "user",
      groups     = Set.empty,
      claims     = Map("email" -> "alice@corporate.example"),
      authMethod = "keycloak-ropc",
      tenant     = None
    )
    val h = oidcHandlers(
      directory = Map("alice@corporate.example" -> List(UserGrant(None, "admin"))),
      profile  = profile
    )
    val (_, r) = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync().toOption.get
    r.superuser shouldBe true
  }

  it should "403 not_provisioned when the directory yields no grants" in {
    val h   = oidcHandlers(directory = Map.empty)
    val out = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    val (code, err) = out.swap.toOption.get
    code      shouldBe StatusCode.Forbidden
    err.error shouldBe "not_provisioned"
  }

  it should "403 admin_required when no admin grant exists" in {
    val h = oidcHandlers(
      directory = Map("alice@corp" -> List(UserGrant(Some("t-a"), "user")))
    )
    val out         = h.login(LoginRequest("alice@corp", "p")).unsafeRunSync()
    val (code, err) = out.swap.toOption.get
    code      shouldBe StatusCode.Forbidden
    err.error shouldBe "admin_required"
  }

  it should "discard the JWT role claim entirely (admin claim does NOT mint superuser)" in {
    val profile = AuthenticatedProfile(
      username   = "imposter",
      role       = "admin",
      groups     = Set("admin"),
      claims     = Map("email" -> "imposter"),
      authMethod = "keycloak-ropc",
      tenant     = None
    )
    val h           = oidcHandlers(directory = Map.empty, profile = profile)
    val out         = h.login(LoginRequest("imposter", "p")).unsafeRunSync()
    val (code, err) = out.swap.toOption.get
    code      shouldBe StatusCode.Forbidden
    err.error shouldBe "not_provisioned"
  }