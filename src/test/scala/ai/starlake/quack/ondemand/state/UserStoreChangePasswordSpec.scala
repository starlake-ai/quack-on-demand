package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Self-service password change: verify the current password, swap the bcrypt hash, and clear
  * `must_change_password` in one call. All failures collapse into `InvalidCredentials` so the
  * public endpoint built on top cannot be used to enumerate accounts.
  */
class UserStoreChangePasswordSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodcpw")

  private def withFreshDb(test: (PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodcpw_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "changePassword" should "swap the hash and clear the flag on a correct current password" in
    withFreshDb { (store, users) =>
      users.upsertUser(None, "alice", "temp1", "admin", mustChangePassword = Some(true))
      users.changePassword(None, "alice", "temp1", "real1") shouldBe Right(())
      val u = store.findUser(None, "alice").get
      u.mustChangePassword shouldBe false
      u.role shouldBe "admin"
      u.enabled shouldBe true
      val hash = store.getPasswordHash(None, "alice").get
      BCrypt.verifyer().verify("real1".toCharArray, hash).verified shouldBe true
      BCrypt.verifyer().verify("temp1".toCharArray, hash).verified shouldBe false
    }

  it should "swap the hash and clear the flag for a tenant-scoped user" in
    withFreshDb { (store, users) =>
      store.upsertTenant(Tenant(id = "t-1"))
      users.upsertUser(Some("t-1"), "bob", "temp1", "user", mustChangePassword = Some(true))
      users.changePassword(Some("t-1"), "bob", "temp1", "real1") shouldBe Right(())
      val u = store.findUser(Some("t-1"), "bob").get
      u.mustChangePassword shouldBe false
      val hash = store.getPasswordHash(Some("t-1"), "bob").get
      BCrypt.verifyer().verify("real1".toCharArray, hash).verified shouldBe true
    }

  it should "reject a wrong current password" in withFreshDb { (_, users) =>
    users.upsertUser(None, "alice", "temp1", "admin")
    users.changePassword(None, "alice", "WRONG", "real1") shouldBe
      Left(UserStore.ChangePasswordError.InvalidCredentials)
  }

  it should "reject an unknown user with the same error" in withFreshDb { (_, users) =>
    users.changePassword(None, "ghost", "x", "y") shouldBe
      Left(UserStore.ChangePasswordError.InvalidCredentials)
  }

  it should "reject a disabled user even with the correct password" in
    withFreshDb { (store, users) =>
      store.upsertTenant(Tenant(id = "t-1"))
      // upsertUserIdentity never touches `enabled`, so seed the disabled row
      // the same way UserEnabledAuthSpec does: a precomputed hash through
      // upsertUserWithHash(enabled = false).
      val hash = BCrypt.withDefaults().hashToString(12, "temp1".toCharArray)
      store.upsertUserWithHash(Some("t-1"), "bob", hash, "user", enabled = false)
      users.changePassword(Some("t-1"), "bob", "temp1", "real1") shouldBe
        Left(UserStore.ChangePasswordError.InvalidCredentials)
    }

  it should "scope tenant lookups (tenant user cannot change the superuser row)" in
    withFreshDb { (_, users) =>
      users.upsertUser(None, "alice", "rootpw", "admin")
      users.changePassword(Some("t-1"), "alice", "rootpw", "x") shouldBe
        Left(UserStore.ChangePasswordError.InvalidCredentials)
    }
