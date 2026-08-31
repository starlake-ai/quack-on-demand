package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** [[PatAuthenticator.resolve]] must carry the minted row's [[TokenRestriction]] onto the
  * [[PatPrincipal]] it returns, and `dropAdmin` must demote the principal exactly the way an
  * `enabled = true, role != admin` row already is demoted (see `PatAuthenticatorSpec`): to the same
  * profile-only `SessionScope` a non-admin login session gets, not to a new notion of "restricted
  * admin".
  */
class PatScopeAuthSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodpsa")

  private def withFreshDb(test: (String, UserStore, PatStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodpsa_test_${System.nanoTime()}"
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

  private def authOf(users: UserStore, pats: PatStore): PatAuthenticator =
    new PatAuthenticator(
      pats,
      id => users.userById(id),
      u => List(UserGrant(u.tenant, u.role))
    )

  "resolve" should "carry the row's restriction onto the principal" in
    withFreshDb { (_, users, pats) =>
      users.upsertUser(None, "alice", "pw", "admin")
      val uid      = users.userIdOf(None, "alice").get
      val r        = TokenRestriction.Unrestricted.copy(databases = Some(Set("acme_db")))
      val (_, raw) = pats.mint(uid, "agent", r, None, 0)
      authOf(users, pats).resolve(raw).map(_.restriction.databases) shouldBe Some(
        Some(Set("acme_db"))
      )
    }

  // dropAdmin reuses the existing profile-only demotion rather than adding a new
  // notion of "restricted admin": an admin's agent token becomes a data-only one.
  it should "demote an admin token to profile-only under dropAdmin" in
    withFreshDb { (_, users, pats) =>
      users.upsertUser(None, "alice", "pw", "admin")
      val uid      = users.userIdOf(None, "alice").get // seeded tenant-less admin
      val (_, raw) = pats.mint(
        uid,
        "agent",
        TokenRestriction.Unrestricted.copy(dropAdmin = true),
        None,
        0
      )
      val p = authOf(users, pats).resolve(raw).get
      p.isAdmin shouldBe false
      p.scope.superuser shouldBe false
      p.scope.manageableTenants shouldBe Set.empty
    }
