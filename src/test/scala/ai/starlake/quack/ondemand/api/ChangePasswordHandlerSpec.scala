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
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.{
  ManagementAuthMode,
  ManagementAuthModeResolver,
  ManagementIdentitySource
}
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import scala.util.Try

/** Wire contract of the public `POST /api/auth/change-password` handler against a real Postgres
  * [[UserStore]].
  *
  * The endpoint is pre-session: the current password IS the credential, so the only gates are the
  * store's own credential check plus two policy rules owned by the handler (new password non-empty,
  * and different from the current one). Anti-enumeration is the load-bearing property: unknown
  * user, wrong current password and disabled account must all answer the same
  * `401 invalid_credentials`.
  */
class ChangePasswordHandlerSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodcph")

  // Every provider disabled: the handler never consults the auth service, and the
  // fake below keeps the empty chains out of the picture entirely.
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

  private val DefaultSystemQuery =
    "SELECT password_hash, role, enabled, must_change_password FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"
  private val DefaultTenantQuery =
    "SELECT password_hash, role, enabled, must_change_password FROM qodstate_user WHERE tenant = ? AND username = ? LIMIT 1"

  private def authConfig(url: String) =
    DatabaseAuthConfig(
      enabled = true,
      jdbcUrl = url,
      username = TestPostgres.pgUser,
      password = TestPostgres.pgPass,
      systemQuery = DefaultSystemQuery,
      tenantQuery = DefaultTenantQuery
    )

  /** AuthHandlers wired exactly as `AuthHandlersSpec` does, plus the change-password store.
    * `changePasswordStore = None` reproduces a manager booted without the database backend.
    */
  private def handlers(users: Option[UserStore]): AuthHandlers =
    val fakeSvc = new AuthenticationService(emptyConfig, "x"):
      override val hasProviders: Boolean = true
      override def authenticateBasic(
          scope: AuthScope,
          username: String,
          password: String
      ): Either[AuthFailure, AuthenticatedProfile] =
        fail("changePassword must never consult the auth service")

    new AuthHandlers(
      authService = fakeSvc,
      tokens = new SessionTokenStore,
      identitySource = ManagementIdentitySource.Db,
      grantsForIdentity = (_, _) => Nil,
      authModeResolver = new ManagementAuthModeResolver(
        id => Some(Tenant(id = id, authProvider = "db")),
        ManagementAuthMode.Db
      ),
      resolveTenant = _ => None,
      changePasswordStore = users
    )

  private def handlers(users: UserStore): AuthHandlers = handlers(Some(users))

  private def withFreshDb(test: (PostgresControlPlaneStore, UserStore, String) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodcph_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, userStore, url)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "changePassword" should "swap the password, clear the flag, and unblock DatabaseAuthenticator" in
    withFreshDb { (store, userStore, url) =>
      userStore.upsertUser(None, "alice", "temp", "admin", mustChangePassword = Some(true))
      val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
      try
        // Before: the flag blocks the login even with the correct temp password.
        auth.authenticate(AuthScope.System, "alice", "temp") shouldBe
          Left(AuthFailure.PasswordChangeRequired)

        val out = handlers(userStore)
          .changePassword(ChangePasswordRequest(None, "alice", "temp", "real"))
          .unsafeRunSync()

        out shouldBe Right(())
        store.findUser(None, "alice").get.mustChangePassword shouldBe false
        // After: the new password authenticates, the old one no longer does.
        auth.authenticate(AuthScope.System, "alice", "real").isRight shouldBe true
        auth.authenticate(AuthScope.System, "alice", "temp") shouldBe
          Left(AuthFailure.InvalidCredentials("Invalid password"))
      finally auth.close()
    }

  it should "401 invalid_credentials on a wrong current password" in
    withFreshDb { (store, userStore, _) =>
      userStore.upsertUser(None, "alice", "temp", "admin", mustChangePassword = Some(true))
      val out = handlers(userStore)
        .changePassword(ChangePasswordRequest(None, "alice", "WRONG", "real"))
        .unsafeRunSync()

      out match
        case Left((status, err)) =>
          status shouldBe StatusCode.Unauthorized
          err.error shouldBe "invalid_credentials"
        case Right(_) => fail("expected 401")
      // Untouched: neither the password nor the flag moved.
      store.findUser(None, "alice").get.mustChangePassword shouldBe true
    }

  it should "400 invalid_password when new equals current" in
    withFreshDb { (store, userStore, _) =>
      userStore.upsertUser(None, "alice", "temp", "admin", mustChangePassword = Some(true))
      val out = handlers(userStore)
        .changePassword(ChangePasswordRequest(None, "alice", "temp", "temp"))
        .unsafeRunSync()

      out match
        case Left((status, err)) =>
          status shouldBe StatusCode.BadRequest
          err.error shouldBe "invalid_password"
        case Right(_) => fail("expected 400")
      // The policy gate runs before the store, so the flag survives.
      store.findUser(None, "alice").get.mustChangePassword shouldBe true
    }

  it should "400 invalid_password on an empty new password" in
    withFreshDb { (store, userStore, _) =>
      userStore.upsertUser(None, "alice", "temp", "admin", mustChangePassword = Some(true))
      val out = handlers(userStore)
        .changePassword(ChangePasswordRequest(None, "alice", "temp", ""))
        .unsafeRunSync()

      out match
        case Left((status, err)) =>
          status shouldBe StatusCode.BadRequest
          err.error shouldBe "invalid_password"
        case Right(_) => fail("expected 400")
      store.findUser(None, "alice").get.mustChangePassword shouldBe true
    }

  it should "401 invalid_credentials for a disabled user with the correct password" in
    withFreshDb { (store, userStore, _) =>
      store.upsertUserWithHash(
        None,
        "alice",
        at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(10, "temp".toCharArray),
        "admin",
        enabled = false,
        mustChangePassword = true
      )
      val out = handlers(userStore)
        .changePassword(ChangePasswordRequest(None, "alice", "temp", "real"))
        .unsafeRunSync()

      // Same answer as an unknown user or a wrong password: no account-state oracle.
      out match
        case Left((status, err)) =>
          status shouldBe StatusCode.Unauthorized
          err.error shouldBe "invalid_credentials"
        case Right(_) => fail("expected 401")
      store.findUser(None, "alice").get.mustChangePassword shouldBe true
    }

  it should "503 auth_disabled when no UserStore is wired" in {
    val out = handlers(None)
      .changePassword(ChangePasswordRequest(None, "alice", "temp", "real"))
      .unsafeRunSync()

    out match
      case Left((status, err)) =>
        status shouldBe StatusCode.ServiceUnavailable
        err.error shouldBe "auth_disabled"
      case Right(_) => fail("expected 503")
  }
