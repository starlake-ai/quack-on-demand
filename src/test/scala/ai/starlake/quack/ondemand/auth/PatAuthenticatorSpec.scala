package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

/** Principal resolution for personal access tokens: [[PatAuthenticator]] turns a raw bearer token
  * into the same `(SessionScope, AuthenticatedProfile)` shape a password login mints, so the
  * management plane can consume a PAT wherever it consumes a session JWT.
  */
class PatAuthenticatorSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodpau")

  private def withFreshDb(test: (String, UserStore, PatStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodpau_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val pats  = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(dbName, users, pats)
      finally
        pats.close()
        users.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  /** The real store, the real row lookup, and the row-only `grantsFor` the class documents as the
    * required wiring (`mintSessionFor`'s `Db`-mode grant list). Deliberately NOT
    * `UserStore.grantsForIdentity`: that one matches by username across tenants, and usernames are
    * unique only per tenant, so it would let a PAT inherit a same-named foreign admin's tenants.
    */
  private def authOf(users: UserStore, pats: PatStore): PatAuthenticator =
    new PatAuthenticator(
      pats,
      id => users.userById(id),
      u => List(UserGrant(u.tenant, u.role))
    )

  private def seed(
      users: UserStore,
      pats: PatStore,
      tenant: Option[String],
      username: String,
      role: String,
      expiresAt: Option[Instant] = None
  ): (String, String) =
    users.upsertUser(tenant, username, "pw", role)
    val uid      = users.userIdOf(tenant, username).get
    val (_, tok) = pats.mint(uid, "claude-code", expiresAt)
    (uid, tok)

  "resolve" should "give a tenant-less admin PAT a superuser scope" in withFreshDb {
    (_, users, pats) =>
      val (uid, token) = seed(users, pats, None, "root", "admin")
      val principal    = authOf(users, pats).resolve(token).getOrElse(fail("PAT did not resolve"))
      principal.user.id shouldBe uid
      principal.patId should startWith("pat-")
      principal.scope.superuser shouldBe true
      principal.scope.manageableTenants shouldBe empty
      principal.isAdmin shouldBe true
  }

  it should "demote a tenant-less NON-admin row to a profile-only principal" in withFreshDb {
    (_, users, pats) =>
      // A PAT authenticates as its owning user, so it may never outrank that user's own login
      // session: `AuthHandlers.mintSessionFor` refuses this row `admin_required`, and the
      // tenant-less-but-not-admin shape must not buy a superuser scope here either.
      val (_, token) = seed(users, pats, None, "ghost", "user")
      val auth       = authOf(users, pats)
      val principal  = auth.resolve(token).getOrElse(fail("PAT did not resolve"))
      principal.scope.superuser shouldBe false
      principal.scope.manageableTenants shouldBe empty
      principal.isAdmin shouldBe false
      auth.sessionOf(token).map(_.profile.role) shouldBe Some("user")
  }

  it should "scope a tenant-admin PAT to the tenants it administers" in withFreshDb {
    (_, users, pats) =>
      val (_, token) = seed(users, pats, Some("t-acme"), "alice", "admin")
      val auth       = authOf(users, pats)
      val principal  = auth.resolve(token).getOrElse(fail("PAT did not resolve"))
      principal.scope.superuser shouldBe false
      principal.scope.manageableTenants shouldBe Set("t-acme")
      principal.isAdmin shouldBe true
      auth.scopeOf(token) shouldBe Some(principal.scope)
      auth.isAdmin(token) shouldBe true
  }

  it should "resolve a regular tenant user as a non-admin principal" in withFreshDb {
    (_, users, pats) =>
      val (_, token) = seed(users, pats, Some("t-acme"), "bob", "user")
      val auth       = authOf(users, pats)
      val principal  = auth.resolve(token).getOrElse(fail("PAT did not resolve"))
      principal.scope.superuser shouldBe false
      principal.scope.manageableTenants shouldBe empty
      principal.isAdmin shouldBe false
      auth.isAdmin(token) shouldBe false
  }

  it should "refuse a disabled user's token" in withFreshDb { (dbName, users, pats) =>
    val (uid, token) = seed(users, pats, Some("t-acme"), "carol", "admin")
    TestPostgres.psql(dbName, s"UPDATE qodstate_user SET enabled = false WHERE id = '$uid'")
    val auth = authOf(users, pats)
    auth.resolve(token) shouldBe None
    auth.scopeOf(token) shouldBe None
    auth.isAdmin(token) shouldBe false
    auth.sessionOf(token) shouldBe None
  }

  it should "refuse a revoked token and an expired token" in withFreshDb { (_, users, pats) =>
    users.upsertUser(None, "root", "pw", "admin")
    val uid          = users.userIdOf(None, "root").get
    val (rec, live)  = pats.mint(uid, "revoked-soon", None)
    val (_, expired) = pats.mint(uid, "expired", Some(Instant.now().minusSeconds(60)))
    val auth         = authOf(users, pats)
    auth.resolve(live) should not be empty
    pats.revoke(uid, rec.id) shouldBe true
    auth.resolve(live) shouldBe None
    auth.resolve(expired) shouldBe None
  }

  it should "refuse a session-JWT-shaped value without any lookup" in withFreshDb {
    (_, users, pats) =>
      val (_, token) = seed(users, pats, None, "root", "admin")
      val lookups    = new java.util.concurrent.atomic.AtomicInteger(0)
      val auth       = new PatAuthenticator(
        pats,
        id => { lookups.incrementAndGet(); users.userById(id) },
        u => List(UserGrant(u.tenant, u.role))
      )
      // What is pinned is the end-to-end property: a non-PAT value costs no user lookup (the
      // counter stays at zero) and no connection (the pool is closed, so any query would throw --
      // the real token below shows the store IS otherwise on the path). It does NOT pin WHICH
      // layer short-circuits: PatStore.verify carries the same prefix guard, so dropping the one
      // in PatAuthenticator would keep this green. `PatStore` is final, so a stub that counts
      // verify calls is not available to separate them.
      pats.close()
      auth.resolve("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.sig") shouldBe None
      auth.isAdmin("") shouldBe false
      lookups.get() shouldBe 0
      an[Exception] should be thrownBy auth.resolve(token)
  }

  "sessionOf" should "mint a pat-authenticated session with the demoted role" in withFreshDb {
    (_, users, pats) =>
      val (_, adminToken) = seed(users, pats, Some("t-acme"), "alice", "admin")
      val (_, userToken)  = seed(users, pats, Some("t-acme"), "bob", "user")
      val auth            = authOf(users, pats)
      val before          = Instant.now()

      val adminSession = auth.sessionOf(adminToken).getOrElse(fail("admin PAT did not resolve"))
      adminSession.profile.username shouldBe "alice"
      adminSession.profile.role shouldBe "admin"
      adminSession.profile.authMethod shouldBe "pat"
      adminSession.profile.tenant shouldBe Some("t-acme")
      adminSession.profile.groups shouldBe empty
      adminSession.profile.claims shouldBe empty
      adminSession.scope shouldBe SessionScope(superuser = false, Set("t-acme"))
      // Minted now, not carried over from the token's own createdAt: bounded on BOTH sides.
      adminSession.createdAt.isBefore(before.minusSeconds(1)) shouldBe false
      adminSession.createdAt.isAfter(Instant.now().plusSeconds(5)) shouldBe false

      val userSession = auth.sessionOf(userToken).getOrElse(fail("user PAT did not resolve"))
      userSession.profile.role shouldBe "user"
      userSession.profile.authMethod shouldBe "pat"
      userSession.scope.manageableTenants shouldBe empty
  }
