package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.{AuthenticatedProfile, AuthenticationService}
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
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** Unit tests for [[AuthHandlers]].
  *
  * Uses a fake [[AuthenticationService]] subclass to avoid real DB / network calls.
  */
class AuthHandlersSpec extends AnyFlatSpec with Matchers:

  // Minimal config with all providers disabled so the parent constructor
  // builds empty chains (no I/O). The fake subclass overrides the
  // public `authenticateBasic` so the chains are never consulted.
  private val emptyConfig = AuthenticationConfig(
    roleClaim = "role",
    database = DatabaseAuthConfig(
      enabled  = false,
      jdbcUrl  = "",
      username = "",
      password = "",
      query    = ""
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
      capturedScope: scala.collection.mutable.Buffer[(Option[String], String, String)]
  ): AuthHandlers =
    val fakeSvc = new AuthenticationService(emptyConfig, "x"):
      override val hasProviders: Boolean = true
      override def authenticateBasic(
          tenant: Option[String],
          username: String,
          password: String
      ): Either[String, AuthenticatedProfile] =
        capturedScope += ((tenant, username, password))
        result

    new AuthHandlers(fakeSvc, new SessionTokenStore)

  "login" should "forward tenant scope to the auth service and echo it on the response" in {
    val profile = AuthenticatedProfile(
      username   = "alice",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = Some("t-abc123")
    )
    val calls = scala.collection.mutable.Buffer.empty[(Option[String], String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("alice", "secret", tenant = Some("t-abc123"))

    val result = h.login(req).unsafeRunSync()

    result shouldBe a[Right[?, ?]]
    val resp = result.toOption.get
    resp.tenant  shouldBe Some("t-abc123")
    resp.username shouldBe "alice"
    calls should have size 1
    calls.head._1 shouldBe Some("t-abc123")
  }

  it should "pass None scope when tenant is absent" in {
    val profile = AuthenticatedProfile(
      username   = "root",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = None
    )
    val calls = scala.collection.mutable.Buffer.empty[(Option[String], String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("root", "pass", tenant = None)

    h.login(req).unsafeRunSync()

    calls.head._1 shouldBe None
  }

  it should "coerce blank tenant string to None before forwarding" in {
    val profile = AuthenticatedProfile(
      username   = "root",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = None
    )
    val calls = scala.collection.mutable.Buffer.empty[(Option[String], String, String)]
    val h     = makeHandlers(Right(profile), calls)
    val req   = LoginRequest("root", "pass", tenant = Some("   "))

    h.login(req).unsafeRunSync()

    calls.head._1 shouldBe None
  }

  "whoami" should "surface the tenant from the stored profile" in {
    val profile = AuthenticatedProfile(
      username   = "alice",
      role       = "admin",
      groups     = Set.empty,
      claims     = Map.empty,
      authMethod = "db",
      tenant     = Some("t-abc123")
    )
    val store = new SessionTokenStore
    val token = store.mint(profile)
    val calls = scala.collection.mutable.Buffer.empty[(Option[String], String, String)]
    val h     = makeHandlers(Right(profile), calls)
    // Use the store we already minted from:
    val hWithStore = new AuthHandlers(
      new AuthenticationService(emptyConfig, "x"):
        override val hasProviders: Boolean = true
        override def authenticateBasic(
            tenant: Option[String],
            username: String,
            password: String
        ): Either[String, AuthenticatedProfile] = Right(profile),
      store
    )

    val result = hWithStore.whoami(token).unsafeRunSync()

    result shouldBe a[Right[?, ?]]
    val resp = result.toOption.get
    resp.tenant   shouldBe Some("t-abc123")
    resp.username shouldBe "alice"
  }