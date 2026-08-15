package ai.starlake.quack.security

import ai.starlake.quack.LockoutConfig
import ai.starlake.quack.edge.auth.{AuthFailure, AuthScope, DatabaseAuthenticator}
import ai.starlake.quack.edge.config.DatabaseAuthConfig
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Try

/** Task 9 -- lockout enforcement across the shared password-verify surfaces.
  *
  * `DatabaseAuthenticator.authenticate` is the single hook behind BOTH the REST login and the
  * FlightSQL handshake; `UserStore.changePassword` is the third verify surface. All three must:
  *   - lock an emailed row after exactly `maxFailures` consecutive bad passwords;
  *   - refuse a locked row BEFORE bcrypt (a locked account is denied even with the right password);
  *   - reset the counter on a successful auth below the threshold;
  *   - NEVER lock an emailless row (superuser + pre-email accounts have no self-service reset);
  *   - clear the lock on any password write (setPasswordById / admin upsert);
  *   - do nothing at all when lockout is disabled.
  */
class AccountLockoutSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodlock")

  private val DefaultSystemQuery =
    "SELECT password_hash, role, enabled, must_change_password FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"
  private val DefaultTenantQuery =
    "SELECT password_hash, role, enabled, must_change_password FROM qodstate_user WHERE tenant = ? AND username = ? LIMIT 1"

  private val enabledCfg  = LockoutConfig(enabled = true, maxFailures = 3)
  private val disabledCfg = LockoutConfig(enabled = false, maxFailures = 3)

  private def authConfig(url: String) =
    DatabaseAuthConfig(
      enabled = true,
      jdbcUrl = url,
      username = TestPostgres.pgUser,
      password = TestPostgres.pgPass,
      systemQuery = DefaultSystemQuery,
      tenantQuery = DefaultTenantQuery
    )

  /** Read `failed_attempts` / `locked_at` straight off the row -- the enforcement writes them
    * directly against qodstate_user, so the assertions read them the same way.
    */
  private def failedAttempts(url: String, tenant: Option[String], username: String): Int =
    val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val sql = tenant match
        case Some(_) =>
          "SELECT failed_attempts FROM qodstate_user WHERE tenant = ? AND username = ?"
        case None =>
          "SELECT failed_attempts FROM qodstate_user WHERE tenant IS NULL AND username = ?"
      val ps = c.prepareStatement(sql)
      try
        tenant match
          case Some(t) =>
            ps.setString(1, t)
            ps.setString(2, username)
          case None =>
            ps.setString(1, username)
        val rs = ps.executeQuery()
        try
          rs.next() shouldBe true
          rs.getInt(1)
        finally rs.close()
      finally ps.close()
    finally c.close()

  private def withFreshDb(cfg: LockoutConfig)(
      test: (UserStore, DatabaseAuthenticator, String) => Unit
  ): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodlock_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass, lockout = cfg)
      val auth  =
        new DatabaseAuthenticator(
          authConfig(url),
          roleClaim = "role",
          lockout = cfg,
          lockoutStore = Some(users)
        )
      try test(users, auth, url)
      finally
        auth.close()
        users.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  // ------------------------------------------------------------------
  // authenticate -- the shared REST + FlightSQL hook
  // ------------------------------------------------------------------

  "authenticate" should "lock an emailed row after exactly maxFailures and refuse it before bcrypt" in
    withFreshDb(enabledCfg) { (users, auth, url) =>
      users.upsertUser(Some("t1"), "alice", "pw", "user", email = Some(Some("alice@x.io")))

      // Two wrong attempts: below the threshold, still unlocked.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope") shouldBe
        Left(AuthFailure.InvalidCredentials("Invalid password"))
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope") shouldBe
        Left(AuthFailure.InvalidCredentials("Invalid password"))
      users.isLocked(Some("t1"), "alice") shouldBe false
      failedAttempts(url, Some("t1"), "alice") shouldBe 2

      // The third (Nth) wrong attempt trips the lock.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope") shouldBe
        Left(AuthFailure.InvalidCredentials("Invalid password"))
      users.isLocked(Some("t1"), "alice") shouldBe true

      // Now even the CORRECT password is refused, before bcrypt, as AccountLocked.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "pw") shouldBe
        Left(AuthFailure.AccountLocked)
    }

  it should "reset the counter on a successful auth below the threshold" in
    withFreshDb(enabledCfg) { (users, auth, url) =>
      users.upsertUser(Some("t1"), "alice", "pw", "user", email = Some(Some("alice@x.io")))

      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope").isLeft shouldBe true
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope").isLeft shouldBe true
      failedAttempts(url, Some("t1"), "alice") shouldBe 2

      // A correct password wins and clears the counter.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "pw").isRight shouldBe true
      failedAttempts(url, Some("t1"), "alice") shouldBe 0

      // Two more wrong attempts are again below the (reset) threshold: not locked.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope").isLeft shouldBe true
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope").isLeft shouldBe true
      users.isLocked(Some("t1"), "alice") shouldBe false
    }

  it should "reset the counter when a must_change user supplies the correct password" in
    withFreshDb(enabledCfg) { (users, auth, url) =>
      users.upsertUser(
        Some("t1"),
        "alice",
        "pw",
        "user",
        mustChangePassword = Some(true),
        email = Some(Some("alice@x.io"))
      )

      // maxFailures - 1 wrong attempts: one shy of the lock.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope").isLeft shouldBe true
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope").isLeft shouldBe true
      failedAttempts(url, Some("t1"), "alice") shouldBe 2

      // The CORRECT password returns PasswordChangeRequired -- and still clears the counter, so
      // proving the credential does not leave the user one mistype from a lockout.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "pw") shouldBe
        Left(AuthFailure.PasswordChangeRequired)
      failedAttempts(url, Some("t1"), "alice") shouldBe 0

      // A single subsequent wrong attempt is below the (reset) threshold: not locked.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope").isLeft shouldBe true
      users.isLocked(Some("t1"), "alice") shouldBe false
    }

  it should "never lock an emailless row (superuser case) no matter how many failures" in
    withFreshDb(enabledCfg) { (users, auth, url) =>
      users.upsertUser(None, "root", "pw", "admin") // no email

      (1 to 10).foreach { _ =>
        auth.authenticate(AuthScope.System, "root", "nope") shouldBe
          Left(AuthFailure.InvalidCredentials("Invalid password"))
      }
      users.isLocked(None, "root") shouldBe false
      failedAttempts(url, None, "root") shouldBe 0

      // The correct password still authenticates -- the account was never locked.
      auth.authenticate(AuthScope.System, "root", "pw").isRight shouldBe true
    }

  it should "clear the lock on setPasswordById so the new password authenticates" in
    withFreshDb(enabledCfg) { (users, auth, _) =>
      val id =
        users.upsertUser(Some("t1"), "alice", "pw", "user", email = Some(Some("alice@x.io"))).id

      (1 to 3).foreach(_ => auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope"))
      users.isLocked(Some("t1"), "alice") shouldBe true
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "pw") shouldBe
        Left(AuthFailure.AccountLocked)

      // A password write (the reset path) unlocks.
      users.setPasswordById(id, "brandnew")
      users.isLocked(Some("t1"), "alice") shouldBe false
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "brandnew").isRight shouldBe true
    }

  it should "clear must_change_password on setPasswordById so the reset user is not re-prompted" in
    withFreshDb(enabledCfg) { (users, auth, _) =>
      val id = users
        .upsertUser(
          Some("t1"),
          "alice",
          "pw",
          "user",
          mustChangePassword = Some(true),
          email = Some(Some("alice@x.io"))
        )
        .id

      // Before the reset, the correct password is bounced with PasswordChangeRequired.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "pw") shouldBe
        Left(AuthFailure.PasswordChangeRequired)

      // A self-service reset is a user-chosen credential, so it clears the flag: the user is not
      // forced to immediately change the password they just picked.
      users.setPasswordById(id, "brandnew")
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "brandnew").isRight shouldBe true
    }

  it should "clear the lock on an admin upsertUser password write" in
    withFreshDb(enabledCfg) { (users, auth, _) =>
      users.upsertUser(Some("t1"), "alice", "pw", "user", email = Some(Some("alice@x.io")))
      (1 to 3).foreach(_ => auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope"))
      users.isLocked(Some("t1"), "alice") shouldBe true

      // Admin reset (upsert with a fresh password) unlocks.
      users.upsertUser(Some("t1"), "alice", "adminset", "user", email = Some(Some("alice@x.io")))
      users.isLocked(Some("t1"), "alice") shouldBe false
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "adminset").isRight shouldBe true
    }

  // ------------------------------------------------------------------
  // changePassword -- the third verify surface
  // ------------------------------------------------------------------

  "changePassword" should "increment on a wrong current password and lock after maxFailures" in
    withFreshDb(enabledCfg) { (users, _, url) =>
      users.upsertUser(Some("t1"), "alice", "pw", "user", email = Some(Some("alice@x.io")))

      users.changePassword(Some("t1"), "alice", "wrong", "new1") shouldBe
        Left(UserStore.ChangePasswordError.InvalidCredentials)
      failedAttempts(url, Some("t1"), "alice") shouldBe 1

      users.changePassword(Some("t1"), "alice", "wrong", "new2") shouldBe
        Left(UserStore.ChangePasswordError.InvalidCredentials)
      users.changePassword(Some("t1"), "alice", "wrong", "new3") shouldBe
        Left(UserStore.ChangePasswordError.InvalidCredentials)
      users.isLocked(Some("t1"), "alice") shouldBe true

      // Once locked, change-password refuses up front with the distinct Locked signal.
      users.changePassword(Some("t1"), "alice", "pw", "new4") shouldBe
        Left(UserStore.ChangePasswordError.Locked)
    }

  // ------------------------------------------------------------------
  // disabled -- byte-unchanged path
  // ------------------------------------------------------------------

  "authenticate with lockout disabled" should "never lock and never touch the counter" in
    withFreshDb(disabledCfg) { (users, auth, url) =>
      users.upsertUser(Some("t1"), "alice", "pw", "user", email = Some(Some("alice@x.io")))

      (1 to 10).foreach { _ =>
        auth.authenticate(AuthScope.Tenant("t1"), "alice", "nope") shouldBe
          Left(AuthFailure.InvalidCredentials("Invalid password"))
      }
      failedAttempts(url, Some("t1"), "alice") shouldBe 0
      users.isLocked(Some("t1"), "alice") shouldBe false

      // The correct password still authenticates -- nothing was locked.
      auth.authenticate(AuthScope.Tenant("t1"), "alice", "pw").isRight shouldBe true
    }
